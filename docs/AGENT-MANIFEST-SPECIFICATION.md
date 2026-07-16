# Conduit Agent Manifest Specification

> **Single source of truth.** This document specifies every field of every manifest kind the
> Conduit gateway reads. It is self-contained: nothing here defers to another prose document. A
> newcomer can author any manifest from this file alone. Where a claim needs an authority, this
> document cites the **code path** that enforces it (`file:line`), never another narrative doc.

The three JSON Schemas that this document describes in prose are the machine-checkable contract:

| Manifest kind | Authoritative schema (runtime copy loaded from the gateway classpath) |
|---|---|
| Agent | `gateway/src/main/resources/agent-manifest.schema.json` |
| Domain | `gateway/src/main/resources/domain-manifest.schema.json` |
| Sub-domain | `gateway/src/main/resources/sub-domain-manifest.schema.json` |

Every field, type, default, enum, and required/optional status below was read directly from those
schemas and from the Java records that deserialize them. If this prose and a schema ever disagree,
the schema wins — but the two are kept identical by design (see §6).

---

## Table of contents

1. [The three manifest kinds and how they compose](#1-the-three-manifest-kinds-and-how-they-compose)
2. [Agent manifest — every field](#2-agent-manifest--every-field)
3. [The `io` dataflow contract in full](#3-the-io-dataflow-contract-in-full)
4. [Domain manifest — every field](#4-domain-manifest--every-field)
5. [Sub-domain manifest — every field](#5-sub-domain-manifest--every-field)
6. [Validation & lifecycle](#6-validation--lifecycle)
7. [The World-B invariant — why manifests exist](#7-the-world-b-invariant--why-manifests-exist)
8. [Three fully-worked annotated examples](#8-three-fully-worked-annotated-examples)
9. [Field-reference appendix](#9-field-reference-appendix)

---

## 1. The three manifest kinds and how they compose

Conduit describes a business domain with three kinds of JSON manifest. None of them live in gateway
Java; all of them live on disk under `registry/` and are loaded at boot.

### 1.1 Folder layout

```
registry/
├── manifests/<domain>/<agent_id>.json      ← AGENT manifests (one per agent)
│   ├── wealth-management/meridian.wealth.holdings.json
│   ├── asset-servicing/meridian.servicing.nav.json
│   └── hr/meridian.hr.policy_qa.json
│
├── domains/<domain>.json                    ← DOMAIN manifests (one per domain)
│   ├── wealth-management.json
│   ├── insurance.json
│   └── hr.json
│
└── domains/<domain>/<sub-domain>.json       ← SUB-DOMAIN manifests (one per sub-domain)
    ├── wealth-management/private-banking.json
    ├── insurance/claims-servicing.json
    └── hr/hr-knowledge.json
```

The file-glob discipline is deliberate and load-bearing. `DomainManifestStore.loadDomains()`
(`gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifestStore.java:130`) matches
`domains/*.json` — exactly the domain files, one level deep. `loadSubDomains()` (line 205) matches
`domains/*/*.json` — exactly the sub-domain files, two levels deep. The two globs are disjoint, so
each file is validated against the *right* schema. (An earlier `domains/**/*.json` glob matched both
and silently dropped domain-level files — the bug that comment at line 200-204 records.)

Agent manifests are consumed by the **registry-service** (the `registry` Spring profile), not by
`DomainManifestStore`. The registry ingests `registry/manifests/**/*.json`, introspects each live
agent, embeds its examples, and writes the routing index into Redis. The gateway then reads that
index. See §6 for the full lifecycle.

### 1.2 How the three compose into an `EffectiveManifest`

At request time the gateway does not look at three manifests independently. It merges the routed
agent's **domain** manifest and **sub-domain** manifest (plus the agent id) into a single
`EffectiveManifest` value object via `EffectiveManifest.merge(domain, sub, agentId)`
(`gateway/src/main/java/ai/conduit/gateway/domain/manifest/EffectiveManifest.java:24`). The merge
pulls:

- `requiredContext`, `clarificationSchema`, `resourceScoped`, `entity_types` copy ← **sub-domain**
- `coverage`, `mustPreserve`/`canDrop` (memory), `clarifyStyle`, `clarifyTone` ← **domain**
- `agentId` ← the routed agent

The relationships:

- **A domain has many sub-domains.** `sub-domain.parent_domain` must equal an existing
  `domain.domain_id`; `DomainManifestStore.validate()` (line 240) fails startup otherwise.
- **A sub-domain has many agents.** `sub-domain.agents[]` lists the `agent_id`s that belong to it;
  the store builds a reverse `agentId → subDomainId` map from these lists
  (`DomainManifestStore.java:232`) so `getEffective()` still works when an agent record in Redis
  lost its `sub_domain` field to a Jackson quirk.
- **An agent names its own home.** `agent.domain` and `agent.sub_domain` name the domain and
  sub-domain the agent belongs to; both must resolve to loaded manifests.

The gateway holds **no** domain/sub-domain/agent literals. Everything domain-shaped that follows is
data in one of these three files. That is the whole product thesis (§7).

---

## 2. Agent manifest — every field

**Schema:** `gateway/src/main/resources/agent-manifest.schema.json`
**Java record:** `gateway/src/main/java/ai/conduit/gateway/registry/model/AgentManifest.java`
**`additionalProperties`:** `false` at the top level — an unknown key fails validation.

An agent manifest is the contract a domain team submits to register **one** agent. It is
A2A-Agent-Card-aligned with three gateway extensions (`protocol`, `connection`, `constraints`) plus
the optional `io` dataflow contract. The agent's *wire* input/output schemas are **not** in the
manifest — they are derived at registration time by introspecting the live agent (§6.3); a submitted
`output_schema` is only a fallback.

### 2.1 Required top-level fields

The schema `required` list (`agent-manifest.schema.json:8`) is exactly these twelve:
`agent_id, name, description, version, provider, domain, audience, protocol, connection,
capabilities, skills, constraints`.

#### `agent_id` — string, **required**
Stable dotted identifier. Pattern `^[a-z0-9]+(\.[a-z0-9_]+)+$` (schema line 13): at least two
dot-separated lowercase segments, e.g. `meridian.wealth.holdings`. This is the Redis key, the
routing-index tag, and the value listed in a sub-domain's `agents[]`. It never changes for the life
of the agent.

#### `name` — string, **required**, `minLength: 1`
Human display name, e.g. `"Wealth Holdings"`. Fed verbatim to the LLM routing reranker as a
candidate label (`LlmRoutingRerankerClient.java:78`).

#### `description` — string, **required**, `minLength: 1`
One-paragraph description of what the agent does and what it is keyed by. Also fed to the reranker
(`LlmRoutingRerankerClient.java:79`), so it is worth writing it to disambiguate this agent from its
siblings (e.g. the NAV agent's description explicitly says it is keyed by `fund_id`, *not*
`relationship_id`, so relationship-level prompts do not select it).

#### `version` — string, **required**
Semantic version. Pattern `^[0-9]+\.[0-9]+\.[0-9]+$` (line 19), e.g. `"1.0.0"`.

#### `provider` — object, **required**
Owning organization. `additionalProperties: false`.
- `organization` — string, **required**, `minLength: 1`. e.g. `"Meridian Demo Bank"`.
- `contactEmail` — string, optional.

#### `domain` — string, **required**, `minLength: 1`
The business domain this agent belongs to. **Not** enumerated in the schema (line 31-35): a new
domain onboards by adding manifests, and the value is validated at boot against the loaded domain
manifests (`DomainManifestStore`), never against a hardcoded list. Must equal a `domain.domain_id`.

#### `audience` — string enum, **required**
Who may reach this agent (line 36-40). Two values:
- `"segment"` — gated by business-segment membership **and** per-segment data classification. The
  coverage `CHECK` (§4.4) is the entitlement gate.
- `"enterprise"` — open to **all** authenticated users; the segment gate is **skipped**. Use for
  non-book knowledge such as HR policy Q&A. An enterprise agent's sub-domain is typically
  `resource_scoped: false` and declares no `required_context` (see the HR example, §8).

#### `protocol` — string enum, **required**
Which in-JVM adapter invokes the agent (line 41-45): `"http"`, `"mcp"`, or `"a2a"`. The required
keys inside `connection` depend on this value (the schema's `allOf`/`if`/`then` block, lines
270-311). `a2a` is defined in the schema and the `AgentIntrospector` switch
(`AgentIntrospector.java:43`) currently handles only `http` and `mcp`; `a2a` is the stubbed seam.

#### `connection` — object, **required**
How to reach the agent. Its shape is conditional on `protocol`:

- **`protocol: "http"`** → `connection` requires (schema line 272-283):
  - `openapi_url` — string. URL of the agent's OpenAPI 3 spec, e.g.
    `"http://wealth-http:8081/openapi.json"`. The introspector fetches this
    (`AgentIntrospector.java:59`) to derive the input schema and resolved connection.
  - `operation_id` — string. The `operationId` inside that spec to invoke, e.g.
    `"get_holdings_holdings_get"`.

- **`protocol: "mcp"`** → `connection` requires (schema line 285-299):
  - `server_url` — string. The MCP endpoint, e.g. `"http://servicing-mcp:8082/mcp"`.
  - `tool` — string. The MCP tool name, e.g. `"get_nav"`.
  - `transport` — string enum, optional, **default `"streamable"`**: `"streamable"` (Streamable
    HTTP, single `/mcp` endpoint, current spec) | `"sse"` (legacy, deprecated HTTP+SSE two-channel
    handshake) | `"stdio"`. An absent value is treated as `streamable`; the gateway carries **no**
    hardcoded transport default in Java (`AgentManifest.Connection.isLegacySse()` at line 96 is the
    only reader, and it only returns true when `sse` is explicitly pinned).
  - `protocol_version` — string, optional. Per-agent MCP protocol version negotiated on
    `initialize`, e.g. `"2025-11-25"`. When absent the adapter falls back to the configured
    `conduit.mcp.protocol-version` (streamable) / `conduit.mcp.legacy-protocol-version` (sse). Never
    a Java string literal.

- **`protocol: "a2a"`** → `connection` requires (schema line 300-310):
  - `agent_card_url` — string. URL of the agent's A2A Agent Card.

The Java `Connection` record (`AgentManifest.java:73`) holds all of these fields flat; the ones that
do not apply to the chosen protocol are simply null.

#### `capabilities` — object, **required**, `additionalProperties: true`
Declared capabilities (line 50-57).
- `streaming` — boolean, **required**. Whether the agent streams.
- Additional keys are allowed. In practice manifests also declare `pushNotifications` (boolean),
  which the Java `Capabilities` record (`AgentManifest.java:71`) binds.

#### `skills` — array, **required**, `minItems: 1`
The agent's advertised skills. **This is the routing corpus.** Each item is an object with
`additionalProperties: false` and required keys `id, name, description, tags, examples`:
- `id` — string, **required**, `minLength: 1`. Skill identifier, e.g. `"get_holdings"`.
- `name` — string, **required**, `minLength: 1`.
- `description` — string, **required**, `minLength: 1`. Fed to the reranker
  (`LlmRoutingRerankerClient.java:86`).
- `tags` — array of non-empty strings, **required**, `minItems: 1`. Structured/faceted discovery
  keys, e.g. `["holdings","portfolio","positions","allocation","wealth-management"]`.
- `examples` — array of non-empty strings, **required**, `minItems: 3`. Representative user
  prompts. **These are embedded and become the HNSW routing corpus** (see below) *and* are passed
  to the LLM reranker (`LlmRoutingRerankerClient.java:87-92`).
- `inputModes` — array of strings, optional. e.g. `["application/json"]`.
- `outputModes` — array of strings, optional.

**How `examples` become the routing index.** At ingestion the registry flattens every example across
every skill (`AgentManifest.allExamples()`, `AgentManifest.java:222`), embeds them as a batch
(`VectorIndexWriter.index()`, `VectorIndexWriter.java:100-127`), and writes one Redis hash per
example keyed `agent:vec:<agent_id>:<i>` carrying the vector plus tag fields `agent_id`, `domain`,
`sub_domain`, and `is_mutating`. At request time the user's query is embedded and matched by cosine
similarity against these vectors (RediSearch HNSW). So the **capability-phrased, entity-free** rule
for examples matters: write examples as *capabilities* ("current holdings for this relationship"),
never with a literal entity baked in ("holdings for REL-00188") — a baked-in id pollutes the vector
space and a bare id routes at noise level anyway (that is what the deterministic `id_pattern` path in
§5 is for). The example set should also be phrased so it does **not** collide with a sibling agent's
corpus.

#### `constraints` — object, **required**, `additionalProperties: false`
Scope-of-authority and operational limits (line 90-116). Required keys `access_mode,
data_classification, sla_timeout_ms`:
- `access_mode` — string enum, **required**: `"read"` | `"write"`. Phase-1 agents MUST be `"read"`;
  the resolver/entitlement layer enforces read-only. This value drives the routing-index
  `is_mutating` flag (`VectorIndexWriter.java:119` — `"write"` → `"1"`, else `"0"`), which powers
  the `@is_mutating:[0 0]` read-only fan-out filter without a reindex.
- `data_classification` — string enum, **required**: `"public"` | `"internal"` | `"confidential"` |
  `"confidential-pii"`. Feeds the per-segment classification gate.
- `sla_timeout_ms` — integer, **required**, `minimum: 100`, `maximum: 60000`. Per-agent deadline the
  harness joins to; a slow agent is harvested as a partial, never allowed to cancel siblings.
- `rate_limit` — object, optional, `additionalProperties: false`. `requests` (integer `≥1`,
  required) and `per_seconds` (integer `≥1`, required).

### 2.2 Optional top-level fields

#### `sub_domain` — string, optional
The sub-domain this agent belongs to within its domain, e.g. `"private-banking"` (line 117-120).
Resolves to a loaded sub-domain manifest. Because Jackson can drop this on a record round-trip, the
gateway keeps a reverse map from `sub-domain.agents[]` as a fallback (§1.2).

#### `max_response_tokens` — integer, optional, `minimum: 1`, `maximum: 100000`
The gateway truncates this agent's response to this many tokens before synthesis (line 121-126).

#### `securitySchemes` — object, optional
OpenAPI-aligned auth declaration (line 58-61). Free-form object; not required.

#### `output_schema` — object, optional
A JSON-Schema **fallback** for the agent's output (line 127-130). Used only when protocol
introspection cannot derive an output schema; otherwise the gateway stores the *introspected* schema
under the same key. Several real manifests declare it anyway (e.g. `meridian.wealth.holdings`) so the
DAG resolver's `select`/figure contracts can be validated against a known shape at registration time.

#### `io` — object, optional
The semantic dataflow contract for multi-step (DAG) orchestration. Fully specified in §3.

### 2.3 Derived fields (written by the registry, not submitted)

These are **not** part of a submission and are rejected by `additionalProperties: false` if you try
to submit them — the registry adds them after introspection (`AgentManifest.java:43-48`):
`input_schema` (introspected wire input), `output_schema` (introspected, overrides the submitted
fallback), `resolved_connection` (`base_url`/`method`/`path`), `indexed` (boolean, stamped `true` by
`AgentRegistrar.stampAndStore()`), and `registered_at` (timestamp).

---

## 3. The `io` dataflow contract in full

**Schema:** `agent-manifest.schema.json:131-268`
**Java record:** `AgentManifest.Io` / `Consume` / `Produce` / `MapSpec` / `ProducedEntity` /
`ProducedFigure` (`AgentManifest.java:128-219`).

`io` is **optional** and `additionalProperties: false`. It is **not** the wire schema (that is still
derived via introspection). It declares, in *domain-symbolic* terms, what a capability **needs** and
what it **produces**, so the deterministic DAG resolver can wire dependent steps. A capability with
no `io` block is single-node only — it participates in flat fan-out but is never a producer or
consumer in a DAG. The DAG path lights up per-capability as `io` contracts are declared.

Every string in `io` (`type`, `from`, `entity`, `select`, `path`, `condition`, `over`) is a
**manifest-declared symbol matched by equality or evaluated as CEL** — none is interpreted or
hardcoded in gateway Java.

### 3.1 `io.consumes` — array, optional

Inputs this capability needs. Each item has `additionalProperties: false` and a `oneOf`: **exactly
one** of `entity` or `from` (schema line 142-145).

- `entity` — string, `minLength: 1`. A sub-domain `entity_types` **`key`** this capability needs
  (e.g. `"relationship_id"`, `"fund_id"`). It is a **leaf** input, satisfied by deterministic entity
  resolution from the blackboard — it creates **no** DAG edge and needs no upstream agent.
- `from` — string, `minLength: 1`. An upstream capability's produced-output **`type`**. Matching it
  by string equality creates a **producer → consumer edge** in the DAG.
- `required` — boolean, optional, **default `true`** (schema line 157; `Consume.isRequired()` returns
  true when null, `AgentManifest.java:189`). A required-but-unsatisfied input skips the node's
  dispatch.
- `select` — string (CEL), optional, **meaningful only alongside `from`** (line 158-161).
  Projects/reshapes the producer's output into exactly what this consumer expects (field projection,
  not a blob pass-through). Absent = identity pass-through (backward-compatible). Evaluated by the
  gateway's `Blackboard.bind` (referenced at `AgentManifest.java:179`); generic, never
  domain-hardcoded. Example (from `meridian.wealth.concentration`):
  `"select": "{positions: positions, total_value: total_value, relationship_id: relationship_id, ...}"`.

### 3.2 `io.produces` — array, optional

Named, typed outputs this capability places on the blackboard for downstream `from` bindings. Each
item has `additionalProperties: false` and required keys `name, type`:

- `name` — string, **required**, `minLength: 1`. Blackboard key under which the result is published.
- `type` — string, **required**, `minLength: 1`. Symbolic output type a downstream consumer matches
  with `from`. Manifest-declared, matched by string equality (e.g. `"wealth.holdings"`).
- `entities` — array, optional. Manifest-declared entity ids emitted by this output. Runtime
  coverage filtering reads **only** these selectors; the gateway never scans arbitrary JSON for
  id-looking values. Each item `additionalProperties: false`, required `type, select`:
  - `type` — string. Entity type whose coverage service is declared by the domain/sub-domain
    manifest.
  - `select` — string (CEL) over this producer output evaluating to a string id or array of
    string ids.
- `figures` — array, optional. Manifest-declared **load-bearing figures** this output emits. The
  gateway renders these deterministically from data and validates answer attribution generically
  (see below). Each item `additionalProperties: false`, required `label, path, format`:
  - `label` — string. Human label the answer must use when attributing this rendered figure.
  - `path` — string (CEL) over this producer output evaluating to the figure's raw scalar
    value.
  - `format` — string enum. The generic formatter id, enumerated to **exactly** the set
    `GroundedFigureRenderer` renders and `GroundedFigureValidator` gates on (schema line 223-227). A
    value outside this set is rejected at ingestion rather than silently rendering as `plain` and
    weakening the grounding gate. The enum, with how `GroundedFigureRenderer.format()`
    (`GroundedFigureRenderer.java:75-87`) renders each:

    | `format` | renders as | example input → output |
    |---|---|---|
    | `percent` | scaled to %, 2 dp, `%` suffix | `0.184` → `18.4%`; `18.4` → `18.4%` |
    | `percent1` | scaled to %, 1 dp | `0.184` → `18.4%` |
    | `percent2` | scaled to %, 2 dp | `0.1843` → `18.43%` |
    | `currency_usd` | `$` + grouped, 2 dp | `1234567.5` → `$1,234,567.50` |
    | `count` | rounded integer | `3.0` → `3` |
    | `date` | scalar text as-is | `"2026-03-31"` → `2026-03-31` |
    | `plain` | scalar text as-is | `1.83` → `1.83` |

    (Percent scaling: a magnitude `≤ 1.0` is multiplied by 100, otherwise taken as already a percent
    — `GroundedFigureRenderer.scalePercent()`, line 108.)

### 3.3 `io.condition` — string, optional

A node-level CEL expression over the node's **merged, bound input** (schema line 234-237). It
must evaluate to boolean: `true` dispatches the node, `false` cleanly **skips it as not applicable**.
This is how a downstream node runs only when it is relevant — e.g.
`meridian.wealth.concentration_review` declares `"condition": "input.breach_count > \0\"` so the review
flag node only fires when the upstream concentration analysis found breaches.

**"Not applicable" vs "missing".** A node skipped by a `false` `condition` is *not applicable* — the
answer states the branch did not apply. A required `consume` that could not be satisfied (no upstream
producer, unresolved entity) is *missing* — the answer states what data was unavailable. The two are
distinct dispositions; `condition` expresses the former, `required` the latter.

### 3.4 `io.map` — object, optional

Dynamic map expansion (schema line 239-266, `AgentManifest.MapSpec` at `AgentManifest.java:151`).
`additionalProperties: false`, required key `over`. The gateway evaluates `over` against the node's
merged, bound input; each selected item is optionally reshaped by `item_select` and dispatched to
**this same capability**, bounded by the declared caps and the global gateway ceilings.
- `over` — string (CEL), **required**. Must evaluate to an array.
- `item_select` — string (CEL), optional. Produces the per-item wire input from each array
  item.
- `max_items` — integer, optional, `minimum: 1`. Per-node item cap; runtime clamps to the gateway
  global maximum.
- `max_concurrency` — integer, optional, `minimum: 1`. Per-node concurrency cap; runtime clamps to
  the gateway global maximum.

Real example — `meridian.servicing.trade_penalty` fans one penalty calculation per failed trade:
```json
"map": { "over": "input.failed",
         "item_select": "{trade_id: trade_id, security: security, ...}",
         "max_items": 2, "max_concurrency": 2 }
```

### 3.5 How the DAG resolver wires producer → consumer

The resolver is deterministic and purely symbolic: a producer's `produces[].type` and a consumer's
`consumes[].from` are matched by **string equality** to form an edge. `entity` consumes are leaves
satisfied from resolved entities (no edge). So a two-hop pipeline reads:

```
holdings  produces type "wealth.holdings"
             │  (from: "wealth.holdings", select: {...})
             ▼
concentration  produces type "wealth.concentration"
                  │  (from: "wealth.concentration", condition: breach_count > 0)
                  ▼
concentration_review  produces type "wealth.concentration_review"
```

No gateway Java names any of those types; they are all manifest strings.

---

## 4. Domain manifest — every field

**Schema:** `gateway/src/main/resources/domain-manifest.schema.json`
**Java record:** `gateway/src/main/java/ai/conduit/gateway/domain/manifest/DomainManifest.java`
**`additionalProperties`:** `false`.

A domain manifest declares coverage endpoints, the assistant framing phrase, clarification wording
policy, and governed-memory policy. Required top-level keys (schema line 8): `domain_id,
display_name, memory_compaction`.

#### `domain_id` — string, **required**
Pattern `^[a-z0-9]+(-[a-z0-9]+)*$` (kebab-case), e.g. `"wealth-management"`. Matches `agent.domain`
and `sub-domain.parent_domain`.

#### `display_name` — string, **required**, `minLength: 1`
Human label, e.g. `"Wealth Management"`.

#### `domain_context` — string, optional, `minLength: 1`
A short, neutral phrase describing this domain's data coverage, e.g. `"client financial data"`. The
gateway **composes** the phrases declared across *all* loaded domains into the classifier /
synthesizer framing string, ordered by `domain_id` for stability
(`DomainManifestStore.composedDomainContext()`, `DomainManifestStore.java:577`). With phrases
`["client financial data", "insurance policies and claims"]` the composed framing is:

> *an enterprise data assistant covering client financial data and insurance policies and claims*

When **no** domain declares a `domain_context`, the neutral default `"an enterprise data assistant"`
is used — never a domain-flavored string (`composeDomainContext()`, line 593). This composed string
is read by `IntentClassifier` (`IntentClassifier.java:224`) and `AnswerSynthesizer`
(`AnswerSynthesizer.java:327`). This is the *only* way domain-facing framing reaches the prompts —
domain copy lives here, never in gateway Java (World-B).

#### `clarify_style` — string enum, optional, **default `"template"`**
Clarification **wording** policy (schema line 21-26): `"template"` serves the byte-for-byte
deterministic question; `"composed"` lets the gateway's `ClarificationComposer` phrase a natural
question over the grounded candidate set. The clarify **decision** stays deterministic regardless
(§5, `required_context`); this only affects wording, and the deterministic template is always the
validated fallback. `DomainManifest.clarifyStyleOrDefault()` (line 26) defaults blank/null to
`"template"`.

#### `clarify_tone` — string, optional, `minLength: 1`
Tone/style hint passed to the `ClarificationComposer` when `clarify_style` is `"composed"` (e.g.
`"warm, concise, and professional — like a helpful colleague"`). Ignored for `"template"`.

#### `coverage` — object, optional, `additionalProperties: false`
The DISCOVER / CHECK / RESOLVE endpoint templates (schema line 32-59). Required keys `discover_url,
check_url, resolve_url`. **Required whenever any child sub-domain is `resource_scoped: true`** —
`DomainManifestStore.validate()` (line 247) fails startup if a resource-scoped sub-domain's parent
domain has no `coverage.discover_url`.
- `discover_url` — string, **required**, `minLength: 1`. Template URL for **DISCOVER**. Must include
  `{principal_id}` when used by a resource-scoped sub-domain.
- `check_url` — string, **required**, `minLength: 1`. Template URL for **CHECK**. Must include
  `{principal_id}` and `{id}`.
- `resolve_url` — string, **required**, `minLength: 1`. Template URL for **RESOLVE**.
- `cache_ttl_seconds` — integer, optional, **default `30`**, `minimum: 1`, `maximum: 3600`.

URL templates may contain `${ENV_VAR}` placeholders (e.g.
`"${WEALTH_COVERAGE_URL}/coverage/{principal_id}"`); the store resolves them from the Spring
`Environment` at load (`resolveEnvVarsInCoverage()`, `DomainManifestStore.java:162`). The `{...}`
path params are bound per request by `CoverageClient.bindPathParams()`
(`CoverageClient.java:174`).

**The DISCOVER / CHECK / RESOLVE contract** (`CoverageClient.java`), all fail-closed (a 5xx or
timeout throws `CoverageUnavailableException` and the caller must deny, never grant):
- **DISCOVER** — `GET discover_url` with header `X-Tenant-Id`. Returns the list of
  `CoverageResource {id, label, sub_domain}` visible to the principal. Populates clarification option
  lists when `options_source: "discover"`.
- **CHECK** — `GET check_url` with `X-Tenant-Id`. Returns `CoverageCheckResult {allowed, reason}`.
  `allowed=false` carries a machine-readable **reason code** (`reason`) that maps to a sub-domain
  `denial_messages` entry (§5). This is the entitlement gate.
- **RESOLVE** — `POST resolve_url` with `X-Tenant-Id` and body `{reference, type}`. Returns
  `CoverageResolveResult {resolved, id, canonical_name, candidates[]}`. **RESOLVE is
  principal-agnostic** (`CoverageClient.java:113-124`): it is scoped only by tenant, and the client
  deliberately does **not** send a `principal_id`. The book gate is enforced by the *subsequent CHECK*
  and the gateway-side `candidates ∩ discover` intersection — never by filtering resolution.
  `resolved=false` with `candidates.size() > 1` is ambiguous (triggers clarify); with no candidates
  is not-found.

**The bearer token** on all three calls is the **forwarded end-user JWT**
(`CoverageClient.applyBearer()`, line 182) — coverage refuses the call if no caller identity is
present. The gateway does not mint or substitute an identity.

Reason codes are not enumerated in the schema — they are free strings the coverage service returns,
mapped to copy by each sub-domain's `denial_messages`. The real manifests use `not-covered`,
`not-in-book`, `unknown-resource`, `coverage-transferred`, `relationship-closed` / `policy-closed`,
and a `default` fallback (§5, §8).

#### `memory_compaction` — object, **required**, `additionalProperties: false`
Governed-memory policy. Compaction itself is owned by the external memory service, **not** the
gateway; the gateway only carries the policy. Required keys `envelope_version, must_preserve,
summary_policy`. (The Java `MemoryCompaction` record binds only `must_preserve` and `can_drop`
— `DomainManifest.java:38`; the rest of the block is consumed by the memory service, and unknown
keys are ignored by `@JsonIgnoreProperties`.)
- `envelope_version` — string enum, **required**: only `"context-envelope.v1"`.
- `must_preserve` — array of non-empty strings, **required**, `minItems: 1`. Fact keys/labels the
  memory service must preserve, e.g. `["relationship_id","client_name","period","domain"]`.
- `can_drop` — array of non-empty strings, optional, **default `[]`**. Payload fields safe to omit
  from governed summaries.
- `summary_policy` — object, **required**, `additionalProperties: false`. Required keys `owner,
  max_summary_tokens, ledger_retention_days, include_runtime_events`:
  - `owner` — string const **`"memory-service"`** (schema line 87-90). Compaction ownership must
    remain outside the gateway.
  - `max_summary_tokens` — integer, **required**, `minimum: 64`, `maximum: 4000`.
  - `refresh_after_turns` — integer, optional, `minimum: 1`, `maximum: 100`.
  - `ledger_retention_days` — integer, **required**, `minimum: 1`, `maximum: 3650`.
  - `include_runtime_events` — array, **required**, `minItems: 1`. Each item matches
    `^(gateway|memory)\.[a-z0-9_]+$`, e.g. `"gateway.entity_resolved"`,
    `"gateway.response_completed"`.
  - `redact_fields` — array of non-empty strings, optional, **default `[]`**.

---

## 5. Sub-domain manifest — every field

**Schema:** `gateway/src/main/resources/sub-domain-manifest.schema.json`
**Java record:** `gateway/src/main/java/ai/conduit/gateway/domain/manifest/SubDomainManifest.java`
**`additionalProperties`:** `false`.

The sub-domain manifest is where most of the request-path domain knowledge lives: the entity types
the extractor/resolver loop over, the deterministic clarify gate, coverage scoping, agent membership,
and every user-facing string. Required top-level keys (schema line 8-16): `sub_domain_id,
display_name, parent_domain, resource_scoped, entity_types, required_context, agents`.

#### `sub_domain_id` — string, **required**
Pattern `^[a-z0-9]+(-[a-z0-9]+)*$` (kebab-case), e.g. `"private-banking"`. Matches `agent.sub_domain`
and the entries in another manifest's `agents[]` reverse map.

#### `display_name` — string, **required**, `minLength: 1`
e.g. `"Private Banking"`.

#### `parent_domain` — string, **required**
Pattern `^[a-z0-9]+(-[a-z0-9]+)*$`. Must equal an existing `domain.domain_id`
(`DomainManifestStore.validate()` line 242 fails startup otherwise).

#### `resource_scoped` — boolean, **required**
Whether this sub-domain's entities live in a per-principal book requiring coverage DISCOVER/CHECK.
`true` for private-banking / claims-servicing (an agent's data is gated by the caller's book);
`false` for enterprise knowledge like `hr-knowledge`. When `true`, the parent domain **must** declare
`coverage.discover_url` (validated at startup, §4). Only `resource_scoped: true` sub-domains
participate in the deterministic id-pattern reference grounding
(`DomainManifestStore.identifyAllByIdPattern()`, line 441; `identifyAllByReference()`, line 483).

#### `entity_types` — array, **required**
The load-bearing declaration that makes the input pipeline domain-agnostic: extraction, resolution,
and binding all loop over these instead of hardcoding fields
(`EntityType`, `gateway/src/main/java/ai/conduit/gateway/domain/manifest/EntityType.java`). Each item
`additionalProperties: false`, required keys `key, extract_as, kind, display, required`:
- `key` — string, **required**, `minLength: 1`. The canonical **resolved** field name used in agent
  input binding, e.g. `relationship_id`, `fund_id`, `period`. This is the value referenced by
  `io.consumes[].entity` and by `required_context`.
- `extract_as` — string, **required**, `minLength: 1`. The field the LLM extracts the raw **human**
  value into, e.g. `relationship_reference`. Keeps the raw reference separate from the resolved id
  (World-B invariant 6: the LLM never produces an id).
- `kind` — string enum, **required**: `"resolvable"` (name → id via the coverage RESOLVE call) |
  `"literal"` (value used as-is) | `"list"` (array of strings). Helpers `isResolvable()` /
  `isLiteral()` / `isList()` (`EntityType.java:35-37`).
- `display` — string, **required**, `minLength: 1`. Human label used to compile prompt and
  clarification copy, e.g. `"client relationship"`, `"reporting period"`.
- `id_pattern` — string (regex), optional. Recognizes a literal id so the resolver can short-circuit
  and the keyword-fallback extractor can fire, e.g. `"REL-\\d+"`, `"POL-\\d+"`,
  `"(Q[1-4]\\s+\\d{4}|YTD|QTD|MTD)"`. Compiled once at load and cached
  (`DomainManifestStore.compileIdPatterns()`, line 106) so the request path never recompiles. A bare
  id like `REL-00188` carries no domain vocabulary, so embedding routing scores it at noise level;
  the `id_pattern` on a required, resolvable, resource-scoped entity type is what deterministically
  identifies its domain (`identifyAllByIdPattern()`, line 441).
- `resolve_type` — string, optional. The `type` string passed to the coverage RESOLVE call
  (`CoverageClient.resolve(reference, entityType, ...)`) — resolvable kinds only, e.g.
  `"relationship"`, `"policy"`.
- `required` — boolean, **required**. Whether this entity must be present for the request to
  proceed. (Note: the deterministic CLARIFY gate reads `required_context`, below, not this flag —
  `required_context` is the authoritative "must be present" list; `DomainManifestStore.java:339`.)
- `default` — string | number | boolean, optional. Default applied when a literal's value is absent,
  e.g. `period` defaults to `"QTD"` (wealth) or `"YTD"` (insurance).

#### `required_context` — array of non-empty strings, **required**
The manifest-declared entity `key`s that **must** be resolved before the request can proceed. This is
the **deterministic CLARIFY gate**: the gateway clarifies exactly when
`extracted ∩ required_context = ∅`, decided in gateway code over this list — never LLM-judged
(World-B invariant 2; the union across sub-domains is `requiredContextKeys()`,
`DomainManifestStore.java:339`). May be empty (`[]`) for enterprise knowledge sub-domains that need
no entity (e.g. `hr-knowledge`).

#### `agents` — array, **required**, `minItems: 1`
The `agent_id`s belonging to this sub-domain; each matches `^[a-z0-9]+(\.[a-z0-9_]+)+$`. Builds the
reverse `agentId → subDomainId` map at load (`DomainManifestStore.java:232`).

#### `denial_messages` — object, optional
Map of coverage **reason code → user-facing denial copy** (`additionalProperties` values are non-empty
strings). Looked up by `DomainManifestStore.denialMessage(reasonCode, subDomainId)`
(`DomainManifestStore.java:403`), which resolves the exact reason code, then that sub-domain's
`default` entry, then falls back cross-domain. The `default` key is the required fallback. Real keys:
`not-covered`, `not-in-book`, `unknown-resource`, `coverage-transferred`,
`relationship-closed`/`policy-closed`, `default`.

#### `messages` — object, optional
Map of **message key → user-facing copy** (`additionalProperties` values are non-empty strings) —
the sub-domain's clarification / error / status copy. This is how the gateway holds **zero** domain
copy: every user-facing string is sourced here (`DomainManifestStore.message(key, subDomainId)`, line
368). Copy may embed `{placeholder}` tokens the gateway fills at render time — e.g.
`reference_not_found` contains `{reference}` and `followup_clarification` contains `{entity}`.
Observed message keys across the real manifests:
- `no_coverage` — the principal has no resources in book.
- `reference_not_found` — RESOLVE found nothing for `{reference}`.
- `needs_more_detail` — no reachable service can answer as asked.
- `specify_entity` — ask for the entity name or id.
- `missing_entity_question` — the deterministic CLARIFY question.
- `followup_clarification` — narrow a vague follow-up about `{entity}`.
- `capability_unavailable` — no authorized service is reachable for this part of the request.

(The keys are open — a sub-domain may declare any key its flows reference; the gateway looks them up
by name and never hardcodes the copy. `compare_partial_resolution` and similar comparison-flow keys
follow the same map pattern when a flow needs them.)

#### `clarification_schema` — object, optional
Map of **entity `key` → clarification descriptor** driving *how* to ask for a missing entity. Each
value `additionalProperties: false`, required keys `question, options_source, priority`
(`ClarificationSchema`, `gateway/src/main/java/ai/conduit/gateway/domain/manifest/ClarificationSchema.java`):
- `question` — string, **required**, `minLength: 1`. The clarification prompt.
- `options_source` — string enum, **required**: `"discover"` (offer the DISCOVER resource list) |
  `"agent_derived"` (options come from an agent) | `"none"` (free text) | `"principal_book"`.
- `priority` — integer, **required**, `minimum: 1`. Lower fires first when several entities are
  missing.
- `default` — string | number | boolean, optional.

---

## 6. Validation & lifecycle

### 6.1 Fail-loud schema validation at load

**Agent manifests** are validated against `agent-manifest.schema.json` by `ManifestValidator.validate()`
(`gateway/src/main/java/ai/conduit/gateway/registry/service/ManifestValidator.java:38`) before
introspection and storage; a schema failure throws `ManifestValidationException`.

**Domain and sub-domain manifests** get the *same* fail-loud contract at gateway startup.
`DomainManifestStore.validateSchema()` (`DomainManifestStore.java:80`) validates each file against the
classpath schema and throws `IllegalStateException` on any error — which **aborts container startup**.
This is deliberate: silently dropping a malformed manifest would remove a domain's entity types,
CLARIFY gates, or copy with no signal (`DomainManifestStore.java:40-44, 143, 218`). Cross-manifest
invariants are also enforced at startup: unknown `parent_domain` and a resource-scoped sub-domain
whose parent lacks `coverage.discover_url` both fail startup (`validate()`, line 240).

### 6.2 The three synced schema copies

The agent schema exists in **three byte-identical copies**: the repo root
(`agent-manifest.schema.json`), the registry (`registry/agent-manifest.schema.json`), and the gateway
classpath (`gateway/src/main/resources/agent-manifest.schema.json`). **Only the classpath copy is
loaded at runtime** (`ManifestValidator` reads `/agent-manifest.schema.json` from the classpath). The
copies are kept identical by `ManifestSchemaCopiesInSyncTest`
(`gateway/src/test/java/ai/conduit/gateway/registry/loader/ManifestSchemaCopiesInSyncTest.java`),
which fails the build on drift. The domain and sub-domain schemas each have two copies (`registry/`
and the gateway classpath), likewise kept identical (the runtime copies are authoritative;
`DomainManifestStore.java:42-44` notes the sync test). **Editing one copy means editing all copies.**

### 6.3 Registry ingestion vs. the gateway read side

Ingestion runs in the **`registry` Spring profile** as its own container, on startup, reconciling the
manifest folder as the source of truth. `AgentRegistrar.register()`
(`gateway/src/main/java/ai/conduit/gateway/registry/service/AgentRegistrar.java:72`) runs the pipeline
per manifest:
1. **validate** the submission against the schema (`ManifestValidator`).
2. **introspect** the live agent to derive its wire input/output schema and resolved connection
   (`AgentIntrospector.introspect()`, `AgentIntrospector.java:42` — HTTP fetches the OpenAPI spec and
   finds `operation_id`; MCP does `tools/list` and finds `tool`).
3. **validate select contracts** across the registry (`SelectContractValidator`).
4. **persist + index**: `stampAndStore()` writes the full manifest to Redis (`agent:manifest:<id>`)
   and adds the id to the agents set; `VectorIndexWriter.index()` embeds every skill example and
   writes the HNSW routing hashes (`AgentRegistrar.java:102`, `VectorIndexWriter.java:100`).

The **gateway never embeds or mutates routing data.** The `ManifestEmbedder`, `VectorIndexWriter`,
and `AgentRegistrar` beans are all `@Profile("registry")`
(`VectorIndexWriter.java:34`, `AgentRegistrar.java:38`), so they are simply **absent** from the
gateway's context — the guarantee is a missing bean, not discipline. At boot the gateway verifies the
index exists, is non-empty, and was built by the same embedding model it queries with (the model
stamp, `VectorIndexWriter.ensureIndex()`, line 59), and refuses to start otherwise.

### 6.4 Orphan pruning on removal

The registry reconciles the folder: an agent whose manifest file is gone is **deregistered**.
`AgentRegistrar.deregister()` (`AgentRegistrar.java:118`) deletes the Redis manifest key, removes the
id from the agents set, and calls `VectorIndexWriter.removeAgent()` to drop its vector entries. A
re-index of an existing agent likewise deletes its old vectors first (`VectorIndexWriter.index()`
calls `removeAgent()` at line 101) so a shrunk example set never leaves stale vectors behind.

---

## 7. The World-B invariant — why manifests exist

Conduit's product thesis is **World B**: the gateway is a *manifest interpreter with zero embedded
domain knowledge*. A new business domain is onboarded by adding manifest JSON plus a coverage-service
URL — **never** by changing gateway Java. Everything domain-shaped is data in the three manifests this
document specifies:

- **No domain literal in gateway source** — no domain/sub-domain names, no client/entity names, no
  `REL-`/`POL-`/`FND-` id patterns, no entity-type literals (`"relationship"`, `"fund"`), no
  user-facing copy. Every one of those appears in this document as a manifest field:
  domain/sub-domain names are `domain_id`/`sub_domain_id`; id patterns are `entity_types[].id_pattern`;
  entity literals are `entity_types[].key`/`resolve_type`; user copy is `messages`/`denial_messages`
  and the composed `domain_context`.
- **CLARIFY is deterministic** — decided over `required_context` in gateway code, never in a prompt
  (§5).
- **Entity context is map-based** — adding an entity type is an `entity_types` edit, not a new Java
  field; the extract/resolve/bind loop iterates `DomainManifestStore.entityTypes()` (§5).
- **LLM prompts are compiled from the manifest** — `IntentClassifier` and `AnswerSynthesizer` build
  their framing from the composed `domain_context` (§4), never from hardcoded strings.
- **RESOLVE is principal-agnostic; CHECK is the only gate** — enforced in `CoverageClient` (§4.4).
- **Zero fabricated ids** — `extract_as` holds the human reference; the coverage RESOLVE call
  produces the id; an unresolved reference clarifies via `messages.reference_not_found` (§5).

The deterministic gate for this is `scripts/world-b-check.sh`, which greps `gateway/src/main/java` for
domain coupling and must report `CRITICAL: 0`. If you find yourself typing any of the above into
gateway Java — stop; it belongs in a manifest.

---

## 8. Three fully-worked annotated examples

All three are schema-valid and are (lightly abridged) copies of real files in `registry/`. Comments
(`//`) are annotations for this document and are **not** legal JSON — strip them in a real file.

### 8.1 Agent manifest — an HTTP producer that starts a DAG

`registry/manifests/wealth-management/meridian.wealth.holdings.json`:

```jsonc
{
  "agent_id": "meridian.wealth.holdings",     // dotted id; the Redis key + routing tag
  "name": "Wealth Holdings",                   // reranker candidate label
  "description": "Returns the current portfolio positions and asset-class allocation for a given
                  wealth management relationship. Accepts relationship_id as the primary key.",
  "version": "1.0.0",                          // semver
  "provider": { "organization": "Meridian Demo Bank" },
  "domain": "wealth-management",               // must equal a domain_id
  "audience": "segment",                       // book-gated (coverage CHECK applies)
  "sub_domain": "private-banking",             // must equal a sub_domain_id
  "max_response_tokens": 2000,                 // gateway truncates before synthesis
  "protocol": "http",                          // → connection needs openapi_url + operation_id
  "connection": {
    "openapi_url": "http://wealth-http:8081/openapi.json",  // introspected at registration
    "operation_id": "get_holdings_holdings_get"             // op to invoke in that spec
  },
  "capabilities": { "streaming": false, "pushNotifications": false },
  "skills": [
    {
      "id": "get_holdings",
      "name": "get_holdings",
      "description": "Retrieve current holdings, positions, and asset allocation breakdown for a
                      wealth relationship.",
      "tags": ["holdings", "portfolio", "positions", "allocation", "wealth-management"],
      "examples": [                            // ≥3; embedded → HNSW routing corpus; capability-phrased,
        "current holdings for this relationship",  //   entity-FREE (no literal id baked in)
        "portfolio allocation for this client",
        "what is this account invested in",
        "position breakdown for the relationship",
        "portfolio summary for this client",
        "total portfolio value",
        "what does the portfolio hold"
      ],
      "inputModes": ["application/json"],
      "outputModes": ["application/json"]
    }
  ],
  "constraints": {
    "access_mode": "read",                     // read → is_mutating=0 (fannable)
    "data_classification": "confidential-pii", // per-segment classification gate
    "sla_timeout_ms": 30000                    // 100..60000
  },
  "output_schema": {                            // OPTIONAL fallback; lets select/figure contracts validate
    "type": "object",
    "properties": {
      "relationship_id": { "type": "string" }, "relationship_name": { "type": "string" },
      "positions": { "type": "array", "items": { "type": "object" } },
      "allocation_by_class": { "type": "array", "items": { "type": "object" } },
      "total_value": { "type": "number" }, "currency": { "type": "string" },
      "as_of_date": { "type": "string" }
    },
    "required": ["relationship_id", "relationship_name", "positions",
                 "allocation_by_class", "total_value", "currency", "as_of_date"]
  },
  "io": {                                       // OPTIONAL DAG contract
    "consumes": [
      { "entity": "relationship_id", "required": true }  // LEAF input from resolved entities (no edge)
    ],
    "produces": [
      { "name": "holdings", "type": "wealth.holdings" }  // downstream consumers bind from: "wealth.holdings"
    ]
  }
}
```

An **MCP** connection block instead of HTTP would read (from `meridian.servicing.nav.json`):
```jsonc
"protocol": "mcp",
"connection": {
  "server_url": "http://servicing-mcp:8082/mcp",  // Streamable HTTP /mcp endpoint
  "tool": "get_nav",                              // MCP tool to call
  "transport": "streamable"                       // default; "sse" = legacy, "stdio" also allowed
}
```
An **enterprise** agent (`meridian.hr.policy_qa.json`) sets `"audience": "enterprise"` — the segment
gate is skipped, its sub-domain is `resource_scoped: false`, and it declares no `io` (single-node).

### 8.2 Domain manifest

`registry/domains/wealth-management.json`:

```jsonc
{
  "domain_id": "wealth-management",             // kebab-case; matches agent.domain
  "display_name": "Wealth Management",
  "domain_context": "client financial data",    // composed into the assistant framing across all domains
  "clarify_style": "composed",                   // natural clarify wording (template is the fallback)
  "clarify_tone": "warm, concise, and professional — like a helpful colleague",
  "coverage": {                                  // required because private-banking is resource_scoped
    "discover_url": "${WEALTH_COVERAGE_URL}/coverage/{principal_id}",           // ${ENV} resolved at load
    "check_url":    "${WEALTH_COVERAGE_URL}/coverage/{principal_id}/resources/{id}",
    "resolve_url":  "${WEALTH_COVERAGE_URL}/entities/resolve",
    "cache_ttl_seconds": 30                       // default 30; 1..3600
  },
  "memory_compaction": {                          // REQUIRED; policy only — memory-service owns compaction
    "envelope_version": "context-envelope.v1",    // the only allowed value
    "must_preserve": ["relationship_id", "client_name", "period", "domain"],
    "can_drop": ["raw_agent_outputs", "routing_decisions"],
    "summary_policy": {
      "owner": "memory-service",                  // const — must stay outside the gateway
      "max_summary_tokens": 600,                  // 64..4000
      "refresh_after_turns": 8,                   // optional; 1..100
      "ledger_retention_days": 90,                // 1..3650
      "include_runtime_events": [                 // ≥1; each ^(gateway|memory)\.[a-z0-9_]+$
        "gateway.entity_resolved", "gateway.coverage_checked",
        "gateway.agent_completed", "gateway.response_completed"
      ],
      "redact_fields": ["raw_agent_outputs"]      // optional; default []
    }
  }
}
```

### 8.3 Sub-domain manifest

`registry/domains/wealth-management/private-banking.json`:

```jsonc
{
  "sub_domain_id": "private-banking",           // kebab-case; matches agent.sub_domain
  "display_name": "Private Banking",
  "parent_domain": "wealth-management",          // must equal a domain_id
  "resource_scoped": true,                        // book-gated → parent MUST have coverage.discover_url
  "entity_types": [                               // the input pipeline loops over these — no Java fields
    {
      "key": "relationship_id",                   // canonical resolved field (io.consumes.entity, required_context)
      "extract_as": "relationship_reference",     // where the LLM puts the raw human reference
      "kind": "resolvable",                       // name → id via coverage RESOLVE
      "display": "client relationship",           // used to compile prompt/clarify copy
      "id_pattern": "REL-\\d+",                   // deterministic id recognizer (compiled+cached at load)
      "resolve_type": "relationship",             // 'type' sent to RESOLVE
      "required": true
    },
    { "key": "fund_id", "extract_as": "fund_reference", "kind": "resolvable",
      "display": "fund or investment strategy", "id_pattern": "FND-\\w+",
      "resolve_type": "fund", "required": false },
    { "key": "period", "extract_as": "period", "kind": "literal",   // literal → used as-is
      "display": "reporting period", "id_pattern": "(Q[1-4]\\s+\\d{4}|YTD|QTD|MTD)",
      "required": false, "default": "QTD" },                        // default applied when absent
    { "key": "ticker_references", "extract_as": "ticker_references", "kind": "list",  // array of strings
      "display": "stock tickers", "id_pattern": "[A-Z]{2,5}", "required": false }
  ],
  "required_context": ["relationship_id"],        // DETERMINISTIC clarify gate: extracted ∩ this = ∅ → clarify
  "denial_messages": {                            // coverage reason code → copy; 'default' is the fallback
    "not-covered": "That client is not in your coverage.",
    "not-in-book": "That client is not in your coverage.",
    "unknown-resource": "That client is not in your coverage.",
    "coverage-transferred": "Coverage for that client has been transferred.",
    "relationship-closed": "That client relationship is no longer active.",
    "default": "Access denied for this client relationship."
  },
  "messages": {                                   // every user-facing string; {placeholders} filled at render
    "no_coverage": "You have no client relationships in your coverage.",
    "reference_not_found": "I could not find a client relationship matching \"{reference}\". Please
                            provide the relationship ID or a more specific name.",
    "needs_more_detail": "Sorry — none of the services I can reach are able to answer that as asked...",
    "specify_entity": "Please specify the client name or relationship ID.",
    "missing_entity_question": "Which client relationship are you asking about? Please provide the
                                relationship ID or client name to continue.",
    "followup_clarification": "Could you clarify what you'd like to know about {entity}? For example:
                               holdings, performance, settlements, or corporate actions.",
    "capability_unavailable": "I can't reach a service authorized to answer that part of your request..."
  },
  "clarification_schema": {                       // HOW to ask for a missing entity
    "relationship_id": { "question": "Which client relationship are you asking about?",
                         "options_source": "discover", "priority": 1 },   // offer DISCOVER list
    "period": { "question": "Which period would you like to review?",
                "options_source": "agent_derived", "default": "QTD", "priority": 2 }
  },
  "agents": [                                     // ≥1; builds the agentId → subDomainId reverse map
    "meridian.wealth.holdings", "meridian.wealth.performance",
    "meridian.wealth.risk_profile", "meridian.wealth.goal_planning"
  ]
}
```

---

## 9. Field-reference appendix

Legend: **R** = required, **O** = optional. "Default" is the schema default (blank = none).

### 9.1 Agent manifest (`agent-manifest.schema.json`)

| Field | R/O | Type | Default | Meaning |
|---|---|---|---|---|
| `agent_id` | R | string `^[a-z0-9]+(\.[a-z0-9_]+)+$` | | Stable dotted id; Redis key + routing tag |
| `name` | R | string (≥1) | | Display name; reranker label |
| `description` | R | string (≥1) | | What the agent does; reranker input |
| `version` | R | string `x.y.z` | | Semantic version |
| `provider.organization` | R | string (≥1) | | Owning org |
| `provider.contactEmail` | O | string | | Contact |
| `domain` | R | string (≥1) | | Domain id (validated vs loaded domains) |
| `audience` | R | enum `segment`\|`enterprise` | | `enterprise` skips the segment gate |
| `protocol` | R | enum `http`\|`mcp`\|`a2a` | | Which adapter invokes the agent |
| `connection.openapi_url` | R if http | string | | OpenAPI spec URL |
| `connection.operation_id` | R if http | string | | Operation to invoke |
| `connection.server_url` | R if mcp | string | | MCP endpoint |
| `connection.tool` | R if mcp | string | | MCP tool name |
| `connection.transport` | O (mcp) | enum `streamable`\|`sse`\|`stdio` | `streamable` | MCP transport |
| `connection.protocol_version` | O (mcp) | string | | Per-agent MCP version override |
| `connection.agent_card_url` | R if a2a | string | | A2A Agent Card URL |
| `capabilities.streaming` | R | boolean | | Streams? (`additionalProperties` allowed, e.g. `pushNotifications`) |
| `securitySchemes` | O | object | | OpenAPI-aligned auth |
| `skills[].id` | R | string (≥1) | | Skill id |
| `skills[].name` | R | string (≥1) | | Skill name |
| `skills[].description` | R | string (≥1) | | Skill description; reranker input |
| `skills[].tags` | R | array<string> (≥1) | | Faceted discovery keys |
| `skills[].examples` | R | array<string> (≥3) | | Embedded → HNSW routing corpus; entity-free |
| `skills[].inputModes` | O | array<string> | | e.g. `application/json` |
| `skills[].outputModes` | O | array<string> | | |
| `constraints.access_mode` | R | enum `read`\|`write` | | `write` → `is_mutating=1`; phase-1 = `read` |
| `constraints.data_classification` | R | enum `public`\|`internal`\|`confidential`\|`confidential-pii` | | Classification gate |
| `constraints.sla_timeout_ms` | R | int 100..60000 | | Per-agent deadline |
| `constraints.rate_limit.requests` | R if rate_limit | int ≥1 | | |
| `constraints.rate_limit.per_seconds` | R if rate_limit | int ≥1 | | |
| `sub_domain` | O | string | | Sub-domain id |
| `max_response_tokens` | O | int 1..100000 | | Truncate response before synthesis |
| `output_schema` | O | object | | Fallback output schema (else introspected) |
| `io` | O | object | | DAG dataflow contract (§3) |
| `io.consumes[].entity` | O (oneOf) | string (≥1) | | Sub-domain entity `key` (leaf, no edge) |
| `io.consumes[].from` | O (oneOf) | string (≥1) | | Upstream produced `type` (creates edge) |
| `io.consumes[].required` | O | boolean | `true` | Whether the input is mandatory |
| `io.consumes[].select` | O | string (CEL) | | Reshape producer output (only with `from`) |
| `io.produces[].name` | R | string (≥1) | | Blackboard key |
| `io.produces[].type` | R | string (≥1) | | Symbolic output type matched by `from` |
| `io.produces[].entities[].type` | R | string (≥1) | | Entity type for coverage filtering |
| `io.produces[].entities[].select` | R | string (CEL) | | Selects the id(s) |
| `io.produces[].figures[].label` | R | string (≥1) | | Label the answer must use |
| `io.produces[].figures[].path` | R | string (CEL) | | Raw scalar selector |
| `io.produces[].figures[].format` | R | enum (7, §3.2) | | Renderer/validator formatter id |
| `io.condition` | O | string (CEL→bool) | | `false` = skip node as not-applicable |
| `io.map.over` | R if map | string (CEL→array) | | Array to fan over |
| `io.map.item_select` | O | string (CEL) | | Per-item wire input |
| `io.map.max_items` | O | int ≥1 | | Per-node item cap (clamped globally) |
| `io.map.max_concurrency` | O | int ≥1 | | Per-node concurrency cap (clamped globally) |
| `input_schema` / `output_schema` / `resolved_connection` / `indexed` / `registered_at` | derived | — | | Written by the registry after introspection (not submitted) |

### 9.2 Domain manifest (`domain-manifest.schema.json`)

| Field | R/O | Type | Default | Meaning |
|---|---|---|---|---|
| `domain_id` | R | string `^[a-z0-9]+(-[a-z0-9]+)*$` | | Domain id (kebab) |
| `display_name` | R | string (≥1) | | Human label |
| `domain_context` | O | string (≥1) | | Neutral coverage phrase; composed into the assistant framing |
| `clarify_style` | O | enum `template`\|`composed` | `template` | Clarify wording policy (decision stays deterministic) |
| `clarify_tone` | O | string (≥1) | | Tone hint for the composer (only when `composed`) |
| `coverage.discover_url` | R if coverage | string (≥1) | | DISCOVER template; `{principal_id}` when scoped |
| `coverage.check_url` | R if coverage | string (≥1) | | CHECK template; `{principal_id}` + `{id}` |
| `coverage.resolve_url` | R if coverage | string (≥1) | | RESOLVE template |
| `coverage.cache_ttl_seconds` | O | int 1..3600 | `30` | Coverage cache TTL |
| `memory_compaction.envelope_version` | R | const `context-envelope.v1` | | Envelope version |
| `memory_compaction.must_preserve` | R | array<string> (≥1) | | Facts the memory service must keep |
| `memory_compaction.can_drop` | O | array<string> | `[]` | Fields safe to drop |
| `memory_compaction.summary_policy.owner` | R | const `memory-service` | | Compaction owner (outside gateway) |
| `…summary_policy.max_summary_tokens` | R | int 64..4000 | | Summary size |
| `…summary_policy.refresh_after_turns` | O | int 1..100 | | Refresh cadence |
| `…summary_policy.ledger_retention_days` | R | int 1..3650 | | Ledger retention |
| `…summary_policy.include_runtime_events` | R | array `^(gateway\|memory)\.[a-z0-9_]+$` (≥1) | | Events to include |
| `…summary_policy.redact_fields` | O | array<string> | `[]` | Fields to redact |

*Coverage as a whole is optional, but required when any child sub-domain is `resource_scoped: true`
(enforced at startup, §4).*

### 9.3 Sub-domain manifest (`sub-domain-manifest.schema.json`)

| Field | R/O | Type | Default | Meaning |
|---|---|---|---|---|
| `sub_domain_id` | R | string `^[a-z0-9]+(-[a-z0-9]+)*$` | | Sub-domain id (kebab) |
| `display_name` | R | string (≥1) | | Human label |
| `parent_domain` | R | string `^[a-z0-9]+(-[a-z0-9]+)*$` | | Owning domain id (validated) |
| `resource_scoped` | R | boolean | | Book-gated (coverage applies) vs enterprise knowledge |
| `entity_types[].key` | R | string (≥1) | | Canonical resolved field name |
| `entity_types[].extract_as` | R | string (≥1) | | Field the LLM extracts the human reference into |
| `entity_types[].kind` | R | enum `resolvable`\|`literal`\|`list` | | Resolution behavior |
| `entity_types[].display` | R | string (≥1) | | Human label for compiled copy |
| `entity_types[].id_pattern` | O | string (regex) | | Literal-id recognizer (compiled+cached) |
| `entity_types[].resolve_type` | O | string | | `type` sent to coverage RESOLVE (resolvable only) |
| `entity_types[].required` | R | boolean | | Presence expectation (gate uses `required_context`) |
| `entity_types[].default` | O | string\|number\|boolean | | Default for an absent literal |
| `required_context` | R | array<string> | | Deterministic CLARIFY gate keys (may be `[]`) |
| `agents` | R | array<string> `^[a-z0-9]+(\.[a-z0-9_]+)+$` (≥1) | | Member agent ids |
| `denial_messages` | O | map<string,string(≥1)> | | Coverage reason code → denial copy (`default` fallback) |
| `messages` | O | map<string,string(≥1)> | | User-facing copy; `{placeholder}` tokens filled at render |
| `clarification_schema.<key>.question` | R (if entry) | string (≥1) | | Clarify prompt |
| `clarification_schema.<key>.options_source` | R (if entry) | enum `discover`\|`agent_derived`\|`none`\|`principal_book` | | Where options come from |
| `clarification_schema.<key>.priority` | R (if entry) | int ≥1 | | Ask order (lower first) |
| `clarification_schema.<key>.default` | O | string\|number\|boolean | | Default answer |

---

*End of specification. The schemas at the paths listed at the top of this document, and the Java
records that deserialize them, are the ultimate authority; this prose is kept identical to them by
design.*
