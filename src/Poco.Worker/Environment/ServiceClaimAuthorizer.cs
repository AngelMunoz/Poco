using Poco.Core.Approving;

namespace Poco.Worker.Environment;

/// <summary>
/// The Worker's fulfilment of the same <see cref="IClaimAuthorizer"/>
/// contract Mono fulfils from HTTP claims. Here there is no user and no
/// claims: the batch service identity may auto-approve only small payouts,
/// everything larger is left for a human. Same interface, same Core
/// operation, completely unrelated auth model — exactly what a
/// contract-based operation is supposed to allow.
/// </summary>
public sealed class ServiceClaimAuthorizer : IClaimAuthorizer
{
    public const string ServiceIdentity = "poco-worker";
    private const decimal AutoApproveLimit = 10_000m;

    public bool CanApprove(string approver, decimal payout) =>
        approver == ServiceIdentity && payout <= AutoApproveLimit;
}
