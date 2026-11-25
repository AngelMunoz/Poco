using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Policies;

namespace Poco.Worker.Environment;

/// <summary>
/// The Worker's environment for Pattern 2: the same <see cref="IPolicyStore"/>
/// contract Mono fulfils, but this instance is a batch snapshot — data as it
/// stood when the run started, with no HTTP request and no DbContext in sight.
/// In production this could wrap a file, a queue message or a read replica;
/// Core cannot tell the difference.
/// </summary>
public sealed class WorkerPolicyStore : IPolicyStore, IPolicyRepository
{
    private readonly List<Policy> _policies;

    public WorkerPolicyStore()
    {
        var today = DateOnly.FromDateTime(DateTime.Now);
        _policies =
        [
            new Policy(Guid.Parse("11111111-1111-1111-1111-111111111111"), "Alice Meyer", 50_000m, 500m, today.AddDays(20), true),
            new Policy(Guid.Parse("22222222-2222-2222-2222-222222222222"), "Bob Chen", 5_000m, 250m, today.AddDays(180), true),
        ];
    }

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
