# Poco: one small insurance library, used three ways

This repo is a learning example. It shows how one library of business rules
can be used by a web API, by AI agents (through MCP), and by a background
worker — without the library knowing anything about HTTP, agents, or workers.

The business is made-up insurance: pricing a quote, triaging a claim,
checking a renewal, approving a claim, and issuing a policy.

## Try it first

```bash
dotnet build Poco.slnx

# Web API on http://localhost:5032 (example requests are in src/Poco.Mono/Poco.Mono.http)
dotnet run --project src/Poco.Mono/Poco.Mono.csproj

# Background worker (works through a small queue every 5 seconds, watch the log)
dotnet run --project src/Poco.Worker/Poco.Worker.csproj
```

Most examples need an `X-Api-Key` header. These demo keys are in
`src/Poco.Mono/appsettings.json` — use them only for this demo:

| Key | Who it pretends to be | Can do |
|---|---|---|
| `demo-agent-key` | Sales agent | Quotes, renewals, issue policies, MCP |
| `demo-adjuster-key` | Claims adjuster | Triage and approve claims, MCP |
| `demo-viewer-key` | Read-only viewer | Look at policies and claim decisions |
| `demo-admin-key` | Admin | Everything, including large claim approvals |

## The three projects

```
src/
├── Poco.Core/      The business rules. A plain library, no web code at all.
│   ├── Policies/   One shared record: Policy
│   ├── Quoting/    QuotePremium — work out a premium
│   ├── Claims/     TriageClaim — decide what a claim pays
│   ├── Renewing/   AssessRenewal — can this policy renew, and for how much?
│   ├── Approving/  ApproveClaim — may this payout be approved?
│   └── Issuing/    IssuePolicy — turn a quote into a policy
├── Poco.Mono/      The web API (also serves the MCP tools for agents)
└── Poco.Worker/    A background service that runs the same rules on a timer
```

Both apps reference `Poco.Core`. That is the whole trick — the rest of this
document explains how the sharing works without turning into a mess.

## The main idea, in plain words

**Core never fetches anything itself. The caller brings what the rule needs.**

Think of a Core operation like a recipe that says "give me the current
tariff rate and today's date, and I will price this quote". The recipe does
not open a database, read a config file, or check the clock itself.
Whoever calls it — an HTTP endpoint, a worker, a test — supplies those
things. That is why the same recipe works everywhere: each caller supplies
things in its own way.

Every call follows the same three steps:

```
1. The caller gathers inputs       HTTP body, queue item, config, database…
2. It calls one Core operation     QuotePremium.Execute(...), TriageClaim.Execute(...)
3. It translates the result back   HTTP 200/400/404, a log line, an MCP reply…
```

