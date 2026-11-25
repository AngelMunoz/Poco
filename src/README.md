# Poco on .NET: the C# implementation

The .NET version of the Poco demo (ideas and shared business walkthrough
are in the [root README](../README.md)). This document covers only what is
specific to this ecosystem: layout, C# idioms, and runtime trade-offs.

## Try it

Requires the .NET 11 SDK (this machine has 11.0.100; C# 15 unions below
need it — no preview flags or polyfills are used).

```bash
dotnet build Poco.slnx                                            # from the repo root
dotnet run --project src/Poco.Mono/Poco.Mono.csproj               # API + MCP, http://localhost:5032
dotnet run --project src/Poco.Worker/Poco.Worker.csproj           # worker, ticks every 5s
```

Demo keys (in `src/Poco.Mono/appsettings.json`, demo only): send an
`X-Api-Key` header — `demo-agent-key`, `demo-adjuster-key`,
`demo-viewer-key`, `demo-admin-key`. The OpenAPI document is served at
`http://localhost:5032/openapi/v1.json` (Development only, via the
built-in `MapOpenApi`); there is no bundled Swagger UI, so bring your own
client. Example requests, including MCP calls, are in
`src/Poco.Mono/Poco.Mono.http`.

## Layout

```
src/
├── Poco.Core/     plain library, net11.0, no packages, AOT-compatible
│   └── Policies/ Quoting/ Claims/ Renewing/ Approving/ Issuing/
├── Poco.Mono/     web API + MCP, one folder per slice, 31-line Program.cs
│   └── Hosting/ Security/ Quotes/ Claims/ Policies/ Mcp/
└── Poco.Worker/   worker, its own Environment/ implementations
```

`Program.cs` is orchestration only (`Add*` then one `Map*` per slice).
Each slice folder holds its endpoint, its policy requirement, and the
environment types it owns.

## How the concepts map to C#

- **Results are C# 15 unions** (`public union ClaimOutcome(ClaimDecision,
  ClaimRejected, PolicyNotFound);`). Cases are plain records in business
  words; `switch` over a union is exhaustive with no discard arm, so a new
  Core case warns in every consumer until handled.
- **Dependency styles**, in order of preference: plain values
  (`QuotePremium.Execute(request, tariffRate, quoteId, quotedAt)`),
  delegates (`AssessRenewal` takes a `Func<Guid, int>`), stateful
  instances (`IPolicyStore`, `IClaimLedger`, `TimeProvider` — the BCL's
  own instance-supplied clock), and interface contracts
  (`IClaimAuthorizer`, `IBillingGateway`, `IPolicyRepository`).
- **Authorization** is ASP.NET Core: a demo API-key handler turns a key
  into `permission` claims, and one named policy per slice capability is
  applied with `.RequireAuthorization(...)`, plus a fallback policy.
  Swapping the handler for JwtBearer/OIDC touches only
  `Security/MonoSecurity.cs`.
- **MCP** uses the official C# SDK (`ModelContextProtocol.AspNetCore`) at
  `POST /mcp`. Tools are another adapter type; each re-checks the same
  permission as its HTTP counterpart. MCP has its own serializer, so tools
  return JSON from a source-generated context in
  `Mcp/InsuranceTools.cs`.
- **The Worker has no caller**: it fulfils the same contracts as a service
  identity (auto-approve only small payouts) and ticks on a
  `PeriodicTimer`.

## Local cloud-like environment: Aspire

This demo does not wire up Aspire (to stay the same size as the JVM one),
but .NET has a first-class answer when the demo grows real dependencies —
databases, queues, other services — and you want them running locally the
way they will run in the cloud:

- An **AppHost** project models the whole system in code: this API and
  worker as projects, plus containers for infrastructure (Postgres, Redis,
  a queue…) pulled from a registry.
- One command (`aspire run`, or running the AppHost) starts everything:
  it allocates ports, injects connection strings and service-discovery
  endpoints as configuration, order dependencies with wait-for rules, and
  opens a dashboard with the logs, traces, and metrics of every resource
  in one place.
- It needs a container runtime — **Docker or Podman**. On this machine
  that is Aspire CLI 17 with Podman 6.2, no Docker daemon required.

The AppHost sketch for this repo makes the fit obvious — Core needs no
changes at all, and the hosts just gain neighbours:

```csharp
var api = builder.AddProject<Projects.Poco_Mono>("api");
var worker = builder.AddProject<Projects.Poco_Worker>("worker")
    .WithReference(api);
