using System.Text.Json.Serialization;
using Poco.Core.Quoting;

namespace Poco.Mono.Hosting;

/// <summary>
/// Host plumbing only: JSON options and OpenAPI. No slice, no business
/// concept lives here — this exists so <c>Program.cs</c> does not have to
/// wade through framework setup to show the shape of the API.
/// </summary>
public static class MonoHost
{
    public static void AddMonoHost(this WebApplicationBuilder builder)
    {
        builder.Services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.TypeInfoResolverChain.Insert(0, AppJsonSerializerContext.Default);
            options.SerializerOptions.Converters.Add(new JsonStringEnumConverter<RiskLevel>());
        });

        // Learn more about configuring OpenAPI at https://aka.ms/aspnet/openapi
        builder.Services.AddOpenApi();
    }

    public static void MapMonoOpenApi(this WebApplication app)
    {
        if (app.Environment.IsDevelopment())
        {
            app.MapOpenApi().AllowAnonymous();
        }
    }
}
