package com.poco.api.policies

import com.poco.core.issuing.BillingGateway
import com.poco.core.issuing.BillingOutcome
import java.math.BigDecimal
import java.util.UUID

/**
 * This API's fulfilment of Core's BillingGateway contract: a demo card
 * gateway. Production would call a payment provider here; the worker fulfils
 * the same contract with monthly invoicing.
 */
class DemoBillingGateway : BillingGateway {
    override fun charge(holderName: String, annualPremium: BigDecimal): BillingOutcome =
        if (annualPremium > BigDecimal("100000")) {
            BillingOutcome.Declined("Demo gateway: premium exceeds the demo card limit.")
        } else {
            BillingOutcome.Charged("API-${UUID.randomUUID()}")
        }
}
