package com.poco.api.security

/**
 * Capability names, kept as authorities rather than roles: a caller either
 * holds the exact capability or it does not, and new caller kinds (human
 * roles today, agents tomorrow) are composed by granting permissions.
 */
object Permissions {
    const val QUOTES_CREATE = "quotes:create"
    const val CLAIMS_TRIAGE = "claims:triage"
    const val CLAIMS_READ = "claims:read"
    const val CLAIMS_APPROVE = "claims:approve"
    const val CLAIMS_APPROVE_LARGE = "claims:approve:large"
    const val POLICIES_READ = "policies:read"
    const val POLICIES_ISSUE = "policies:issue"
    const val RENEWALS_ASSESS = "renewals:assess"
    const val MCP_USE = "mcp:use"
}
