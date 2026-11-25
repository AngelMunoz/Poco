package com.poco.worker.environment

import com.poco.core.approving.ApprovalLedger
import com.poco.core.approving.ClaimApproval
import com.poco.core.approving.ClaimAuthorizer
import com.poco.core.claims.ClaimDecision
import com.poco.core.claims.ClaimLedger
import com.poco.core.claims.PolicyStore
import com.poco.core.issuing.BillingGateway
import com.poco.core.issuing.BillingOutcome
import com.poco.core.issuing.PolicyRepository
import com.poco.core.policies.Policy
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The worker's fulfilment of the lookup/save contracts: a batch snapshot —
 * data as it stood when the run started, with no HTTP request in sight.
 * The API fulfils the same interfaces with its own store; Core cannot tell.
 */
class WorkerPolicyStore : PolicyStore, PolicyRepository {
    private val policies = mutableListOf<Policy>()

    init {
        val today = LocalDate.now()
        policies += Policy(UUID.fromString("11111111-1111-1111-1111-111111111111"), "Alice Meyer", BigDecimal("50000"), BigDecimal("500"), today.plusDays(20), true)
        policies += Policy(UUID.fromString("22222222-2222-2222-2222-222222222222"), "Bob Chen", BigDecimal("5000"), BigDecimal("250"), today.plusDays(180), true)
    }

    val all: List<Policy> get() = policies.toList()

    var lookupCount = 0
        private set

    override fun find(policyId: UUID): Policy? {
        lookupCount++
        return policies.firstOrNull { it.id == policyId }
    }

    override fun save(policy: Policy) {
        policies.removeAll { it.id == policy.id }
        policies.add(policy)
    }
}

/**
 * The worker's fulfilment of the sink contracts: instead of serving
 * decisions over HTTP, it accumulates running totals and prints each one.
 * Stateful by design — the totals only make sense because the same instance
 * lives for the whole process.
 */
class LoggingClaimLedger : ClaimLedger, ApprovalLedger {
    private val recordedDecisions = mutableListOf<ClaimDecision>()
    private val recordedApprovals = mutableListOf<ClaimApproval>()

    val decisions: List<ClaimDecision> get() = recordedDecisions.toList()
    var totalPayout: BigDecimal = BigDecimal.ZERO
        private set

    override fun record(decision: ClaimDecision) {
        recordedDecisions += decision
        totalPayout += decision.payout
        println(
            "Claim triaged for policy ${decision.policyId}: payout ${decision.payout}, " +
                "manual review ${decision.requiresManualReview} (ledger total $totalPayout, ${recordedDecisions.size} decisions)",
        )
    }

    override fun record(approval: ClaimApproval) {
        recordedApprovals += approval
        println(
            "Claim approved for policy ${approval.policyId}: payout ${approval.payout} " +
                "by ${approval.approver} (${recordedApprovals.size} approvals)",
        )
    }
}

/**
 * The worker's fulfilment of the authorizer contract: there is no user and
 * no authorities here. The batch service identity may auto-approve only
 * small payouts; everything larger is left for a human. Same interface,
 * same Core operation, unrelated auth model.
 */
object ServiceClaimAuthorizer : ClaimAuthorizer {
    const val SERVICE_IDENTITY = "poco-worker"

    private val AUTO_APPROVE_LIMIT = BigDecimal("10000")

    override fun canApprove(approver: String, payout: BigDecimal): Boolean =
        approver == SERVICE_IDENTITY && payout <= AUTO_APPROVE_LIMIT
}

/** The worker's fulfilment of the billing contract: monthly invoicing. */
object InvoiceBillingGateway : BillingGateway {
    override fun charge(holderName: String, annualPremium: BigDecimal): BillingOutcome =
        BillingOutcome.Charged("INV-${holderName.replace(' ', '-')}-$annualPremium")
}

/**
 * The worker's fulfilment of the review-queue contract: jobs collect here
 * and are picked up on a later tick — visibly separate from the moment they
 * were scheduled. A broker would replace this class in production; the
 * timing gap, not the transport, is what this demonstrates.
 */
class WorkerReviewQueue : com.poco.core.reviewing.ReviewJobQueue {
    private val pending = ArrayDeque<com.poco.core.reviewing.ClaimReviewJob>()

    val pendingCount: Int get() = pending.size

    fun enqueueNow(job: com.poco.core.reviewing.ClaimReviewJob) {
        pending.add(job)
        println("Review job ${job.jobId} queued for policy ${job.policyId} (payout ${job.payout}): ${job.reason}")
    }

    override suspend fun enqueue(job: com.poco.core.reviewing.ClaimReviewJob) = enqueueNow(job)

    /** Simulates the later, separate step where a queued job is actually worked. */
    fun processNext(): Boolean {
        val job = pending.removeFirstOrNull() ?: return false
        println("Review job ${job.jobId} picked up on a later tick for policy ${job.policyId} — where a human review would start (scheduled at ${job.requestedAt})")
        return true
    }
}
