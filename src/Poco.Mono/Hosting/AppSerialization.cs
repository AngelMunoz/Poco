using System.Text.Json.Serialization;
using Poco.Core.Approving;
using Poco.Core.Claims;
using Poco.Core.Issuing;
using Poco.Core.Policies;
using Poco.Core.Quoting;
using Poco.Core.Renewing;
using Poco.Core.Reviewing;
using Poco.Core.Verifying;

using Poco.Mono.Claims;

namespace Poco.Mono;

/// <summary>Transport-level error shape shared by every HTTP slice adapter.</summary>
public sealed record ApiError(string Error);

[JsonSerializable(typeof(QuoteRequest))]
[JsonSerializable(typeof(Quote))]
[JsonSerializable(typeof(ClaimRequest))]
[JsonSerializable(typeof(ClaimDecision))]
[JsonSerializable(typeof(ClaimDecision[]))]
[JsonSerializable(typeof(Policy[]))]
[JsonSerializable(typeof(RenewalOffer))]
[JsonSerializable(typeof(Policy))]
[JsonSerializable(typeof(ClaimApproval))]
[JsonSerializable(typeof(ClaimApproval[]))]
[JsonSerializable(typeof(ApproveClaimBody))]
[JsonSerializable(typeof(ClaimReviewRequest))]
[JsonSerializable(typeof(ClaimReviewReceipt))]
[JsonSerializable(typeof(ClaimReviewJob[]))]
[JsonSerializable(typeof(PolicyCoverage))]
[JsonSerializable(typeof(ApiError))]
internal partial class AppJsonSerializerContext : JsonSerializerContext
{
}