Step 1 and 3 are different in every app. Step 2 is shared. Code that does
steps 1 and 3 is called an *adapter* here. Adapters should stay thin: if an
adapter starts containing a business rule ("premiums double for young
drivers"), that rule belongs in Core so the other callers get it too.

A few words used below:

- **Composition root** — the place where an app builds its objects and hands
  them to the code that needs them. Here that is mostly `Program.cs`.
- **Contract** — a promise about shape, usually an interface: "give me any
  object that can find a policy by id". How the object does it is its own
  business.
- **Union** — a type that can be one of a fixed list of results. Explained
  with an example further down.
- **Policy (authorization)** — a named rule like "this endpoint needs the
  `claims:triage` permission".

## Walkthrough 1: the same pricing, two callers

`QuotePremium` in `src/Poco.Core/Quoting/QuotePremium.cs` is a plain
function. It needs four ready-made values:

```csharp
QuotePremium.Execute(request, tariffRate, quoteId, quotedAt)
```

The web API looks the tariff up in `appsettings.json` for each request, uses
`Guid.NewGuid()` and the current time. The worker looks the tariff up in a
fixed table it captured at startup and uses its own clock. Both then get
the same answer for the same input — for example, Dana Novak, 34, EU,
40,000 coverage, medium risk always costs **1,400.00**, in the API response
and in the worker log. If the two apps ever disagree, the difference is in
what they passed in, and you can see those values in each app's setup code.

## Walkthrough 2: when a rule needs more than values

Triaging a claim needs to *look up a policy* and *record the decision*.
That is not a value you can compute first, so `TriageClaim` asks for two
objects instead:

```csharp
TriageClaim.Execute(request, policies, ledger, timeProvider)
```

`policies` and `ledger` are interfaces defined in Core
(`IPolicyStore`, `IClaimLedger`). Core defines what they must be able to
do; each app writes its own version:

- The API keeps policies in an in-memory list and records decisions in
  another list, so it can show them at `GET /claims/decisions`.
- The worker keeps its own list (a snapshot from startup) and its ledger
  adds up a running total and writes each decision to the log.
- A real app would write a version backed by EF Core, Dapper, or a file.
  Core would not change at all, because it only ever talks to the interface.

## Four ways to hand things to a Core operation

The operations use four styles, from simplest to most structured. Pick the
simplest one that fits — you can always move up later.

| Style | Example | Use it when |
|---|---|---|
| Just values | `QuotePremium(request, tariffRate, id, time)` | The caller can work the value out first. Easiest to test: same input, same output, every time. |
| A function (delegate) | `AssessRenewal(policy, premium, id => ..., () => ...)` | The rule needs one small question answered, like "how many claims does this policy have?" Passing a lambda is less work than writing a class. |
| An object (interface) | `TriageClaim(request, IPolicyStore, IClaimLedger, ...)` | The dependency holds state or is shared by several operations — a store with a cache, a ledger that accumulates, a clock you want to fake in tests. |
| Contracts you implement | `ApproveClaim`, `IssuePolicy` (next section) | Same as above, but the operation is built around the interfaces: the interface *is* the deal between Core and your app. |

Rule of thumb: start with values. If you find yourself passing a value that
was painful to compute, or the same two objects to three operations, that
is a sign to use an interface. If it is just one quick lookup, a lambda is
probably enough.

## Results: one of a few named answers (unions)

Operations do not throw exceptions for normal business outcomes, and they
do not return `null` to mean "no". They return a *union*: exactly one of a
fixed list of results, written with the C# 15 `union` keyword:

```csharp
public union ClaimOutcome(ClaimDecision, ClaimRejected, PolicyNotFound);
```

Read it as: triaging a claim ends in a decision, a rejection, or "policy
not found". Callers handle the result with a `switch`, and the compiler
checks that every case is handled:

```csharp
TriageClaim.Execute(...) switch
{
    ClaimDecision decision => TypedResults.Ok(decision),        // → HTTP 200
    PolicyNotFound         => TypedResults.NotFound(),          // → HTTP 404
    ClaimRejected rejected => TypedResults.BadRequest(...),     // → HTTP 400
};
```

Why this helps:

- The possible outcomes are written down in one place, in business words.
- You cannot forget a case: remove one line above and the build complains.
  When Core later adds a new case, every caller complains until it handles
  it. That is how a change in the library safely reaches all its users.
- Bad states are hard to build by accident: a declined renewal has no
  premium field at all, instead of a premium that is `null` plus a boolean
  you have to keep in sync.

Unions are used inside the process only. At the edges (HTTP, MCP, logs),
the adapter translates each case into that world's format, as above.

## Contract-based operations: the interface is the deal

Two operations are built entirely around interfaces that *your* app
implements:

| Operation | Core asks your app for | The API does it by | The worker does it by |
|---|---|---|---|
| `ApproveClaim` | "May this person approve this amount?" (`IClaimAuthorizer`), "record this approval" (`IApprovalLedger`) | Checking the logged-in caller's permissions | Checking a service allow-list with a small auto-approve limit |
| `IssuePolicy` | "Charge this much" (`IBillingGateway`), "save this policy" (`IPolicyRepository`) | A demo card gateway; the same in-memory store used for lookups | Monthly invoicing; its snapshot store |

This is the part to show a sceptical team-mate. Core asks
`CanApprove("adjuster", 25_000)` and accepts yes or no. It does not know —
and cannot see — whether your app answered from ASP.NET permission claims,
a config allow-list, a database of underwriters, or a test stub that always
says yes. The interface is the contract; how you fulfil it is your choice.

That also answers the common worry, "but our app does authorization its own
way". Good — that is where authorization belongs. It stays in your app,
behind the interface, and Core stays reusable.

Small detail worth copying: one class can fulfil several contracts. The
API's `InMemoryPolicyStore` is both the lookup store (triage) and the
repository (issue). You do not need one class per interface unless you
want one.

## Who is allowed to do what

Two different questions, often mixed up:

- **Authentication**: who is calling? (Here: which demo API key was sent?)
- **Authorization**: what is that caller allowed to do? (Here: named
  policies, each needing one `permission` claim, set in `Security/`.)

The API checks authorization on each endpoint, for example `POST /claims`
needs `ClaimsTriage` and `GET /policies` needs `PoliciesRead`. No key at
all gets a 401; a valid caller without the permission gets a 403.

Notice how approval shows both layers working together:

1. The endpoint policy is the front gate: callers without `claims:approve`
   cannot call `POST /claims/approve` at all.
2. Inside, the app's authorizer makes the detailed call: the adjuster may
   approve 17,500 but not 25,000, because only the admin also has
   `claims:approve:large`.

In production you would swap the demo key handler for JWT/OIDC login in
`Security/MonoSecurity.cs`. The policies and endpoints would not change,
because they only look at the resulting claims.

### What about the worker? It has no caller

That is correct, and it is fine. The worker is not serving a user, so it
does not need user permissions. It works as *itself*:

- Give the worker its own identity (a service account or managed identity)
  that can only reach the queues and tables it needs, separate from the
  API's credentials.
- If a person started the job, decide "may they ask for this?" in the API
  *before* enqueueing, and stamp the job with who asked and which customer
  (tenant) it belongs to. The worker then only touches that customer's
  data — in this design, by being handed a store limited to that tenant.
- Some limits are business rules, not permissions, so they live in Core:
  a claim above the manual-review threshold is never auto-approved, whoever
  runs the operation. You saw this in the worker log: it triages a 17,500
  payout and leaves the approval to a human.

## MCP tools for agents: the same pattern, one more time

Agents call the tools in `src/Poco.Mono/Mcp/InsuranceTools.cs` at
`POST /mcp` (`quote_premium`, `triage_claim`, `assess_renewal`,
`approve_claim`, `list_policies`, `list_claim_decisions`). Each tool is a
small adapter, exactly like an HTTP endpoint: check the caller's
permission, gather inputs, call the same Core operation, translate the
result. An agent is just another caller with its own key and permissions —
connecting requires `mcp:use`, and each tool re-checks the permission its
HTTP counterpart needs, so an agent that may quote still cannot approve
claims.

## Add your own operation (step by step)

Say you want a rule "Is this customer eligible for premium (pay-monthly)
billing?" that both apps can use.

1. **One new file in Core**, e.g. `Billing/CheckBillingEligibility.cs`, with:
   the input record, the result records plus a `union`, and one static
   `Execute` method holding the rule.
2. **Decide what the rule needs from outside**, using the table above:
   values if you can, a lambda for one lookup, an interface if it is a
   shared capability. Keep the interface small — only the methods this
   operation actually uses.
3. **One thin adapter per caller**: in the API, an endpoint that gathers
   inputs, calls `Execute`, and switches over every union case to pick a
   status code; in the worker, a few lines in the batch that do the same
   and log the result. No rule logic in either adapter.
4. **Write the fulfilments your app needs** (a class implementing the new
   interface, or a lambda), and register/pass them in that app's setup.
   The other app does the same in its own way.

That is all. If step 2 feels forced, it usually means the rule needs less
from the outside than you first thought — try passing plain values first.

## Why this can work (the short version)

- **Changes are caught by the compiler.** Add a case to a union, or a
  method to a contract, and every caller that must react stops building
  until it does. In a monorepo, that is most of the safety you need.
- **Each app owns its own lifetime choices.** The API can create things per
  request, the worker keeps singletons for its whole run, and Core never
  notices, because Core constructs nothing and stores nothing.
- **Testing Core is cheap.** Most operations are functions with explicit
  inputs, so a test is just: call with known values, check the result case.
  No web server, no database, no container setup:

  ```csharp
  var outcome = QuotePremium.Execute(
      new QuoteRequest("Dana Novak", 34, "EU", 40_000m, RiskLevel.Medium),
      tariffRate: 0.035m, Guid.NewGuid(), DateTimeOffset.UtcNow);
  // outcome is a Quote with AnnualPremium 1400.00
  ```

- **The boundary stays honest.** Dependencies are parameters, so what an
  operation needs is visible in its signature. If a signature gets long,
  that is useful feedback about the design, not something to hide.

## Common mistakes to avoid

- **Putting infrastructure in Core** (a DbContext, `HttpContext`, a logger,
  config reading). The moment Core does that, one caller inherits another
  caller's setup — usually the worker inheriting web stuff it cannot use.
- **Registering services from Core** (an `AddPocoCore()` method). Lifetimes
  and scopes differ per app; let each app register its own implementations.
- **Writing business rules in adapters.** If the API and the worker must
  agree on it, it belongs in Core; adapters only translate.
- **Adding a catch-all to a union switch** (`_ => ...`). It silences the
  compiler warning that is supposed to protect you when cases change.
- **Checking permissions inside Core.** Core can *ask* a contract
  ("may this approver approve?"); the app's answer, based on its own auth,
  is where the check lives.

## Deliberate simplifications in this demo

Data is in-memory and resets on restart; demo keys sit in `appsettings.json`;
the worker's queue is a seeded list in code; billing is pretend. Each of
those is a fulfilment detail inside one app, so replacing any of them
(a real database, real login, a real queue, a real payment provider)
changes one app's implementation classes and leaves Core and the other
app untouched. That replaceability is the point of the exercise.
