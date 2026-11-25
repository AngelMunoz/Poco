using Poco.Core.Policies;
using Poco.Core.Verifying;

namespace Poco.Mono.Policies;

/// <summary>
/// Mono's fulfilment of the async registry contract: stand-in for a policy
/// system across the network. The small delay is the point — lookups like
/// this are why VerifyPolicyCoverage is async at all, even though its
/// answer, once it arrives, is final.
/// </summary>
public sealed class SimulatedPolicyRegistry(InMemoryPolicyStore store) : IAsyncPolicyRegistry
{
    public async ValueTask<Policy?> FindAsync(Guid policyId, CancellationToken cancellationToken)
    {
        await Task.Delay(25, cancellationToken);
        return store.Find(policyId);
    }
}
