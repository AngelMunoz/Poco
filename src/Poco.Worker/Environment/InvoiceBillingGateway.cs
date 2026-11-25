using Poco.Core.Issuing;

namespace Poco.Worker.Environment;

/// <summary>
/// The Worker's fulfilment of Core's <see cref="IBillingGateway"/> contract:
/// batch-issued policies are invoiced monthly instead of charged to a card.
/// Mono charges a demo card behind the same interface.
/// </summary>
public sealed class InvoiceBillingGateway : IBillingGateway
{
    public BillingOutcome Charge(string holderName, decimal annualPremium) =>
        new BillingCharged($"INV-{holderName.Replace(' ', '-')}-{annualPremium:0}");
}
