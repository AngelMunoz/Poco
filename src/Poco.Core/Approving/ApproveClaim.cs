namespace Poco.Core.Approving;

public sealed record ClaimApprovalRequest(Guid PolicyId, decimal Payout, string Approver);

public sealed record ClaimApproval(Guid PolicyId, decimal Payout, string Approver, DateTimeOffset ApprovedAt);

public sealed record ApprovalDenied(string Reason);

public sealed record ApprovalNotAuthorized(string Approver, decimal Payout);

/// <summary>C# 15 union: approved, denied on the merits, or not authorized to approve.</summary>
public union ClaimApprovalOutcome(ClaimApproval, ApprovalDenied, ApprovalNotAuthorized);

/// <summary>
/// Contract of the operation — this is the showcase the team asked for.
///
/// "May this approver approve this payout?" is an authorization question,
/// and Core deliberately does not answer it. Core only declares the shape
/// of the question. How it is fulfilled is entirely the consumer's business:
/// Mono answers it from the current HTTP user's permission claims, the
/// Worker answers it from a service-identity allowlist with a lower limit,
/// and a test answers it with a one-line stub. The contract is enforced by
/// the interface; the implementation is never inspected.
/// </summary>
public interface IClaimAuthorizer
{
    bool CanApprove(string approver, decimal payout);
}

/// <summary>Contract for recording an approval. Storage is the consumer's problem.</summary>
public interface IApprovalLedger
{
    void Record(ClaimApproval approval);
}

/// <summary>
/// Contract-based operation: every environmental concern (authorization,
/// recording, time) arrives as an interface the consumer implements.
/// Core orchestrates the contracts; it cannot tell — and does not care —
/// whether <paramref name="authorizer"/> reads claims, config, a database
/// or a hardcoded list behind the interface.
/// </summary>
public static class ApproveClaim
{
    public static ClaimApprovalOutcome Execute(
        ClaimApprovalRequest request,
        IClaimAuthorizer authorizer,
        IApprovalLedger ledger,
        TimeProvider timeProvider)
    {
        ArgumentNullException.ThrowIfNull(authorizer);
        ArgumentNullException.ThrowIfNull(ledger);
        ArgumentNullException.ThrowIfNull(timeProvider);

        if (request.Payout <= 0)
            return new ApprovalDenied("Payout must be positive.");

        if (string.IsNullOrWhiteSpace(request.Approver))
            return new ApprovalDenied("Approver is required.");

        // The one authorization decision in the flow, delegated wholesale
        // to the consumer's contract implementation.
        if (!authorizer.CanApprove(request.Approver.Trim(), request.Payout))
            return new ApprovalNotAuthorized(request.Approver.Trim(), request.Payout);

        var approval = new ClaimApproval(
            request.PolicyId, request.Payout, request.Approver.Trim(), timeProvider.GetUtcNow());

        ledger.Record(approval);

        return approval;
    }
}
