# Poco on the JVM: the same demo, Kotlin-flavoured

This is the JVM counterpart of the C# demo in the repository root. Same
business, same size, same acceptance checks — translated into Kotlin idioms
rather than transliterated from C#. If you know the C# version, use the
mapping table below as your tour guide.

## Run it

Requires a JDK 21 (the Gradle toolchain is pinned to it).

```bash
./gradlew build

./gradlew :poco-api:bootRun      # web API + MCP on http://localhost:5033
./gradlew :poco-worker:run       # background worker, ticks every 5 seconds
```

OpenAPI is included (springdoc): the spec is at
`http://localhost:5033/v3/api-docs` and Swagger UI at
`http://localhost:5033/swagger-ui.html`, both public for testing while
business routes still require the demo `X-Api-Key` header. Controllers are
the documentation source — there is no hand-maintained spec to drift.

Demo API keys, endpoints and flows match the C# version (see the root
README): `demo-agent-key`, `demo-adjuster-key`, `demo-viewer-key`,
`demo-admin-key`, sent as `X-Api-Key`. MCP lives at `POST /mcp` with the
same keys.

## Layout

```
jvm/
├── poco-core/     pure Kotlin library — no Spring, no server, no I/O
│   └── com.poco.core/{policies, quoting, claims, renewing, approving, issuing}
├── poco-api/      Spring Boot + Kotlin — one package per slice
│   └── com.poco.api/{config, security, quotes, claims, policies, mcp}
└── poco-worker/   plain Kotlin program (no framework), same Core
```

As in the C# version, each app has an explicit composition root: Spring
beans are written out in `config/ApiEnvironmentConfig.kt` instead of using
component-scan magic, so the API's whole environment can be read top to
bottom. `Program.cs` there, `@Configuration` here — same job.

## How the concepts translated

