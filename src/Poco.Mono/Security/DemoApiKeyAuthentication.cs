using System.Security.Claims;
using System.Text.Encodings.Web;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace Poco.Mono.Security;

public sealed record DemoIdentity(string Name, IReadOnlyList<string> Permissions);

/// <summary>API key → identity, built once from configuration in the composition root.</summary>
public sealed class DemoIdentityStore(IReadOnlyDictionary<string, DemoIdentity> identitiesByKey)
{
    public DemoIdentity? Find(string apiKey) =>
        identitiesByKey.TryGetValue(apiKey, out var identity) ? identity : null;
}

/// <summary>
/// DEMO authentication only: an <c>X-Api-Key</c> header mapped to permission
/// claims. This class is the seam to replace in production — swap this scheme
/// for JwtBearer / OpenID Connect in the composition root and every policy,
/// endpoint and MCP tool keeps working unchanged, because authorization only
/// ever looks at the resulting claims. Authentication (who are you?) is
/// replaceable precisely because authorization (what may you do?) is separate.
/// Demo keys live in appsettings.json; real keys must never do that.
/// </summary>
public sealed class DemoApiKeyAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    DemoIdentityStore identities)
    : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "DemoApiKey";
    public const string HeaderName = "X-Api-Key";

    protected override Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        if (!Request.Headers.TryGetValue(HeaderName, out var key) || string.IsNullOrWhiteSpace(key))
            return Task.FromResult(AuthenticateResult.NoResult());

        var identity = identities.Find(key.ToString());
        if (identity is null)
            return Task.FromResult(AuthenticateResult.Fail("Invalid API key."));

        var claims = new List<Claim> { new(ClaimTypes.Name, identity.Name) };
        claims.AddRange(identity.Permissions.Select(p => new Claim(Permissions.ClaimType, p)));

        var principal = new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName));
        return Task.FromResult(AuthenticateResult.Success(new AuthenticationTicket(principal, SchemeName)));
    }
}
