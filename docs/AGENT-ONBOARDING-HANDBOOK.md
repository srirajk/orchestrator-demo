# The Conduit Agent Onboarding Handbook

> **Audience.** A domain team — wealth, servicing, insurance, HR, risk, anyone — that wants the
> Conduit gateway to *find* your agent, *route* the right questions to it, *compose* it into
> multi-step plans, and *govern* who may reach it. The point of the platform is this: **you
> describe your agent, and the platform finds it, routes to it, composes it into plans, and governs
> who may reach it — you don't build any of that plumbing yourself.** You supply a description of
> your agent and a running service; everything else is done for you. This handbook exists to make
> you fluent in that.
>
> **How to read it.** If you just want to *do it*, start with **Section 0 — the copy-paste
> quickstart** — a literally-executable walkthrough that onboards a throwaway domain end-to-end in
> ten commands. Then come back for the depth. Sections 1–3 are the mental model and the manifest
> contract — read them once, top to bottom. Sections 4 and 5 are the two chapters that decide
> whether your agent actually *works* in production: **how it gets found** (routing) and **how it
> composes** (workflow). They are the heart of the book; do not skim them. Sections 6–10 are the
> operational reality — protocols, authorization, boot, verification, governance. Section 11 is
> four complete worked examples you can copy. Section 12 is the list of ways to get it wrong.
> Section 13 is the failure-mode catalog — symptom → cause → fix, with the exact error strings.
> Section 14 is the full three-level schema reference (agent / domain / sub-domain) to keep open
> while you write.
>
> **This is the one canonical onboarding document.** It absorbed the former
> `domain-onboarding-standard.md`, which described a manifest schema that never shipped; that file
> is now a pointer stub. Everything here is checked against the code and schemas in this repo as of
> the audit in `docs/specs/ONBOARDING-DOCS-AUDIT.md`.

---

## Table of contents

