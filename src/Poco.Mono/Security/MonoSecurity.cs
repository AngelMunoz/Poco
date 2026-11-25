using Microsoft.AspNetCore.Authentication;

namespace Poco.Mono.Security;

/// <summary>
/// The whole security wiring for this host, in one file: demo identities
/// from configuration, the authentication scheme, and every authorization
/// policy. Authentication (who are you?) is replaceable here — swap
/// <see cref="DemoApiKeyAuthenticationHandler"/> for JwtBearer/OIDC and the
/// policies, slices and MCP tools keep working because they only read claims.
/// </summary>
public static class MonoSecurity
{
    public static void AddMonoSecurity(this WebApplicationBuilder builder)
    {
        var demoIdentities = builder.Configuration.GetSection("DemoIdentities").GetChildren()
            .ToDictionary(
                child => child.Key,
                child => new DemoIdentity(
                    child["Name"]!,
                    child.GetSection("Permissions").GetChildren().Select(c => c.Value!).ToArray()));

        builder.Services.AddSingleton(new DemoIdentityStore(demoIdentities));
        builder.Services
            .AddAuthentication(DemoApiKeyAuthenticationHandler.SchemeName)
            .AddScheme<AuthenticationSchemeOptions, DemoApiKeyAuthenticationHandler>(
                DemoApiKeyAuthenticationHandler.SchemeName, null);

        builder.Services.AddAuthorizationBuilder()
            .AddPolicy(AuthorizationPolicies.QuotesCreate, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.QuotesCreate))
            .AddPolicy(AuthorizationPolicies.ClaimsTriage, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.ClaimsTriage))
            .AddPolicy(AuthorizationPolicies.ClaimsRead, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.ClaimsRead))
            .AddPolicy(AuthorizationPolicies.PoliciesRead, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.PoliciesRead))
            .AddPolicy(AuthorizationPolicies.RenewalsAssess, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.RenewalsAssess))
            .AddPolicy(AuthorizationPolicies.ClaimsApprove, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.ClaimsApprove))
            .AddPolicy(AuthorizationPolicies.PoliciesIssue, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.PoliciesIssue))
            .AddPolicy(AuthorizationPolicies.McpUse, policy => policy.RequireClaim(Permissions.ClaimType, Permissions.McpUse))
            // Secure by default: a future endpoint that forgets
            // RequireAuthorization still requires an authenticated caller.
            .AddFallbackPolicy("Authenticated", policy => policy.RequireAuthenticatedUser());

        // MCP tools read the current caller from the HTTP context to
        // re-check their own permission (endpoint policy only gates connecting).
        builder.Services.AddHttpContextAccessor();
    }

    public static void UseMonoSecurity(this WebApplication app)
    {
        app.UseAuthentication();
        app.UseAuthorization();
    }
}
