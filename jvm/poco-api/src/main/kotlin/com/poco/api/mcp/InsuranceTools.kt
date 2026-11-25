package com.poco.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.poco.api.claims.InMemoryClaimLedger
import com.poco.api.policies.InMemoryPolicyStore
import com.poco.api.security.Permissions
import com.poco.core.approving.ApproveClaim
import com.poco.core.approving.ClaimApprovalOutcome
import com.poco.core.approving.ClaimApprovalRequest
import com.poco.core.approving.ClaimAuthorizer
import com.poco.core.claims.ClaimOutcome
import com.poco.core.claims.ClaimRequest
import com.poco.core.claims.TriageClaim
import com.poco.core.quoting.QuoteOutcome
import com.poco.core.quoting.QuotePremium
import com.poco.core.quoting.QuoteRequest
import com.poco.core.quoting.RiskLevel
import com.poco.core.renewing.AssessRenewal
import com.poco.core.reviewing.ClaimReviewRequest
import com.poco.core.reviewing.ReviewScheduleOutcome
import com.poco.core.reviewing.ScheduleClaimReview
import com.poco.core.renewing.RenewalOutcome
import kotlinx.coroutines.runBlocking
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

/**
 * MCP adapter over the same Core operations the HTTP controllers use. A tool
 * is just another vertical-slice adapter: check permission, resolve host
 * inputs, call one Core operation, translate the sealed outcome into the
 * transport's channels (text content = success, isError = failure). Core
 * gains no MCP type and no dependency on this SDK.
 *
 * Authorization is two-layered: the /mcp route requires mcp:use to connect,
 * and each tool re-checks the permission its HTTP counterpart requires using
 * the caller details the transport extracted from the API key.
 */
