using Poco.Core.Claims;
using Poco.Worker;
using Poco.Core.Reviewing;
using Poco.Worker.Environment;

var builder = Host.CreateApplicationBuilder(args);

// The Worker is its own consuming environment: it registers its own
// stateful instances for Core's contracts. Mono registers completely
// different implementations of the same contracts in its own root.
// Worker is a singleton hosted service, so singletons match its lifetime.
builder.Services.AddSingleton<WorkerPolicyStore>();
builder.Services.AddSingleton<IPolicyStore>(sp => sp.GetRequiredService<WorkerPolicyStore>());
builder.Services.AddSingleton<LoggingClaimLedger>();
builder.Services.AddSingleton<IClaimLedger>(sp => sp.GetRequiredService<LoggingClaimLedger>());
builder.Services.AddSingleton<ServiceClaimAuthorizer>();
builder.Services.AddSingleton<InvoiceBillingGateway>();
builder.Services.AddSingleton<WorkerReviewQueue>();
builder.Services.AddSingleton<IReviewJobQueue>(sp => sp.GetRequiredService<WorkerReviewQueue>());
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddHostedService<Worker>();

var host = builder.Build();
host.Run();
