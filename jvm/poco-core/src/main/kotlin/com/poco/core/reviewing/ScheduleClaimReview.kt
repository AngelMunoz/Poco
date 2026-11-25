package com.poco.core.reviewing

import com.poco.core.reviewing.ReviewScheduleOutcome.Queued
import com.poco.core.reviewing.ReviewScheduleOutcome.Rejected
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ClaimReviewRequest(val policyId: UUID, val payout: BigDecimal, val reason: String)

data class ClaimReviewJob(
    val jobId: UUID,
    val policyId: UUID,
    val payout: BigDecimal,
    val reason: String,
    val requestedAt: Instant,
)

data class ClaimReviewReceipt(val jobId: UUID, val policyId: UUID, val status: String, val requestedAt: Instant)

// Queued (a receipt, not a decision) or not even queued. There is deliberately
// no "reviewed" case: the review has not happened when this returns.
sealed interface ReviewScheduleOutcome {
    data class Queued(val receipt: ClaimReviewReceipt) : ReviewScheduleOutcome
    data class Rejected(val reason: String) : ReviewScheduleOutcome
}

/**
 * Contract: put this job somewhere it will be picked up later. A queue, a
 * table, an outbox — the consumer chooses. Enqueue returning means
 * "accepted for later", never "done".
 */
interface ReviewJobQueue {
    suspend fun enqueue(job: ClaimReviewJob)
}

/**
 * Asynchronous BY NATURE: this operation's job is to start something, not to
 * finish it. It validates, enqueues a human-review job, and returns a
 * receipt. The review happens later, somewhere else. Callers must not read
 * the receipt as an approval — the sealed result has no approved case.
 * HTTP adapters map this to 202 Accepted; the receipt is the whole answer.
 */
object ScheduleClaimReview {
    suspend fun execute(request: ClaimReviewRequest, queue: ReviewJobQueue, jobId: UUID, clock: Clock): ReviewScheduleOutcome {
        if (request.policyId == UUID(0, 0)) return Rejected("Policy id is required.")
        if (request.payout <= BigDecimal.ZERO) return Rejected("Payout must be positive.")
        if (request.reason.isBlank()) return Rejected("A reason for the review is required.")

        val now = clock.instant()
        val job = ClaimReviewJob(jobId, request.policyId, request.payout, request.reason.trim(), now)

        // The async step is the point: once the queue accepts the job, this
        // operation is over — successfully, with nothing reviewed yet.
        queue.enqueue(job)

        return Queued(ClaimReviewReceipt(job.jobId, job.policyId, "Queued", now))
    }
}
