package com.poco.worker

import com.poco.core.approving.ApproveClaim
import com.poco.core.approving.ClaimApprovalOutcome
import com.poco.core.approving.ClaimApprovalRequest
import com.poco.core.claims.ClaimOutcome
import com.poco.core.claims.ClaimRequest
import com.poco.core.claims.TriageClaim
import com.poco.core.issuing.IssuePolicy
import com.poco.core.issuing.PolicyIssueOutcome
import com.poco.core.quoting.QuoteOutcome
import com.poco.core.quoting.QuotePremium
import com.poco.core.quoting.QuoteRequest
import com.poco.core.quoting.RiskLevel
import com.poco.core.renewing.AssessRenewal
import com.poco.core.reviewing.ClaimReviewRequest
import com.poco.core.reviewing.ReviewScheduleOutcome
import com.poco.core.reviewing.ScheduleClaimReview
import com.poco.core.renewing.RenewalOutcome
import kotlinx.coroutines.runBlocking
import com.poco.worker.environment.InvoiceBillingGateway
import com.poco.worker.environment.LoggingClaimLedger
import com.poco.worker.environment.WorkerReviewQueue
import com.poco.worker.environment.ServiceClaimAuthorizer
import com.poco.worker.environment.WorkerPolicyStore
import java.math.BigDecimal
import java.time.Clock
import java.util.ArrayDeque
import java.util.UUID

/**
 * Batch consumer of the same Core operations the API serves over HTTP.
 * Scoping differs on purpose: no request scope here, so this process holds
 * its stateful dependencies for its whole lifetime. Core never assumed a
 * scope to begin with. Pending queues stand in for a real feed.
 */
private val tariffSnapshot: Map<String, BigDecimal> = mapOf(
    "EU-Low" to BigDecimal("0.020"),
    "EU-Medium" to BigDecimal("0.035"),
    "EU-High" to BigDecimal("0.060"),
    "US-Low" to BigDecimal("0.025"),
)

private val pendingQuotes = ArrayDeque(
    listOf(
        QuoteRequest("Dana Novak", 34, "EU", BigDecimal("40000"), RiskLevel.Medium),
        QuoteRequest("Eli Ross", 71, "US", BigDecimal("25000"), RiskLevel.Low),
        QuoteRequest("Too Young", 16, "EU", BigDecimal("10000"), RiskLevel.Low),
    ),
)

private val reviewQueue = WorkerReviewQueue()

private val pendingClaims = ArrayDeque(
    listOf(
        ClaimRequest(UUID.fromString("11111111-1111-1111-1111-111111111111"), BigDecimal("18000"), "Water damage"),
        ClaimRequest(UUID.fromString("22222222-2222-2222-2222-222222222222"), BigDecimal("900"), "Glass"),
        ClaimRequest(UUID.fromString("99999999-9999-9999-9999-999999999999"), BigDecimal("100"), "Theft"),
    ),
)

fun main() {
    val clock = Clock.systemUTC()
    val policies = WorkerPolicyStore()
    val ledger = LoggingClaimLedger()

    println("Poco worker (JVM) started as ${ServiceClaimAuthorizer.SERVICE_IDENTITY}; ticking every 5s")

    while (true) {
        repricePendingQuotes(policies, clock)
        triagePendingClaims(policies, ledger, clock)
        assessRenewals(policies, ledger, clock)
        // Queued review jobs are worked on their own schedule — a later
        // tick than the one that scheduled them.
        reviewQueue.processNext()
        println("Batch tick complete: ${policies.lookupCount} policy lookups so far, ledger total ${ledger.totalPayout}")
        Thread.sleep(5_000)
    }
}

private fun repricePendingQuotes(policies: WorkerPolicyStore, clock: Clock) {
    while (true) {
        val request = pendingQuotes.poll() ?: break
        val tariffRate = tariffSnapshot["${request.region}-${request.risk}"] ?: BigDecimal.ZERO

        when (val outcome = QuotePremium.execute(request, tariffRate, UUID.randomUUID(), clock.instant())) {
            is QuoteOutcome.Quoted -> {
                val quote = outcome.quote
                println("Repriced quote for ${quote.holderName}: premium ${quote.annualPremium} (valid until ${quote.validUntil})")
                when (val issued = IssuePolicy.execute(quote, policies, InvoiceBillingGateway, clock)) {
                    is PolicyIssueOutcome.Issued -> println(
                        "Policy issued for ${issued.policy.holderName}: coverage ${issued.policy.coverageLimit}, expires ${issued.policy.expiresOn}",
                    )
                    is PolicyIssueOutcome.Rejected -> println("Policy not issued for ${quote.holderName}: ${issued.reason}")
                }
            }
            is QuoteOutcome.Rejected -> println("Quote for ${request.holderName} rejected: ${outcome.reason}")
        }
    }
}

private fun triagePendingClaims(policies: WorkerPolicyStore, ledger: LoggingClaimLedger, clock: Clock) {
    while (true) {
        val request = pendingClaims.poll() ?: break

        when (val outcome = TriageClaim.execute(request, policies, ledger, clock)) {
            is ClaimOutcome.Decided -> {
                val decision = outcome.decision
                // Same Core operation the API gates by authorities; the
                // worker's service authorizer auto-approves only small payouts.
                when (
                    val approval = ApproveClaim.execute(
                        ClaimApprovalRequest(decision.policyId, decision.payout, ServiceClaimAuthorizer.SERVICE_IDENTITY),
                        ServiceClaimAuthorizer, ledger, clock,
                    )
                ) {
                    is ClaimApprovalOutcome.Approved -> Unit // the ledger already printed it
                    is ClaimApprovalOutcome.NotAuthorized -> println(
                        "Payout ${decision.payout} for policy ${decision.policyId} exceeds the worker auto-approve limit; left for human approval",
                    )
                    is ClaimApprovalOutcome.Denied -> println("Approval denied for policy ${decision.policyId}: ${approval.reason}")
                }

                // Asynchronous by nature: flagged for manual review, the
                // worker does not review now. It schedules and moves on.
                if (decision.requiresManualReview) {
                    val scheduled = runBlocking {
                        ScheduleClaimReview.execute(
                            ClaimReviewRequest(decision.policyId, decision.payout, "Payout above the manual-review threshold"),
                            reviewQueue, UUID.randomUUID(), clock,
                        )
                    }
                    if (scheduled is ReviewScheduleOutcome.Queued) {
                        println("Review scheduled as job ${scheduled.receipt.jobId} with status ${scheduled.receipt.status} — not reviewed yet, by design")
                    }
                }
            }
            is ClaimOutcome.NotFound -> println("Claim skipped: policy ${outcome.policyId} not in snapshot")
            is ClaimOutcome.Rejected -> println("Claim for policy ${request.policyId} rejected: ${outcome.reason}")
        }
    }
}

private fun assessRenewals(policies: WorkerPolicyStore, ledger: LoggingClaimLedger, clock: Clock) {
    for (policy in policies.all) {
        when (
            val outcome = AssessRenewal.execute(
                policy, BigDecimal("1000"),
                recentClaimCount = { policyId -> ledger.decisions.count { it.policyId == policyId } },
                clock = clock,
            )
        ) {
            is RenewalOutcome.Offered -> println("Renewal offer for ${policy.holderName} (${policy.id}): ${outcome.offer.renewalPremium}")
            is RenewalOutcome.Declined -> println("No renewal for ${policy.holderName} (${policy.id}): ${outcome.declined.reason}")
        }
    }
}
