namespace Poco.Core.Policies;

/// <summary>
/// The only shared kernel in Core: a policy as data. No behaviour, no storage,
/// no framework type — each consumer keeps policies wherever it already does
/// (EF, Dapper, a file, a list) and maps/projects them into this shape.
/// </summary>
public sealed record Policy(
    Guid Id,
    string HolderName,
    decimal CoverageLimit,
    decimal Deductible,
    DateOnly ExpiresOn,
    bool IsActive);
