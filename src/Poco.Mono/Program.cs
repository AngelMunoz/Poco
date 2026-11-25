using Poco.Mono.Claims;
using Poco.Mono.Mcp;
using Poco.Mono.Policies;
using Poco.Mono.Quotes;
using Poco.Mono.Hosting;
using Poco.Mono.Security;

// Program.cs is orchestration only — the table of contents of this API.
// Every line below delegates to the slice or concern that owns the plumbing:
// Hosting (framework setup), Security (who may call), and one folder per slice.

var builder = WebApplication.CreateSlimBuilder(args);

builder.AddMonoHost();
var environment = builder.AddInsuranceEnvironment();
builder.AddMonoSecurity();
builder.AddInsuranceMcp();

var app = builder.Build();

app.UseMonoSecurity();
app.MapMonoOpenApi();

// Vertical slices at the API level: each Map* is one self-contained feature
// (route, policy, and translation to one Core operation).
app.MapQuotes(environment);
app.MapClaims(environment);
app.MapPolicies(environment);
app.MapInsuranceMcp();

app.Run();
