using Poco.Core.Policies;

namespace Poco.Core.Renewing;

public sealed record RenewalOffer(Guid PolicyId, decimal RenewalPremium);

public sealed record RenewalDeclined(Guid PolicyId, string Reason);

/// <summary>
/// C# 15 union: an offer <em>or</em> a decline, with no nullable premium and
/// no boolean-plus-reason pair to keep in sync. Invalid combinations
/// (declined with a premium, offered without one) are unrepresentable.
/// </summary>
public union RenewalOutcome(RenewalOffer, RenewalDeclined);

/// <summary>
/// Pattern 3 — delegates for one-off, single-question dependencies.
///
/// When a dependency is a single query ("how many recent claims does this
/// policy have?"), forcing the consumer to write an interface implementation
/// is ceremony. A <see cref="Func{T, TResult}"/> lets the caller pass a lambda
/// over whatever it already has — a list, a DbSet, a cache. The lambda may
/// close over state, so this pattern can still be stateful; it just carries
/// no named contract. Prefer Pattern 2 when several operations share the
/// same dependency or its lifetime matters.
/// </summary>
public static class AssessRenewal
{
    public static RenewalOutcome Execute(
        Policy policy,
        decimal currentAnnualPremium,
        Func<Guid, int> recentClaimCount,
        Func<DateTimeOffset> clock)
    {
        ArgumentNullException.ThrowIfNull(recentClaimCount);
        ArgumentNullException.ThrowIfNull(clock);

        if (!policy.IsActive)
            return new RenewalDeclined(policy.Id, "Policy is not active.");

        var today = DateOnly.FromDateTime(clock().DateTime);

        // Renewal window: from 30 days before expiry until 30 days after it.
        if (today < policy.ExpiresOn.AddDays(-30))
            return new RenewalDeclined(policy.Id, "Policy is not yet in its renewal window.");

        if (today > policy.ExpiresOn.AddDays(30))
            return new RenewalDeclined(policy.Id, "Renewal window has closed.");

        var claims = recentClaimCount(policy.Id);

        if (claims >= 5)
            return new RenewalDeclined(policy.Id, "Too many recent claims; manual underwriting required.");

        var factor = claims switch
        {
            0 => 0.95m, // no-claims discount
            _ => 1m + (0.10m * claims),
        };

        return new RenewalOffer(policy.Id, Math.Round(currentAnnualPremium * factor, 2));
    }
}
