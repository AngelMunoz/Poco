package com.poco.api.claims

import com.poco.core.approving.ApprovalLedger
import com.poco.core.approving.ClaimApproval
import com.poco.core.claims.ClaimDecision
import com.poco.core.claims.ClaimLedger

/**
 * This API's fulfilment of two Core contracts: decisions and approvals
 * accumulate in memory so they can be served back over HTTP. The worker
 * fulfils the same contracts with a logging ledger instead.
 */
class InMemoryClaimLedger : ClaimLedger, ApprovalLedger {
    private val recordedDecisions = mutableListOf<ClaimDecision>()
    private val recordedApprovals = mutableListOf<ClaimApproval>()

    val decisions: List<ClaimDecision> get() = synchronized(recordedDecisions) { recordedDecisions.toList() }
    val approvals: List<ClaimApproval> get() = synchronized(recordedApprovals) { recordedApprovals.toList() }

    override fun record(decision: ClaimDecision) {
        synchronized(recordedDecisions) { recordedDecisions.add(decision) }
    }

    override fun record(approval: ClaimApproval) {
        synchronized(recordedApprovals) { recordedApprovals.add(approval) }
    }
}
