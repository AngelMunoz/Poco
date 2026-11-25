using Poco.Core.Policies;
using Poco.Core.Quoting;

namespace Poco.Core.Issuing;

public sealed record BillingCharged(string Reference);

public sealed record BillingDeclined(string Reason);

/// <summary>What a billing contract may answer: charged (with a reference) or declined (with a reason).</summary>
public union BillingOutcome(BillingCharged, BillingDeclined);

/// <summary>
/// Contract: take the holder's money for the first premium.
/// Mono fulfils it with a demo card gateway, the Worker with monthly
/// invoicing, production with a payment provider — Core sees the same union.
/// </summary>
public interface IBillingGateway
{
    BillingOutcome Charge(string holderName, decimal annualPremium);
}

/// <summary>Contract: persist an issued policy. EF, Dapper, a list — the consumer chooses.</summary>
public interface IPolicyRepository
{
    void Save(Policy policy);
}

public sealed record PolicyIssueRejected(string Reason);

/// <summary>C# 15 union: the issued <see cref="Policy"/> itself, or why it was not issued.</summary>
public union PolicyIssueOutcome(Policy, PolicyIssueRejected);

/// <summary>
/// Contract-based operation composing two consumer contracts: it charges
/// through <see cref="IBillingGateway"/> first (no charge, no policy) and
/// only then persists through <see cref="IPolicyRepository"/>. Core defines
/// the orchestration and the vocabulary (<see cref="BillingOutcome"/>);
/// every fulfilment detail lives behind the interfaces, in the consumer.
/// </summary>
public static class IssuePolicy
{
    private const decimal DefaultDeductible = 500m;

    public static PolicyIssueOutcome Execute(
        Quote quote,
        IPolicyRepository policies,
        IBillingGateway billing,
        TimeProvider timeProvider)
    {
        ArgumentNullException.ThrowIfNull(policies);
        ArgumentNullException.ThrowIfNull(billing);
        ArgumentNullException.ThrowIfNull(timeProvider);

        var today = DateOnly.FromDateTime(timeProvider.GetUtcNow().DateTime);

        if (quote.ValidUntil < today)
            return new PolicyIssueRejected("Quote has expired; re-quote before issuing.");

        switch (billing.Charge(quote.HolderName, quote.AnnualPremium))
        {
            case BillingDeclined declined:
                return new PolicyIssueRejected($"Payment declined: {declined.Reason}");
            case BillingCharged:
                break;
        }

        var policy = new Policy(
            quote.Id,
            quote.HolderName,
            quote.CoverageAmount,
            DefaultDeductible,
            today.AddYears(1),
            IsActive: true);

        policies.Save(policy);

        return policy;
    }
}