| C# version | JVM/Kotlin version | Why |
|---|---|---|
| `record` | `data class` | The direct equivalent: value semantics for free. |
| C# 15 `union` | `sealed interface` + `data class` cases, exhaustive `when` | Same guarantee: add a case and every unhandled caller stops compiling. |
| Static `Execute(...)` | `object QuotePremium { fun execute(...) }` | One named operation per file, no instances to construct. |
| `Func<Guid, int>` | `(UUID) -> Int` function type | Lambdas are the normal way to pass one small question. |
| `TimeProvider` | `java.time.Clock` | The JVM's own fakeable clock — the same idea, the local idiom. |
| Interface contracts | Interfaces, `fun interface` for single methods | Single-method contracts can then be lambdas in tests and Java/Kotlin call sites. |
| `Guid`, `decimal` | `java.util.UUID`, `BigDecimal` | Money is never a `Double`; scales are set explicitly in Core. |
| ASP.NET policies | Spring Security route rules by authority | Route rules in `security/ApiKeySecurity.kt`, same permission names. |
| MCP tools (C# SDK) | Java MCP SDK, stateless WebMVC transport | `mcp/McpConfig.kt` carries the caller's name/permissions into each tool via the transport context. |
| Worker `BackgroundService` | Plain `main()` loop | The worker needs no framework; a 5-second loop and `println` are enough at demo size. |

Two deliberate non-translations:

- **Core is synchronous plain Kotlin.** No coroutines, no `suspend`, no
  Spring annotations in `poco-core`. Async concerns live at the edges, and
  keeping Core plain is what lets a Java team read (or port) it easily.
- **Authorization is still the consumer's job.** `ApproveClaim` asks a
  `ClaimAuthorizer`; the API answers it from Spring Security authorities,
  the worker from a service allow-list with a lower limit. Clients who
  prefer Java can implement any of these interfaces from Java unchanged.

## One Spring gotcha worth knowing

Returning 401/403 with `sendError` triggers the servlet `/error` dispatch,
which re-enters the security chain without the API-key authentication and
masks a 403 as a 401. The security handlers therefore set the response
status directly. Look for the comment in `security/ApiKeySecurity.kt`.

## Local cloud-like environment: two options, and yes — Aspire works here too

The JVM world splits what Aspire bundles into separate tools:

- **Docker/Podman Compose** for the infrastructure (databases, queues),
  with Spring Boot's Docker Compose support starting those services when
  the app starts, or **Testcontainers** (Spring Boot's `@ServiceConnection`)
  provisioning them from code for tests and dev. Same containers, same
  "no manual provisioning" idea — but you launch the apps yourself, and
  there is no unified dashboard or service-discovery model on top.
- Nothing JVM-native quite matches Aspire's single package of
  orchestration + dashboard + telemetry + publish model. Quarkus Dev
  Services come closest in spirit for dependencies, inside a different
  framework than this demo's Spring Boot.

The more interesting answer: **you do not need a JVM equivalent, because
Aspire itself is polyglot.** Current Aspire (the docs describe 13.6+, and
CLI 17 is installed on this machine) ships the first-party
`Aspire.Hosting.Java` integration. From the same kind of AppHost (written
in C# or TypeScript), one line models this exact project:

```csharp
var jvmApi = builder.AddSpringBootApp("jvmapi", "../poco-api");
```

Aspire then builds and launches it through its Gradle wrapper (a JDK on
`PATH` is required), attaches the OpenTelemetry Java agent, and shows it
in the same dashboard next to any .NET resources — so a single AppHost
could orchestrate the .NET API, the .NET worker, and this JVM API side by
side. The dashboard also accepts OTLP telemetry from any stack, so even a
Compose-orchestrated JVM app can report into it.

Practical guidance: if the team standardises on Aspire for the .NET side,
adopting the same AppHost for the JVM services is reasonable and keeps
one local-environment story. If the JVM side lives on its own, Compose +
Testcontainers is the idiomatic, tool-supported path — just expect to
assemble the dashboard/observability piece yourself. (The Java hosting
integration is recent; check its current version before committing to it.)

This demo deliberately wires up neither — it stays the same size as the
.NET version — but both projects are shaped so orchestration can be added
around them without touching Core.

## Developing in constrained environments (cloud dev boxes, VDI)

"Performance" here means the dev loop. Honest summary first: the JVM loop
is heavier than the .NET one, and the weight is in the tooling, not in
your code.

Where the time and memory go:

- **Daemons.** Gradle keeps a daemon JVM and Kotlin keeps a compile
  daemon, next to the JVM running your app. On this machine, the resident
  build daemons after a few builds were each holding several hundred MB.
  On a shared VDI desktop, that is the main cost to budget for.
- **Cold vs warm builds.** The first-ever `:poco-core:build` in this repo
  took about 3 minutes (dependency downloads plus cold daemons). With
  warm caches, a no-op `./gradlew build` is ~3 s and a one-file Core
  change rebuilds in ~3 s, because Gradle only recompiles the touched
  module and its dependents. The gap between cold and warm is the story.
- **App restarts.** Spring Boot re-creates its whole context on restart
  (our API needs ~2-3 s to first response). Restarting to test a Core
  change is the slow path — test Core directly instead.

Tips that make it comfortable anyway:

- Do not stop the Gradle daemon (`gradle --stop`) between builds; every
  cold start repays the 3-minute tax in miniature.
- Build the module you touched (`:poco-core:build`, `:poco-api:test`)
  instead of the whole project while iterating.
- Keep Gradle/Kotlin caches (`~/.gradle`) on the fast, persistent disk —
  on ephemeral cloud dev boxes, losing them means re-downloading the
  world each morning.
- Consider Spring Boot devtools or `spring.main.lazy-initialization`
  for the API module if restarts dominate your loop (not wired in here,
  to keep the demo minimal).
- Keep GraalVM native image out of the dev loop entirely. Like .NET's
  AOT publish, it is a slow release-time build, not an edit-time tool.

None of this affects the architecture story: Core still compiles and
tests in seconds without Spring ever starting. The weight appears when
you insist on booting the full API to check a library change — which is
exactly what this repo's structure lets you avoid.

## Asynchronous operations, two kinds

- `ScheduleClaimReview` is **asynchronous by nature**: a `suspend`
  enqueue through `ReviewJobQueue`, returning a `Queued` receipt. The API
  answers `POST /claims/schedule-review` with **202 Accepted** and lists
  still-pending jobs; the worker schedules reviews for manual-review
  payouts and processes them on a later tick. No "reviewed" case exists.
- `VerifyPolicyCoverage` is **asynchronous only in implementation**: its
  registry contract suspends (the API's stand-in registry delays 25 ms),
  but the completed call holds the final coverage answer.

Kotlin notes: `suspend` lives in Core's contracts (coroutines is an `api`
dependency of `poco-core` for exactly that reason), while the servlet
controllers bridge with `runBlocking` — a demo shortcut worth replacing
with a reactive stack only if a real workload demands it. The rule is the
same as in C#: model a dispatch when the work continues elsewhere, model
a final outcome when the caller needs the answer.

## Parity checklist (verified against the running apps)

- Quote for Dana Novak (EU, Medium, 40,000): premium **1400.00** in the API,
  MCP, and the worker.
- Claim of 18,000 against Alice's policy: payout **17500**, manual review.
- Adjuster approves 17,500 but not 25,000 (the consumer's authorizer, not
  the route rule, stops it); admin approves 25,000.
- Worker issues both batch policies via invoicing, auto-approves only the
  650 payout, and leaves 17,500 for a human. Ledger total **18150**.
- No key → 401, wrong permission → 403, `/mcp` without `mcp:use` → 403,
  per-tool permission failures return MCP errors.
- Schedule-review returns 202 with a `Queued` receipt and the job remains
  visible in the queue; verify-coverage returns the final covered amount
  (17,500 for an 18,000 claim on Alice's policy) in both hosts.
