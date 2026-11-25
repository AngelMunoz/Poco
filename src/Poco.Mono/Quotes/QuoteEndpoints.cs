using Microsoft.AspNetCore.Http.HttpResults;
using Poco.Core.Quoting;
using Poco.Mono.Hosting;
using Poco.Mono.Security;

namespace Poco.Mono.Quotes;

/// <summary>
/// Vertical slice adapter (HTTP in/out). Pattern 1 in use: this endpoint is
/// the environment, so it resolves every plain input Core needs — tariff
/// from its own table, a new id, the current time — then calls one static
/// function and translates the outcome back to HTTP. No business rule here.
/// </summary>
public static class QuoteEndpoints
{
    public static void MapQuotes(this WebApplication app, MonoEnvironment environment)
    {
        app.MapPost("/quotes", Results<Ok<Quote>, BadRequest<ApiError>> (QuoteRequest request) =>
            {
                // Consumer-side resolution: Core receives a value, never the table.
                var tariffRate = environment.Tariffs.TryGetValue($"{request.Region}-{request.Risk}", out var rate)
                    ? rate
                    : 0m;

                // Exhaustive switch over the C# 15 union: no discard arm.
                // Adding a case to QuoteOutcome warns here until handled.
                return QuotePremium.Execute(request, tariffRate, Guid.NewGuid(), DateTimeOffset.UtcNow) switch
                {
                    Quote quote => TypedResults.Ok(quote),
                    QuoteRejected rejected => TypedResults.BadRequest(new ApiError(rejected.Reason)),
                };
            })
            .WithName("QuotePremium")
            .RequireAuthorization(AuthorizationPolicies.QuotesCreate);
    }
}
