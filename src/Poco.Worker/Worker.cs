using Poco.Core.Approving;
using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Policies;
using Poco.Core.Quoting;
using Poco.Core.Renewing;
using Poco.Worker.Environment;
using ApproveClaimOp = Poco.Core.Approving.ApproveClaim;
using IssuePolicyOp = Poco.Core.Issuing.IssuePolicy;

namespace Poco.Worker;

/// <summary>
/// Batch consumer of the same Core operations Mono serves over HTTP.
/// Scoping differs on purpose: there is no request scope here, so this
/// singleton hosted service holds its stateful dependencies for its whole
/// lifetime and supplies them explicitly on every tick. Nothing about Core
/// had to change to support that — it never assumed a scope to begin with.
///
/// Pending work below stands in for a queue/file/feed. A real worker would
/// dequeue per tick; the calls into Core would be identical.
/// </summary>
public class Worker(
    ILogger<Worker> logger,
    WorkerPolicyStore policies,
    LoggingClaimLedger ledger,
    TimeProvider timeProvider,
    ServiceClaimAuthorizer claimAuthorizer,
    InvoiceBillingGateway billingGateway) : BackgroundService
{
    // Pattern 1 environment: a frozen tariff snapshot taken at startup.
    // Mono resolves tariffs live from config per request; the batch job
    // deliberately prices with the snapshot it started with. Same pure
    // function, different resolved value, legitimately different premium.
    private static readonly IReadOnlyDictionary<string, decimal> TariffSnapshot =
        new Dictionary<string, decimal>
        {
            ["EU-Low"] = 0.020m,
            ["EU-Medium"] = 0.035m,
            ["EU-High"] = 0.060m,
            ["US-Low"] = 0.025m,
        };

    private readonly Queue<QuoteRequest> _pendingQuotes = new(
    [
        new QuoteRequest("Dana Novak", 34, "EU", 40_000m, RiskLevel.Medium),
        new QuoteRequest("Eli Ross", 71, "US", 25_000m, RiskLevel.Low),
        new QuoteRequest("Too Young", 16, "EU", 10_000m, RiskLevel.Low),
    ]);

    private readonly Queue<ClaimRequest> _pendingClaims = new(
    [
        new ClaimRequest(Guid.Parse("11111111-1111-1111-1111-111111111111"), 18_000m, "Water damage"),
        new ClaimRequest(Guid.Parse("22222222-2222-2222-2222-222222222222"), 900m, "Glass"),
        new ClaimRequest(Guid.Parse("99999999-9999-9999-9999-999999999999"), 100m, "Theft"),
    ]);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), timeProvider);

        do
        {
            RepricePendingQuotes();
            TriagePendingClaims();
            AssessRenewals();

            logger.LogInformation(
                "Batch tick complete at {Time}: {Lookups} policy lookups so far, ledger total {TotalPayout}",
                timeProvider.GetUtcNow(), policies.LookupCount, ledger.TotalPayout);
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    private void RepricePendingQuotes()
    {
        while (_pendingQuotes.TryDequeue(out var request))
        {
            var tariffRate = TariffSnapshot.GetValueOrDefault($"{request.Region}-{request.Risk}", 0m);

            // Pattern 1: plain resolved values in, union out.
            var outcome = QuotePremium.Execute(
                request, tariffRate, Guid.NewGuid(), timeProvider.GetUtcNow());

            switch (outcome)
            {
                case Quote quote:
                    logger.LogInformation(
                        "Repriced quote for {Holder}: premium {Premium} (valid until {ValidUntil})",
                        quote.HolderName, quote.AnnualPremium, quote.ValidUntil);

                    // Contract-based op: issue from the fresh quote through
                    // this worker's repository + invoicing contracts.
                    switch (IssuePolicyOp.Execute(quote, policies, billingGateway, timeProvider))
                    {
                        case Policy issued:
                            logger.LogInformation(
                                "Policy issued for {Holder}: id {PolicyId}, coverage {Coverage}, expires {ExpiresOn}",
                                issued.HolderName, issued.Id, issued.CoverageLimit, issued.ExpiresOn);
                            break;
                        case PolicyIssueRejected rejected:
                            logger.LogWarning("Policy not issued for {Holder}: {Reason}", quote.HolderName, rejected.Reason);
                            break;
                    }
                    break;
                case QuoteRejected rejected:
                    logger.LogWarning("Quote for {Holder} rejected: {Reason}", request.HolderName, rejected.Reason);
                    break;
            }
        }
    }

    private void TriagePendingClaims()
    {
        while (_pendingClaims.TryDequeue(out var request))
        {
            // Pattern 2: this worker's own stateful instances, passed directly.
            var outcome = TriageClaim.Execute(request, policies, ledger, timeProvider);

            switch (outcome)
            {
                case ClaimDecision decision:
                    // Contract-based op: the worker's service authorizer
                    // auto-approves only small payouts; larger ones stay
                    // for a human — same Core operation Mono gates by claims.
                    switch (ApproveClaimOp.Execute(
                        new ClaimApprovalRequest(decision.PolicyId, decision.Payout, ServiceClaimAuthorizer.ServiceIdentity),
                        claimAuthorizer, ledger, timeProvider))
                    {
                        case ClaimApproval:
                            break; // the ledger already logged the approval
                        case ApprovalNotAuthorized:
                            logger.LogWarning(
                                "Payout {Payout} for policy {PolicyId} exceeds the worker auto-approve limit; left for human approval",
                                decision.Payout, decision.PolicyId);
                            break;
                        case ApprovalDenied denied:
                            logger.LogWarning("Approval denied for policy {PolicyId}: {Reason}", decision.PolicyId, denied.Reason);
                            break;
                    }
                    break;
                case PolicyNotFound notFound:
                    logger.LogWarning("Claim skipped: policy {PolicyId} not in snapshot", notFound.PolicyId);
                    break;
                case ClaimRejected rejected:
                    logger.LogWarning("Claim for policy {PolicyId} rejected: {Reason}", request.PolicyId, rejected.Reason);
                    break;
            }
        }
    }

    private void AssessRenewals()
    {
        foreach (var policy in policies.Policies)
        {
            // Pattern 3: one ad-hoc query as a lambda over this worker's ledger.
            var outcome = AssessRenewal.Execute(
                policy,
                currentAnnualPremium: 1_000m,
                recentClaimCount: policyId => ledger.Decisions.Count(d => d.PolicyId == policyId),
                clock: timeProvider.GetUtcNow);

            switch (outcome)
            {
                case RenewalOffer offer:
                    logger.LogInformation(
                        "Renewal offer for {Holder} ({PolicyId}): {Premium}",
                        policy.HolderName, offer.PolicyId, offer.RenewalPremium);
                    break;
                case RenewalDeclined declined:
                    logger.LogInformation(
                        "No renewal for {Holder} ({PolicyId}): {Reason}",
                        policy.HolderName, declined.PolicyId, declined.Reason);
                    break;
            }
        }
    }
}
