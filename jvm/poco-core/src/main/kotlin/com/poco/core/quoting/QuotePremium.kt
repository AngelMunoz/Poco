package com.poco.core.quoting

import com.poco.core.quoting.QuoteOutcome.Quoted
import com.poco.core.quoting.QuoteOutcome.Rejected
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

enum class RiskLevel { Low, Medium, High }

data class QuoteRequest(
    val holderName: String,
    val age: Int,
    val region: String,
    val coverageAmount: BigDecimal,
    val risk: RiskLevel,
)

data class Quote(
    val id: UUID,
    val holderName: String,
    val region: String,
    val risk: RiskLevel,
    val coverageAmount: BigDecimal,
    val annualPremium: BigDecimal,
    val quotedAt: Instant,
    val validUntil: LocalDate,
)

// Kotlin's answer to the C# union: a sealed interface whose cases are data
// classes. `when` over it is exhaustive — adding a case breaks every caller
// that has not handled it yet, exactly the feedback loop we want in a monorepo.
sealed interface QuoteOutcome {
    data class Quoted(val quote: Quote) : QuoteOutcome
    data class Rejected(val reason: String) : QuoteOutcome
}

/**
 * Pattern 1 — a simple function taking the right inputs.
 *
 * Every dependency has already been resolved by the caller into a plain value
 * (tariff rate, id, timestamp), so this operation needs no interface and no
 * container. Same inputs, same output. The caller owns where the tariff rate
 * came from: config, a table, a remote pricing service — Core does not care.
 */
object QuotePremium {
    fun execute(request: QuoteRequest, tariffRate: BigDecimal, quoteId: UUID, quotedAt: Instant): QuoteOutcome {
        if (request.holderName.isBlank()) return Rejected("Holder name is required.")
        if (request.age !in 18..85) return Rejected("Holder must be between 18 and 85 years old.")
        if (request.coverageAmount <= BigDecimal.ZERO) return Rejected("Coverage amount must be positive.")
        if (tariffRate <= BigDecimal.ZERO) return Rejected("No tariff rate supplied for region '${request.region}'.")

        val ageFactor = when {
            request.age < 25 -> BigDecimal("1.25")
            request.age > 65 -> BigDecimal("1.40")
            else -> BigDecimal.ONE
        }
        val premium = (request.coverageAmount * tariffRate * ageFactor).setScale(2, RoundingMode.HALF_UP)

        return Quoted(
            Quote(
                id = quoteId,
                holderName = request.holderName.trim(),
                region = request.region,
                risk = request.risk,
                coverageAmount = request.coverageAmount,
                annualPremium = premium,
                quotedAt = quotedAt,
                validUntil = LocalDate.ofInstant(quotedAt, ZoneOffset.UTC).plusDays(30),
            ),
        )
    }
}
