namespace Poco.Mono.Security;

/// <summary>
/// Capability names, kept as permission claims rather than roles: a caller
/// either holds the exact capability or it does not, and new caller kinds
/// (human roles today, MCP clients tomorrow) are composed by granting
/// permissions, never by editing policy definitions.
/// </summary>
public static class Permissions
{
    public const string ClaimType = "permission";

    public const string QuotesCreate = "quotes:create";
    public const string ClaimsTriage = "claims:triage";
    public const string ClaimsRead = "claims:read";
    public const string PoliciesRead = "policies:read";
    public const string RenewalsAssess = "renewals:assess";
    public const string ClaimsApprove = "claims:approve";
    public const string ClaimsApproveLarge = "claims:approve:large";
    public const string PoliciesIssue = "policies:issue";
    public const string McpUse = "mcp:use";
}

/// <summary>
/// ASP.NET Core authorization policy names. Each vertical slice requires
/// exactly one policy on its adapter; Core never sees any of this —
/// authorization is a property of the consuming environment, not of the
/// business operation (the Worker simply has no unauthenticated caller).
/// </summary>
public static class AuthorizationPolicies
{
    public const string QuotesCreate = "QuotesCreate";
    public const string ClaimsTriage = "ClaimsTriage";
    public const string ClaimsRead = "ClaimsRead";
    public const string PoliciesRead = "PoliciesRead";
    public const string RenewalsAssess = "RenewalsAssess";
    public const string ClaimsApprove = "ClaimsApprove";
    public const string PoliciesIssue = "PoliciesIssue";
    public const string McpUse = "McpUse";
}
