using ModelContextProtocol.AspNetCore;
using Poco.Mono.Security;

namespace Poco.Mono.Mcp;

/// <summary>
/// MCP transport wiring, kept with the tools it serves: the server is
/// registered like any other adapter and mapped behind the McpUse policy.
/// Each tool additionally re-checks its own permission — connecting and
/// triaging are different capabilities.
/// </summary>
public static class InsuranceMcp
{
    public static void AddInsuranceMcp(this WebApplicationBuilder builder)
    {
        builder.Services.AddMcpServer()
            .WithHttpTransport()
            .WithTools<InsuranceTools>();
    }

    public static void MapInsuranceMcp(this WebApplication app)
    {
        app.MapMcp("/mcp").RequireAuthorization(AuthorizationPolicies.McpUse);
    }
}