0. [Quickstart — onboard a domain in ten commands](#0-quickstart--onboard-a-domain-in-ten-commands)
1. [Philosophy — the empty engine](#1-philosophy--the-empty-engine)
   - [Key terms — one sentence each](#key-terms--one-sentence-each)
2. [Mental model & prerequisites](#2-mental-model--prerequisites)
3. [Anatomy of an agent manifest](#3-anatomy-of-an-agent-manifest)
   - [3.5 The domain & sub-domain manifests — where a new domain's vocabulary lives](#35-the-domain--sub-domain-manifests--where-a-new-domains-vocabulary-lives)
4. [The art of skill examples](#4-the-art-of-skill-examples)
5. [Making your agent composable — the workflow story](#5-making-your-agent-composable--the-workflow-story)
6. [Reaching your service — HTTP and MCP](#6-reaching-your-service--http-and-mcp)
7. [Authorization & entitlement](#7-authorization--entitlement)
   - [7.5 The coverage service you must build](#75-the-coverage-service-you-must-build)
8. [What happens at ingestion (the registry-service)](#8-what-happens-at-ingestion-the-registry-service)
9. [Verify & measure](#9-verify--measure)
10. [Governance & lifecycle](#10-governance--lifecycle)
11. [Worked end-to-end examples](#11-worked-end-to-end-examples)
12. [Anti-patterns](#12-anti-patterns)
13. [Failure-mode catalog — symptom → cause → fix](#13-failure-mode-catalog--symptom--cause--fix)
14. [Appendix — full schema reference (agent / domain / sub-domain)](#14-appendix--full-schema-reference-agent--domain--sub-domain)

---

## 0. Quickstart — onboard a domain in ten commands

This is the whole loop, executable, with a **throwaway domain** you can delete afterward. It uses
only real scripts and services in this repo. It reuses one already-running demo service
(`hr-policy`) as the new agent's introspection target, so you can watch the full
ingest → route loop **without standing up any new infrastructure** — exactly the seam that makes a
real onboard "manifests + a service URL." For a *real* domain you point `connection` at *your own*
service instead (§6) and, if your data is client-scoped, add a coverage service (§7.5).

The design payoff to notice while you run it: **you add a whole new business domain without editing
one line of gateway Java or one gateway environment variable.** The domain name, the assistant's
framing copy (`domain_context`), the entity vocabulary, the routing examples, the user-facing
messages — all of it lives in the three JSON files you create below. That is World B (§1), made
concrete.

### Step 1 — bring up the core stack and seed identities (once)

```bash
cd /Users/srirajkadimisetty/projects/orchestrator-demo
docker compose -p orchestrator-demo up -d          # no-profile core set — the everyday demo
bash scripts/seed-users.sh                          # seed demo principals into Redis
```

### Step 2 — write the domain manifest

`registry/domains/quickstart-demo.json` — `domain_id`, `display_name`, and `memory_compaction`
are schema-**required**; `domain_context` is optional but is what makes onboarding zero-config
(§3.5, incoming change 3). Copy `memory_compaction` verbatim from an existing domain — it is
schema-required boilerplate consumed by a future memory service, not by the gateway today (§3.5).

```json
{
  "domain_id": "quickstart-demo",
  "display_name": "Quickstart Demo",
  "domain_context": "a throwaway onboarding demo",
  "memory_compaction": {
    "envelope_version": "context-envelope.v1",
    "must_preserve": ["policy_topic", "domain"],
    "can_drop": ["raw_agent_outputs", "routing_decisions"],
    "summary_policy": {
      "owner": "memory-service",
      "max_summary_tokens": 400,
      "refresh_after_turns": 6,
      "ledger_retention_days": 30,
      "include_runtime_events": ["gateway.agent_completed", "gateway.response_completed"],
      "redact_fields": []
    }
  }
}
```

### Step 3 — write the sub-domain manifest

`registry/domains/quickstart-demo/quickstart-kb.json`. `entity_types`, `required_context`, and
`agents` are all schema-**required** (§3.5). `resource_scoped: false` means role-gate only — **no
coverage service needed** — which is why this quickstart works with zero new services.

```json
{
  "sub_domain_id": "quickstart-kb",
  "display_name": "Quickstart Knowledge Base",
  "parent_domain": "quickstart-demo",
  "resource_scoped": false,
  "entity_types": [
    {
      "key": "policy_topic",
      "extract_as": "policy_topic",
      "kind": "literal",
      "display": "quickstart topic",
      "required": false
    }
  ],
  "required_context": [],
  "agents": ["meridian.quickstart.demo_qa"]
}
```

### Step 4 — write the agent manifest

`registry/manifests/quickstart-demo/meridian.quickstart.demo_qa.json`. `audience: "enterprise"`
skips the structural gate (§7). The `connection` points at the **already-running** `hr-policy`
service purely so introspection succeeds — for a real agent this is *your* service. Examples are
capability-phrased and entity-free (§4.x).

```json
{
  "agent_id": "meridian.quickstart.demo_qa",
  "name": "Quickstart Demo Q&A",
  "description": "Throwaway onboarding-demo agent. Reuses the hr-policy service only as an introspection target. Delete after the walkthrough.",
  "version": "1.0.0",
  "provider": { "organization": "Quickstart" },
  "domain": "quickstart-demo",
  "sub_domain": "quickstart-kb",
  "audience": "enterprise",
  "protocol": "http",
  "connection": {
    "openapi_url": "http://hr-policy:8091/openapi.json",
    "operation_id": "get_policy_qa"
  },
  "capabilities": { "streaming": false },
  "constraints": { "access_mode": "read", "data_classification": "internal", "sla_timeout_ms": 5000 },
  "skills": [{
    "id": "quickstart_demo_qa",
    "name": "quickstart_demo_qa",
    "description": "Throwaway quickstart agent used to demonstrate onboarding.",
    "tags": ["quickstart", "onboarding-demo"],
    "examples": [
      "run the onboarding quickstart walkthrough",
      "demonstrate the quickstart demo agent",
      "show the quickstart onboarding example working"
    ]
  }]
}
```

### Step 5 — re-ingest (the registry-service, not the gateway)

Ingestion is the **registry-service**'s job, not the gateway's (§8). The registry mounts
`./registry` read-only, so re-running it picks up your new files. The gateway does **not** need a
rebuild — it reads the index from Redis.

```bash
docker compose -p orchestrator-demo up -d --build registry-service
```

### Step 6 — confirm it ingested cleanly

```bash
docker compose -p orchestrator-demo logs registry-service | grep -E "ingestion complete|UNVALIDATED"
```

You are looking for exactly these two lines (§8):

```
Registry ingestion complete: N loaded, 0 rejected
select validation: X validated, 0 UNVALIDATED (no output schema)
```

If instead you see `Rejected 1 manifest(s). A partially-loaded registry routes silently…` and the
container exited, one of your three files is schema-invalid — the log line just above it names the
file and the failing field. Fix it and re-run Step 5. (This is fail-loud by design; see §13.)

### Step 7 — World-B gate (no domain knowledge leaked into the gateway)

```bash
bash scripts/world-b-check.sh        # must report CRITICAL: 0
```

You changed only JSON under `registry/`, so this stays at 0 by construction — that is the whole
point.

### Step 8 — smoke the routing + entitlement path

```bash
bash scripts/smoke-route.sh          # asserts on POST /debug/route decisions, no synthesis
```

`smoke-route.sh` drives `POST /debug/route`, which is config-gated OFF by default and **ON for the
demo** via compose (`CONDUIT_DEBUG_ROUTE_DECISION_ENABLED`, `application.yml` `conduit.debug.route-decision.enabled`).
The core demo stack enables it; a prod profile leaves it off.

### Step 9 — measure routing didn't regress (the goal-pick gate)

```bash
bash scripts/routing-measurement-gate.sh
```

This runs `eval/goal-pick/measure_goal_pick.py` against the live gateway's `/debug/route` using
the labeled dataset `eval/goal-pick/labeled_queries.json`, and fails on low domain accuracy, weak
abstention, or canonical-intent poaching (§4.6, §9). **Prerequisites:** the stack up (Step 1),
users seeded (Step 1), and `/debug/route` enabled (Step 8's caveat).

### Step 10 — tear the throwaway down (proves offboarding)

```bash
rm registry/domains/quickstart-demo.json
rm -r registry/domains/quickstart-demo
rm -r registry/manifests/quickstart-demo
docker compose -p orchestrator-demo up -d --build registry-service
docker compose -p orchestrator-demo logs registry-service | grep -E "Pruning|reconciled"
```

The manifest folder is the source of truth: an agent whose manifest is gone is **pruned** on the
next ingest (`Pruning N orphaned agent(s)`), and the domain disappears with its files. No gateway
code references it by name, so there is nothing else to clean up — the same World-B property that
made onboarding cheap makes offboarding cheap (§10).

> **The full onboarding gate**, for a real change, is `scripts/verify.sh` (build → `docker compose
> up` → smoke → e2e → eval, with `world-b-check.sh` wired in as a hard gate). The ten steps above
> are the fast inner loop; `verify.sh` is the belt-and-braces outer one.

---

## 1. Philosophy — the empty engine

Start here, because every rule in this handbook is a consequence of one design decision.

**The Conduit gateway is an empty engine that reads a catalogue.** It carries *zero* embedded
knowledge of any specific agent, client, or business domain. It does not know that "wealth" is a
domain, that relationships have IDs shaped like `REL-…`, that a portfolio can be over-concentrated,
or that CSDR penalties exist. It knows how to read manifests, introspect services, embed sentences,
resolve a dependency graph, enforce three authorization gates, and stream an answer. Everything
domain-specific — every entity type, every ID pattern, every user-facing phrase, every routing
example — lives in **data you supply**: a manifest plus a service URL.

We call this invariant **World B**. In World A, onboarding a new domain means a platform engineer
changes the gateway itself: a special case for "wealth", a regex for the ID format, a hardcoded
prompt string. That is the world most "AI gateways" actually live in, and it does not scale past a
demo — every new domain is a code change, a build, a deploy, a regression risk, and a bottleneck on
one team. **In World B, a new agent — or an entire new business domain — ships as a manifest and a
running service. The gateway itself does not change.**

Hold onto the contrast in one line each:

- **World A** — the platform is taught each domain by hand, so every new domain is a change to
  shared platform logic funneled through the one team that owns it, and it quietly stops scaling
  once a few domains accumulate.
- **World B** — the platform knows nothing about any domain and only how to read a domain that
  *describes itself*, so a new domain ships as a self-owned declaration that changes nothing shared.

### The journey — how World A becomes World B

World A is where almost everyone starts, because it feels natural. Your **first** domain arrives —
say wealth — and you teach the platform about it: it learns what a relationship is, what
"over-concentrated" means, how to recognize and route a wealth question. It works. You ship it, and
it looks like a triumph.

Your **second** domain — insurance — arrives, and you teach the platform again: policy rules, claim
status, a different ID shape. Still fine. But notice what just happened: the platform now *contains*
two domains' worth of knowledge, side by side, and the two teams now share one codebase.

By the **fifth or tenth** domain, the triumph has quietly become a trap. The platform is a monolith
of everyone's domain knowledge — wealth's ID format next to insurance's policy logic next to
servicing's settlement rules. Every new domain is now a change to *shared* code: a build, a deploy, a
regression risk across *all* domains, and a queue behind the one team that owns the platform. A bug
in the servicing logic can break wealth. Onboarding has become a platform *project*, negotiated and
scheduled, rather than something a domain team can just *do*. This is the point at which most
"AI gateways" quietly stop scaling — not because anyone made a bad decision, but because every
individually-reasonable "just add a case for my domain" accreted into a tangle no one can safely touch.

**World B is the inversion that escapes the trap.** The insight is to turn the relationship inside
out: what if the platform knew *nothing* about wealth, insurance, or servicing — and instead knew
only how to read a domain that *describes itself*? Then domain knowledge doesn't live in the platform
at all; it lives in a **declaration the domain team owns**. Each domain becomes a self-contained
package — a manifest plus a running service — that the platform interprets without ever having been
taught about it. Adding the tenth domain is exactly as easy as adding the first, touches no other
team's work, and is owned by the people who actually understand it.

That inversion is the entire reason this handbook exists. You onboard by *describing* your agent — what
it answers, what it needs, what it produces, who may reach it — and a platform that knows how to read
descriptions, but nothing about your domain, does the rest. Everything that follows is just the
vocabulary of that description, and how to write it well.

### Why this matters to *you*, concretely

- **You are not blocked on the gateway team.** You do not file a ticket asking them to "add support
  for servicing." You write a manifest, stand up your service, and the gateway picks you up at boot.
- **Your onboarding is reviewable as data.** A manifest is a diffable, reviewable file. A reviewer
  reads it and knows exactly what you declared — what you route on, what you consume, what you
  produce, what classification your data carries. There is no hidden behavior buried in the platform.
- **The blast radius of your change is your manifest.** You cannot break another team's agent by
  onboarding yours, because you did not touch shared code. The worst you can do is fail your own
  boot validation — loudly, at startup, before any user sees it.

### The invariant is enforced, not aspirational

This is not a style guideline you can quietly violate. On every build, an automated check
(`scripts/world-b-check.sh`) scans the gateway source for domain coupling — domain names, client
names, entity-type literals, ID patterns like `REL-`/`POL-`, user-facing domain copy — and reports a
**CRITICAL** count. That count must be **0**. If a platform change to accommodate your agent would
raise it above zero, the change is wrong by construction: whatever you were about to hardcode belongs
in *your manifest*, not in the platform.

The practical test, stated as a rule you can apply while typing: **if you find yourself wanting a
change to the platform to onboard your agent, that is a bug in the manifest model — raise it, do not
work around it.** In the entire life of this platform so far, four live domains
(`wealth-management`, `asset-servicing`, `insurance`, `hr`) and eighteen agents were onboarded with
zero changes to the gateway. Yours should be no different.

**And "zero changes" now means zero — code *and* config.** The last hand-edited gateway setting an
onboarding used to touch was the assistant's self-description (the framing phrase the intent
classifier and answer synthesizer put in their prompts). That is now a manifest field —
`domain_context` on the domain manifest (§3.5) — which the gateway composes from *all* loaded
domains' phrases. Adding a domain no longer requires editing a gateway environment variable either.
The "extend by describing, never by editing" claim is, as of that change, literally complete.

---

## Key terms — one sentence each

A one-line reference for every term this handbook leans on. Skim it now; keep it open while you read
the deep chapters. The terms are grouped by where they show up on the request path, so related ones
sit together.

**The pieces.**

- **Gateway** — the single, domain-agnostic engine that reads manifests, routes questions, composes
  plans, enforces entitlement, and streams the answer, carrying no domain knowledge of its own.
- **Agent** — a self-contained service you own that answers one kind of question or performs one
  computation, described to the gateway entirely by a manifest.
- **Manifest** — the JSON document you write to describe an agent — identity, routing examples, io
  contract, constraints — and the only thing the gateway needs in order to onboard you.
- **Domain** — a business area such as wealth-management or insurance, whose vocabulary and
  coverage-service URLs live in a domain manifest, never in the gateway.
- **Sub-domain** — a division within a domain (e.g. private-banking, custody-operations) that
  supplies the concrete entity types and coverage-service URLs your agent inherits.
- **Audience** — the one manifest word (`segment` or `enterprise`) that decides whether the
  structural entitlement gate applies to your agent.

**Finding the right agent (routing).**

- **Semantic routing** — matching a user's question to an agent by *meaning* rather than keywords,
  by comparing embedded vectors for nearest neighbor.
- **Skill examples** — the plain-English question phrasings in `skills[].examples` that get embedded
  and become the actual routing signal, not documentation.
- **Embedding** — the numeric vector a sentence is turned into (here by MiniLM) so that closeness in
  vector space approximates closeness in meaning.
- **Goal** — the single capability the router picks for a question, and the point the resolver builds
  the plan backward from.
- **Abstain floor** — the configured score and margin thresholds below which the router declines to
  route rather than guess, catching out-of-scope and near-tie questions.
- **Routing measurement-gate** — the labeled goal-pick harness, run in CI on every new agent, that
  fails onboarding if adding you drops overall accuracy or steals a neighbor's queries.
- **Poaching** — one agent's over-broad examples capturing questions that rightly belong to a
  neighboring agent's intent.

**Composing agents (the io contract).**

- **The io contract** — the optional manifest block (`produces`, `consumes`, `condition`, `map`) that
  makes an agent composable into a multi-step plan; without it, the agent is a flat single node.
- **Produces** — a symbolic, namespaced output type your agent publishes: the socket other agents
  plug into.
- **Consumes** — your declared inputs, each either a resolved `entity` (a leaf input) or a `from`
  dependency on another agent's produced type.
- **DAG / plan** — the directed graph of agents assembled for one request by matching consumers'
  `from` to producers' `type`, never hand-written.
- **The resolver** — the deterministic component that builds that plan at request time by following
  type-equality backward from the goal until every input bottoms out in a resolvable entity.
- **Fan-in** — an agent shape that computes over one or more *other* agents' outputs instead of
  fetching raw data (e.g. a concentration or settlement-risk analytic).
- **Select (the translator)** — a human-written CEL projection on a `consumes` edge that reshapes
  a producer's output into the consumer's expected input, diffable and boot-validated instead of
  LLM-improvised.
- **CEL** — CEL (Common Expression Language), the declarative expression language used for every `select`, `condition`, and
  `map.over` expression, chosen because it is deterministic and reviewable.
- **Conditional (io.condition)** — a CEL boolean over a node's bound input that gates *execution*,
  cleanly skipping the node (`skipped_condition_false`) when false — "not applicable," never "missing."
- **Map / iteration (io.map)** — a bounded, deterministic instruction to run an agent once per element
  of a collection, capped by `max_items` / `max_concurrency` and honest about truncation and emptiness.
- **Blackboard** — the shared per-run store where each agent's output is published under its `name`
  for downstream consumers to read.

**Running the plan and answering.**

- **Executor** — the component that calls each agent in the plan, forwards the JWT, enforces the three
  gates per hop, and harvests outputs to the deadline.
- **Partial-result tolerance** — the guarantee that a failed agent never cancels its siblings; the
  plan joins to the deadline, harvests survivors, synthesizes from what returned, and states what is
  missing.
- **Synthesizer** — the final step that composes one grounded answer from agent outputs, which it
  treats as delimited data to summarize, never as instructions.
- **Grounding / grounded figures** — the rule that every number in the answer comes from an agent's
  output as delimited data; the model summarizes but never computes, recalls, or invents a figure.

**Who may reach it (authorization).**

- **Entitlement** — the whole question of whether this principal may reach this agent and this data,
  answered by three independent gates configured as data.
- **The three gates** — structural ("can this segment reach this domain at all?"), classification
  ("is this data too sensitive for this principal?"), and coverage ("is this specific client in this
  user's book?"), independent by design for defense in depth.
- **Coverage / book-of-business** — the set of clients a given user actually covers, answered only by
  a coverage service and stored nowhere else — never in the gateway, never in a manifest.
- **RESOLVE-vs-CHECK** — resolution finds an entity across *all* entities (principal-agnostic), while
  the coverage check is the only gate that decides whether this principal may actually have it.
- **Per-hop identity / fail-closed** — the requirement that the end-user's JWT is forwarded to and
  verified by every service, which must deny on any verification failure rather than fall open.

**Service, boot, and discipline.**

- **Introspection** — the gateway deriving your input and output wire schemas from your live service
  (OpenAPI, or MCP `tools/list`), so you never hand-write those schemas into the manifest.
- **Entity / entity resolution** — a real business object (a relationship, a policy) and the
  deterministic lookup that turns a human reference in the question into its real ID, never a
  fabricated one.
- **The World-B check** — the automated `scripts/world-b-check.sh` scan that must report CRITICAL 0,
  proving no domain knowledge leaked into the gateway source.

---

## 2. Mental model & prerequisites

### The mental model in one picture

```
   user question
        │
        ▼
  ┌───────────────┐   "which agent means this?"   embeddings of YOUR skill.examples
  │  SEMANTIC     │◄──────────────────────────────  (§4 — routing)
  │  ROUTER       │
  └──────┬────────┘
         │ picks a goal capability (or ABSTAINS)
         ▼
  ┌───────────────┐   "what does that goal NEED, and who produces it?"
  │  DAG RESOLVER │◄──────────────────────────────  YOUR io contract (§5 — workflow)
  └──────┬────────┘   wires producer → consumer, conditionals, maps
         │ a plan (one node, or a fan-in, or a branch, or a per-item map)
         ▼
  ┌───────────────┐   three gates, every hop         Cerbos + data_classification + coverage (§7)
  │  EXECUTOR     │──── forwards the end-user JWT ──►  YOUR service (HTTP / MCP, §6)
  └──────┬────────┘   harvests outputs to the deadline
         │
         ▼
  ┌───────────────┐   agent outputs are DELIMITED DATA, never instructions
  │  SYNTHESIZER  │   the model summarizes; it never computes or invents a number
  └──────┬────────┘
         ▼
   one grounded, streamed answer
```

Two things are worth internalizing from that picture:

1. **The system is probabilistic only at the edges.** Exactly one step on the request path is a
   judgment call: the semantic router deciding which capability the question means. Everything after
   it — dependency wiring, conditionals, iteration, entitlement — is deterministic code over data
   that has already been fetched. This is deliberate (it is the Temporal "quarantine non-determinism
   in activities" discipline). It is why §4 (making the one probabilistic step reliable) and §5
   (making the deterministic middle composable) are the two chapters that matter most.

2. **Your agent is a stranger the gateway learns by reading and asking.** It reads your manifest
   (what you *declare*) and it introspects your service (what you *are*). It never assumes.

### Prerequisites — what you need before you write your manifest

- **A reachable service**, either:
  - **HTTP** with an OpenAPI (Swagger) document the gateway can fetch, or
  - **MCP** (FastMCP) exposing a tool via `tools/list`.
- **Per-hop identity.** Your service must **verify the caller's JWT and fail closed.** The gateway
  forwards the end-user's token to every hop; a service that trusts the caller blindly will not pass
  security review (§7). Decide this now — it is not a bolt-on.
- **A coverage service**, *if* your data is client-scoped — a small service that answers "is this
  client in this user's book?" The book of business lives there and *only* there, never in the
  gateway and never in your manifest (§7).
- **Clarity on your agent's shape.** Before you write `io`, answer one question: is your agent a
  **leaf data agent** (fetches raw facts for one entity), an **analytics fan-in** (computes over
  other agents' outputs), a **conditional step** (runs only when a predicate holds), or a **per-item
  mapper** (runs once per element of a collection)? §5 is organized around exactly these four shapes.

---

## 3. Anatomy of an agent manifest

Your agent is described by a **manifest** — a JSON document you write. One agent = one JSON file, at
`registry/manifests/<domain>/<agent_id>.json` (the ingest glob is `manifests/**/*.json`), validated
against the agent-manifest JSON schema. Below is every field, what it means, and — the part that
matters — *why it exists*. Throughout, keep one distinction in mind:

> **The schema exists in three synced copies** — repo root `agent-manifest.schema.json`,
> `registry/agent-manifest.schema.json`, and the gateway classpath copy at
> `gateway/src/main/resources/agent-manifest.schema.json`. **Only the classpath copy is loaded at
> runtime** (by the registry-service's `ManifestValidator`); the other two exist for editing and
> reference. They must stay byte-identical — `ManifestSchemaCopiesInSyncTest` fails the build on
> drift — so if you ever edit the schema (rare; you almost never should), edit all three.

> **DECLARED vs DERIVED.** Some things you *declare* (identity, classification, routing examples,
> the `io` contract). Some things the gateway *derives* by introspecting your live service (your
> input and output wire schemas). **You never write wire schemas into the manifest.** Declaring what
> the gateway could discover for itself invites drift between the manifest and the truth of the
> service; the manifest is for intent the gateway *cannot* introspect (what you mean, what you
> consume, who may reach you).

### Identity

| Field | Meaning | Why it exists |
|---|---|---|
| `agent_id` | Stable dotted id, `^[a-z0-9]+(\.[a-z0-9_]+)+$` — e.g. `meridian.wealth.holdings`. First segment is your tenant/namespace. | It is the stable handle everything else references — traces, plan graphs, the `io` graph. Because it is stable, you can rename your service or move hosts without breaking references. Namespacing the first segment keeps two tenants from colliding on `holdings`. |
| `name`, `description` | Human-readable. | The `description` is not decoration — write it as a precise statement of what the agent does and, critically, what it does **not** do. The concentration agent's description literally says *"Computes from the supplied holdings; does not fetch data and does not take a relationship_id."* That sentence prevents a whole class of misuse. |
| `version` | Semver (`1.0.0`). | Lets governance track and diff changes to a manifest over time (§10). |
| `provider` | `{ organization, contactEmail? }`. | Who owns this agent when it misbehaves at 2am. |

### Placement in the domain

| Field | Meaning | Why it exists |
|---|---|---|
| `domain` | The business domain, e.g. `wealth-management`. **Not** an enum in the schema. | World B: the value is interpreted against the *loaded domain manifests* — themselves now schema-validated at ingestion (§8) — not against a hardcoded list. A new domain is a new manifest, not a value hardcoded into the platform. |
| `sub_domain` | The sub-domain within it, e.g. `private-banking`, `custody-operations`. | Resolves to a loaded sub-domain manifest, which is where **entity types** and coverage-service URLs live (§3.5). This is how your agent inherits its domain's vocabulary without *containing* it. |
| `audience` | `segment` or `enterprise`. | `segment` = gated by business-segment membership + per-segment classification (the normal case for client data). `enterprise` = any authenticated user, segment gate skipped (e.g. an HR policy Q&A agent). This single word decides whether the structural gate (§7) applies. |

### How to reach it

| Field | Meaning | Why it exists |
|---|---|---|
| `protocol` | `http`, `mcp`, or `a2a`. | Selects which `ProtocolAdapter` invokes you. New protocols are added behind that interface, not by branching the request path. |
| `connection` | The *only* "how to call me" information. HTTP: `{ openapi_url, operation_id }`. MCP: `{ server_url, tool, transport?, protocol_version? }` — `transport` is `streamable` (default) \| `sse` (deprecated) \| `stdio` (§6). A2A: `{ agent_card_url }`. | Notice what is **absent**: your input and output data shapes. Those are *derived* by introspection (§6). The connection tells the gateway where to knock; the service tells it what it looks like. |
| `capabilities` | `{ streaming: bool, … }`. | Declares transport-level features. Additive; unknown keys allowed. |

### Scope of authority and limits

| Field | Meaning | Why it exists |
|---|---|---|
| `constraints.access_mode` | `read` or `write`. Phase-1 agents are `read`. | The read/write seam is where the future write-path (durable, Temporal-style) will attach. Declaring it now means the resolver can enforce read-only today and light up writes later without a schema change. |
| `constraints.data_classification` | `public` \| `internal` \| `confidential` \| `confidential-pii`. | Drives the **classification gate** (§7). A `confidential-pii` agent is only reachable by a principal whose per-segment clearance covers PII. This is a data field precisely so that raising an agent's sensitivity is a manifest edit, never a code change. |
| `constraints.sla_timeout_ms` | 100–60000. | The executor joins to a deadline and harvests survivors (partial-result tolerance). Your timeout is your promise; a slow agent degrades gracefully instead of hanging the plan. |
| `constraints.rate_limit` | `{ requests, per_seconds }` (optional). | Back-pressure, declared. |
| `max_response_tokens` | Optional cap. | The gateway truncates your response to this many tokens before synthesis. Keeps a chatty agent from blowing the synthesis context. |

### The two fields that carry the intelligence

| Field | Meaning | Covered in |
|---|---|---|
| `skills[]` | Each `{ id, name, description, tags[], examples[≥3] }`. **The `examples` are the routing fuel** — plain-English questions, embedded so the router matches user questions to your agent by *meaning*. | §4 — read it in full. |
| `io` (optional) | The dataflow contract that makes your agent composable: `produces`, `consumes`, `condition`, `map`. Absent `io` = a flat, single-node agent. | §5 — read it in full. |
| `output_schema` (optional) | A JSON-Schema **fallback** for your output, used *only* if the protocol cannot be introspected. Prefer real introspection. | §6. |

> **The one thing you must not do here:** do **not** put input/output wire schemas in the manifest.
> The gateway derives those by introspection. The `output_schema` field is a narrow escape hatch for
> when introspection genuinely cannot see your output (§6), not a place to hand-maintain a schema in
> parallel with your service.

### 3.5 The domain & sub-domain manifests — where a new domain's vocabulary lives

If you are adding an agent to an **existing** domain, you can skip this section — your agent's
`domain`/`sub_domain` already resolve, and you inherit the vocabulary. If you are onboarding a
**new domain**, you write two more files first, and this is the single biggest conceptual step. All
three levels are schema-validated at ingestion and a malformed one **fails startup** (§8), so get
them right here.

The three files and their schemas:

| Level | File | Schema (classpath copy loaded at runtime) |
|---|---|---|
| Domain | `registry/domains/<domain>.json` | `registry/domain-manifest.schema.json` |
| Sub-domain | `registry/domains/<domain>/<sub-domain>.json` | `registry/sub-domain-manifest.schema.json` |
| Agent | `registry/manifests/<domain>/<agent_id>.json` | `agent-manifest.schema.json` |

> Note the folder overlap: a domain file is `registry/domains/foo.json`; its sub-domain files are
> one level **down**, in `registry/domains/foo/`. The full per-field tables for all three are in
> §14; this section is the narrative + the one thing documented nowhere else — the `entity_types`
> anatomy.

#### The domain manifest — coverage, framing, governance

Required: `domain_id`, `display_name`, `memory_compaction`. The rest is optional but load-bearing.

- **`domain_context`** *(optional string, NEW)* — a short, neutral phrase describing this domain's
  data coverage (e.g. `"internal HR policies"`, `"client financial data"`). The gateway composes
  the phrases from **all** loaded domains, ordered by `domain_id`, into the framing string the
  intent classifier and answer synthesizer put in their prompts. This is what replaced the old
  hand-edited `CONDUIT_ASSISTANT_DOMAIN_CONTEXT` environment variable — declaring it here is what
  makes onboarding a new domain require **zero gateway config** (§1). Omit it and the gateway falls
  back to a domain-*neutral* default; it never invents a domain-flavored string.
- **`clarify_style`** *(optional, `template` | `composed`, default `template`)* and **`clarify_tone`**
  *(optional string)* — the *wording* policy for clarifying questions. `template` serves the
  deterministic question byte-for-byte; `composed` lets the gateway phrase a natural question over
  the grounded candidate set, using `clarify_tone` as a hint. **The clarify *decision* stays
  deterministic either way** (§4.8, and the CLARIFY rule) — this only affects phrasing, and the
  template is always the validated fallback. (`wealth-management.json` uses `composed`.)
- **`coverage`** *(optional object)* — `{ discover_url, check_url, resolve_url, cache_ttl_seconds? }`.
  Present only for domains whose data is client-scoped. **When the object is present the schema
  requires all three URLs.** The *runtime* rule is narrower: a `resource_scoped` sub-domain requires
  its parent domain to carry `coverage.discover_url` (enforced at load — §8). This is the coverage
  service you must build; its full contract is §7.5.
- **`memory_compaction`** *(required object)* — `envelope_version`, `must_preserve`,
  `summary_policy` (with `owner` fixed to `"memory-service"`). **Today this is schema-required
  boilerplate**: the gateway parses only `must_preserve`/`can_drop` and does *not* act on the
  `summary_policy`. It is declared now and executed later by a future external memory service (there
  is no memory service in the stack today). **Copy the block verbatim from an existing domain** and
  move on — even the role-less `hr.json` carries one.

#### The sub-domain manifest — entity types and user-facing copy

Required: `sub_domain_id`, `display_name`, `parent_domain`, `resource_scoped`, `entity_types`,
`required_context`, `agents`.

- **`resource_scoped`** *(boolean)* — `true` = DISCOVER + CHECK against the coverage service before
  fan-out (client-scoped data); `false` = the structural/role gate is sufficient, **no coverage
  service** (e.g. `hr-knowledge`, `custody-operations`).
- **`required_context`** *(array)* — entity keys that must be present before the gateway fans out.
  Missing → a deterministic CLARIFY (`extracted ∩ required_context = ∅`, decided in gateway code,
  never by an LLM).
- **`clarification_schema`** *(object, per entity key)* — `{ question, options_source, priority, default? }`.
  `options_source` is `discover` (call `coverage.discover_url`) | `agent_derived` | `none` (open
  text) | **`principal_book`** (offer the principal's own book of business). The `question` string
  is the exact copy the gateway posts — **this is where "which client?" lives, never in Java.**
- **`messages`** *(object)* — a key→copy map of user-facing strings the gateway reads by key. One is
  load-bearing: **`capability_unavailable`** (the copy shown when no reachable, authorized service
  can answer part of a request). Others in live use: `no_coverage`, `reference_not_found`,
  `missing_entity_question`, `followup_clarification`, `specify_entity`, `needs_more_detail`.
- **`denial_messages`** *(object)* — a coverage reason-code → copy map (`not-covered`,
  `coverage-transferred`, `relationship-closed`, `default`, …). §7.5 lists the reason codes.
- **`agents`** *(array, ≥1)* — the agent IDs belonging to this sub-domain.

##### `entity_types` — the anatomy (schema-required, documented nowhere else until now)

Each element of `entity_types` declares one kind of business object your agents consume. This is the
map-based entity model (adding an entity type is a manifest edit, not a new Java field). Required
per element: `key`, `extract_as`, `kind`, `display`, `required`.

| Field | Required | Type | What it does |
|---|---|---|---|
| `key` | yes | string | The canonical entity key an agent's `io.consumes[].entity` references (e.g. `relationship_id`, `policy_topic`). |
| `extract_as` | yes | string | The variable name the entity extractor binds the human reference to before resolution (e.g. `relationship_reference`). |
| `kind` | yes | `resolvable` \| `literal` \| `list` | `resolvable` = a human reference resolved to a real ID by the coverage service (RESOLVE, §7.5); `literal` = an in-line value matched by `id_pattern` (e.g. a period, a policy topic); `list` = a collection of literals (e.g. tickers). |
| `display` | yes | string | The human phrase used when the gateway compiles prompts and clarifications about this entity (e.g. "client relationship"). Domain copy lives here, not in code. |
| `id_pattern` | no | string (regex) | If a raw reference already matches this pattern, resolution short-circuits (e.g. `REL-\\d+` is already an ID; a `literal`'s pattern is how it is recognized in the text). |
| `resolve_type` | no | string | The `type` passed to the coverage RESOLVE call for a `resolvable` entity (e.g. `relationship`, `fund`). |
| `required` | yes | boolean | Whether this entity must be present for the sub-domain to fan out (drives CLARIFY together with `required_context`). |
| `default` | no | string/number/bool | A fallback value for an optional `literal` (e.g. a `period` defaulting to `"QTD"`). |

Live example — `registry/domains/wealth-management/private-banking.json` declares `relationship_id`
(`resolvable`, `id_pattern: "REL-\\d+"`, `resolve_type: "relationship"`, required), `fund_id`
(`resolvable`, `FND-\\w+`, optional), `period` (`literal`, default `"QTD"`), and `ticker_references`
(`list`). Read that file alongside this table; it is the canonical model for a new sub-domain.

---

## 4. The art of skill examples

This is the chapter people underinvest in and then wonder why the router "can't find" their agent.
Your skill `examples` are not documentation. **They are the routing algorithm's training data, and
they are the entire reason the right question reaches your agent instead of your neighbor's.** Treat
them with the seriousness of production code, because that is what they are.

### 4.1 How examples literally become the routing

At **ingestion** (in the registry-service, not the gateway — §8), every string in every
`skills[].examples` array is embedded with a MiniLM model (`all-MiniLM-L6-v2`, 384-dimensional) into
a vector index (Redis, HNSW). Embedding goes through the `TextEmbedder` port — split into
`ManifestEmbedder` (the corpus, ingestion-only) and `QueryEmbedder` (the request path) — implemented
by `RemoteEmbedder`, which calls a **Python sentence-transformers sidecar** over HTTP. (There is no
in-JVM embedding model and no class called `EmbeddingClient`.) At request time the gateway embeds
the user's question through the same `QueryEmbedder` and finds the nearest example vectors by cosine
similarity. The agent whose examples sit *closest in meaning* to the question wins the route. The
index is stamped with the embedding model id; if the gateway would query it with a different model,
it refuses to start (§8, §13) — the corpus and the query must be the same geometry.

Two consequences follow immediately, and they govern everything else in this section:

1. **The router matches by meaning, not keywords.** "Is this account invested too heavily in one
   name?" can route to a concentration agent whose examples never used the word "invested." That is
   the power of embeddings — and also the danger: meanings you did not intend can be *close* to
   yours.
2. **Your examples define a region of meaning-space that you are claiming.** Two agents whose
   examples overlap in that space will fight over the same questions. Which brings us to the central
   craft.

### 4.1a Capability-first: name the capability, never the client

This is the single most important correctness property of the whole routing layer, and it changes
how you write every example. **Route on the capability asked, never on the entity named.** Concretely:

**The corpus is de-entitied.** Your examples must describe *what you do*, using a neutral deictic
("this relationship", "this client", "the account") where a specific name might otherwise go. They
must **never** contain a real client, entity, or ID name. The live manifests were deliberately
scrubbed to this rule: `meridian.wealth.holdings` says `"current holdings for this relationship"`
(not "…for the Whitman relationship"); `meridian.wealth.concentration` says
`"how concentrated is this relationship"` (not "…the Whitman relationship"). An example carrying a
client name is now an **anti-pattern** (§12).

**Why it is non-negotiable: the query is masked before the router ever sees it.** On the request
path, resolved entity spans are blanked out of the routing text and replaced with a neutral mask
token (`conduit.routing.entity-mask-token`, default `"the subject"`) before the semantic router
runs — this is the `RoutePreparationPolicy` masking stage (stamped `route-prep-v2`). So the router
never routes on the words "Whitman" or "REL-00042"; it routes on the residual *capability* phrasing
("how concentrated is **the subject**"). The consequence for you is mechanical and absolute:

> **An example keyed to an entity name can never match a masked query.** The name is gone by the
> time routing happens. A client-name example is dead weight at best; at worst it blurs your claimed
> region with a token no real query will carry. Write the capability; the platform handles the
> entity.

This is *why* the collision story in §4.4 fixed a poaching agent by narrowing it to its capability
("one row per failed trade") rather than by adding entity- or name-specific examples. Capability-first
is the rule; §4.2–4.5 are how you execute it well.

### 4.2 Quality over quantity — the single most important rule

**A few sharp, intent-specific examples beat a pile of generic ones.** This is not a stylistic
preference; it is a direct consequence of how nearest-neighbor routing works. Every example you add
is a claim on a region of meaning-space. A generic example ("show me the data", "what's the status")
plants a flag in a crowded, contested region and pulls your agent toward questions that are not
really yours. A specific example ("compute the HHI for this client's holdings") plants a flag exactly
where your unique intent lives, and *nowhere else*.

The schema requires a minimum of **3** examples. That minimum is a floor, not a target. The right
number is "enough distinct, realistic phrasings to cover the ways a real user asks for *your*
intent" — often somewhere between 5 and 12 — and **not one more**. Padding the list with near-duplicate
or generic phrasings does not make routing better; it makes your claimed region blurrier and more
likely to collide.

### 4.3 What makes a *good* example

A good example is:

- **Specific to your distinct intent.** It describes the thing only *you* do. Compare the real
  concentration agent's `"which holdings are driving issuer concentration in this book"` (unmistakably
  concentration analysis) against a hypothetical `"tell me about this portfolio"` (could be holdings,
  performance, risk, concentration — anyone's question).
- **A realistic, varied user phrasing.** Real users ask the same thing many ways: "is this portfolio
  too concentrated", "does this account breach our concentration limits", "are any single names making
  up too much of the portfolio". Varied phrasings of *one* intent generalize; near-duplicates do not.
- **Not keyword-stuffing.** `"concentration risk HHI single-name diversification breach limit exposure"`
  is not a question and does not embed like one. Users speak in sentences; embed sentences.
- **Not the literal eval or test string.** This one is subtle and important enough to have its own
  subsection (§4.7). If you paste the exact strings your test harness checks into your examples, you
  will inflate your measured accuracy while teaching the router *nothing that generalizes*. That is
  cheating the measurement, and it will fail in production on the first paraphrase.

### 4.4 The collision problem — a true story from this platform

Here is the failure mode that will bite you, told through an incident that actually happened while
building Conduit's asset-servicing domain.

We had three servicing agents that all deal, in some sense, with settlements:

- **`settlement_status`** — a leaf data agent that returns pending and failed settlement records for
  a relationship. ("any pending settlements on this account", "failed trades for the relationship".)
- **`settlement_risk`** — a fan-in analytics agent that assesses settlement risk from status, custody,
  and cash context. ("what is the settlement risk on this servicing relationship".)
- **`trade_penalty`** — a per-item *map* agent that itemizes a CSDR cash-penalty row for *each*
  failed trade.

`trade_penalty` shipped with **over-broad examples**. Its list included generic settlement phrasings
— things like "settlement", "failed settlement", and even bare relationship names — reaching for any
question that mentioned settlements at all. The result was a textbook collision: `trade_penalty`
**poached its neighbor `settlement_risk`'s queries.** A user asking "assess settlement risk for this
fund" — squarely `settlement_risk`'s intent — was landing on the per-trade penalty mapper, because
`trade_penalty` had planted flags all over the shared "settlement" region of meaning-space.

The fix was **not** to add more examples or to touch the platform. It was to **narrow
`trade_penalty` to its true intent.** Its real, distinct job is *"itemize per-failed-trade rows"* —
one penalty calculation per failed trade — and nothing else. The examples were rewritten to describe
exactly that and only that:

```json
"examples": [
  "itemize failed trade rows one trade at a time",
  "list every failed trade as separate penalty calculation rows",
  "produce a per-trade penalty table for failed trade items",
  "map each failed trade item into one penalty row",
  "show skipped failed trade rows when the map cap is reached",
  "return trade id, security, age days, and penalty amount per row",
  "itemize per-failed-trade penalty details",
  "row-by-row failed trade penalty listing"
]
```

Notice what changed. Every example now screams *"one row per failed trade."* None of them says bare
"settlement risk" or a relationship name. `trade_penalty` gave up its claim on the contested
"settlement" region and staked out only the "per-item itemization" region that is genuinely its own.
After the change, it **verified 3/3** on its own intent queries — and, just as importantly, stopped
stealing `settlement_risk`'s. The neighbor got its questions back.

**The lesson.** When two of your agents (or yours and a neighbor's) compete for the same questions,
the answer is almost never "add examples." It is "narrow each agent to its *true, distinct* intent so
their regions of meaning-space stop overlapping." An example that could plausibly belong to a
neighboring agent is a liability, not an asset.

### 4.5 How to disambiguate from a neighboring agent

Concretely, when you suspect a collision:

1. **Name the neighbor and name the boundary.** Write one sentence for each agent: "I do X; the
   neighbor does Y." If you cannot write two clearly different sentences, the two agents should
   probably be *one* agent, or one is redundant.
2. **Audit every example against that boundary.** For each example ask: *could a reasonable person
   read this as the neighbor's job?* If yes, it is over-broad — cut it or sharpen it until it is
   unmistakably yours.
3. **Push the distinguishing words to the front and center.** `trade_penalty` leans on "itemize",
   "per-trade", "one row", "row-by-row". `settlement_risk` leans on "assess", "risk", "escalation",
   "using cash and custody context". The vocabulary that *separates* you should be the vocabulary
   your examples are built from.
4. **Do not fix a collision by inflating the winner.** Adding ten more examples to the agent that
   *should* win is curve-fitting; it may win this query and lose three others. Narrow the loser's
   over-broad claim instead.

### 4.6 The measurement discipline — you cannot tune what you cannot measure

Routing quality is not a matter of opinion, and it is not something you eyeball once and forget. This
platform measures it with a **goal-pick harness**: a labeled set of queries, each tagged with the
agent it *should* reach, run through the **real shipped MiniLM path** (not the hash/stub embeddings
the unit tests use), scoring three shapes of query on their own terms:

- **Flat queries** (should reach a single leaf agent) — correct iff the router's top-scored agent is
  the expected one.
- **Analytics queries** (should reach a fan-in goal) — correct iff the resolved DAG goal is the
  expected goal.
- **Out-of-scope queries** (should reach *no one*) — correct iff the router **abstains** (§4.8).

The harness is a real, runnable command:

```bash
bash scripts/routing-measurement-gate.sh
```

It runs `eval/goal-pick/measure_goal_pick.py` against the **live gateway's** `POST /debug/route`
using the labeled dataset `eval/goal-pick/labeled_queries.json`, and fails on low domain accuracy,
weak abstention, or canonical-intent poaching. **Prerequisites:** the stack up, users seeded
(`bash scripts/seed-users.sh`), and `/debug/route` enabled (config-gated off by default, on for the
demo — §9). The measured number lives in the checked-in baselines at `eval/goal-pick/baselines/`
(with `eval/goal-pick/REBASELINE.md` describing how to re-baseline); read the current baseline there
rather than trusting any number quoted in prose — the metric has moved with each hardening pass and a
hardcoded percentage in a doc will always rot.

The dataset deliberately includes **held-out paraphrases**: queries that share an intent with the
training examples but whose exact wording was *never* used as an example. Those held-out queries are
the honest proof that the router **generalized** to the meaning of the intent rather than
**memorized** the test strings. A score that only holds on strings you also planted as examples is
not a routing result; it is an echo.

A word on honesty, because this measurement has a history worth knowing. An early headline claimed
routing accuracy of ~46%. On inspection that number was a **miscalibrated metric** — the harness had
forced every query to resolve to one of three analytics goals, so a query whose correct answer was a
*leaf data agent* could never score right. Measured correctly, per query shape, true domain-level
routing was far higher, and subsequent hardening (an abstain floor, sharper examples, an LLM
re-ranker for close calls) pushed it into the ~90s. The point of telling you this: **measure the
right thing, and be suspicious of a number that flatters you.** A truthful 85% beats a gamed 95%,
because the gamed one falls apart the first time a real user phrases a question a way your test suite
did not.

### 4.7 Why "spot-on" routing is unattainable — and what the honest bar is

Embedding-based routing is **probabilistic**. It maps fuzzy human language into a geometry and picks
the nearest neighbor. There is no set of examples that makes it perfect, because natural language is
genuinely ambiguous — "how exposed is this client" could honestly mean concentration, or credit, or
market risk, and no manifest can resolve an ambiguity the *user* left in the sentence. Anyone who
promises "spot-on" intent capture is selling you something the technology cannot deliver.

So the honest engineering bar is **not** "perfect routing." It is three things working together:

1. **Good examples** — sharp, specific, varied, non-overlapping (§4.2–4.5).
2. **Continuous measurement** — the goal-pick harness, run as a gate (§4.6, §9).
3. **A guardrail that abstains** — the router declines rather than guesses when it is not sure (§4.8).

That combination is achievable, durable, and honest. "Spot-on" is not. Design for the first; do not
promise the second.

### 4.8 The abstain floor — declining is a feature

A router that always returns *something* is a router that confidently sends "what's the weather in
Tokyo" to your settlement agent. Conduit's router does not do that. It has an **abstain floor**,
configured (not hardcoded) via `conduit.routing.min-score` and `conduit.routing.min-margin`:

- If the top candidate's similarity **score** is below the floor, the router **abstains** — it
  declines or asks a clarifying question rather than forcing a route.
- If the **margin** between the top candidate and the runner-up is below the minimum — a near-tie,
  where two agents are almost equally plausible — it **abstains** rather than flip a coin.

For you, this has a direct implication: **you do not need to (and must not) write examples defensively
to "catch" questions that are not yours.** The floor is the safety net for out-of-scope and ambiguous
questions. Your job is to describe *your* intent well; the floor handles the rest. Broadening your
examples to grab borderline questions doesn't help the user — it just lowers the margin and makes the
router *more* likely to misroute a genuine neighbor's question to you.

### 4.9 The real-world safeguards around routing

Good examples are necessary but not sufficient. In production, three additional mechanisms keep
routing honest, and you should know they exist because they shape how your agent is judged:

- **An onboarding measurement gate.** Every new agent is run through the goal-pick harness *as part
  of onboarding.* If adding your agent **drops overall routing accuracy** or **poaches a neighbor's
  queries**, onboarding fails — the same way a failing test fails a build. Your examples are not
  "done" when they read nicely; they are done when the harness stays green with your agent in the set
  (§9, §10).
- **An LLM re-ranker for close cases.** When the top candidates are genuinely close — or the question
  involves negation ("show me the funds that are *not* in breach"), a known weakness of pure
  embedding similarity — a second, LLM-based pass re-ranks the finalists. This is the one place a
  model touches routing, and it touches only the hard, close calls the embedding step flagged.
- **A production feedback loop.** Real routing decisions, scores, and (where available) corrections
  flow back as signal, so the labeled set and the examples improve against reality rather than against
  our imagination of reality.

### 4.10 "Are my examples good?" — a checklist

Before you submit, run every example past this list:

- [ ] **Distinct intent:** every example describes the thing *only my agent* does. None could be read
      as a neighboring agent's job.
- [ ] **Real phrasings:** each is a sentence a real user might actually type, not a bag of keywords.
- [ ] **Varied, not duplicated:** the list covers several genuinely different ways to ask for the same
      intent — not one phrasing rewritten five times.
- [ ] **No test strings:** none of these is the literal query my eval/test harness checks. My held-out
      paraphrases still route correctly.
- [ ] **Right count:** enough to cover the intent's real phrasings; no padding. (Floor is 3; more is
      not automatically better.)
- [ ] **No neighbor poaching:** I named my nearest agent, wrote the one-sentence boundary between us,
      and confirmed none of my examples crosses it.
- [ ] **Not defensive:** I did not add broad examples to "catch" out-of-scope questions — the abstain
      floor does that.
- [ ] **Measured:** the goal-pick harness stays green (or improves) with my agent in the set, including
      on held-out paraphrases.

---

## 5. Making your agent composable — the workflow story

Section 4 got the right *question* to your agent. This section is about what happens when your agent
is one step in a *plan* — when answering the user requires your output plus someone else's, or
requires running your agent once per item, or only under a condition. This is where a pile of
independent agents becomes an orchestrated system, and — like routing — it is entirely declared in
your manifest and executed deterministically. No LLM improvises the plan. The `io` contract you write
*is* the plan.

If your agent has no `io` block, it is a flat, single-node agent: the router picks it, the executor
calls it, done. That is completely fine — many good agents are leaves. But if your agent's answer
depends on, or feeds, another agent, `io` is how you say so.

### 5.0 First, know your shape

Before writing `io`, decide which of four shapes your agent is. The rest of this section is organized
around them.

| Shape | You are… | `io` you'll write | Real example |
|---|---|---|---|
| **Leaf data agent** | fetching raw facts for one entity | `consumes: [{entity}]`, `produces: [{…}]` | `wealth.holdings` |
| **Analytics fan-in** | computing over other agents' outputs | `consumes: [{from, select}, …]`, `produces` | `wealth.concentration`, `servicing.settlement_risk` |
| **Conditional step** | running only when a predicate holds | as above, plus `condition` | `wealth.concentration_review` |
| **Per-item mapper** | running once per element of a collection | `consumes: [{from, select}]`, plus `map`, `produces` | `servicing.trade_penalty` |

### 5.1 The `io` contract — produces and consumes

Two primitives do most of the work.

**`produces: [{ name, type }]`** declares a symbolic output. `type` is a manifest-declared string
(namespace it: `wealth.holdings`, `servicing.settlement_status`). `name` is the key your result is
published under on the run's blackboard. `type` is the *socket*; other agents plug into it.

**`consumes: [ … ]`** declares your inputs. Each entry is *exactly one* of two things:

- **`{ entity: "<sub-domain entity key>" }`** — a **leaf input** satisfied by deterministic entity
  resolution. `wealth.holdings` consumes `{ entity: "relationship_id" }`: it needs a relationship,
  which the gateway resolves from the user's question (a human reference → a real ID) before calling
  you. No upstream agent is involved.
- **`{ from: "<a produced type>", select: "<CEL>" }`** — a **dependency** on another agent's
  output. This is the edge that builds the DAG. Your `from` names a `type` some other agent
  `produces`; matching them (by **string equality** — this is why namespacing matters) wires
  producer → consumer.

> **CEL dialect (the expression language).** Every `select`, `condition`, `map.over`, `map.item_select`,
> figure `path`, and produced-entity `select` is a [CEL](https://github.com/google/cel-spec) expression —
> **not** JMESPath (the gateway *refuses to start* on a legacy JMESPath string). Each site binds exactly
> one root variable and may reference no other: `input` (a `select`, `condition`, or `map.over`), `item`
> (a `map.item_select` or the per-item id inside a produced-entity `select`), or `output` (a figure
> `path` or produced-entity `select`). **Guard every optional field with `has(...)`** so an absent field
> yields `null` rather than an error — chain-guard dotted paths segment by segment. Examples:
> a projection `{'positions': has(input.positions) ? input.positions : null}`; a condition
> `input.breach_count > 0`; a figure path `has(output.aging) && has(output.aging.max) ? output.aging.max : null`;
> a produced-entity selector `output.items.map(x, x.id)`. The codemod
> `scripts/migrate-selects-to-cel.py` translates legacy JMESPath manifests to this dialect.

### 5.2 The DAG resolver — how multi-step plans build themselves at runtime

Here is the mechanism that makes the whole thing composable. At request time, the router picks a
*goal* capability. The DAG resolver then looks at the goal's `consumes`. For each `from`, it finds the
agent whose `produces.type` matches, and adds that producer to the plan. Then it looks at *that*
producer's `consumes`, and so on, until every dependency bottoms out in a leaf `entity` input the
gateway can resolve. **The multi-step plan is assembled by following `type` equality backward from the
goal — nobody wrote "call holdings, then concentration" anywhere.**

Concretely, the concentration vertical:

```
user: "is the Whitman portfolio over-concentrated?"
  router → goal = wealth.concentration
  concentration.consumes[0].from = "wealth.holdings"
      → who produces "wealth.holdings"?  →  meridian.wealth.holdings
      holdings.consumes[0].entity = "relationship_id"
          → resolve "Whitman" → REL-… (deterministic lookup)
  plan:  [holdings] ──produces wealth.holdings──► [concentration] ──► answer
```

The producer runs, its output lands on the blackboard under `name: "holdings"`, and the consumer's
`select` projects it into concentration's input. You declared two agents independently; the gateway
*discovered* they compose, because their `type`s line up.

### 5.3 The declared translator — `select`, and why a human writes it

When a producer's output shape does not exactly match a consumer's input shape, something has to
reshape it. In many "agentic" systems, an LLM improvises that glue at runtime — reads the producer's
JSON, guesses which fields the consumer wants, and hand-assembles the payload. **We deliberately do
not do that.**

Instead, the reshaping is a **declared translator**: `select`, a human-written **CEL**
projection on the `consumes` edge. Look at the real concentration edge:

```json
"consumes": [{
  "from": "wealth.holdings",
  "select": "{'positions': has(input.positions) ? input.positions : null, 'total_value': has(input.total_value) ? input.total_value : null, 'allocation_by_class': has(input.allocation_by_class) ? input.allocation_by_class : null, 'relationship_id': has(input.relationship_id) ? input.relationship_id : null, 'relationship_name': has(input.relationship_name) ? input.relationship_name : null, 'currency': has(input.currency) ? input.currency : null, 'as_of_date': has(input.as_of_date) ? input.as_of_date : null, 'risk_profile': has(input.risk_profile) ? input.risk_profile : null}"
}]
```

That expression says, explicitly and reviewably: *"take these named fields out of the holdings output
and hand them to me shaped like this."* It is a projection, not a blob pass-through. A reviewer can
read it and know exactly what crosses the boundary.

**Why declared-and-reviewed beats LLM-improvised, for a bank:**

- **It is diffable and auditable.** The mapping between two agents is a line of JSON in a pull
  request, not a decision a model made in production that you can only reconstruct from a trace.
- **It is deterministic.** The same producer output always projects the same way. No temperature, no
  drift, no "why did it map differently this time."
- **It is validated at boot, not discovered broken at runtime.** This is the "teeth." At startup the
  gateway derives the producer's *real* output schema (by introspection — §6), builds a synthetic
  sample from it, runs your `select` against that sample, merges the result exactly as the runtime
  blackboard will, and validates the merged object against your *introspected input schema*. **If your
  `select` references a field the producer does not emit, your agent fails to load — at boot, with a
  precise error naming the offending `from`, `select`, and field.** You find out at startup, not when
  a user's question silently produces a degraded answer.

There is a runtime backstop too (a `select` that somehow slips through is caught by `checkComposable`
before dispatch, and the node fails safe rather than sending garbage), but the boot-time gate is the
one that saves you: broken glue never reaches production.

> **Absent `select` = identity pass-through.** If the shapes already match, omit `select`; the whole
> producer output is passed through. `select` is how you project when they don't — which is most of
> the time in practice, because good producers emit rich outputs and good consumers take slices.

### 5.4 Conditionals — `io.condition`, "not applicable" is not "missing"

Some steps should only run under a condition. The concentration *review flag* should surface only when
there is actually a breach to review. You declare that with **`io.condition`** — a CEL boolean
over your node's merged, bound input:

```json
"io": {
  "consumes": [{
    "from": "wealth.concentration",
    "select": "{'relationship_id': has(input.relationship_id) ? input.relationship_id : null, 'relationship_name': has(input.relationship_name) ? input.relationship_name : null, 'breach_count': has(input.breach_count) ? input.breach_count : null, 'flags': has(input.flags) ? input.flags : null, 'policy': has(input.policy) ? input.policy : null}"
  }],
  "condition": "input.breach_count > 0",
  "produces": [{ "name": "concentration_review", "type": "wealth.concentration_review" }]
}
```

The resolver still *wires* this node structurally (by its `from` edge). The `condition` gates only
*execution*: after the node's inputs are bound, the gateway evaluates the predicate.

- **true** → the node runs normally.
- **false** → the node is **cleanly skipped**, with plan-graph status `skipped_condition_false` and a
  trace event carrying the expression and its verdict.

The crucial discipline — and the reason this is worth a whole subsection — is the difference between
**"not applicable"** and **"missing."** A condition-false skip is *not a failure*. It does **not** fire
the partial-degradation signal, it is **not** classified like a failed or blocked node, and the
synthesizer treats it as **not applicable**, never as "data was unavailable." When the Whitman
portfolio has six breaches, the review node fires and its flag appears in the answer. When a
well-diversified portfolio has zero breaches, the review node skips — and the answer simply does not
mention a review flag. It does **not** say "review data is missing," because nothing is missing;
review just does not apply. A feature is not proven until you have shown it *not* firing, honestly.

The predicate grammar is deliberately small and deterministic — comparisons and boolean combinators
over CEL-extracted values, no arbitrary code, no LLM. It is validated at boot the same way
`select` is: the expression must compile, must evaluate to a boolean on the synthetic sample, and must
reference only fields present in the merged-input schema. A malformed condition fails at load, not at
runtime. (A genuine *evaluation error* at runtime — which should be impossible after boot validation —
becomes a distinct, visible `condition_error` failure, never a silent skip. Errors are loud; skips are
honest.)

### 5.5 Iteration — `io.map`, bounded and deterministic

Sometimes the better answer is *itemized*: not "your total penalty exposure is $X" but "here is each
failed trade, its age, and its penalty." That is iteration, and you declare it with **`io.map`**. The
real `trade_penalty` agent:

```json
"io": {
  "consumes": [{ "from": "servicing.settlement_status", "select": "{'failed': has(input.failed) ? input.failed : null}" }],
  "map": {
    "over": "input.failed",
    "item_select": "{'trade_id': has(item.trade_id) ? item.trade_id : null, 'security': has(item.security) ? item.security : null, 'isin': has(item.isin) ? item.isin : null, 'settle_date': has(item.settle_date) ? item.settle_date : null, 'amount': has(item.amount) ? item.amount : null, 'side': has(item.side) ? item.side : null, 'as_of_date': has(item.as_of_date) ? item.as_of_date : null, 'reason': has(item.reason) ? item.reason : null, 'fail_item': has(item.fail_item) ? item.fail_item : null}",
    "max_items": 2,
    "max_concurrency": 2
  },
  "produces": [{ "name": "trade_penalties", "type": "servicing.trade_penalties" }]
}
```

Semantics, and the guarantees that make it safe to run on live financial data:

- **`over`** is a CEL expression that must resolve to an **array** in the bound input (here, the
  `failed` trades from settlement status). The gateway invokes your agent **once per element**.
- **`item_select`** projects each element into that invocation's input. (Same declared-translator
  discipline as `select`, applied per item.)
- **Bounded by construction.** `max_items` is a hard blast-radius cap; `max_concurrency` bounds
  parallelism. Both are clamped to global ceilings configured in `application.yml`
  (`conduit.orchestration.map.max-items`, `max-concurrency`) — a manifest may only ask for *less* than
  the global ceiling, never more. There are **no unbounded loops**; a map over a finite, capped
  collection terminates by construction. (This is the AWS Step Functions `Map` model, on purpose:
  Step Functions has no raw loops, and neither do we.)
- **Partial-tolerant.** A per-item failure is recorded and does *not* abort the map or the plan. The
  survivors aggregate.
- **Deterministic by index.** The aggregate is ordered by input index, not by completion order. Same
  collection + same data → same aggregate, every run, regardless of which item finished first. (This
  is the Temporal determinism discipline — it is also what makes future replay/audit honest.)
- **Honest about truncation and emptiness.** If the collection exceeds `max_items`, the map runs the
  first `max_items` and **reports the truncation** (a `_truncated` flag, counts, and a trace signal) —
  it never silently drops the tail. If the collection is empty, the map produces an **empty aggregate**
  — a valid "no failing trades" result, treated by synthesis as "none," **not** as "missing data."

Note `trade_penalty`'s `max_items: 2` — a deliberately low cap for the demo so the truncation path is
easy to exercise. In production you would set it to your real ceiling (under the global one).

### 5.5a Declaring load-bearing figures — grounded answer attribution

`produces` can carry more than a `name` and `type`. Each produced output may declare a
**`figures[]`** array — the numbers in your output that the answer is *required* to attribute
correctly. This is the machinery behind "grounded figures" (§1): the synthesizer summarizes agent
output as delimited data and never computes a number, and `figures[]` is how you tell the platform
*which* numbers are load-bearing and *how* they must be rendered, so a renderer and a validator can
enforce it deterministically instead of trusting the model.

Each figure is `{ label, path, format }`, all three **required**:

- **`label`** — the human label the answer must use when it attributes this figure (e.g.
  `"Concentration breach count"`).
- **`path`** — a **CEL** into your producer's output that selects the value (e.g.
  `single_name.top.weight_pct`, `breach_count`). Boot-validated against your introspected output
  schema, exactly like a `select` (§5.3): a `path` that points at a field you don't emit fails
  ingestion.
- **`format`** — a formatter id from a **closed enum**. The allowed values are exactly:

  ```
  percent   percent1   percent2   currency_usd   count   date   plain
  ```

  These are the formats the figure renderer renders and the grounding validator gates on. **A value
  outside this set is rejected at ingestion** (schema `enum`) — it cannot silently fall through to
  `plain` and quietly weaken the grounding gate. (This enum lives in all three schema copies; §3.)

Real example — `meridian.servicing.settlement_risk` declares figures like
`{ "label": "CSDR penalty failed settlement amount", "path": "has(output.failed_amount_usd) ? output.failed_amount_usd : null", "format": "currency_usd" }`
and `{ "label": "Failed exposure to settled cash", "path": "has(output.cash_context) && has(output.cash_context.failed_exposure_to_settled_cash_pct) ? output.cash_context.failed_exposure_to_settled_cash_pct : null", "format": "percent1" }`.
`meridian.wealth.concentration` declares a `percent1` top-single-name weight, a `count` breach
count, and a `plain` HHI. Declaring figures is optional, but if your answer quotes a number that
*matters*, declare it as a figure so its attribution is enforced rather than hoped for.

### 5.6 The determinism + fail-safe guarantee, stated plainly

Across §5.3–5.5, one principle repeats, and it is the spine of the whole orchestration layer:

> **The LLM lives only at the edges. The middle is deterministic, declared, and reviewable.**

- The *router* (probabilistic) picks the goal — one LLM-shaped step, guarded by the abstain floor.
- The *agents* (the edges) do the non-deterministic work — they fetch, they compute, they call
  models if they wish. Their outputs are the only ground truth.
- Everything *between* — dependency wiring, `select` translation, `condition` branching, `map`
  iteration — is deterministic code over already-fetched data. No model improvises control flow. No
  plan is invented at runtime that you did not declare.

That separation is what lets a bank run this on live financial data: the parts that must be auditable,
diffable, and replayable *are*, and the parts that are inherently fuzzy are quarantined to the edges
and treated as untrusted data.

---

## 6. Reaching your service — HTTP and MCP

The gateway derives your input **and** output wire schemas by introspecting your live service. This
is not a nicety — it is the machinery that lets §5's `select`/`condition`/`map` be *validated at
boot*. If the gateway cannot see your output schema, it cannot prove your consumers' `select`s are
correct, and it will tell you so, loudly.

### HTTP (OpenAPI)

- Expose an OpenAPI document at `connection.openapi_url`.
- The `connection.operation_id` must exist in it.
- The operation must declare a **request body schema** (→ your input shape) and a **2xx
  `application/json` response schema** (→ your output shape).
- **`$ref`s are resolved** against `components/schemas`, recursively. You may (and should) factor your
  schemas with `$ref`; the gateway follows them.

The gateway reads the response schema to build a *real* output schema — properties, required fields,
nested objects and arrays — for your producer. That real schema is what a downstream `select` is
validated against.

### MCP (FastMCP)

- Your tool must appear in `tools/list` with an **input schema**.
- Ideally it also declares an **`outputSchema`** (structured output). FastMCP supports this; use it.
  The gateway reads it as your output shape.
- **If your tool genuinely cannot declare an output schema,** provide the manifest `output_schema`
  field as a fallback. This is the *only* sanctioned use of that field.

**Transport — Streamable HTTP is the default.** MCP here is **Streamable HTTP at spec version
`2025-11-25`**: a single `/mcp` endpoint (`connection.server_url: "http://…:8082/mcp"`). Declare it
with `connection.transport: "streamable"` — or omit `transport` entirely, since `streamable` is the
schema default. The `sse` value exists **only** as a deprecated legacy escape hatch (the old
two-channel HTTP+SSE handshake); do not reach for it in a new agent. An optional
`connection.protocol_version` overrides the negotiated spec version per-agent; absent it, the gateway
uses `conduit.mcp.protocol-version` (`2025-11-25` for streamable) or `conduit.mcp.legacy-protocol-version`
(`2024-11-05` for `sse`). The live MCP manifests in this repo (e.g.
`meridian.servicing.settlement_status`) use `"transport": "streamable"` against a `/mcp` endpoint.

### Why introspection matters (and the honest-degradation rule)

If a producer's output schema is unavailable — no introspection *and* no manifest `output_schema` —
the gateway does **not** silently pass your `select` as "probably fine," and it does **not** crash. It
logs a clear warning naming the unvalidated edge and emits a boot summary line:

```
select validation: X validated, Y UNVALIDATED (no output schema)
```

**Your onboarding target is `Y = 0`.** Any unvalidated edge is a hole in your safety net — a `select`
that *could* reference a non-existent field and you would only find out at request time. Give every
producer an introspectable (or declared) output schema so every edge is validated at boot. That is why
even the leaf agents in this repo (`holdings`, `settlement_status`) carry an `output_schema`: it
guarantees their consumers can be validated.

---

## 7. Authorization & entitlement

Every request passes **three independent gates.** They are independent on purpose — defense in depth,
where each gate answers a different question and no single one is load-bearing alone. You configure all
three as *data*; none of them lives in the platform.

### Gate 1 — Structural (Cerbos): *can this segment reach this domain at all?*

A Cerbos PDP answers the coarse question: does this principal's business segment have any business
touching this domain? The mapping from segment → domain lives in the Cerbos policy
(`infra/cerbos/policies/…`) — config, not a platform change. If your new domain needs a new
segment→domain mapping, you add it to the policy. **Why a separate structural gate:** it is the
cheap, coarse "are you even in the right building" check, evaluated before any data is touched.

`audience: "enterprise"` agents skip this gate — they are open to any authenticated user (e.g. HR
policy Q&A). `audience: "segment"` agents are subject to it. That one manifest word decides it.

### Gate 2 — Classification: *is this data too sensitive for this principal?*

Your manifest's `constraints.data_classification` (`public` → `confidential-pii`) is checked against
the principal's per-segment clearance. A `confidential-pii` agent is only reachable by a principal
cleared for PII. **Why a separate classification gate:** sensitivity is a property of the *data*, and
raising it must be a manifest edit — never a code change, and never entangled with *who* is in *whose*
book.

### Gate 3 — Coverage: *is this specific client in this user's book of business?*

This is the fine-grained, data-aware gate, and it is the one most easily gotten wrong. The **book of
business** — which clients a given banker actually covers — lives in a **coverage service**, and *only*
there. Never in the gateway. Never in your manifest. The coverage service answers two questions for a
principal: `discover` ("which entities may they access?") and `check` ("is entity X in their book?").

Two rules govern it, and they are non-negotiable:

- **The coverage service is the single source of truth for the book.** If the book appears anywhere
  else — a list in the gateway, a field in a manifest — that is a World-B violation and a security
  bug waiting to happen (two sources of truth that will drift).
- **RESOLVE is principal-agnostic; the coverage CHECK is the only gate.** When the gateway resolves a
  human reference ("Whitman") to an entity ID, it searches *all* entities — it does **not** filter by
  the principal's book. Filtering resolution by the book would leak information (the *shape* of what
  you cannot see) and would conflate two concerns. Resolution finds the entity; the coverage **check**
  decides whether this principal may have it. Keep them separate.

### Per-hop identity: forward the JWT, verify it, fail closed

The gateway forwards the **end-user's JWT** to your service on every hop. Your service must:

- **Verify it** — correct JWKS, valid signature, issuer, audience, and expiry.
- **Fail closed** — no fail-open fallback, no "if verification errors, allow." A service that trusts
  the caller blindly, or that treats a verification failure as permission, **will not pass security
  review.**

**Why per-hop and not just at the gateway:** the gateway is not a trust boundary you can hide behind.
Each service independently confirms it is acting for the real end user. Defense in depth means the
compromise of one hop does not hand an attacker every downstream service.

### 7.5 The coverage service you must build

If your domain's data is client-scoped (`resource_scoped: true`), you must stand up a **coverage
service** and declare its three URLs on the domain manifest's `coverage` block (§3.5). The gateway
calls it **generically** — it does not know what the responses *mean*; it only knows the contract.
Implement it exactly.

Two rules frame everything below: **the coverage service is the single source of truth for the book
of business** (it appears nowhere else — not the gateway, not a manifest), and **RESOLVE is
principal-agnostic while CHECK is the only gate** (resolution finds an entity across *all* entities;
the coverage check decides whether *this* principal may have it — §7 Gate 3).

**Every call carries:** `Authorization: Bearer <the forwarded end-user JWT>` — the *caller's* token,
not any "gateway service token"; the gateway forwards the exact bearer it was handed and **refuses
the coverage call if there is no caller identity.** Plus `X-Tenant-Id: <tenant_id from the validated
JWT>` (the IAM service does enrich tokens with `tenant_id`, and the gateway sends it on every coverage
call).

#### DISCOVER — "which entities may this principal see?"

```
GET {discover_url}          e.g. GET /coverage/rm_jane
→ 200: [ { "id": "REL-00042", "label": "Whitman Family Office", "sub_domain": "private-banking" }, … ]
→ 200: []                    (principal has no resources)
→ 503 / timeout:             gateway FAILS CLOSED — tells the user it cannot proceed, no stale data
```

Return **only** resources this principal is authorized to see — the list *is* the filter. Tag each
with its `sub_domain` so the gateway knows the routing bucket. A DISCOVER list is for display and
option-population; **it is not authorization** — CHECK still runs.

#### CHECK — "is this specific entity in this principal's book?" (the gate)

```
GET {check_url}             e.g. GET /coverage/rm_jane/resources/REL-00042
→ 200: { "allowed": true }
→ 200: { "allowed": false, "reason": "not-covered" }
→ 503 / timeout:            gateway FAILS CLOSED
```

Always call CHECK, even for a resource that came back from DISCOVER. Nothing reaches an agent without
a passed CHECK; results are cached per principal+tenant+entity for `cache_ttl_seconds` (default 30),
then re-checked. **Reason codes** map to the sub-domain manifest's `denial_messages` copy (§3.5):
`not-covered`, `coverage-transferred`, `relationship-closed`, `service-error` (→ fail closed). Your
sub-domain declares the exact user-facing wording for each; the gateway just looks it up by code.

#### RESOLVE — "turn a human reference into a canonical ID" (principal-agnostic)

```
POST {resolve_url}          body: { "reference": "Whitman Family Office", "type": "relationship", "principal_id": "rm_jane" }
→ 200 resolved:   { "resolved": true,  "id": "REL-00042", "canonical_name": "Whitman Family Office", "candidates": [] }
→ 200 ambiguous:  { "resolved": false, "id": null, "candidates": [ {…}, {…} ] }
→ 200 not-found:  { "resolved": false, "id": null, "candidates": [] }
```

`principal_id` is passed **for audit only — the service must NOT filter by it.** Filtering resolution
by the principal's book would leak the *shape* of what they cannot see, and it conflates two concerns:
finding the entity (RESOLVE) versus deciding access (CHECK). Ambiguous → the gateway intersects the
candidates with a fresh DISCOVER, shows only the accessible ones, and posts a clarifying question.
Not found → the gateway posts a "couldn't find a match" message (from `messages.reference_not_found`).
**Zero fabricated IDs:** the LLM extracts the human reference; this deterministic lookup resolves it;
an unresolved reference triggers a clarification, never a guess.

---

## 8. What happens at ingestion (the registry-service)

**Ingestion is a separate service, not the gateway.** The pipeline below runs in the
**registry-service** — the same JVM image under the `registry` Spring profile
(`SPRING_PROFILES_ACTIVE: registry`), its own container, which mounts `./registry` read-only and
runs the ingest on startup. The `ManifestEmbedder`, `VectorIndexWriter`, and `AgentRegistrar` beans
are `@Profile("registry")`, so **the gateway cannot embed or mutate routing data** — by construction.
The registry-service's health goes green only *after* ingestion succeeds, and the gateway's
`depends_on` blocks on that. This is why you re-ingest by rebuilding the registry-service, not the
gateway (Quickstart Step 5), and why the gateway never needs a rebuild to pick up a manifest change —
it reads the finished index from Redis.

The pipeline, per manifest — knowing it tells you exactly where a mistake will surface, at ingestion,
not in front of a user:

0. **Validate the domain & sub-domain manifests** against `domain-manifest.schema.json` /
   `sub-domain-manifest.schema.json`. Every file under `registry/domains/` is schema-validated;
   a malformed or unschema'd one throws with the file named and **aborts startup** (it is no longer
   silently skipped — this is the fail-loud change). The error reads
   `Domain manifest 'foo.json' failed schema validation: <detail>` (or `Sub-domain manifest '…'`).
1. **Validate your agent manifest** against the agent schema (classpath copy). Malformed → rejected.
2. **Introspect** your service — fetch the OpenAPI doc or `tools/list` — and **derive** your input and
   output wire schemas. (Unreachable service or missing operation → flagged; the agents must be up,
   which is why the registry-service `depends_on` them.)
3. **Embed** your `skills[].examples` (via the sidecar) into the vector index. Your agent is now
   routable by meaning (§4).
4. **Wire** your `io` contract into the DAG resolver — match your `produces.type` to others'
   `consumes.from`, building the edges (§5).
5. **Boot-validate** every `select`, `condition`, `map`, and figure `path` against the introspected
   schemas — the "teeth" (§5.3, §5.5a). A `select` referencing a non-emitted field, a `condition`
   that isn't boolean, a `map` whose `over` isn't an array, a figure `path` that doesn't exist →
   **your manifest fails to load, with a precise error naming the `agentId`.**
6. **Register**, then **reconcile**: the manifest folder is the source of truth, so any agent still
   registered but no longer described by a manifest is **pruned** (§10).

The ingestion log ends with exactly these two lines:

```
Registry ingestion complete: N loaded, 0 rejected
select validation: X validated, 0 UNVALIDATED (no output schema)
```

A green ingest is `0 rejected` **and** `0 UNVALIDATED`.

**A rejected manifest fails the *whole* ingestion, not just your file.** With the default
`conduit.registry.ingest.fail-on-invalid=true`, any rejected manifest throws
`Rejected N manifest(s). A partially-loaded registry routes silently to whichever agents happened to
survive.` and the registry container **exits 1**, so its health never goes green and the gateway
never starts. This is deliberate — *"a registry that does not fully load is not a degraded registry,
it is an unknown one."* Your onboarding failure is loud and blocks the boot, by design, rather than
quietly dropping your agent and misrouting in production.

**The gateway side is verification only.** On startup the gateway runs a `RegistryReadinessVerifier`
that refuses to boot unless the index (a) exists, (b) has at least one agent registered, and (c) was
built by the *same* embedding model the gateway will query with. Its remedy text: *"The registry
service ingests the manifests and builds the index; wait for it to become healthy (docker compose up
-d registry-service) before starting the gateway."* If you see the gateway refusing to start with one
of those three messages (§13), start or finish the registry-service first.

> **Write control plane (alternative to folder-drop + re-ingest).** The registry-service also exposes
> `POST/PUT/DELETE /admin/agents` (also `@Profile("registry")`) for programmatic registration. The
> gateway keeps only `GET /admin/agents` (read). For onboarding, the folder-drop + re-ingest flow in
> the Quickstart is the reconciled source-of-truth path; the write API is there when you need it.

---

## 9. Verify & measure

Two layers of verification. The checklist proves your agent is *correct*; the measurement gate proves
it *stays* correct and doesn't harm the system.

### The onboarding checklist

- [ ] **Schema valid** — manifest passes the agent schema (and, for a new domain, the domain &
      sub-domain schemas — §8 step 0).
- [ ] **World-B clean** — `bash scripts/world-b-check.sh` reports **CRITICAL: 0**. (You added no
      domain knowledge to the gateway. If onboarding you required a change to the gateway itself,
      stop — it belongs in your manifest.)
- [ ] **Ingests green** — `Registry ingestion complete: N loaded, 0 rejected`, your `agent_id`
      present, and `select validation: X validated, 0 UNVALIDATED (no output schema)`
      (`docker compose -p orchestrator-demo logs registry-service`).
- [ ] **Routes correctly** — a question phrased like your intent reaches your agent; a neighbor's
      question does *not*. Smoke it with `bash scripts/smoke-route.sh` (asserts on `POST /debug/route`).
- [ ] **Entitlement denies out-of-book** — an out-of-book principal is denied; an in-book one is
      served. (If entitlement-gated.)
- [ ] **Composes** — if you declared `io`, the `plan_graph` shows your node wired to its
      producers/consumers, and (if conditional) both the fires and the honest-skip paths behave.

### The measurement gate — the durable safeguard

The checklist is a point-in-time pass. The **goal-pick measurement gate** (§4.6, §4.9) is what keeps
routing honest over time. Run it — `bash scripts/routing-measurement-gate.sh` (it drives the live
gateway's `POST /debug/route` over `eval/goal-pick/labeled_queries.json`; **prerequisites:** stack up,
`bash scripts/seed-users.sh` run, and `/debug/route` enabled — it is config-gated off by default and
on for the demo via `CONDUIT_DEBUG_ROUTE_DECISION_ENABLED`) — with your agent in the set and confirm:

- Overall domain-level routing accuracy **does not drop** because you were added.
- Your agent does **not poach** a neighbor's queries.
- Your own intent queries route to you — including **held-out paraphrases** you did *not* use as
  examples (proof of generalization, not memorization).

If adding your agent lowers the number or steals a neighbor's questions, onboarding is not done —
narrow your examples (§4.4–4.5) and re-measure. This gate is intended to run in CI on every new agent
(§10).

---

## 10. Governance & lifecycle

Onboarding is not a one-time event; a manifest is a living artifact with a lifecycle.

- **Example review.** Because `skills[].examples` *are* the routing behavior, they get reviewed like
  code. A reviewer checks them against the §4.10 checklist — specificity, no neighbor overlap, no test
  strings, no defensive breadth. "It reads nicely" is not the bar; "it routes correctly and doesn't
  collide" is.
- **Versioning.** Bump `version` (semver) on every meaningful manifest change. A change to `examples`
  or an `io` edge can shift routing or plan structure — version it so the change is trackable and
  diffable, and so a regression can be pinned to a specific version.
- **Measurement as CI.** The goal-pick gate (§9) runs on every new or changed agent. A manifest edit
  that drops routing accuracy or poaches a neighbor fails the pipeline, exactly like a failing unit
  test. This is the mechanism that stops the slow rot of a shared routing space as agents accumulate.
- **The feedback loop.** Production routing decisions and outcomes feed back into the labeled set and
  the examples, so both improve against real usage rather than against our guesses about usage. When
  the feedback loop surfaces a systematic miss, the fix is almost always a *manifest* fix — sharper
  examples, a better `select` — not a gateway change.
- **Deprecation / offboarding.** Retiring an agent is deleting its manifest and standing down its
  service, then re-ingesting. Removal is **enforced by orphan reconciliation**: the manifest folder
  is the source of truth, so on the next ingest any agent still registered but no longer described by
  a manifest is pruned (`conduit.registry.ingest.prune-orphans=true`, default). You'll see
  `Pruning N orphaned agent(s)` in the registry log (or `Registry reconciled — no orphaned agents`
  when there's nothing to prune). Because nothing in the gateway references the agent by name, there
  is no code to clean up — the same World-B property that made onboarding cheap makes offboarding
  cheap.

---

## 11. Worked end-to-end examples

Four complete, real manifests from this repo, one per shape. Copy and adapt.

### 11.1 A leaf data agent — `meridian.wealth.holdings`

Fetches raw facts for one entity. Consumes a resolved `relationship_id`; produces `wealth.holdings`
for others to build on. No dependencies, no condition, no map.

```json
{
  "agent_id": "meridian.wealth.holdings",
  "name": "Wealth Holdings",
  "description": "Returns the current portfolio positions and asset-class allocation for a given wealth management relationship. Accepts relationship_id as the primary key.",
  "version": "1.0.0",
  "provider": { "organization": "Meridian Demo Bank" },
  "domain": "wealth-management",
  "sub_domain": "private-banking",
  "audience": "segment",
  "protocol": "http",
  "connection": {
    "openapi_url": "http://wealth-http:8081/openapi.json",
    "operation_id": "get_holdings_holdings_get"
  },
  "capabilities": { "streaming": false },
  "constraints": { "access_mode": "read", "data_classification": "confidential-pii", "sla_timeout_ms": 30000 },
  "skills": [{
    "id": "get_holdings",
    "name": "get_holdings",
    "description": "Retrieve current holdings, positions, and asset allocation breakdown for a wealth relationship.",
    "tags": ["holdings", "portfolio", "positions", "allocation", "wealth-management"],
    "examples": [
      "current holdings for this relationship",
      "portfolio allocation for this client",
      "what is this account invested in",
      "position breakdown for the relationship",
      "portfolio summary for this client",
      "portfolio value and top positions",
      "show me the portfolio for this relationship",
      "total portfolio value",
      "what does the portfolio hold"
    ]
  }],
  "output_schema": {
    "type": "object",
    "properties": {
      "relationship_id": { "type": "string" }, "relationship_name": { "type": "string" },
      "positions": { "type": "array", "items": { "type": "object" } },
      "allocation_by_class": { "type": "array", "items": { "type": "object" } },
      "total_value": { "type": "number" }, "currency": { "type": "string" }, "as_of_date": { "type": "string" }
    },
    "required": ["relationship_id", "relationship_name", "positions", "allocation_by_class", "total_value", "currency", "as_of_date"]
  },
  "io": {
    "consumes": [{ "entity": "relationship_id", "required": true }],
    "produces": [{ "name": "holdings", "type": "wealth.holdings" }]
  }
}
```

Why it is shaped this way: its only input is a resolved entity (`relationship_id`), so it consumes an
`entity`, not a `from`. It publishes `wealth.holdings` — the socket the concentration agent plugs into.
Its examples are all unmistakably "what's in the portfolio," distinct from concentration's "is it too
concentrated." It carries an `output_schema` so its consumers' `select`s can be boot-validated.

### 11.2 A composable analytics fan-in — `meridian.wealth.concentration`

Computes over another agent's output. It does **not** fetch data and does **not** take a
`relationship_id` — it consumes the holdings payload via a declared `select` and produces a
concentration analysis. (Full manifest at `registry/manifests/wealth-management/meridian.wealth.concentration.json`.)
The `io` and the key examples:

```json
"skills": [{
  "id": "analyze_concentration",
  "examples": [
    "is this portfolio too concentrated",
    "how concentrated is this relationship",
    "does this account breach our concentration limits",
    "compute the HHI for this client's holdings",
    "which holdings are driving issuer concentration in this book",
    "are any single names making up too much of the portfolio",
    "identify the top issuer exposures pushing concentration higher"
  ],
  "tags": ["concentration", "risk", "diversification", "hhi", "single-name", "wealth-management"]
}],
"io": {
  "consumes": [{
    "from": "wealth.holdings",
    "required": true,
    "select": "{'positions': has(input.positions) ? input.positions : null, 'total_value': has(input.total_value) ? input.total_value : null, 'allocation_by_class': has(input.allocation_by_class) ? input.allocation_by_class : null, 'relationship_id': has(input.relationship_id) ? input.relationship_id : null, 'relationship_name': has(input.relationship_name) ? input.relationship_name : null, 'currency': has(input.currency) ? input.currency : null, 'as_of_date': has(input.as_of_date) ? input.as_of_date : null, 'risk_profile': has(input.risk_profile) ? input.risk_profile : null}"
  }],
  "produces": [{ "name": "concentration", "type": "wealth.concentration" }]
}
```

Why: `from: "wealth.holdings"` makes the resolver pull in the holdings agent automatically — you never
wrote "call holdings first." The `select` is the declared translator, projecting exactly the fields the
concentration compute needs, boot-validated against holdings' real output schema. The examples were
specifically tuned (the last three) to out-score a servicing confuser on the "which positions are
creating issuer concentration" query — a real routing-hardening fix, done in *manifest data* rather
than the platform.

### 11.3 A conditional node — `meridian.wealth.concentration_review`

Runs only when there is a breach to review. Consumes the concentration output, gates on
`breach_count > 0`, produces a review flag.

```json
"io": {
  "consumes": [{
    "from": "wealth.concentration",
    "required": true,
    "select": "{'relationship_id': has(input.relationship_id) ? input.relationship_id : null, 'relationship_name': has(input.relationship_name) ? input.relationship_name : null, 'breach_count': has(input.breach_count) ? input.breach_count : null, 'flags': has(input.flags) ? input.flags : null, 'policy': has(input.policy) ? input.policy : null}"
  }],
  "condition": "input.breach_count > 0",
  "produces": [{ "name": "concentration_review", "type": "wealth.concentration_review" }]
}
```

Why: the `condition` gates *execution*, not resolution — the node is still wired by its `from` edge,
but it only runs when the upstream analysis found a breach. On the over-concentrated Whitman portfolio
(six breaches) it fires and its flag appears; on a well-diversified portfolio (zero breaches) it is
`skipped_condition_false` and the answer simply omits it — **never** claiming review data is
"missing," because not-applicable is not missing. Note the description's honesty: it "reflects the
analysis; it does not provide investment advice." Any threshold is firm-configured, not a universal
cutoff invented by the agent.

### 11.4 A map node — `meridian.servicing.trade_penalty`

Runs once per failed trade. This is the agent from the collision story (§4.4) — note how narrow and
itemization-specific its examples are now. Consumes the `failed` array from settlement status, maps
over it, produces a per-trade penalty aggregate.

```json
"skills": [{
  "id": "calculate_trade_penalty",
  "examples": [
    "itemize failed trade rows one trade at a time",
    "list every failed trade as separate penalty calculation rows",
    "produce a per-trade penalty table for failed trade items",
    "map each failed trade item into one penalty row",
    "show skipped failed trade rows when the map cap is reached",
    "return trade id, security, age days, and penalty amount per row",
    "itemize per-failed-trade penalty details",
    "row-by-row failed trade penalty listing"
  ],
  "tags": ["settlements", "failed-trades", "csdr", "cash-penalty", "map-iteration", "asset-servicing"]
}],
"io": {
  "consumes": [{ "from": "servicing.settlement_status", "select": "{'failed': has(input.failed) ? input.failed : null}" }],
  "map": {
    "over": "input.failed",
    "item_select": "{'trade_id': has(item.trade_id) ? item.trade_id : null, 'security': has(item.security) ? item.security : null, 'isin': has(item.isin) ? item.isin : null, 'settle_date': has(item.settle_date) ? item.settle_date : null, 'amount': has(item.amount) ? item.amount : null, 'side': has(item.side) ? item.side : null, 'as_of_date': has(item.as_of_date) ? item.as_of_date : null, 'reason': has(item.reason) ? item.reason : null, 'fail_item': has(item.fail_item) ? item.fail_item : null}",
    "max_items": 2,
    "max_concurrency": 2
  },
  "produces": [{ "name": "trade_penalties", "type": "servicing.trade_penalties" }]
}
```

Why: the `select` narrows the settlement-status output to just the `failed` array; `map.over` iterates
it; `item_select` shapes each trade into one invocation. Bounded (`max_items`, `max_concurrency`,
clamped to global ceilings), partial-tolerant, deterministic by index, honest about truncation and
empty collections. And its examples describe *only* per-item itemization — the discipline that ended
its poaching of `settlement_risk`.

---

## 12. Anti-patterns

Each of these is a real way onboarding goes wrong. Learn them as a list of "not that."

- **Client / entity names in examples.** `"current holdings for the Whitman relationship"`, a bare
  relationship name, a real ID. This is now a hard anti-pattern: the request path **masks** resolved
  entity spans out of the routing text before the router runs (§4.1a), so a name-keyed example can
  *never* match a real query — it is dead weight that only blurs your claimed region. Fix: write the
  capability with a neutral deictic ("this relationship", "this client"); the platform handles the
  entity.
- **Over-broad or keyword-stuffed examples.** `"settlement"`, `"failed settlement"` — these claim a
  huge, contested region of meaning-space and poach neighbors (the `trade_penalty` incident, §4.4).
  Fix: narrow each example to your *distinct* intent. Sentences, not keyword bags.
- **Pasting test/eval strings as examples.** Inflates your measured accuracy while teaching the router
  nothing that generalizes; it collapses on the first paraphrase. Fix: write realistic, varied
  phrasings; keep the literal test strings *out* and prove generalization on held-out paraphrases
  (§4.7).
- **Defensive breadth to "catch" out-of-scope questions.** Broad examples added to grab borderline
  queries just lower the routing margin and cause *more* misroutes. Fix: describe your intent well and
  let the abstain floor decline the rest (§4.8).
- **Book/client data in the manifest or gateway.** The book of business belongs to the coverage
  service alone; domain vocabulary belongs to the domain manifest. A client list in your manifest is a
  World-B violation and a second source of truth that will drift. Fix: coverage service for the book,
  domain manifest for vocabulary (§7).
- **A `select` referencing a field the producer doesn't emit.** Boot rejects it with a precise error.
  Fix the **`select`**, not the validator — the validator caught a real bug (§5.3). Weakening the
  validator to make it "pass" reintroduces the exact silent-degrade hole it exists to close.
- **No introspectable output schema (especially MCP).** Your consumers' `select`s can't be validated;
  you show up as `UNVALIDATED` in the boot summary. Fix: declare an MCP `outputSchema`, or provide the
  manifest `output_schema` fallback (§6).
- **An agent that trusts the caller.** No JWT verification, or a fail-open fallback on verification
  error. Fails security review. Fix: verify the forwarded JWT — signature, issuer, audience, expiry —
  and **fail closed** (§7).
- **Reaching for a platform change to onboard.** If onboarding your agent seems to *need* a change to
  the gateway itself, that is a bug in the manifest model, not a task for you. Raise it; do not
  hardcode around it (§1).

---

## 13. Failure-mode catalog — symptom → cause → fix

When onboarding goes wrong it fails *loud*, at ingestion or gateway startup, with a specific string.
Grep the registry-service and gateway logs
(`docker compose -p orchestrator-demo logs registry-service` / `… logs gateway`) and match the
symptom here. Every string below is emitted by the code in this repo.

| Symptom (log string) | Cause | Fix |
|---|---|---|
| `Rejected N manifest(s). A partially-loaded registry routes silently to whichever agents happened to survive.` + registry container **exits 1** | An agent manifest failed agent-schema validation (`fail-on-invalid=true`). One bad manifest fails the *whole* ingest. | Read the `  rejected: …` line just above it — it names the file and reason. Fix the manifest, re-ingest (Quickstart Step 5). |
| `Domain manifest 'X.json' failed schema validation: …` / `Sub-domain manifest 'X.json' failed schema validation: …` (startup aborts) | A domain/sub-domain file violates `domain-manifest.schema.json` / `sub-domain-manifest.schema.json` (e.g. missing `entity_types`, `memory_compaction`; a bad `figures[].format`). | Fix the field named in the detail. Common: sub-domain missing required `entity_types`; a figure `format` outside the enum. |
| `No manifests found at <pattern>` | Wrong path or the `./registry` mount is missing/empty. | Confirm files are under `registry/manifests/<domain>/` and the registry-service mounts `./registry:/registry:ro`. |
| `select validation failed: agentId=<id> …` | A `consumes[].select` references a field the producer does not emit. | Fix the **`select`** (not the validator) — align it to the producer's real output schema (§5.3). |
| `condition validation failed: agentId=<id> …` / `map validation failed …` / `produced figure validation failed …` | A `condition` isn't boolean, a `map.over` isn't an array, or a figure `path` doesn't exist in the output. | Correct the CEL against the introspected schema (§5.4, §5.5, §5.5a). |
| `select validation: X validated, Y UNVALIDATED (no output schema)` with `Y > 0` (warning, not fatal) | A producer has no introspectable/declared output schema, so its consumers' `select`s can't be proven. | Give the producer an MCP `outputSchema` or a manifest `output_schema` fallback (§6). Target `Y = 0`. |
| `Embedding service never became ready after N attempts` | The embeddings sidecar is down/unreachable during ingestion. | Bring up `embeddings` (`docker compose -p orchestrator-demo up -d embeddings`), then re-ingest. |
| `Embedding service returned N-dim vectors but conduit.embedding.dimension is M` | Sidecar model / configured dimension mismatch. | Align the sidecar model and `CONDUIT_EMBEDDING_DIMENSION` (MiniLM = 384). |
| Gateway refuses to start: `The routing vector index does not exist…` / `…exists but no agents are registered…` / `…was built by embedding model '…'` | `RegistryReadinessVerifier`: no index, empty index, or model-id mismatch between the index and the gateway's query embedder. | Start/finish the **registry-service** first (`docker compose -p orchestrator-demo up -d registry-service`) so the index exists and matches; then start the gateway (§8). |
| `Sub-domain 'X' references unknown parent domain 'Y'` | A sub-domain's `parent_domain` doesn't resolve to a loaded domain manifest. | Fix `parent_domain`, or add the missing domain manifest. |
| `Sub-domain 'X' is resource_scoped=true but its parent domain 'Y' has no coverage.discover_url configured.` | A client-scoped sub-domain's parent domain lacks a `coverage` block. | Add the `coverage` block (with `discover_url`) to the domain manifest, or set `resource_scoped: false` (§3.5, §7.5). |
| `Pruning N orphaned agent(s) — registered but described by no manifest…` (warning) | You deleted a manifest; the agent is being deregistered on this ingest. | Expected during offboarding (§10). Nothing to fix. |

---

## 14. Appendix — full schema reference (agent / domain / sub-domain)

Three schema files, three synced copies of the agent schema (only the gateway classpath copy is
loaded at runtime; edit all three — §3). Fields marked `*` are required.

### 14.1 Agent manifest — `agent-manifest.schema.json`

**Required top-level:** `agent_id`, `name`, `description`, `version`, `provider`, `domain`,
`audience`, `protocol`, `connection`, `capabilities`, `skills`, `constraints`.

| Field | Type / values | Notes |
|---|---|---|
| `agent_id`* | string, `^[a-z0-9]+(\.[a-z0-9_]+)+$` | Stable, namespaced. |
| `name`*, `description`* | string (non-empty) | Describe what it does *and does not* do. |
| `version`* | string, `^\d+\.\d+\.\d+$` | Semver. |
| `provider`* | `{ organization*, contactEmail? }` | Owner. |
| `domain`* | string | Interpreted against loaded (schema-validated) domain manifests, not an enum. |
| `sub_domain` | string | Resolves to a loaded sub-domain manifest. |
| `audience`* | `segment` \| `enterprise` | `enterprise` skips the structural gate. |
| `protocol`* | `http` \| `mcp` \| `a2a` | Selects the adapter. |
| `connection`* | object | HTTP: `{ openapi_url*, operation_id* }`. MCP: `{ server_url*, tool*, transport?, protocol_version? }` — `transport` = `streamable` (default) \| `sse` (deprecated) \| `stdio`. A2A: `{ agent_card_url* }`. |
| `capabilities`* | `{ streaming*: bool, … }` | Transport-feature object; additive. **Not** a routing signal. |
| `securitySchemes` | object | Optional declared auth schemes for the agent's own endpoint. |
| `constraints.access_mode`* | `read` \| `write` | Phase-1 = `read`. |
| `constraints.data_classification`* | `public` \| `internal` \| `confidential` \| `confidential-pii` | Drives the classification gate (§7). |
| `constraints.sla_timeout_ms`* | int 100–60000 | Join deadline. |
| `constraints.rate_limit` | `{ requests*, per_seconds* }` | Optional back-pressure. |
| `max_response_tokens` | int 1–100000 | **Optional** truncation before synthesis. |
| `skills[]`* | `{ id*, name*, description*, tags[≥1]*, examples[≥3]*, inputModes?, outputModes? }` | `examples` are the routing fuel; capability-phrased, entity-free (§4). |
| `output_schema` | JSON Schema object | **Fallback only** when introspection can't derive output (§6). |
| `io.consumes[]` | each `{ entity, required? }` **or** `{ from, select?, required? }` | Exactly one of `entity`/`from` per item; `required` may sit on either variant. `select` = CEL projection (§5.3). |
| `io.produces[]` | `{ name*, type*, entities?, figures? }` | `type` matched by string equality — namespace it. `figures[]` = `{ label*, path*, format* }`; `format` ∈ `percent, percent1, percent2, currency_usd, count, date, plain` (closed enum — §5.5a). |
| `io.condition` | string (CEL boolean) | Node runs only if true; else `skipped_condition_false` (§5.4). |
| `io.map` | `{ over*, item_select?, max_items?, max_concurrency? }` | Bounded iteration; caps clamped to global ceilings (§5.5). |

### 14.2 Domain manifest — `domain-manifest.schema.json`

**Required:** `domain_id`, `display_name`, `memory_compaction`. (`additionalProperties: false` — no
stray keys.) See §3.5 for the narrative.

| Field | Req | Type | Notes |
|---|---|---|---|
| `domain_id`* | yes | string `^[a-z0-9]+(-[a-z0-9]+)*$` | Matches agent `domain`. |
| `display_name`* | yes | string | Human name shown in the glass-box. |
| `domain_context` | no | string | Neutral coverage phrase composed (across all domains) into the classifier/synthesizer framing. Replaces the old env var (§1, §3.5). |
| `clarify_style` | no | `template` \| `composed` (default `template`) | Clarification *wording* policy; decision stays deterministic. |
| `clarify_tone` | no | string | Tone hint used only when `clarify_style: composed`. |
| `coverage` | no | `{ discover_url*, check_url*, resolve_url*, cache_ttl_seconds? }` | Required as a set when present. Runtime requires `discover_url` for any resource-scoped child (§7.5). |
| `memory_compaction`* | yes | `{ envelope_version*, must_preserve*, can_drop?, summary_policy* }` | `summary_policy.owner` is fixed `"memory-service"`. Schema-required boilerplate today; the gateway acts only on `must_preserve`/`can_drop` (§3.5). |

### 14.3 Sub-domain manifest — `sub-domain-manifest.schema.json`

**Required:** `sub_domain_id`, `display_name`, `parent_domain`, `resource_scoped`, `entity_types`,
`required_context`, `agents`. (`additionalProperties: false`.)

| Field | Req | Type | Notes |
|---|---|---|---|
| `sub_domain_id`* | yes | string `^[a-z0-9]+(-[a-z0-9]+)*$` | Matches agent `sub_domain`. |
| `display_name`* | yes | string | Human name. |
| `parent_domain`* | yes | string | Must resolve to a loaded domain (fail-fast, §8). |
| `resource_scoped`* | yes | boolean | `true` = DISCOVER + CHECK before fan-out; `false` = role gate only. |
| `entity_types`* | yes | array of `{ key*, extract_as*, kind*, display*, id_pattern?, resolve_type?, required*, default? }` | The entity model — full anatomy in §3.5. `kind` ∈ `resolvable, literal, list`. |
| `required_context`* | yes | array of string | Entity keys that must be present before fan-out; missing → deterministic CLARIFY. |
| `clarification_schema` | no | object of `{ question*, options_source*, priority*, default? }` | `options_source` ∈ `discover, agent_derived, none, principal_book`. `question` is the exact posted copy. |
| `messages` | no | object (key → string) | User-facing copy read by key; load-bearing key: `capability_unavailable`. |
| `denial_messages` | no | object (reason-code → string) | Coverage denial copy (`not-covered`, `coverage-transferred`, …). |
| `agents`* | yes | array (≥1) of agent-id | Members of this sub-domain. |

---

*Extending Conduit is a description exercise, by design. You describe your agent; the platform finds
it, routes to it, composes it, and governs it. If you ever find yourself needing a change to the
gateway itself to onboard an agent, that is not your task — it is a bug in the manifest model. Raise
it.*
