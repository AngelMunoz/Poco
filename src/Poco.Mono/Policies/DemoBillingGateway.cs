using Poco.Core.Issuing;

namespace Poco.Mono.Policies;

/// <summary>
/// Mono's fulfilment of Core's <see cref="IBillingGateway"/> contract:
/// a demo card gateway (charges succeed, absurd premiums are declined).
/// Production would call a payment provider here; the Worker fulfils the
/// same contract with monthly invoicing. Core sees only
/// <see cref="BillingOutcome"/> either way.
/// </summary>
public sealed class DemoBillingGateway : IBillingGateway
{
    public BillingOutcome Charge(string holderName, decimal annualPremium) =>
        annualPremium > 100_000m
            ? new BillingDeclined("Demo gateway: premium exceeds the demo card limit.")
            : new BillingCharged($"MONO-{Guid.NewGuid():N}");
}
