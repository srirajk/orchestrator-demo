# The Conduit Agent Onboarding Handbook

> **Audience.** A domain team — wealth, servicing, insurance, HR, risk, anyone — that wants the
> Conduit gateway to *find* your agent, *route* the right questions to it, *compose* it into
> multi-step plans, and *govern* who may reach it. The point of the platform is this: **you
> describe your agent, and the platform finds it, routes to it, composes it into plans, and governs
> who may reach it — you don't build any of that plumbing yourself.** You supply a description of
> your agent and a running service; everything else is done for you. This handbook exists to make
> you fluent in that.
>
> **How to read it.** Sections 1–3 are the mental model and the manifest contract — read them
> once, top to bottom. Sections 4 and 5 are the two chapters that decide whether your agent
> actually *works* in production: **how it gets found** (routing) and **how it composes**
> (workflow). They are the heart of the book; do not skim them. Sections 6–10 are the operational
> reality — protocols, authorization, boot, verification, governance. Section 11 is four complete
> worked examples you can copy. Section 12 is the list of ways to get it wrong. Section 13 is a
> condensed schema reference to keep open while you write.

---

## Table of contents

1. [Philosophy — the empty engine](#1-philosophy--the-empty-engine)
2. [Mental model & prerequisites](#2-mental-model--prerequisites)
3. [Anatomy of an agent manifest](#3-anatomy-of-an-agent-manifest)
4. [The art of skill examples](#4-the-art-of-skill-examples)
5. [Making your agent composable — the workflow story](#5-making-your-agent-composable--the-workflow-story)
6. [Reaching your service — HTTP and MCP](#6-reaching-your-service--http-and-mcp)
7. [Authorization & entitlement](#7-authorization--entitlement)
8. [What the gateway does at boot](#8-what-the-gateway-does-at-boot)
9. [Verify & measure](#9-verify--measure)
10. [Governance & lifecycle](#10-governance--lifecycle)
11. [Worked end-to-end examples](#11-worked-end-to-end-examples)
12. [Anti-patterns](#12-anti-patterns)
13. [Appendix — condensed schema reference](#13-appendix--condensed-schema-reference)

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
work around it.** In the entire life of this platform so far, three live domains and sixteen agents
were onboarded with zero changes to the gateway. Yours should be no different.

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
`registry/manifests/<domain>/<agent_id>.json`, validated against
`registry/agent-manifest.schema.json`. Below is every field, what it means, and — the part that
matters — *why it exists*. Throughout, keep one distinction in mind:

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
| `domain` | The business domain, e.g. `wealth-management`. **Not** an enum in the schema. | World B: the gateway validates this against the *loaded domain manifests* at boot, not against a hardcoded list. A new domain is a new manifest, not a value hardcoded into the platform. |
| `sub_domain` | The sub-domain within it, e.g. `private-banking`, `custody-operations`. | Resolves to a loaded sub-domain manifest, which is where entity types and coverage-service URLs live. This is how your agent inherits its domain's vocabulary without *containing* it. |
| `audience` | `segment` or `enterprise`. | `segment` = gated by business-segment membership + per-segment classification (the normal case for client data). `enterprise` = any authenticated user, segment gate skipped (e.g. an HR policy Q&A agent). This single word decides whether the structural gate (§7) applies. |

### How to reach it

| Field | Meaning | Why it exists |
|---|---|---|
| `protocol` | `http`, `mcp`, or `a2a`. | Selects which `ProtocolAdapter` invokes you. New protocols are added behind that interface, not by branching the request path. |
| `connection` | The *only* "how to call me" information. HTTP: `{ openapi_url, operation_id }`. MCP: `{ server_url, tool, transport? }`. A2A: `{ agent_card_url }`. | Notice what is **absent**: your input and output data shapes. Those are *derived* by introspection (§6). The connection tells the gateway where to knock; the service tells it what it looks like. |
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

---

## 4. The art of skill examples

This is the chapter people underinvest in and then wonder why the router "can't find" their agent.
Your skill `examples` are not documentation. **They are the routing algorithm's training data, and
they are the entire reason the right question reaches your agent instead of your neighbor's.** Treat
them with the seriousness of production code, because that is what they are.

### 4.1 How examples literally become the routing

At boot, the gateway takes every string in every `skills[].examples` array and embeds it with a
MiniLM model (`all-MiniLM-L6-v2`, 384-dimensional, behind `EmbeddingClient`) into a vector index
(Redis, HNSW). At request time it embeds the user's question the same way and finds the
nearest example vectors by cosine similarity. The agent whose examples sit *closest in meaning* to
the question wins the route.

Two consequences follow immediately, and they govern everything else in this section:

1. **The router matches by meaning, not keywords.** "Is this account invested too heavily in one
   name?" can route to a concentration agent whose examples never used the word "invested." That is
   the power of embeddings — and also the danger: meanings you did not intend can be *close* to
   yours.
2. **Your examples define a region of meaning-space that you are claiming.** Two agents whose
   examples overlap in that space will fight over the same questions. Which brings us to the central
   craft.

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

The current harness measures domain-level routing in the **~93%** range on the labeled set — with
**held-out paraphrases**: queries that share an intent with the training examples but whose exact
wording was deliberately *never* used as an example. Those held-out queries are the honest proof that
the router **generalized** to the meaning of the intent rather than **memorized** the test strings. A
score that only holds on strings you also planted as examples is not a routing result; it is an echo.

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
- **`{ from: "<a produced type>", select: "<JMESPath>" }`** — a **dependency** on another agent's
  output. This is the edge that builds the DAG. Your `from` names a `type` some other agent
  `produces`; matching them (by **string equality** — this is why namespacing matters) wires
  producer → consumer.

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

Instead, the reshaping is a **declared translator**: `select`, a human-written **JMESPath**
projection on the `consumes` edge. Look at the real concentration edge:

```json
"consumes": [{
  "from": "wealth.holdings",
  "select": "{positions: positions, total_value: total_value, allocation_by_class: allocation_by_class, relationship_id: relationship_id, relationship_name: relationship_name, currency: currency, as_of_date: as_of_date, risk_profile: risk_profile}"
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
there is actually a breach to review. You declare that with **`io.condition`** — a JMESPath boolean
over your node's merged, bound input:

```json
"io": {
  "consumes": [{
    "from": "wealth.concentration",
    "select": "{relationship_id: relationship_id, relationship_name: relationship_name, breach_count: breach_count, flags: flags, policy: policy}"
  }],
  "condition": "breach_count > `0`",
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
over JMESPath-extracted values, no arbitrary code, no LLM. It is validated at boot the same way
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
  "consumes": [{ "from": "servicing.settlement_status", "select": "{failed: failed}" }],
  "map": {
    "over": "failed",
    "item_select": "{trade_id: trade_id, security: security, isin: isin, settle_date: settle_date, amount: amount, side: side, as_of_date: as_of_date, reason: reason, fail_item: fail_item}",
    "max_items": 2,
    "max_concurrency": 2
  },
  "produces": [{ "name": "trade_penalties", "type": "servicing.trade_penalties" }]
}
```

Semantics, and the guarantees that make it safe to run on live financial data:

- **`over`** is a JMESPath expression that must resolve to an **array** in the bound input (here, the
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

---

## 8. What the gateway does at boot

When the gateway starts, for each manifest it runs a fixed pipeline. Knowing it tells you exactly
where a mistake will surface — and it will surface *here*, at boot, not in front of a user.

1. **Validate** your manifest against `agent-manifest.schema.json`. (Malformed manifest → rejected.)
2. **Introspect** your service — fetch the OpenAPI doc or `tools/list` — and **derive** your input and
   output wire schemas. (Unreachable service or missing operation → flagged.)
3. **Embed** your `skills[].examples` with MiniLM into the vector index. Your agent is now routable by
   meaning (§4).
4. **Wire** your `io` contract into the DAG resolver — match your `produces.type` to others'
   `consumes.from`, building the edges (§5).
5. **Boot-validate** every `select`, `condition`, and `map` against the introspected schemas — the
   "teeth" (§5.3). A `select` referencing a non-emitted field, a `condition` that isn't boolean, a
   `map` whose `over` isn't an array → **your manifest fails to load, with a precise error.**
6. **Register.** The boot log reports `Registry bootstrap complete: N loaded, 0 failed` and
   `select validation: … 0 UNVALIDATED`.

A green boot is `N loaded, 0 failed` **and** `0 UNVALIDATED`. Anything else is your onboarding telling
you precisely what to fix, before anyone downstream is affected.

---

## 9. Verify & measure

Two layers of verification. The checklist proves your agent is *correct*; the measurement gate proves
it *stays* correct and doesn't harm the system.

### The onboarding checklist

- [ ] **Schema valid** — manifest passes `agent-manifest.schema.json`.
- [ ] **World-B clean** — `scripts/world-b-check.sh` reports **CRITICAL 0**. (You added no domain
      knowledge to the gateway. If onboarding you required a change to the gateway itself, stop — it
      belongs in your manifest.)
- [ ] **Boots green** — `N loaded, 0 failed`, your `agent_id` present, and `select validation … 0
      UNVALIDATED`.
- [ ] **Routes correctly** — a question phrased like your intent reaches your agent; a neighbor's
      question does *not*.
- [ ] **Entitlement denies out-of-book** — an out-of-book principal is denied; an in-book one is
      served. (If entitlement-gated.)
- [ ] **Composes** — if you declared `io`, the `plan_graph` shows your node wired to its
      producers/consumers, and (if conditional) both the fires and the honest-skip paths behave.

### The measurement gate — the durable safeguard

The checklist is a point-in-time pass. The **goal-pick measurement gate** (§4.6, §4.9) is what keeps
routing honest over time. Run the labeled goal-pick harness with your agent in the set and confirm:

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
- **Deprecation.** Retiring an agent is removing its manifest and standing down its service. Because
  nothing in the gateway references it by name, there is no code to clean up — the same World-B
  property that made onboarding cheap makes offboarding cheap.

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
      "current holdings for the Whitman relationship",
      "portfolio allocation for this client",
      "what is this account invested in",
      "position breakdown for the relationship",
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
    "how concentrated is the Whitman relationship",
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
    "select": "{positions: positions, total_value: total_value, allocation_by_class: allocation_by_class, relationship_id: relationship_id, relationship_name: relationship_name, currency: currency, as_of_date: as_of_date, risk_profile: risk_profile}"
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
    "select": "{relationship_id: relationship_id, relationship_name: relationship_name, breach_count: breach_count, flags: flags, policy: policy}"
  }],
  "condition": "breach_count > `0`",
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
    "return trade id, security, age days, and penalty amount per row",
    "row-by-row failed trade penalty listing"
  ],
  "tags": ["settlements", "failed-trades", "csdr", "cash-penalty", "map-iteration", "asset-servicing"]
}],
"io": {
  "consumes": [{ "from": "servicing.settlement_status", "select": "{failed: failed}" }],
  "map": {
    "over": "failed",
    "item_select": "{trade_id: trade_id, security: security, isin: isin, settle_date: settle_date, amount: amount, side: side, as_of_date: as_of_date, reason: reason, fail_item: fail_item}",
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

- **Over-broad or keyword-stuffed examples.** `"settlement"`, `"failed settlement"`, a bare
  relationship name — these claim a huge, contested region of meaning-space and poach neighbors (the
  `trade_penalty` incident, §4.4). Fix: narrow each example to your *distinct* intent. Sentences, not
  keyword bags.
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

## 13. Appendix — condensed schema reference

Validate against `registry/agent-manifest.schema.json`. **Required top-level:** `agent_id`, `name`,
`description`, `version`, `provider`, `domain`, `audience`, `protocol`, `connection`, `capabilities`,
`skills`, `constraints`.

| Field | Type / values | Notes |
|---|---|---|
| `agent_id` | string, `^[a-z0-9]+(\.[a-z0-9_]+)+$` | Stable, namespaced. |
| `name`, `description` | string (non-empty) | Describe what it does *and does not* do. |
| `version` | string, `^\d+\.\d+\.\d+$` | Semver. |
| `provider` | `{ organization*, contactEmail? }` | — |
| `domain` | string | Validated against loaded domain manifests, not an enum. |
| `sub_domain` | string | Resolves to a loaded sub-domain manifest. |
| `audience` | `segment` \| `enterprise` | `enterprise` skips the structural gate. |
| `protocol` | `http` \| `mcp` \| `a2a` | Selects the adapter. |
| `connection` | object | HTTP: `{ openapi_url*, operation_id* }`. MCP: `{ server_url*, tool*, transport? }` (`sse`\|`stdio`). A2A: `{ agent_card_url* }`. |
| `capabilities` | `{ streaming*: bool, … }` | Additive. |
| `constraints.access_mode` | `read` \| `write` | Phase-1 = `read`. |
| `constraints.data_classification` | `public` \| `internal` \| `confidential` \| `confidential-pii` | Drives the classification gate. |
| `constraints.sla_timeout_ms` | int 100–60000 | Join deadline. |
| `constraints.rate_limit` | `{ requests*, per_seconds* }` | Optional. |
| `max_response_tokens` | int 1–100000 | Optional truncation. |
| `skills[]` | `{ id*, name*, description*, tags[≥1]*, examples[≥3]*, inputModes?, outputModes? }` | `examples` are the routing fuel (§4). |
| `output_schema` | JSON Schema object | **Fallback only** when introspection can't derive output (§6). |
| `io.consumes[]` | each `{ entity }` **or** `{ from, select?, required? }` | Exactly one of `entity`/`from` per item. `select` = JMESPath projection (§5.3). |
| `io.produces[]` | `{ name*, type*, entities? }` | `type` matched by string equality — namespace it. `entities` is for per-producer coverage selectors. |
| `io.condition` | string (JMESPath boolean) | Node runs only if true; else `skipped_condition_false` (§5.4). |
| `io.map` | `{ over*, item_select?, max_items?, max_concurrency? }` | Bounded iteration; caps clamped to global ceilings (§5.5). |

**Boot success looks like:** `Registry bootstrap complete: N loaded, 0 failed` and
`select validation: … 0 UNVALIDATED`. **World-B gate:** `scripts/world-b-check.sh` → CRITICAL 0.

---

*Extending Conduit is a description exercise, by design. You describe your agent; the platform finds
it, routes to it, composes it, and governs it. If you ever find yourself needing a change to the
gateway itself to onboard an agent, that is not your task — it is a bug in the manifest model. Raise
it.*
