# Poco: one insurance library, used by an API, agents, and a worker

This repo is a learning example about *sharing business rules without
sharing infrastructure*. A small made-up insurance business (quotes,
claims, renewals, approvals, issuing policies) is written once as a plain
library. A web API, AI agents, and a background worker all use that
library — and none of them forces its way of working on the others.

The demo is implemented twice, with the same scope and the same acceptance
checks, so the ideas can be compared across ecosystems:

| Implementation | Where | Start with |
|---|---|---|
| .NET / C# | `src/` | [`src/README.md`](src/README.md) |
| JVM / Kotlin | `jvm/` | [`jvm/README.md`](jvm/README.md) |

This root document is about the ideas, which are language-agnostic. Each
language folder explains its own tooling, idioms, and trade-offs.

## Run each project

Prerequisites: .NET 11 SDK for the `src/` side, JDK 21 for the `jvm/` side
(Gradle wrapper included there). Business routes on both APIs need an
`X-Api-Key` demo header (`demo-agent-key`, `demo-adjuster-key`,
`demo-viewer-key`, `demo-admin-key`); APIs also serve MCP at `POST /mcp`.

| Project | Run (from repo root) | Test / inspect at |
|---|---|---|
| .NET API + MCP | `dotnet run --project src/Poco.Mono/Poco.Mono.csproj` | Spec: `http://localhost:5032/openapi/v1.json` (Development only). Requests: `src/Poco.Mono/Poco.Mono.http` |
| .NET Worker | `dotnet run --project src/Poco.Worker/Poco.Worker.csproj` | Console log only — it schedules and processes work on a 5s tick |
| JVM API + MCP | `cd jvm && ./gradlew :poco-api:bootRun` | Swagger UI: `http://localhost:5033/swagger-ui.html` · Spec: `http://localhost:5033/v3/api-docs` |
| JVM Worker | `cd jvm && ./gradlew :poco-worker:run` | Console log only, same 5s tick |

Two testing notes:

- The JVM API ships Swagger UI, so it is the quickest way to click through
  the routes. Spec and UI are public on purpose; business routes still
  return 401/403 without the right key.
- The .NET API ships the OpenAPI document but no built-in UI. Feed
  `/openapi/v1.json` to your favourite client (Postman, Insomnia, VS Code
  REST Client with the included `.http` file), which renders the same
  contract. Both documents currently list the identical 11 paths.

## The main idea, in plain words

**The library never fetches anything itself. The caller brings what the
rule needs.**

Think of a pricing rule as a recipe: "give me the current tariff rate and
today's date, and I will price this quote". The recipe does not open a
database, read a config file, or check the clock. Whoever calls it — an
HTTP endpoint, a worker, an agent tool, a test — supplies those things in
its own way. That is why the same recipe works everywhere.

Every call follows the same three steps:

```
1. The caller gathers inputs       HTTP body, queue item, config, database…
2. It calls one library operation  price this quote, triage this claim…
3. It translates the result back   HTTP 200/400/404, a log line, an agent reply…
```

Steps 1 and 3 differ per caller. Step 2 is shared. Code doing steps 1 and
3 is called an *adapter* here, and adapters should stay thin: if an adapter
grows a business rule, that rule belongs in the library so every caller
gets it.

## Seven operations, four ways to supply what they need

| Operation | What it does | What the caller supplies |
|---|---|---|
| Price a quote | Works out a premium | Plain values (tariff rate, id, time) |
| Triage a claim | Decides the payout | Stateful objects (a policy store, a ledger, a clock) |
| Assess a renewal | Offers or declines a renewal premium | One small function ("how many recent claims?") |
| Approve a claim | Decides whether a payout may be approved | A contract: "may this approver approve this amount?" |
| Issue a policy | Charges, then saves a policy | Contracts: billing and policy storage |
| Schedule a claim review | Starts a human review — and stops there | A queue contract; returns a receipt, not a result |
| Verify coverage | Answers "is this covered, and how much?" | An async lookup contract; returns the final answer |

