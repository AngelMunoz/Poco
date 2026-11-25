package com.poco.api.claims

import com.poco.api.security.Permissions
import com.poco.core.approving.ClaimAuthorizer
import org.springframework.security.core.context.SecurityContextHolder
import java.math.BigDecimal

/**
 * This API's fulfilment of Core's ClaimAuthorizer contract: authorization
 * solved from the current HTTP caller. The named approver must be the
 * authenticated user, the user must hold claims:approve, and amounts above
 * the limit additionally need claims:approve:large. Core asked one yes/no
 * question; every part of this answer is this host's own auth model.
 */
class HttpClaimAuthorizer : ClaimAuthorizer {
    override fun canApprove(approver: String, payout: BigDecimal): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication ?: return false
        if (authentication.name != approver) return false

        val authorities = authentication.authorities.map { it.authority }.toSet()
        if (Permissions.CLAIMS_APPROVE !in authorities) return false

        return payout <= LARGE_APPROVAL_LIMIT || Permissions.CLAIMS_APPROVE_LARGE in authorities
    }

    private companion object {
        val LARGE_APPROVAL_LIMIT = BigDecimal("20000")
    }
}
