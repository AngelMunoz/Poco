using Poco.Core.Policies;

namespace Poco.Core.Verifying;

public sealed record PolicyCoverage(Guid PolicyId, decimal CoverageLimit, decimal CoveredAmount);

public sealed record CoverageRejected(string Reason);

/// <summary>A final answer: covered (with amounts), rejected, or no such policy.</summary>
public union PolicyVerificationOutcome(PolicyCoverage, CoverageRejected, PolicyNotFoundResult);

public sealed record PolicyNotFoundResult(Guid PolicyId);

/// <summary>
/// Contract: look a policy up somewhere that takes time — another service,
/// a database across the network. That is why it returns a
/// <see cref="ValueTask{TResult}"/> instead of a plain value.
/// </summary>
public interface IAsyncPolicyRegistry
{
    ValueTask<Policy?> FindAsync(Guid policyId, CancellationToken cancellationToken);
}

/// <summary>
/// Asynchronous in IMPLEMENTATION, synchronous in MEANING — the opposite of
/// ScheduleClaimReview. The lookup may involve I/O, so the operation is
/// async and the caller awaits it; but when the await completes, the caller
/// holds the final answer, exactly as if the operation had been a plain
/// function. Nothing is queued, nothing continues afterwards. Whether an
/// operation returns <see cref="Task"/> is a plumbing question; whether the
/// work is finished when it returns is the design question.
/// </summary>
public static class VerifyPolicyCoverage
{
    public static async ValueTask<PolicyVerificationOutcome> ExecuteAsync(
        Guid policyId,
        decimal claimedAmount,
        IAsyncPolicyRegistry registry,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(registry);

        if (claimedAmount <= 0)
            return new CoverageRejected("Claimed amount must be positive.");

        var policy = await registry.FindAsync(policyId, cancellationToken).ConfigureAwait(false);
        if (policy is null)
            return new PolicyNotFoundResult(policyId);

        if (!policy.IsActive)
            return new CoverageRejected("Policy is not active.");

        var covered = Math.Max(0m, Math.Min(claimedAmount, policy.CoverageLimit) - policy.Deductible);

        return new PolicyCoverage(policy.Id, policy.CoverageLimit, covered);
    }
}
