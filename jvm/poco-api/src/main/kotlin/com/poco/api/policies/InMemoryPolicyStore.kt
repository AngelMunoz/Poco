package com.poco.api.policies

import com.poco.core.claims.PolicyStore
import com.poco.core.issuing.PolicyRepository
import com.poco.core.policies.Policy
import java.util.UUID

/**
 * This API's fulfilment of two Core contracts: lookups for triage and saving
 * for issue. Swap the list for JPA/JDBC and neither Core nor the controllers
 * change — only this file knows where policies live. Synchronized because
 * web requests arrive concurrently and state lives for the app lifetime.
 */
class InMemoryPolicyStore(seed: List<Policy>) : PolicyStore, PolicyRepository {
    private val policies = seed.toMutableList()

    fun all(): List<Policy> = synchronized(policies) { policies.toList() }

    override fun save(policy: Policy) {
        synchronized(policies) {
            policies.removeAll { it.id == policy.id }
            policies.add(policy)
        }
    }

    override fun find(policyId: UUID): Policy? = synchronized(policies) {
        policies.firstOrNull { it.id == policyId }
    }
}
