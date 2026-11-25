namespace Poco.Core.Reviewing;

public sealed record ClaimReviewRequest(Guid PolicyId, decimal Payout, string Reason);

public sealed record ClaimReviewJob(Guid JobId, Guid PolicyId, decimal Payout, string Reason, DateTimeOffset RequestedAt);

public sealed record ClaimReviewReceipt(Guid JobId, Guid PolicyId, string Status, DateTimeOffset RequestedAt);

public sealed record ReviewSchedulingRejected(string Reason);

/// <summary>Queued (a receipt, not a decision) or not even queued. There is no "reviewed" case — by design.</summary>
public union ReviewScheduleOutcome(ClaimReviewReceipt, ReviewSchedulingRejected);

/// <summary>
/// Contract: put this job somewhere it will be picked up later. A queue, a
/// table, an outbox — the consumer chooses. Enqueue returning means
/// "accepted for later", never "done".
/// </summary>
public interface IReviewJobQueue
{
    ValueTask EnqueueAsync(ClaimReviewJob job, CancellationToken cancellationToken);
}

/// <summary>
/// Asynchronous BY NATURE: this operation's job is to start something, not
/// to finish it. It validates, enqueues a human-review job, and returns a
/// receipt. The review itself happens later, somewhere else — a human, a
/// batch, another service. Callers must not read the receipt as an approval:
/// the union has no approved case, so that misunderstanding does not compile
/// into a plausible-looking branch. HTTP adapters map this to 202 Accepted;
/// the receipt is the whole answer.
/// </summary>
public static class ScheduleClaimReview
{
    public static async ValueTask<ReviewScheduleOutcome> ExecuteAsync(
        ClaimReviewRequest request,
        IReviewJobQueue queue,
        Guid jobId,
        TimeProvider timeProvider,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(queue);
        ArgumentNullException.ThrowIfNull(timeProvider);

        if (request.PolicyId == Guid.Empty)
            return new ReviewSchedulingRejected("Policy id is required.");

        if (request.Payout <= 0)
            return new ReviewSchedulingRejected("Payout must be positive.");

        if (string.IsNullOrWhiteSpace(request.Reason))
            return new ReviewSchedulingRejected("A reason for the review is required.");

        var now = timeProvider.GetUtcNow();
        var job = new ClaimReviewJob(jobId, request.PolicyId, request.Payout, request.Reason.Trim(), now);

        // The async step is the point of the operation: once the queue has
        // accepted the job, this operation is over — successfully, and
        // without the review having happened.
        await queue.EnqueueAsync(job, cancellationToken).ConfigureAwait(false);

        return new ClaimReviewReceipt(job.JobId, job.PolicyId, "Queued", now);
    }
}
