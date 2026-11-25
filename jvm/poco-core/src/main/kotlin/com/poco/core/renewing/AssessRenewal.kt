package com.poco.core.renewing

import com.poco.core.policies.Policy
import com.poco.core.renewing.RenewalOutcome.Declined
import com.poco.core.renewing.RenewalOutcome.Offered
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class RenewalOffer(val policyId: UUID, val renewalPremium: BigDecimal)

data class RenewalDeclined(val policyId: UUID, val reason: String)

sealed interface RenewalOutcome {
    data class Offered(val offer: RenewalOffer) : RenewalOutcome
    data class Declined(val declined: RenewalDeclined) : RenewalOutcome
}

/**
 * Pattern 3 — a function type for a one-off, single-question dependency.
 * Forcing the consumer to implement an interface just to answer "how many
 * recent claims?" is ceremony; a Kotlin function type lets the caller pass a
 * lambda over whatever it already has. Prefer an interface when several
 * operations share the dependency or its lifetime matters.
 */
object AssessRenewal {
    fun execute(
        policy: Policy,
        currentAnnualPremium: BigDecimal,
        recentClaimCount: (UUID) -> Int,
        clock: Clock,
    ): RenewalOutcome {
        if (!policy.isActive) return Declined(RenewalDeclined(policy.id, "Policy is not active."))

        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

        // Renewal window: from 30 days before expiry until 30 days after it.
        if (today < policy.expiresOn.minusDays(30)) {
            return Declined(RenewalDeclined(policy.id, "Policy is not yet in its renewal window."))
        }
        if (today > policy.expiresOn.plusDays(30)) {
            return Declined(RenewalDeclined(policy.id, "Renewal window has closed."))
        }

        val claims = recentClaimCount(policy.id)
        if (claims >= 5) {
            return Declined(RenewalDeclined(policy.id, "Too many recent claims; manual underwriting required."))
        }

        val factor = if (claims == 0) BigDecimal("0.95") else BigDecimal.ONE + BigDecimal("0.10") * claims.toBigDecimal()
        val premium = (currentAnnualPremium * factor).setScale(2, RoundingMode.HALF_UP)

        return Offered(RenewalOffer(policy.id, premium))
    }
}
