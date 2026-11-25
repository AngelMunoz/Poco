using Poco.Core.Approving;
using Poco.Mono.Security;

namespace Poco.Mono.Claims;

/// <summary>
/// Mono's fulfilment of Core's <see cref="IClaimAuthorizer"/> contract:
/// authorization solved from the current HTTP caller. The named approver
/// must be the authenticated user, the user must hold
/// <c>claims:approve</c>, and amounts above the limit additionally need
/// <c>claims:approve:large</c>. Core asked one yes/no question; every part
/// of this answer — claims, principals, thresholds — is this host's own
/// auth model leaking nowhere into Core or the Worker.
/// </summary>
public sealed class HttpClaimAuthorizer(IHttpContextAccessor httpContextAccessor) : IClaimAuthorizer
{
    private const decimal LargeApprovalLimit = 20_000m;

    public bool CanApprove(string approver, decimal payout)
    {
        var user = httpContextAccessor.HttpContext?.User;
        if (user?.Identity?.Name is not { } name || name != approver)
            return false;

        if (!user.HasClaim(Permissions.ClaimType, Permissions.ClaimsApprove))
            return false;

        return payout <= LargeApprovalLimit
            || user.HasClaim(Permissions.ClaimType, Permissions.ClaimsApproveLarge);
    }
}