The last two make a distinction worth keeping straight. *Asynchronous by
nature* means the work is not finished when the call returns: scheduling a
review only enqueues it (web adapters answer 202 Accepted with a receipt,
and the result type deliberately has no "reviewed" case to misread).
*Asynchronous only in implementation* means the call may wait on I/O, but
when it completes you hold the final answer — verify-coverage is a plain
question with an async pipe. Whether a call is async is plumbing; whether
the work is done when it returns is the design decision.

The pattern is to use the simplest supply style that fits: values first,
then a small function, then an object with a lifetime, then a named
contract (an interface) when the dependency is a capability several
operations share.

Two ideas make this safe to evolve:

- **Results are one of a few named answers.** Triage ends in a decision, a
  rejection, or "not found" — never `null`, never an exception for a normal
  outcome. Callers must handle every case, and in both languages the
  compiler checks that they do. Add a case, and every caller that has not
  handled it stops compiling. In a monorepo, that is most of the safety
  you need.
- **Contracts say what, never how.** "May this approver approve 25,000?"
  is answered by the API from the caller's permissions, by the worker
  from a service allow-list with a lower limit, and by a test with a
  one-line stub. The library cannot tell the difference, by design.

## Who is allowed to do what

Authentication (who is calling?) and authorization (what may they do?)
are checked by each *caller-facing* app, never inside the library:

- API callers and agents present a credential and get named permissions
  (for example `claims:triage`). No credential → 401; a credential without
  the needed permission → 403.
- The worker has no caller, and that is fine. It works under its own
  service identity, with access only to what it needs. If a person started
  a job, the API checks their permission *before* enqueueing and stamps
  the job with who asked and which customer it belongs to; the worker then
  stays inside that boundary. It never replays a user's credential.
- Limits that are business rules rather than permissions (a large claim
  always needs a human) live in the library's results, so they hold for
  every caller, including the worker.

Agents are not special: an agent is another caller with its own credential
and usually narrower permissions, reaching the same operations through
MCP tools that behave like extra endpoints.

## Running the whole thing locally

Both implementations run fine as plain processes, which is how the demo
stays small. When real dependencies arrive (a database, a queue, a second
service), orchestration becomes the next question. The .NET side's answer
is **Aspire**: one AppHost models projects plus Docker/Podman containers,
wires configuration and service discovery, and gives every resource one
dashboard for logs, traces, and metrics. The JVM side answers with
**Docker/Podman Compose + Testcontainers**, and can also be orchestrated
by Aspire itself through its Java hosting integration. Details, sketches,
and trade-offs are in each language README.

## Which ecosystem should you pick?

Both implementations are deliberately equivalent, so the choice is about
your team and your machines, not about features. The root README stays out
of ecosystem advocacy; each language folder argues its own case
([.NET](src/README.md), [JVM](jvm/README.md)).

One comparison does belong here, because it affects day-to-day work in
constrained *development* environments — cloud dev boxes and VDI desktops
where RAM and CPU are shared and nothing is fast:

| Dev-time question | .NET / C# | JVM / Kotlin |
|---|---|---|
| Edit → rebuild loop | Fast incremental builds, no permanent daemon | Fast when the Gradle/Kotlin daemons are warm; cold starts are slow |
| Memory while developing | One SDK, lightweight compiler server | Gradle daemon + Kotlin daemon + the app JVM — budget a few hundred MB each |
| Restarting the app | Quick; `dotnet watch` reloads many edits without a restart | Spring context restart per change unless you add dev tooling |
| Ahead-of-time builds | Available, but slow — a release step, not a dev step | Native image, same warning: keep it out of the edit loop |

Short version: on a constrained dev machine the .NET loop is usually the
more comfortable one, mostly because it carries no heavyweight build
daemons. The JVM loop is perfectly workable if you keep the daemons warm,
build only the module you touched, and test library changes without
booting the whole API. Both language READMEs give concrete numbers and
tips from running these exact projects.

## Deliberate simplifications (both implementations)

Data is in-memory and resets on restart; demo keys sit in config;
billing is pretend; the worker's queue is a seeded list. Each of those is
a fulfilment detail inside one app, so replacing any of them (a real
database, real login, a real queue, a real payment provider) changes one
app's implementation classes and leaves the library and the other apps
untouched. That replaceability is the point of the exercise.
