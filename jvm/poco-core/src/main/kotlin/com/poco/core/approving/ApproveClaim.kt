package com.poco.core.approving

import com.poco.core.approving.ClaimApprovalOutcome.Approved
import com.poco.core.approving.ClaimApprovalOutcome.Denied
import com.poco.core.approving.ClaimApprovalOutcome.NotAuthorized
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ClaimApprovalRequest(val policyId: UUID, val payout: BigDecimal, val approver: String)

data class ClaimApproval(val policyId: UUID, val payout: BigDecimal, val approver: String, val approvedAt: Instant)

sealed interface ClaimApprovalOutcome {
    data class Approved(val approval: ClaimApproval) : ClaimApprovalOutcome
    data class Denied(val reason: String) : ClaimApprovalOutcome
    data class NotAuthorized(val approver: String, val payout: BigDecimal) : ClaimApprovalOutcome
}

/**
 * Contract of the operation. "May this approver approve this payout?" is an
 * authorization question, and Core deliberately does not answer it — it only
 * declares the shape of the question. How it is fulfilled is the consumer's
 * business: the API answers from the HTTP caller's authorities, the worker
 * from a service-identity allowlist, a test from a lambda. The contract is
 * enforced by the interface; the implementation is never inspected.
 */
fun interface ClaimAuthorizer {
    fun canApprove(approver: String, payout: BigDecimal): Boolean
}

/** Contract for recording an approval. Storage is the consumer's problem. */
fun interface ApprovalLedger {
    fun record(approval: ClaimApproval)
}

/**
 * Contract-based operation: every environmental concern (authorization,
 * recording, time) arrives as an interface the consumer implements. Core
 * orchestrates the contracts; it cannot tell whether [authorizer] reads
 * security authorities, config, a database or a hardcoded list.
 */
object ApproveClaim {
    fun execute(
        request: ClaimApprovalRequest,
        authorizer: ClaimAuthorizer,
        ledger: ApprovalLedger,
        clock: Clock,
    ): ClaimApprovalOutcome {
        if (request.payout <= BigDecimal.ZERO) return Denied("Payout must be positive.")
        if (request.approver.isBlank()) return Denied("Approver is required.")

        // The one authorization decision in the flow, delegated wholesale to
        // the consumer's contract implementation.
        if (!authorizer.canApprove(request.approver.trim(), request.payout)) {
            return NotAuthorized(request.approver.trim(), request.payout)
        }

        val approval = ClaimApproval(request.policyId, request.payout, request.approver.trim(), clock.instant())
        ledger.record(approval)

        return Approved(approval)
    }
}
