package com.poco.api.policies

import com.poco.core.policies.Policy
import com.poco.core.verifying.AsyncPolicyRegistry
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * This API's fulfilment of the async registry contract: stand-in for a
 * policy system across the network. The small delay is the point — lookups
 * like this are why VerifyPolicyCoverage suspends at all, even though its
 * answer, once it arrives, is final.
 */
class SimulatedPolicyRegistry(private val store: InMemoryPolicyStore) : AsyncPolicyRegistry {
    override suspend fun find(policyId: UUID): Policy? {
        delay(25)
        return store.find(policyId)
    }
}
