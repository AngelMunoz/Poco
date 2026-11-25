package com.poco.api.policies

import com.poco.core.issuing.BillingGateway
import com.poco.core.issuing.IssuePolicy
import com.poco.core.issuing.PolicyIssueOutcome
import com.poco.core.policies.Policy
import com.poco.core.quoting.Quote
import com.poco.core.renewing.AssessRenewal
import com.poco.core.renewing.RenewalOutcome
import com.poco.core.verifying.PolicyVerificationOutcome
import com.poco.core.verifying.VerifyPolicyCoverage
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Vertical slice adapter (HTTP in/out). Renewal uses Pattern 3 (a lambda over
 * this host's ledger), issue uses the contract-based operation (this host's
 * repository and billing implementations).
 */
@RestController
class PoliciesController(
    private val policyStore: InMemoryPolicyStore,
    private val ledger: com.poco.api.claims.InMemoryClaimLedger,
    private val billingGateway: BillingGateway,
    private val policyRegistry: SimulatedPolicyRegistry,
    private val clock: Clock,
    @param:Qualifier("currentPremiums") private val currentPremiums: Map<UUID, BigDecimal>,
) {
    @GetMapping("/policies")
    fun policies(): List<Policy> = policyStore.all()

    @GetMapping("/policies/{id}/renewal")
    fun assessRenewal(@PathVariable id: UUID): ResponseEntity<Any> {
        val policy = policyStore.find(id) ?: return ResponseEntity.notFound().build()
        val currentPremium = currentPremiums[id]
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "No current premium on record for this policy."))

        return when (
            val outcome = AssessRenewal.execute(
                policy, currentPremium,
                recentClaimCount = { policyId -> ledger.decisions.count { it.policyId == policyId } },
                clock = clock,
            )
        ) {
            is RenewalOutcome.Offered -> ResponseEntity.ok(outcome.offer)
            is RenewalOutcome.Declined -> ResponseEntity.badRequest().body(mapOf("error" to outcome.declined.reason))
        }
    }

    // Asynchronous in implementation, synchronous in meaning: the registry
    // lookup suspends, but the response is the final answer.
    @GetMapping("/policies/{id}/verify")
    fun verifyPolicyCoverage(
        @PathVariable id: UUID,
        @org.springframework.web.bind.annotation.RequestParam amount: BigDecimal,
    ): ResponseEntity<Any> = runBlocking {
        when (val outcome = VerifyPolicyCoverage.execute(id, amount, policyRegistry)) {
            is PolicyVerificationOutcome.Covered -> ResponseEntity.ok(outcome.coverage)
            is PolicyVerificationOutcome.NotFound -> ResponseEntity.notFound().build()
            is PolicyVerificationOutcome.Rejected -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
        }
    }

    @PostMapping("/policies/issue")
    fun issuePolicy(@RequestBody quote: Quote): ResponseEntity<Any> =
        when (val outcome = IssuePolicy.execute(quote, policyStore, billingGateway, clock)) {
            is PolicyIssueOutcome.Issued -> ResponseEntity.ok(outcome.policy)
            is PolicyIssueOutcome.Rejected -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
        }
}
