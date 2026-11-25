using Microsoft.AspNetCore.Http.HttpResults;
using Poco.Core.Approving;
using Poco.Core.Claims;
using Poco.Mono.Hosting;
using Poco.Mono.Security;

namespace Poco.Mono.Claims;

/// <summary>
/// Vertical slice adapter (HTTP in/out). Pattern 2 in use: the endpoint hands
/// Core the stateful singleton instances Mono registered — its policy store,
/// its ledger, the system clock — explicitly, per call.
/// </summary>
public static class ClaimEndpoints
{
    public static void MapClaims(this WebApplication app, MonoEnvironment environment)
    {
        var policies = environment.Policies;
        var ledger = environment.Ledger;
        var timeProvider = environment.TimeProvider;
        app.MapPost("/claims", Results<Ok<ClaimDecision>, BadRequest<ApiError>, NotFound> (ClaimRequest request) =>
            TriageClaim.Execute(request, policies, ledger, timeProvider) switch
            {
                ClaimDecision decision => TypedResults.Ok(decision),
                PolicyNotFound => TypedResults.NotFound(),
                ClaimRejected rejected => TypedResults.BadRequest(new ApiError(rejected.Reason)),
            })
            .WithName("TriageClaim")
            .RequireAuthorization(AuthorizationPolicies.ClaimsTriage);

        // Mono can serve its ledger back because its ledger is its own type;
        // Core's IClaimLedger contract deliberately has no query surface.
        app.MapGet("/claims/decisions", () => ledger.Decisions.ToArray())
            .WithName("GetClaimDecisions")
            .RequireAuthorization(AuthorizationPolicies.ClaimsRead);

        // Contract-based operation: the endpoint policy (ClaimsApprove) is
        // the coarse gate, then Core asks Mono's IClaimAuthorizer — which
        // reads this caller's claims, incl. the large-amount limit — for
        // the fine-grained decision. Approver identity comes from the
        // authenticated user, never from the request body.
        app.MapPost("/claims/approve", Results<Ok<ClaimApproval>, BadRequest<ApiError>> (ApproveClaimBody body, HttpContext httpContext) =>
            ApproveClaim.Execute(
                new ClaimApprovalRequest(body.PolicyId, body.Payout, httpContext.User.Identity?.Name ?? ""),
                environment.ClaimAuthorizer, ledger, timeProvider) switch
            {
                ClaimApproval approval => TypedResults.Ok(approval),
                ApprovalDenied denied => TypedResults.BadRequest(new ApiError(denied.Reason)),
                ApprovalNotAuthorized notAuthorized => TypedResults.BadRequest(
                    new ApiError($"'{notAuthorized.Approver}' is not authorized to approve {notAuthorized.Payout}.")),
            })
            .WithName("ApproveClaim")
            .RequireAuthorization(AuthorizationPolicies.ClaimsApprove);

        app.MapGet("/claims/approvals", () => ledger.Approvals.ToArray())
            .WithName("GetClaimApprovals")
            .RequireAuthorization(AuthorizationPolicies.ClaimsRead);
    }
}

/// <summary>Transport DTO: who approves is the caller's identity, so it is not in the body.</summary>
public sealed record ApproveClaimBody(Guid PolicyId, decimal Payout);
