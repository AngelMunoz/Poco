using Poco.Core.Approving;
using Poco.Core.Claims;

namespace Poco.Worker.Environment;

/// <summary>
/// The Worker's environment for Pattern 2's sink: instead of serving
/// decisions over HTTP like Mono, it accumulates running totals across
/// ticks and logs each decision. Stateful by design — the counts only make
/// sense because the same instance lives for the whole host lifetime.
/// </summary>
public sealed class LoggingClaimLedger(ILogger<LoggingClaimLedger> logger) : IClaimLedger, IApprovalLedger
{
    private readonly List<ClaimDecision> _decisions = [];

    public IReadOnlyList<ClaimDecision> Decisions => _decisions;

    public decimal TotalPayout { get; private set; }

    public void Record(ClaimDecision decision)
    {
        _decisions.Add(decision);
        TotalPayout += decision.Payout;
        logger.LogInformation(
            "Claim triaged for policy {PolicyId}: payout {Payout}, manual review {RequiresManualReview} (ledger total {TotalPayout}, {Count} decisions)",
            decision.PolicyId, decision.Payout, decision.RequiresManualReview, TotalPayout, _decisions.Count);
    }

    // IApprovalLedger fulfilment: approvals accumulate alongside decisions.
    private readonly List<ClaimApproval> _approvals = [];

    public IReadOnlyList<ClaimApproval> Approvals => _approvals;

    public void Record(ClaimApproval approval)
    {
        _approvals.Add(approval);
        logger.LogInformation(
            "Claim approved for policy {PolicyId}: payout {Payout} by {Approver} ({Count} approvals)",
            approval.PolicyId, approval.Payout, approval.Approver, _approvals.Count);
    }
}
