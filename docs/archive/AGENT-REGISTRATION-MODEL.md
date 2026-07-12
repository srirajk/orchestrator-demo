# Agent Registration Model — what's declared, and why the domain + sub-domain + agent split

> Audience: engineers and reviewers. This explains the registration contract Conduit uses, what
> every attribute controls, and *why* onboarding is three tiers (domain → sub-domain → agent) rather
> than one flat agent file. The governing principle is **World-B**: the gateway is a generic manifest
> interpreter with zero embedded domain knowledge — a new business capability is onboarded by adding
> manifest JSON, never by changing gateway Java.

## 1. The three tiers (and the org structure they mirror)

A real bank is organized **business domain → desk/sub-domain → service**. The manifests mirror that
exactly, because the thing that varies at each level is different:

| Tier | File | Answers | Varies per… |
|---|---|---|---|
| **Domain** | `registry/domains/<domain>.json` | Where is this domain's book-of-business, and how is its memory governed? | business domain (Wealth, Insurance, Servicing) |
| **Sub-domain** | `registry/domains/<domain>/<sub>.json` | What entities does this desk operate on, and what does "which one?" sound like here? | desk (Private Banking, Claims Servicing, Custody Ops) |
| **Agent** | `registry/manifests/<agent_id>.json` | What can this one service do, how do I reach it, and what's it allowed to touch? | capability (holdings, policy details, NAV) |

**Why not one flat agent file?** Because most of what an agent needs is *shared with its siblings*.
Every wealth private-banking agent operates on the same `relationship_id`, the same coverage
service, and the same "Which client?" clarification copy. Declaring that on each agent would be
massive duplication that drifts out of sync. So:
- **shared entity vocabulary + coverage scoping + clarification copy** → declared ONCE at the
  sub-domain,
- **coverage-service endpoints + memory/compaction policy** → declared ONCE at the domain,
- **only what is unique to the capability** → the agent file.

Adding a capability = **one agent file**. Adding a desk = a sub-domain (entity vocab + copy). Adding
a business line = a domain (coverage + memory). No gateway code at any level.

## 2. Domain manifest — attributes and why
`domain_id`, `display_name` — identity/label.
`coverage.{discover_url, check_url, resolve_url, cache_ttl}` — the **book-of-business** endpoints the
data-aware entitlement gate calls (DISCOVER what a principal covers, CHECK a specific entity, RESOLVE
a human reference → ID). This is *the* reason a domain exists as a tier: coverage is a domain-wide
service, not per-agent.
`clarify_style` (`template` | `composed`) + `clarify_tone` — how a clarifying question is worded
(deterministic template vs a composed natural phrasing). The clarify *decision* stays deterministic;
this is wording only.
`memory_compaction.{envelope_version, must_preserve, can_drop, summary_policy}` — the governed
conversation-memory policy (what facts must survive compaction, retention, owned by the external
memory service). Domain-wide because a conversation lives in a domain.

## 3. Sub-domain manifest — attributes and why
`sub_domain_id`, `display_name`, `parent_domain` — placement.
`resource_scoped` (bool) — **does this desk's work hang off a per-entity book?** True (private
banking: everything is about a client relationship) turns on the coverage gate; false (a knowledge
desk) skips it. Drives whether DISCOVER/RESOLVE/CHECK runs.
`entity_types[]` — **the entity vocabulary**, declared once for the whole desk. Each entry:
`key` (the canonical name, e.g. `relationship_id`), `extract_as` (what the LLM extractor labels it),
`kind` (`resolvable` | `literal` | `list`), `display` (user-facing noun), `id_pattern` (regex that
recognises an already-formed ID so it's used verbatim — never fabricated), `resolve_type` (what the
RESOLVE endpoint resolves it to), `required`, `default`. **This is the vocabulary the DAG's
`io.consumes.entity` refers to** and the basis of the deterministic CLARIFY (`required ∩ resolved = ∅`).
`required_context[]` — which entities MUST resolve before a fetch (the CLARIFY trigger set).
`denial_messages`, `messages`, `clarification_schema` — desk-specific user copy (kept OUT of gateway
code — World-B forbids user-facing domain copy in Java).
`agents[]` — which agent_ids belong to this desk (membership).

