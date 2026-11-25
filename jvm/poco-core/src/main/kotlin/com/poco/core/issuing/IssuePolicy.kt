package com.poco.core.issuing

import com.poco.core.issuing.BillingOutcome.Charged
import com.poco.core.issuing.BillingOutcome.Declined
import com.poco.core.issuing.PolicyIssueOutcome.Issued
import com.poco.core.issuing.PolicyIssueOutcome.Rejected
import com.poco.core.policies.Policy
import com.poco.core.quoting.Quote
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

// What a billing contract may answer: charged (with a reference) or declined (with a reason).
// The case classes are the vocabulary — no wrapper types needed.
sealed interface BillingOutcome {
    data class Charged(val reference: String) : BillingOutcome
    data class Declined(val reason: String) : BillingOutcome
}

/**
 * Contract: take the holder's money for the first premium. The API fulfils it
 * with a demo card gateway, the worker with monthly invoicing, production
 * with a payment provider — Core sees the same sealed result either way.
 */
interface BillingGateway {
    fun charge(holderName: String, annualPremium: BigDecimal): BillingOutcome
}

/** Contract: persist an issued policy. JPA, JDBC, a list — the consumer chooses. */
fun interface PolicyRepository {
    fun save(policy: Policy)
}

sealed interface PolicyIssueOutcome {
    data class Issued(val policy: Policy) : PolicyIssueOutcome
    data class Rejected(val reason: String) : PolicyIssueOutcome
}

private val DEFAULT_DEDUCTIBLE = BigDecimal("500")

/**
 * Contract-based operation composing two consumer contracts: charge through
 * [BillingGateway] first (no charge, no policy), then persist through
 * [PolicyRepository]. Core defines the orchestration and the vocabulary;
 * every fulfilment detail lives behind the interfaces, in the consumer.
 */
object IssuePolicy {
    fun execute(quote: Quote, policies: PolicyRepository, billing: BillingGateway, clock: Clock): PolicyIssueOutcome {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

        if (quote.validUntil < today) return Rejected("Quote has expired; re-quote before issuing.")

        when (val billingResult = billing.charge(quote.holderName, quote.annualPremium)) {
            is Declined -> return Rejected("Payment declined: ${billingResult.reason}")
            is Charged -> Unit // charged; fall through and issue
        }

        val policy = Policy(
            id = quote.id,
            holderName = quote.holderName,
            coverageLimit = quote.coverageAmount,
            deductible = DEFAULT_DEDUCTIBLE,
            expiresOn = today.plusYears(1),
            isActive = true,
        )
        policies.save(policy)

        return Issued(policy)
    }
}
