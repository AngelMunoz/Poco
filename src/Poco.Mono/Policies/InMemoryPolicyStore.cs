using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Policies;

namespace Poco.Mono.Policies;

/// <summary>
/// Mono's environment for Pattern 2: a stateful instance it owns.
/// Registered as a singleton, so the same object (and its lookup counter)
/// lives across requests. Swap this body for an EF/Dapper lookup and neither
/// Core nor the endpoints change — only this file knows where policies live.
/// </summary>
public sealed class InMemoryPolicyStore(IEnumerable<Policy> seed) : IPolicyStore, IPolicyRepository
{
    private readonly List<Policy> _policies = [.. seed];

    public IReadOnlyList<Policy> Policies => _policies;

    public int LookupCount { get; private set; }

    public void Save(Policy policy)
    {
        _policies.RemoveAll(p => p.Id == policy.Id);
        _policies.Add(policy);
    }

    public Policy? Find(Guid policyId)
    {
        LookupCount++;
        return _policies.FirstOrDefault(p => p.Id == policyId);
    }
}
