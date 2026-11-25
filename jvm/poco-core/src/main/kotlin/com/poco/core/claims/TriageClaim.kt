package com.poco.core.claims

import com.poco.core.claims.ClaimOutcome.Decided
import com.poco.core.claims.ClaimOutcome.NotFound
import com.poco.core.claims.ClaimOutcome.Rejected
import com.poco.core.policies.Policy
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class ClaimRequest(val policyId: UUID, val claimedAmount: BigDecimal, val category: String)

data class ClaimDecision(
    val policyId: UUID,
    val category: String,
    val payout: BigDecimal,
    val requiresManualReview: Boolean,
    val triagedAt: Instant,
)

sealed interface ClaimOutcome {
    data class Decided(val decision: ClaimDecision) : ClaimOutcome
    data class Rejected(val reason: String) : ClaimOutcome
    data class NotFound(val policyId: UUID) : ClaimOutcome
}

/**
 * Pattern 2 (contract) — a stateful dependency supplied by the consumer.
 * A policy store has a lifetime: it may hold a JPA session, a connection or
 * a cache. Core describes it once as an interface and lets each consumer
 * hand over its instance. `fun interface` keeps single-method contracts
 * lambda-friendly for tests and small Java/Kotlin call sites.
 */
fun interface PolicyStore {
    fun find(policyId: UUID): Policy?
}

/** Pattern 2 (contract) — a stateful sink. Where decisions go is the consumer's call. */
fun interface ClaimLedger {
    fun record(decision: ClaimDecision)
}

private val MANUAL_REVIEW_THRESHOLD = BigDecimal("10000")

/**
 * Pattern 2 — an operation that requires stateful instances directly.
 * [Clock] is the JVM's own version of this idea: an object (not a static
 * "now") so consumers and tests supply a real or fixed clock.
 */
object TriageClaim {
    fun execute(request: ClaimRequest, policies: PolicyStore, ledger: ClaimLedger, clock: Clock): ClaimOutcome {
        if (request.claimedAmount <= BigDecimal.ZERO) return Rejected("Claimed amount must be positive.")
        if (request.category.isBlank()) return Rejected("Claim category is required.")

        val policy = policies.find(request.policyId) ?: return NotFound(request.policyId)
        val now = clock.instant()

        if (!policy.isActive) return Rejected("Policy is not active.")
        if (policy.expiresOn < LocalDate.ofInstant(now, ZoneOffset.UTC)) return Rejected("Policy has expired.")

        val payout = maxOf(BigDecimal.ZERO, minOf(request.claimedAmount, policy.coverageLimit) - policy.deductible)
        val decision = ClaimDecision(policy.id, request.category.trim(), payout, payout > MANUAL_REVIEW_THRESHOLD, now)

        // The one deliberate side effect, through the consumer's instance.
        ledger.record(decision)

        return Decided(decision)
    }
}
