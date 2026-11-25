package com.poco.api.config

import com.poco.api.claims.HttpClaimAuthorizer
import com.poco.api.claims.InMemoryClaimLedger
import com.poco.api.claims.InMemoryReviewQueue
import com.poco.api.policies.DemoBillingGateway
import com.poco.api.policies.InMemoryPolicyStore
import com.poco.api.policies.SimulatedPolicyRegistry
import com.poco.core.approving.ClaimAuthorizer
import com.poco.core.issuing.BillingGateway
import com.poco.core.policies.Policy
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * This API's composition root, written out as explicit beans on purpose:
 * this file is the one place that knows where the API's values and stateful
 * instances come from. No component-scan magic — read top to bottom and you
 * know the whole environment Core will be handed.
 */
@Configuration
class ApiEnvironmentConfig(private val properties: PocoProperties) {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun policyStore(): InMemoryPolicyStore {
        val aliceId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val bobId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val today = LocalDate.now()

        return InMemoryPolicyStore(
            listOf(
                Policy(aliceId, "Alice Meyer", BigDecimal("50000"), BigDecimal("500"), today.plusDays(20), true),
                Policy(bobId, "Bob Chen", BigDecimal("5000"), BigDecimal("250"), today.plusDays(180), true),
                Policy(UUID.fromString("33333333-3333-3333-3333-333333333333"), "Carol Diaz", BigDecimal("20000"), BigDecimal("1000"), today.minusDays(10), false),
            ),
        )
    }

    @Bean
    fun claimLedger(): InMemoryClaimLedger = InMemoryClaimLedger()

    @Bean
    fun reviewQueue(): InMemoryReviewQueue = InMemoryReviewQueue()

    @Bean
    fun policyRegistry(policyStore: InMemoryPolicyStore): SimulatedPolicyRegistry =
        SimulatedPolicyRegistry(policyStore)

    @Bean
    fun claimAuthorizer(): ClaimAuthorizer = HttpClaimAuthorizer()

    @Bean
    fun billingGateway(): BillingGateway = DemoBillingGateway()

    /** Pattern 1 environment: plain values resolved by this host, from config. */
    @Bean
    @Qualifier("tariffs")
    fun tariffs(): Map<String, BigDecimal> = properties.tariffs

    @Bean
    @Qualifier("currentPremiums")
    fun currentPremiums(): Map<UUID, BigDecimal> = mapOf(
        UUID.fromString("11111111-1111-1111-1111-111111111111") to BigDecimal("1200"),
        UUID.fromString("22222222-2222-2222-2222-222222222222") to BigDecimal("400"),
    )

    companion object {
        val ALICE_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val BOB_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
