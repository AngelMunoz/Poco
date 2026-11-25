package com.poco.api.quotes

import com.poco.core.quoting.QuoteOutcome
import com.poco.core.quoting.QuotePremium
import com.poco.core.quoting.QuoteRequest
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * Vertical slice adapter (HTTP in/out). Pattern 1 in use: this controller is
 * the environment, so it resolves every plain input Core needs — tariff from
 * its own table, a new id, the current time — then calls one function and
 * translates the sealed outcome back to HTTP. No business rule here.
 */
@RestController
class QuotesController(
    @param:Qualifier("tariffs") private val tariffs: Map<String, BigDecimal>,
    private val clock: Clock,
) {
    @PostMapping("/quotes")
    fun quotePremium(@RequestBody request: QuoteRequest): ResponseEntity<Any> {
        val tariffRate = tariffs["${request.region}-${request.risk}"] ?: BigDecimal.ZERO

        return when (val outcome = QuotePremium.execute(request, tariffRate, UUID.randomUUID(), clock.instant())) {
            is QuoteOutcome.Quoted -> ResponseEntity.ok(outcome.quote)
            is QuoteOutcome.Rejected -> ResponseEntity.badRequest().body(mapOf("error" to outcome.reason))
        }
    }
}