```

Why it matters for the dev-loop discussion above: on a constrained dev
machine, one orchestration command replaces five terminals, three
hand-copied connection strings, and the "works on my machine" setup
script — and the dashboard replaces grepping four log files.

## Asynchronous operations, two kinds

- `ScheduleClaimReview` is **asynchronous by nature**: it enqueues a
  `ClaimReviewJob` through `IReviewJobQueue.ValueTask EnqueueAsync` and
  returns a `ClaimReviewReceipt` (`Status = "Queued"`). Mono maps it to
  `POST /claims/schedule-review` → **202 Accepted**, exposes the still
  pending jobs at `GET /claims/review-jobs`, and the Worker schedules
  reviews for manual-review payouts and picks jobs up on a *later* tick.
  The union has no reviewed/approved case, so a receipt cannot be misread
  as a decision.
- `VerifyPolicyCoverage` is **asynchronous only in implementation**: its
  `IAsyncPolicyRegistry.FindAsync` may do I/O (Mono simulates a remote
  registry with a 25 ms delay), but the awaited result is the final
  coverage answer at `GET /policies/{id}/verify?amount=`.

Rule of thumb when adding operations: if the caller's next step needs the
answer, model a final outcome and make the plumbing as async as it needs
to be. If the caller's next step is "someone/something else continues
this", model a dispatch: validate, enqueue, return a receipt, end.

## Advantages of this ecosystem

- One SDK and one project system for library, API, and worker; the whole
  solution builds with a single `dotnet build`.
- ASP.NET Core Minimal APIs keep slices small and readable, with policies,
  OpenAPI, and source-generated JSON built in.
- Strong performance per megabyte: the JIT runtime is lean, and Native
  AOT is available in the same toolchain when startup and memory matter
  most (with the reflection limits noted below).
- `TimeProvider`, records, and now unions make the "explicit inputs,
  named outcomes" style natural to write.

## Developing in constrained environments (cloud dev boxes, VDI)

"Performance" here means the dev loop, not production: how fast you can
edit, rebuild, restart, and re-test on a machine where RAM and CPU are
shared and modest.

- **Incremental builds are the default.** Roslyn only recompiles what
  changed. Measured on this machine (warm SDK, these projects):
  a no-op `dotnet build Poco.slnx` took ~7 s end to end, and a one-file
  change in Core rebuilt in ~2 s, including the consumers that had to
  re-link against it.
- **Restarts are cheap, and often unnecessary.** `dotnet watch` applies
  many edits (endpoints, method bodies) to the running API without a
  restart. When a restart is needed, the API itself is back in about a
  second.
- **No heavyweight build daemons.** There is a lightweight compiler
  server, but nothing comparable to a Gradle + Kotlin daemon pair
  holding hundreds of MB each between builds. On a shared VDI desktop
  that difference is what you feel.
- **Keep AOT out of the dev loop.** `dotnet publish` with Native AOT is
  a slow, native-toolchain build (think minutes, plus trim warnings to
  read — the reflection-based MCP tool discovery needs checking there).
  Develop against the JIT build; publish AOT only for release, the same
  way you would treat any native compilation.

That combination — one SDK, fast incremental compiles, watch mode, small
resident footprint — is why the .NET loop generally feels better on
constrained dev hardware, even before any runtime advantage.

## Deliberate simplifications (this implementation)

In-memory stores and ledgers, seeded worker queues, tariffs and demo
identities in `appsettings.json`, a pretend billing gateway, and in-memory
OpenAPI/MCP state. Each sits behind a slice-local class or config section,
so replacing one changes this app only.
