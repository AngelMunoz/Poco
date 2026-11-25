using Poco.Core.Reviewing;

namespace Poco.Worker.Environment;

/// <summary>
/// The Worker's fulfilment of the review-queue contract: jobs collect here
/// and are picked up on a later tick — visibly separate from the moment
/// they were scheduled. A real worker would dequeue from a broker; the
/// timing gap, not the transport, is what this demonstrates.
/// </summary>
public sealed class WorkerReviewQueue(ILogger<WorkerReviewQueue> logger) : IReviewJobQueue
{
    private readonly Queue<ClaimReviewJob> _pending = new();

    public int PendingCount => _pending.Count;

    public ValueTask EnqueueAsync(ClaimReviewJob job, CancellationToken cancellationToken)
    {
        _pending.Enqueue(job);
        logger.LogInformation(
            "Review job {JobId} queued for policy {PolicyId} (payout {Payout}): {Reason}",
            job.JobId, job.PolicyId, job.Payout, job.Reason);
        return ValueTask.CompletedTask;
    }

    /// <summary>Simulates the later, separate step where a queued job is actually worked.</summary>
    public bool TryProcessNext()
    {
        if (!_pending.TryDequeue(out var job))
            return false;

        logger.LogInformation(
            "Review job {JobId} picked up on a later tick for policy {PolicyId} — this is where a human review would start (scheduled at {RequestedAt})",
            job.JobId, job.PolicyId, job.RequestedAt);
        return true;
    }
}
