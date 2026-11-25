using System.ComponentModel;
using System.Text.Json;
using System.Text.Json.Serialization;
using ModelContextProtocol;
using ModelContextProtocol.Server;
using Poco.Core.Approving;
using Poco.Core.Claims;
using Poco.Core.Policies;
using Poco.Core.Quoting;
using Poco.Core.Renewing;
using Poco.Mono.Claims;
using Poco.Mono.Policies;
using Poco.Mono.Security;
using QuotePremiumOp = Poco.Core.Quoting.QuotePremium;
using ApproveClaimOp = Poco.Core.Approving.ApproveClaim;
using TriageClaimOp = Poco.Core.Claims.TriageClaim;
using AssessRenewalOp = Poco.Core.Renewing.AssessRenewal;

namespace Poco.Mono.Mcp;

/// <summary>
/// MCP adapter over the same Core operations the HTTP endpoints use.
/// An MCP tool is just another vertical-slice adapter: resolve inputs from
/// this host's environment, call one Core operation, translate the union
/// outcome into the transport's channels (return value = success,
/// <see cref="McpException"/> = error). Core gains no MCP attribute, no
/// package reference and no knowledge that agents exist.
///
/// Authorization is two-layered on purpose: the /mcp endpoint itself
/// requires the McpUse policy, and each tool re-checks the exact permission
/// its HTTP counterpart requires — an agent that may connect may still not
/// triage claims.
/// </summary>
[McpServerToolType]
public sealed class InsuranceTools(
    InMemoryPolicyStore policies,
    InMemoryClaimLedger ledger,
    TimeProvider timeProvider,
    IReadOnlyDictionary<string, decimal> tariffs,
    IReadOnlyDictionary<Guid, decimal> currentPremiums,
    IHttpContextAccessor httpContextAccessor,
    IClaimAuthorizer claimAuthorizer)
{
    [McpServerTool(Name = "quote_premium")]
    [Description("Price an insurance quote. Returns the issued quote; errors when the request is rejected.")]
    public string QuotePremium(
        [Description("Full name of the policy holder.")] string holderName,
        [Description("Age of the holder, 18-85.")] int age,
        [Description("Region code, e.g. EU or US.")] string region,
        [Description("Requested coverage amount.")] decimal coverageAmount,
        [Description("Risk level: Low, Medium or High.")] string risk)
    {
        Require(Permissions.QuotesCreate);

        if (!Enum.TryParse<RiskLevel>(risk, ignoreCase: true, out var riskLevel))
            throw new McpException($"Unknown risk level '{risk}'. Use Low, Medium or High.");

        var tariffRate = tariffs.TryGetValue($"{region}-{riskLevel}", out var rate) ? rate : 0m;
        var request = new QuoteRequest(holderName, age, region, coverageAmount, riskLevel);

        return QuotePremiumHandler(request, tariffRate);
    }

    // Separate static helper keeps the switch exhaustive and readable.
    private string QuotePremiumHandler(QuoteRequest request, decimal tariffRate) =>
        QuotePremiumOp.Execute(request, tariffRate, Guid.NewGuid(), timeProvider.GetUtcNow()) switch
        {
            Quote quote => JsonSerializer.Serialize(quote, McpJsonContext.Default.Quote),
            QuoteRejected rejected => throw new McpException(rejected.Reason),
        };

    [McpServerTool(Name = "triage_claim")]
    [Description("Triage a claim against its policy. Returns the decision (payout, manual-review flag); errors when rejected or the policy is unknown.")]
    public string TriageClaim(
        [Description("Id of the policy the claim is against.")] Guid policyId,
        [Description("Claimed amount.")] decimal claimedAmount,
        [Description("Claim category, e.g. 'Water damage'.")] string category)
    {
        Require(Permissions.ClaimsTriage);

        return TriageClaimOp.Execute(
            new ClaimRequest(policyId, claimedAmount, category), policies, ledger, timeProvider) switch
        {
            ClaimDecision decision => JsonSerializer.Serialize(decision, McpJsonContext.Default.ClaimDecision),
            PolicyNotFound notFound => throw new McpException($"Policy {notFound.PolicyId} not found."),
            ClaimRejected rejected => throw new McpException(rejected.Reason),
        };
    }

    [McpServerTool(Name = "assess_renewal")]
    [Description("Assess whether a policy can renew and at what premium. Errors when renewal is declined.")]
    public string AssessRenewal(
        [Description("Id of the policy to assess.")] Guid policyId)
    {
        Require(Permissions.RenewalsAssess);

        var policy = policies.Find(policyId)
            ?? throw new McpException($"Policy {policyId} not found.");

        if (!currentPremiums.TryGetValue(policyId, out var currentPremium))
            throw new McpException("No current premium on record for this policy.");

        return AssessRenewalOp.Execute(
            policy,
            currentPremium,
            recentClaimCount: id => ledger.Decisions.Count(d => d.PolicyId == id),
            clock: () => timeProvider.GetUtcNow()) switch
        {
            RenewalOffer offer => JsonSerializer.Serialize(offer, McpJsonContext.Default.RenewalOffer),
            RenewalDeclined declined => throw new McpException(declined.Reason),
        };
    }

    [McpServerTool(Name = "approve_claim")]
    [Description("Approve a triaged claim payout. The approver is the authenticated MCP caller; errors when that caller may not approve this amount.")]
    public string ApproveClaim(
        [Description("Id of the policy whose claim is approved.")] Guid policyId,
        [Description("Payout amount to approve.")] decimal payout)
    {
        Require(Permissions.ClaimsApprove);

        var approver = httpContextAccessor.HttpContext?.User.Identity?.Name ?? "";

        return ApproveClaimOp.Execute(
            new ClaimApprovalRequest(policyId, payout, approver), claimAuthorizer, ledger, timeProvider) switch
        {
            ClaimApproval approval => JsonSerializer.Serialize(approval, McpJsonContext.Default.ClaimApproval),
            ApprovalDenied denied => throw new McpException(denied.Reason),
            ApprovalNotAuthorized notAuthorized => throw new McpException(
                $"'{notAuthorized.Approver}' is not authorized to approve {notAuthorized.Payout}."),
        };
    }

    [McpServerTool(Name = "list_policies")]
    [Description("List all policies known to this host.")]
    public string ListPolicies()
    {
        Require(Permissions.PoliciesRead);
        return JsonSerializer.Serialize(policies.Policies.ToArray(), McpJsonContext.Default.PolicyArray);
    }

    [McpServerTool(Name = "list_claim_decisions")]
    [Description("List claim decisions triaged so far by this host.")]
    public string ListClaimDecisions()
    {
        Require(Permissions.ClaimsRead);
        return JsonSerializer.Serialize(ledger.Decisions.ToArray(), McpJsonContext.Default.ClaimDecisionArray);
    }

    private void Require(string permission)
    {
        var user = httpContextAccessor.HttpContext?.User;
        if (user is null || !user.HasClaim(Permissions.ClaimType, permission))
            throw new McpException($"Forbidden: this tool requires the '{permission}' permission.");
    }
}

/// <summary>
/// MCP has its own JSON serializer (unrelated to ASP.NET's), and it creates
/// tool schemas/output from tool signatures by reflection. Returning a JSON
/// string serialized with this source-generated context keeps tool payloads
/// typed and AOT-friendly without teaching the MCP SDK about Core's types.
/// </summary>
[JsonSourceGenerationOptions(
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    Converters = [typeof(JsonStringEnumConverter<RiskLevel>)])]
[JsonSerializable(typeof(Quote))]
[JsonSerializable(typeof(ClaimDecision))]
[JsonSerializable(typeof(ClaimApproval))]
[JsonSerializable(typeof(RenewalOffer))]
[JsonSerializable(typeof(Policy[]))]
[JsonSerializable(typeof(ClaimDecision[]))]
internal partial class McpJsonContext : JsonSerializerContext
{
}