@Component
class InsuranceTools(
    private val policyStore: InMemoryPolicyStore,
    private val ledger: InMemoryClaimLedger,
    private val claimAuthorizer: ClaimAuthorizer,
    private val clock: Clock,
    @param:Qualifier("tariffs") private val tariffs: Map<String, BigDecimal>,
    @param:Qualifier("currentPremiums") private val currentPremiums: Map<UUID, BigDecimal>,
    private val reviewQueue: com.poco.api.claims.InMemoryReviewQueue,
    private val objectMapper: ObjectMapper,
) {
    fun specifications(): List<McpStatelessServerFeatures.SyncToolSpecification> = listOf(
        spec(
            name = "quote_premium",
            description = "Price an insurance quote. Errors when the request is rejected.",
            properties = mapOf(
                "holderName" to stringProperty("Full name of the policy holder."),
                "age" to mapOf("type" to "integer", "description" to "Age of the holder, 18-85."),
                "region" to stringProperty("Region code, e.g. EU or US."),
                "coverageAmount" to mapOf("type" to "number", "description" to "Requested coverage amount."),
                "risk" to stringProperty("Risk level: Low, Medium or High."),
            ),
            required = listOf("holderName", "age", "region", "coverageAmount", "risk"),
        ) { context, args ->
            requirePermission(context, Permissions.QUOTES_CREATE)
            val request = QuoteRequest(
                holderName = stringArg(args, "holderName"),
                age = intArg(args, "age"),
                region = stringArg(args, "region"),
                coverageAmount = decimalArg(args, "coverageAmount"),
                risk = runCatching { RiskLevel.valueOf(stringArg(args, "risk")) }
                    .getOrElse { throw IllegalArgumentException("Unknown risk level. Use Low, Medium or High.") },
            )
            val tariffRate = tariffs["${request.region}-${request.risk}"] ?: BigDecimal.ZERO
            when (val outcome = QuotePremium.execute(request, tariffRate, UUID.randomUUID(), clock.instant())) {
                is QuoteOutcome.Quoted -> ok(outcome.quote)
                is QuoteOutcome.Rejected -> error(outcome.reason)
            }
        },
        spec(
            name = "triage_claim",
            description = "Triage a claim against its policy. Errors when rejected or the policy is unknown.",
            properties = mapOf(
                "policyId" to stringProperty("Id of the policy the claim is against."),
                "claimedAmount" to mapOf("type" to "number", "description" to "Claimed amount."),
                "category" to stringProperty("Claim category, e.g. 'Water damage'."),
            ),
            required = listOf("policyId", "claimedAmount", "category"),
        ) { context, args ->
            requirePermission(context, Permissions.CLAIMS_TRIAGE)
            val request = ClaimRequest(uuidArg(args, "policyId"), decimalArg(args, "claimedAmount"), stringArg(args, "category"))
            when (val outcome = TriageClaim.execute(request, policyStore, ledger, clock)) {
                is ClaimOutcome.Decided -> ok(outcome.decision)
                is ClaimOutcome.NotFound -> error("Policy ${outcome.policyId} not found.")
                is ClaimOutcome.Rejected -> error(outcome.reason)
            }
        },
        spec(
            name = "assess_renewal",
            description = "Assess whether a policy can renew and at what premium. Errors when declined.",
            properties = mapOf("policyId" to stringProperty("Id of the policy to assess.")),
            required = listOf("policyId"),
        ) { context, args ->
            requirePermission(context, Permissions.RENEWALS_ASSESS)
            val policyId = uuidArg(args, "policyId")
            val policy = policyStore.find(policyId) ?: return@spec error("Policy $policyId not found.")
            val currentPremium = currentPremiums[policyId] ?: return@spec error("No current premium on record for this policy.")
            when (
                val outcome = AssessRenewal.execute(
                    policy, currentPremium,
                    recentClaimCount = { id -> ledger.decisions.count { it.policyId == id } },
                    clock = clock,
                )
            ) {
                is RenewalOutcome.Offered -> ok(outcome.offer)
                is RenewalOutcome.Declined -> error(outcome.declined.reason)
            }
        },
        spec(
            name = "approve_claim",
            description = "Approve a triaged claim payout. The approver is the authenticated MCP caller.",
            properties = mapOf(
                "policyId" to stringProperty("Id of the policy whose claim is approved."),
                "payout" to mapOf("type" to "number", "description" to "Payout amount to approve."),
            ),
            required = listOf("policyId", "payout"),
        ) { context, args ->
            requirePermission(context, Permissions.CLAIMS_APPROVE)
            val request = ClaimApprovalRequest(uuidArg(args, "policyId"), decimalArg(args, "payout"), callerName(context))
            when (val outcome = ApproveClaim.execute(request, claimAuthorizer, ledger, clock)) {
                is ClaimApprovalOutcome.Approved -> ok(outcome.approval)
                is ClaimApprovalOutcome.Denied -> error(outcome.reason)
                is ClaimApprovalOutcome.NotAuthorized -> error("'${outcome.approver}' is not authorized to approve ${outcome.payout}.")
            }
        },
        spec(
            name = "schedule_claim_review",
            description = "Schedule a human review for a claim. Returns a queued receipt — the review itself happens later, not inside this call.",
            properties = mapOf(
                "policyId" to stringProperty("Id of the policy whose claim needs review."),
                "payout" to mapOf("type" to "number", "description" to "Payout amount to review."),
                "reason" to stringProperty("Why this claim needs a human review."),
            ),
            required = listOf("policyId", "payout", "reason"),
        ) { context, args ->
            requirePermission(context, Permissions.CLAIMS_TRIAGE)
            runBlocking {
                val request = ClaimReviewRequest(uuidArg(args, "policyId"), decimalArg(args, "payout"), stringArg(args, "reason"))
                when (val outcome = ScheduleClaimReview.execute(request, reviewQueue, UUID.randomUUID(), clock)) {
                    is ReviewScheduleOutcome.Queued -> ok(outcome.receipt)
                    is ReviewScheduleOutcome.Rejected -> error(outcome.reason)
                }
            }
        },
        spec(
            name = "list_policies",
            description = "List all policies known to this host.",
            properties = emptyMap(),
            required = emptyList(),
        ) { context, _ ->
            requirePermission(context, Permissions.POLICIES_READ)
            ok(policyStore.all())
        },
        spec(
            name = "list_claim_decisions",
            description = "List claim decisions triaged so far by this host.",
            properties = emptyMap(),
            required = emptyList(),
        ) { context, _ ->
            requirePermission(context, Permissions.CLAIMS_READ)
            ok(ledger.decisions)
        },
    )

    private fun spec(
        name: String,
        description: String,
        properties: Map<String, Map<String, Any>>,
        required: List<String>,
        handler: (McpTransportContext, Map<String, Any?>) -> McpSchema.CallToolResult,
    ): McpStatelessServerFeatures.SyncToolSpecification {
        val tool = McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(McpSchema.JsonSchema("object", properties, required, false, null, null))
            .build()

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { context, request ->
                try {
                    handler(context, request.arguments() ?: emptyMap())
                } catch (e: ForbiddenException) {
                    error(e.message ?: "Forbidden.")
                } catch (e: IllegalArgumentException) {
                    error(e.message ?: "Invalid arguments.")
                }
            }
            .build()
    }

    private fun ok(value: Any): McpSchema.CallToolResult =
        McpSchema.CallToolResult(objectMapper.writeValueAsString(value), false)

    private fun error(message: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult(message, true)

    private fun callerName(context: McpTransportContext): String = context.get(CONTEXT_NAME) as? String ?: ""

    private fun requirePermission(context: McpTransportContext, permission: String) {
        @Suppress("UNCHECKED_CAST")
        val permissions = context.get(CONTEXT_PERMISSIONS) as? Set<String> ?: emptySet()
        if (permission !in permissions) {
            throw ForbiddenException("Forbidden: this tool requires the '$permission' permission.")
        }

        // The API authorizer reads the servlet security context, which the MCP
        // transport does not carry. For tool calls, the transport context is
        // the caller: temporarily mirror it so the same HttpClaimAuthorizer
        // contract implementation answers for both transports.
        if (permission == Permissions.CLAIMS_APPROVE) {
            val authorities = permissions.map { org.springframework.security.core.authority.SimpleGrantedAuthority(it) }
            org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
                org.springframework.security.authentication.UsernamePasswordAuthenticationToken(callerName(context), null, authorities)
        }
    }

    private fun stringProperty(description: String): Map<String, Any> =
        mapOf("type" to "string", "description" to description)

    private fun stringArg(args: Map<String, Any?>, name: String): String =
        args[name]?.toString()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Missing argument '$name'.")

    private fun intArg(args: Map<String, Any?>, name: String): Int =
        (args[name] as? Number)?.toInt() ?: args[name]?.toString()?.toIntOrNull()
            ?: throw IllegalArgumentException("Argument '$name' must be an integer.")

    private fun decimalArg(args: Map<String, Any?>, name: String): BigDecimal =
        (args[name] as? Number)?.let { BigDecimal(it.toString()) } ?: args[name]?.toString()?.toBigDecimalOrNull()
            ?: throw IllegalArgumentException("Argument '$name' must be a number.")

    private fun uuidArg(args: Map<String, Any?>, name: String): UUID =
        runCatching { UUID.fromString(stringArg(args, name)) }
            .getOrElse { throw IllegalArgumentException("Argument '$name' must be a UUID.") }

    private class ForbiddenException(message: String) : RuntimeException(message)

    companion object {
        const val CONTEXT_NAME = "name"
        const val CONTEXT_PERMISSIONS = "permissions"
    }
}
