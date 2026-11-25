package com.poco.core.verifying

import com.poco.core.policies.Policy
import com.poco.core.verifying.PolicyVerificationOutcome.Covered
import com.poco.core.verifying.PolicyVerificationOutcome.NotFound
import com.poco.core.verifying.PolicyVerificationOutcome.Rejected
import java.math.BigDecimal
import java.util.UUID

data class PolicyCoverage(val policyId: UUID, val coverageLimit: BigDecimal, val coveredAmount: BigDecimal)

// A final answer: covered (with amounts), rejected, or no such policy.
sealed interface PolicyVerificationOutcome {
    data class Covered(val coverage: PolicyCoverage) : PolicyVerificationOutcome
    data class Rejected(val reason: String) : PolicyVerificationOutcome
    data class NotFound(val policyId: UUID) : PolicyVerificationOutcome
}

/**
 * Contract: look a policy up somewhere that takes time — another service, a
 * database across the network. That is why it is a `suspend` function.
 */
interface AsyncPolicyRegistry {
    suspend fun find(policyId: UUID): Policy?
}

/**
 * Asynchronous in IMPLEMENTATION, synchronous in MEANING — the opposite of
 * ScheduleClaimReview. The lookup may suspend on I/O, but when the call
 * completes, the caller holds the final answer, exactly as if the operation
 * had been a plain function. Nothing is queued; nothing continues after.
 * Whether an operation suspends is a plumbing question; whether the work
 * is finished when it returns is the design question.
 */
object VerifyPolicyCoverage {
    suspend fun execute(policyId: UUID, claimedAmount: BigDecimal, registry: AsyncPolicyRegistry): PolicyVerificationOutcome {
        if (claimedAmount <= BigDecimal.ZERO) return Rejected("Claimed amount must be positive.")

        val policy = registry.find(policyId) ?: return NotFound(policyId)

        if (!policy.isActive) return Rejected("Policy is not active.")

        val covered = maxOf(BigDecimal.ZERO, minOf(claimedAmount, policy.coverageLimit) - policy.deductible)

        return Covered(PolicyCoverage(policy.id, policy.coverageLimit, covered))
    }
}
