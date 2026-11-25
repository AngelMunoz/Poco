using Poco.Core.Reviewing;

namespace Poco.Mono.Claims;

/// <summary>
/// Mono's fulfilment of the review-queue contract: jobs wait in a list
/// until something picks them up. The list is exposed so the demo can show
/// the important part — after scheduling, the job is still sitting here,
/// un-reviewed. A real app would use a broker/outbox behind this interface.
/// </summary>
public sealed class InMemoryReviewQueue : IReviewJobQueue
{
    private readonly List<ClaimReviewJob> _jobs = [];

    public IReadOnlyList<ClaimReviewJob> Jobs => _jobs;

    public ValueTask EnqueueAsync(ClaimReviewJob job, CancellationToken cancellationToken)
    {
        _jobs.Add(job);
        return ValueTask.CompletedTask;
    }
}
