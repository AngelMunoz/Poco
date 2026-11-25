using System.Globalization;
using Poco.Core.Approving;
using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Reviewing;
using Poco.Core.Verifying;
using Poco.Mono.Claims;
using Poco.Core.Policies;
using Poco.Mono.Policies;

namespace Poco.Mono.Hosting;

/// <summary>
/// Everything Mono supplies to Core's operations, gathered in one place:
/// the stateful instances (Pattern 2), the plain values (Pattern 1) and the
/// lookups the delegate pattern closes over. Slices receive this record
/// whole instead of a growing parameter list per endpoint.
/// </summary>
public sealed record MonoEnvironment(
    InMemoryPolicyStore Policies,
    InMemoryClaimLedger Ledger,
    IReadOnlyDictionary<string, decimal> Tariffs,
    IReadOnlyDictionary<Guid, decimal> CurrentPremiums,
    TimeProvider TimeProvider,
    IClaimAuthorizer ClaimAuthorizer,
    IBillingGateway BillingGateway,
    InMemoryReviewQueue ReviewQueue,
    SimulatedPolicyRegistry PolicyRegistry);

public static class MonoEnvironmentRegistration
{
    /// <summary>
    /// Builds Mono's environment and registers the same instances as
    /// singletons, so HTTP slices (which receive them explicitly) and MCP
    /// tools (which resolve them from DI) operate on identical state.
    /// Another host builds a completely different environment over the
    /// same Core — see the Worker's <c>Program.cs</c>.
    /// </summary>
    public static MonoEnvironment AddInsuranceEnvironment(this WebApplicationBuilder builder)
    {
        var aliceId = Guid.Parse("11111111-1111-1111-1111-111111111111");
        var bobId = Guid.Parse("22222222-2222-2222-2222-222222222222");
        var today = DateOnly.FromDateTime(DateTime.Now);

        // Pattern 2 environment: stateful instances owned by this host.
        // Swap the bodies for EF/Dapper lookups and no slice changes.
        var policies = new InMemoryPolicyStore(
        [
            new Policy(aliceId, "Alice Meyer", CoverageLimit: 50_000m, Deductible: 500m, today.AddDays(20), IsActive: true),
            new Policy(bobId, "Bob Chen", CoverageLimit: 5_000m, Deductible: 250m, today.AddDays(180), IsActive: true),
            new Policy(Guid.Parse("33333333-3333-3333-3333-333333333333"), "Carol Diaz", 20_000m, 1_000m, today.AddDays(-10), IsActive: false),
        ]);
        var ledger = new InMemoryClaimLedger();

        // Pattern 1 environment: plain values resolved by this host (config
        // here; EF, a pricing API or a feature flag would do as well).
        var tariffs = builder.Configuration.GetSection("Tariffs").GetChildren()
            .ToDictionary(
                child => child.Key,
                child => decimal.Parse(child.Value!, CultureInfo.InvariantCulture));

        var currentPremiums = new Dictionary<Guid, decimal>
        {
            [aliceId] = 1_200m,
            [bobId] = 400m,
        };

        builder.Services.AddSingleton(policies);
        builder.Services.AddSingleton<IPolicyStore>(policies);
        builder.Services.AddSingleton(ledger);
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<IReadOnlyDictionary<string, decimal>>(tariffs);
        builder.Services.AddSingleton<IReadOnlyDictionary<Guid, decimal>>(currentPremiums);

        // Contract implementations (Pattern 2, grown up): Mono fulfils
        // Core's authorizer contract from HTTP claims, and the billing
        // contract with a demo gateway. Registered for DI so MCP tools
        // resolve the very same authorizer/billing objects.
        var claimAuthorizer = new HttpClaimAuthorizer(new HttpContextAccessor());
        var billingGateway = new DemoBillingGateway();
        builder.Services.AddSingleton<IClaimAuthorizer>(claimAuthorizer);
        builder.Services.AddSingleton<IBillingGateway>(billingGateway);
        builder.Services.AddSingleton<IPolicyRepository>(policies);

        // Asynchronous operations' environment: a queue that accepts jobs
        // for later (nothing is processed inline), and a registry whose
        // lookups take time, simulated with a small delay.
        var reviewQueue = new InMemoryReviewQueue();
        var policyRegistry = new SimulatedPolicyRegistry(policies);
        builder.Services.AddSingleton<IReviewJobQueue>(reviewQueue);
        builder.Services.AddSingleton<IAsyncPolicyRegistry>(policyRegistry);
        builder.Services.AddSingleton(reviewQueue);

        return new MonoEnvironment(
            policies, ledger, tariffs, currentPremiums, TimeProvider.System,
            claimAuthorizer, billingGateway, reviewQueue, policyRegistry);
    }
}
