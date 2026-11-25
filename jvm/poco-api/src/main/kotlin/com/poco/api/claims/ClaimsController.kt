package com.poco.api.claims

import com.poco.api.policies.InMemoryPolicyStore
import com.poco.core.approving.ApproveClaim
import com.poco.core.approving.ClaimApprovalOutcome
import com.poco.core.approving.ClaimApprovalRequest
import com.poco.core.approving.ClaimAuthorizer
import com.poco.core.claims.ClaimOutcome
import com.poco.core.claims.ClaimRequest
import com.poco.core.claims.TriageClaim
import com.poco.core.reviewing.ClaimReviewRequest
import com.poco.core.reviewing.ReviewScheduleOutcome
import com.poco.core.reviewing.ScheduleClaimReview
import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/** Transport DTO: who approves is the caller's identity, so it is not in the body. */
data class ApproveClaimBody(val policyId: UUID, val payout: BigDecimal)

/**
 * Vertical slice adapter (HTTP in/out). Pattern 2 in use for triage (this
 * host's stateful store/ledger/clock beans), contract-based approval in use
 * below (this host's ClaimAuthorizer bean reads the caller's authorities).
 */
@RestController
class ClaimsController(
    private val policyStore: InMemoryPolicyStore,
    private val ledger: InMemoryClaimLedger,
    private val claimAuthorizer: ClaimAuthorizer,
    private val reviewQueue: InMemoryReviewQueue,
    private val clock: Clock,
) {
    @PostMapping("/claims")
    fun triageClaim(@RequestBody request: ClaimRequest): ResponseEntity<Any> =
        when (val outcome = TriageClaim.execute(request, policyStore, ledger, clock)) {
            is ClaimOutcome.Decided -> ResponseEntity.ok(outcome.decision)
            is ClaimOutcome.NotFound -> ResponseEntity.notFound().build()
            is ClaimOutcome.Rejected -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
        }

    // This API can serve its ledger back because its ledger is its own type;
    // Core's contracts deliberately have no query surface.
    @GetMapping("/claims/decisions")
    fun claimDecisions(): List<com.poco.core.claims.ClaimDecision> = ledger.decisions

    // The route rule (claims:approve) is the coarse gate; Core then asks this
    // host's authorizer for the fine-grained, amount-aware decision.
    @PostMapping("/claims/approve")
    fun approveClaim(
        @RequestBody body: ApproveClaimBody,
        @AuthenticationPrincipal principalName: String?,
    ): ResponseEntity<Any> =
        when (
            val outcome = ApproveClaim.execute(
                ClaimApprovalRequest(body.policyId, body.payout, principalName ?: ""),
                claimAuthorizer, ledger, clock,
            )
        ) {
            is ClaimApprovalOutcome.Approved -> ResponseEntity.ok(outcome.approval)
            is ClaimApprovalOutcome.Denied -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
            is ClaimApprovalOutcome.NotAuthorized -> ResponseEntity.badRequest()
                .body(mapOf("error" to "'${outcome.approver}' is not authorized to approve ${outcome.payout}."))
        }

    // Asynchronous by nature: 202 + a receipt. The review has NOT happened
    // when this responds; the job waits in the queue. (runBlocking bridges
    // the suspend operation into this servlet controller — a demo shortcut.)
    @PostMapping("/claims/schedule-review")
    fun scheduleClaimReview(@RequestBody request: ClaimReviewRequest): ResponseEntity<Any> = runBlocking {
        when (val outcome = ScheduleClaimReview.execute(request, reviewQueue, java.util.UUID.randomUUID(), clock)) {
            is ReviewScheduleOutcome.Queued -> ResponseEntity.accepted().body(outcome.receipt)
            is ReviewScheduleOutcome.Rejected -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
        }
    }

    @GetMapping("/claims/review-jobs")
    fun claimReviewJobs() = reviewQueue.jobs

    @GetMapping("/claims/approvals")
    fun claimApprovals() = ledger.approvals
}
