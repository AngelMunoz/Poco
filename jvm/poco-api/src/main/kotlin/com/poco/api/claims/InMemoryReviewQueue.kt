package com.poco.api.claims

import com.poco.core.reviewing.ClaimReviewJob
import com.poco.core.reviewing.ReviewJobQueue

/**
 * This API's fulfilment of the review-queue contract: jobs wait in a list
 * until something picks them up. The list is exposed so the demo can show
 * the important part — after scheduling, the job still sits here,
 * un-reviewed. A broker/outbox would replace this class in production.
 */
class InMemoryReviewQueue : ReviewJobQueue {
    private val recordedJobs = mutableListOf<ClaimReviewJob>()

    val jobs: List<ClaimReviewJob> get() = synchronized(recordedJobs) { recordedJobs.toList() }

    override suspend fun enqueue(job: ClaimReviewJob) {
        synchronized(recordedJobs) { recordedJobs.add(job) }
    }
}