## 4. Agent manifest — attributes and why (the registration contract)
Grouped by what each attribute *controls*:

**Identity & placement**
- `agent_id` — stable dotted id; the key for entitlement, tracing, metrics, and `io` edges. Pattern
  `^[a-z0-9]+(\.[a-z0-9_]+)+$`.
- `domain`, `sub_domain` — placement in the hierarchy → the agent INHERITS the coverage endpoints,
  entity vocabulary, and clarify copy above it. `domain` is validated against loaded domain manifests
  at boot (not a hardcoded list).
- `provider.organization` — attribution/display (e.g. "Meridian Demo Bank"). *(See the naming note in
  `REPO-STRUCTURE-AND-NAMING.md` — this is where "Meridian" lives while ids say "acme".)*
- `version` — semver of the manifest.

**Entitlement / authority (the gates)**
- `audience` (`segment` | `enterprise`) — THE first authz gate. `segment` = gated by business-segment
  membership + per-segment data-classification + coverage (entity-scoped). `enterprise` = open to all
  authenticated users, no entity, no coverage (e.g. HR policy Q&A, market house-view). Getting this
  wrong is a real bug (a no-entity knowledge agent left as `segment` would hit a coverage gate it
  can't satisfy).
- `constraints.access_mode` (`read` | `write`) — read-only enforcement today; the seam for the future
  action gate.
- `constraints.data_classification` (`public` → `internal` → `confidential` → `confidential-pii`) —
  the classification ladder gate; the principal's tier in the segment must meet the agent's required
  level.
- `constraints.sla_timeout_ms`, `constraints.rate_limit` — the per-agent deadline the resilience
  harness enforces, and optional throttle.

**Transport (how the gateway calls it)**
- `protocol` (`http` | `mcp` | `a2a`) + `connection` — which `ProtocolAdapter` invokes it and how
  (HTTP: `openapi_url` + `operation_id`; MCP: `server_url` + `tool`; A2A: `agent_card_url`).

**Discovery / routing**
- `skills[]` — `id`, `name`, `description`, `tags`, and **`examples`** (≥3). The examples ARE the
  routing signal: they're embedded and matched by semantic KNN. Routing quality ≈ example quality.
  `inputModes`/`outputModes` describe payload media types.
- `capabilities.streaming` — whether the agent streams.

**Dataflow (the new multi-step contract)**
- `io.consumes[]` — what the capability needs: `{entity: "<sub-domain entity key>"}` (a LEAF input,
  satisfied by deterministic entity resolution — no edge) OR `{from: "<produced type>"}` (an EDGE to
  whichever capability produces that type). `required` per item.
- `io.produces[]` — `{name, type}` the capability outputs onto the blackboard for downstream steps.
- This is what the deterministic DagResolver matches (`produces.type` == `consumes.from`) to derive
  the execution DAG. Optional: without `io`, an agent is single-node (flat) only.

**Runtime limits**
- `max_response_tokens` — the gateway truncates the agent's output to this before synthesis.

> Note: the *wire* input/output schema is DERIVED by the gateway via OpenAPI/MCP introspection at
> registration — it is NOT in the submitted manifest. `io` is the *semantic* dataflow contract for
> planning, deliberately separate from the wire schema.

## 5. How they compose — the "effective manifest"
At request time the gateway composes agent + sub-domain + domain into one effective view: the agent's
identity/transport/constraints/io, plus the sub-domain's entity vocabulary + clarify copy + coverage
scoping, plus the domain's coverage endpoints + memory policy. The gateway logic that reads this is
generic; every domain-specific string comes from the manifests. That is World-B in one sentence:
**the gateway interprets manifests; it never contains them.**

## 6. Onboarding checklist (what a new capability actually costs)
1. Agent already in an existing desk → **1 file** (`registry/manifests/<id>.json`) + stand up the
   service + `io` if it participates in multi-step.
2. New desk → + a sub-domain manifest (entity vocab, required context, copy, agent membership).
3. New business line → + a domain manifest (coverage endpoints, memory policy) and, if it needs a new
   segment mapping, a Cerbos policy entry (config, not gateway code).
4. Validate against the three JSON Schemas; run `scripts/world-b-check.sh` (CRITICAL must stay 0).
