namespace Poco.Core.Quoting;

public enum RiskLevel
{
    Low,
    Medium,
    High,
}

public sealed record QuoteRequest(
    string HolderName,
    int Age,
    string Region,
    decimal CoverageAmount,
    RiskLevel Risk);

public sealed record Quote(
    Guid Id,
    string HolderName,
    string Region,
    RiskLevel Risk,
    decimal CoverageAmount,
    decimal AnnualPremium,
    DateTimeOffset QuotedAt,
    DateOnly ValidUntil);

public sealed record QuoteRejected(string Reason);

/// <summary>
/// C# 15 union: a closed set of case types. Cases are plain domain records
/// (a <see cref="Quote"/> is itself the success case), assignments convert
/// implicitly, and every <c>switch</c> over this type must handle all cases —
/// adding a case later turns unhandled consumers into compiler warnings.
/// No marker interface, no private base constructor, no visitor.
/// </summary>
public union QuoteOutcome(Quote, QuoteRejected);

/// <summary>
/// Pattern 1 — a simple static function taking the right inputs.
///
/// Every dependency has already been resolved by the caller into a plain value
/// (tariff rate, id, timestamp), so this operation needs no interface, no
/// delegate and no container. Same inputs always produce the same output,
/// which makes it trivial to test and safe to call from any environment.
/// The caller owns <em>where</em> the tariff rate came from: config, a table,
/// a remote pricing service — Core neither knows nor cares.
/// </summary>
public static class QuotePremium
{
    public static QuoteOutcome Execute(
        QuoteRequest request,
        decimal tariffRate,
        Guid quoteId,
        DateTimeOffset quotedAt)
    {
        if (string.IsNullOrWhiteSpace(request.HolderName))
            return new QuoteRejected("Holder name is required.");

        if (request.Age is < 18 or > 85)
            return new QuoteRejected("Holder must be between 18 and 85 years old.");

        if (request.CoverageAmount <= 0)
            return new QuoteRejected("Coverage amount must be positive.");

        if (tariffRate <= 0)
            return new QuoteRejected($"No tariff rate supplied for region '{request.Region}'.");

        var ageFactor = request.Age switch
        {
            < 25 => 1.25m,
            > 65 => 1.40m,
            _ => 1.00m,
        };

        var premium = Math.Round(request.CoverageAmount * tariffRate * ageFactor, 2);

        return new Quote(
            quoteId,
            request.HolderName.Trim(),
            request.Region,
            request.Risk,
            request.CoverageAmount,
            premium,
            quotedAt,
            DateOnly.FromDateTime(quotedAt.DateTime).AddDays(30));
    }
}
