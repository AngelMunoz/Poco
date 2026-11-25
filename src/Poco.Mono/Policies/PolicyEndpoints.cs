using Microsoft.AspNetCore.Http.HttpResults;
using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Policies;
using Poco.Core.Quoting;
using Poco.Core.Renewing;
using Poco.Core.Verifying;
using Poco.Mono.Claims;
using Poco.Mono.Hosting;
using Poco.Mono.Security;

namespace Poco.Mono.Policies;

/// <summary>
/// Vertical slice adapter (HTTP in/out). Pattern 3 in use: renewal needs one
/// ad-hoc query, so Mono passes a lambda closing over its own ledger instead
/// of inventing an interface for Core to depend on.
/// </summary>
public static class PolicyEndpoints
{
    public static void MapPolicies(this WebApplication app, MonoEnvironment environment)
    {
        var policies = environment.Policies;
        var ledger = environment.Ledger;
        var currentPremiums = environment.CurrentPremiums;
        app.MapGet("/policies", () => policies.Policies.ToArray())
            .WithName("GetPolicies")
            .RequireAuthorization(AuthorizationPolicies.PoliciesRead);

        app.MapGet("/policies/{id:guid}/renewal",
            Results<Ok<RenewalOffer>, BadRequest<ApiError>, NotFound> (Guid id) =>
            {
                var policy = policies.Find(id);
                if (policy is null)
                    return TypedResults.NotFound();

                if (!currentPremiums.TryGetValue(id, out var currentPremium))
                    return TypedResults.BadRequest(new ApiError("No current premium on record for this policy."));

                return AssessRenewal.Execute(
                    policy,
                    currentPremium,
                    recentClaimCount: policyId => ledger.Decisions.Count(d => d.PolicyId == policyId),
                    clock: () => DateTimeOffset.UtcNow) switch
                {
                    RenewalOffer offer => TypedResults.Ok(offer),
                    RenewalDeclined declined => TypedResults.BadRequest(new ApiError(declined.Reason)),
                };
            })
            .WithName("AssessRenewal")
            .RequireAuthorization(AuthorizationPolicies.RenewalsAssess);

        // Asynchronous in implementation, synchronous in meaning: the
        // registry lookup takes time, but the response is the final answer.
        app.MapGet("/policies/{id:guid}/verify",
            async Task<Results<Ok<PolicyCoverage>, BadRequest<ApiError>, NotFound>> (
                Guid id, decimal amount, CancellationToken cancellationToken) =>
                await VerifyPolicyCoverage.ExecuteAsync(
                    id, amount, environment.PolicyRegistry, cancellationToken) switch
                {
                    PolicyCoverage coverage => TypedResults.Ok(coverage),
                    PolicyNotFoundResult => TypedResults.NotFound(),
                    CoverageRejected rejected => TypedResults.BadRequest(new ApiError(rejected.Reason)),
                })
            .WithName("VerifyPolicyCoverage")
            .RequireAuthorization(AuthorizationPolicies.PoliciesRead);

        // Contract-based operation: Core charges through Mono's billing
        // contract, then persists through the policy store (which fulfils
        // both IPolicyStore and IPolicyRepository). The issued policy joins
        // the same store GET /policies serves.
        app.MapPost("/policies/issue",
            Results<Ok<Policy>, BadRequest<ApiError>> (Quote quote) =>
                IssuePolicy.Execute(quote, policies, environment.BillingGateway, environment.TimeProvider) switch
                {
                    Policy policy => TypedResults.Ok(policy),
                    PolicyIssueRejected rejected => TypedResults.BadRequest(new ApiError(rejected.Reason)),
                })
            .WithName("IssuePolicy")
            .RequireAuthorization(AuthorizationPolicies.PoliciesIssue);
    }
}
