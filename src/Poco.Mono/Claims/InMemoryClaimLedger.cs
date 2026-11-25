using Poco.Core.Approving;
using Poco.Core.Claims;

namespace Poco.Mono.Claims;

/// <summary>
/// Mono's environment for Pattern 2's sink: decisions accumulate in memory
/// so they can be served back over HTTP (GET /claims/decisions).
/// The Worker supplies a completely different ledger for the same contract.
/// </summary>
public sealed class InMemoryClaimLedger : IClaimLedger, IApprovalLedger
{
    private readonly List<ClaimDecision> _decisions = [];

    public IReadOnlyList<ClaimDecision> Decisions => _decisions;

    public void Record(ClaimDecision decision) => _decisions.Add(decision);

    private readonly List<ClaimApproval> _approvals = [];

    public IReadOnlyList<ClaimApproval> Approvals => _approvals;

    public void Record(ClaimApproval approval) => _approvals.Add(approval);
}
