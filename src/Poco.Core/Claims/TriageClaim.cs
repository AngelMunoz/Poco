using Poco.Core.Policies;

namespace Poco.Core.Claims;

public sealed record ClaimRequest(Guid PolicyId, decimal ClaimedAmount, string Category);

public sealed record ClaimDecision(
    Guid PolicyId,
    string Category,
    decimal Payout,
    bool RequiresManualReview,
    DateTimeOffset TriagedAt);

public sealed record ClaimRejected(string Reason);

public sealed record PolicyNotFound(Guid PolicyId);

/// <summary>
/// C# 15 union: the decision itself is the success case, rejection and
/// not-found are sibling domain cases. Consumers switch exhaustively —
/// there is no discard arm to hide a future case behind.
/// </summary>
public union ClaimOutcome(ClaimDecision, ClaimRejected, PolicyNotFound);

/// <summary>
/// Pattern 2 (contract) — a stateful dependency supplied by the consumer.
///
/// Unlike a one-off value, a policy store has a lifetime: it may hold a
/// DbContext, a connection, a cache or an open file. Core therefore describes
/// it once as an interface and lets each consumer hand over <em>its</em>
/// instance. Core never constructs, registers or disposes it.
/// </summary>
public interface IPolicyStore
{
    Policy? Find(Guid policyId);
}

/// <summary>
/// Pattern 2 (contract) — a stateful sink. Recording a decision is a side
/// effect the consumer owns: Mono keeps decisions in memory to serve them
/// over HTTP, the Worker accumulates totals and logs them per batch.
/// Same contract, unrelated implementations.
/// </summary>
public interface IClaimLedger
{
    void Record(ClaimDecision decision);
}

/// <summary>
/// Pattern 2 — an operation that requires stateful instances directly.
///
/// Dependencies arrive as constructor-free method parameters on every call:
/// nothing is captured in a service, nothing is resolved from a container
/// inside Core. <see cref="TimeProvider"/> is the BCL's own example of this
/// idea — an instance (not a static clock) so consumers and tests can supply
/// a real or a fake one without Core changing.
/// </summary>
public static class TriageClaim
{
    private const decimal ManualReviewThreshold = 10_000m;

    public static ClaimOutcome Execute(
        ClaimRequest request,
        IPolicyStore policies,
        IClaimLedger ledger,
        TimeProvider timeProvider)
    {
        ArgumentNullException.ThrowIfNull(policies);
        ArgumentNullException.ThrowIfNull(ledger);
        ArgumentNullException.ThrowIfNull(timeProvider);

        if (request.ClaimedAmount <= 0)
            return new ClaimRejected("Claimed amount must be positive.");

        if (string.IsNullOrWhiteSpace(request.Category))
            return new ClaimRejected("Claim category is required.");

        var policy = policies.Find(request.PolicyId);
        if (policy is null)
            return new PolicyNotFound(request.PolicyId);

        var now = timeProvider.GetUtcNow();

        if (!policy.IsActive)
            return new ClaimRejected("Policy is not active.");

        if (policy.ExpiresOn < DateOnly.FromDateTime(now.DateTime))
            return new ClaimRejected("Policy has expired.");

        var payout = Math.Max(0m, Math.Min(request.ClaimedAmount, policy.CoverageLimit) - policy.Deductible);

        var decision = new ClaimDecision(
            policy.Id,
            request.Category.Trim(),
            payout,
            payout > ManualReviewThreshold,
            now);

        // The one deliberate side effect, and it goes through the consumer's
        // instance — Core itself writes nothing anywhere.
        ledger.Record(decision);

        return decision;
    }
}
