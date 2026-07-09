# Onboarding an Agent into Conduit

> Audience: a domain team that wants to register a new agent (or a whole new business domain) so the Conduit
> gateway can route to it, compose it into multi-step plans, and govern access — **without changing any
> gateway code.**

## 0. The one idea
The gateway is an **empty engine that reads a catalogue.** It carries **zero** knowledge of any specific
agent or domain. Everything it knows, it reads from **manifests** (JSON) at startup and learns by
**introspecting your service**. Onboarding = add a manifest + stand up your service. You never touch the
engine. (This invariant is enforced on every build by `scripts/world-b-check.sh` — CRITICAL must be 0.)

## 1. Prerequisites
- Your agent runs as a reachable service, either:
  - **HTTP** with an OpenAPI (Swagger) spec, or
  - **MCP** (FastMCP) exposing a tool via `tools/list`.
- It **verifies the caller's JWT and fails closed** (the gateway forwards the end-user's token to every hop —
  see §7; a service that trusts the caller blindly will not pass security review).
- If your data is client-scoped, a small **coverage service** answering "is this client in this user's book?"

## 2. The five steps (overview)
1. Write the **agent manifest** (`registry/manifests/<domain>/<agent_id>.json`).
2. Stand up your **service** (HTTP OpenAPI or MCP tool) so the gateway can introspect its wire schemas.
3. If client-scoped: stand up (or point at) a **coverage service** (your book-of-business).
4. If a **new domain**: add the **domain + sub-domain manifests** (`registry/domains/…`).
5. If structural authz needs it: add the **segment→domain mapping** to the **Cerbos policy** (config, not code).
Then boot the gateway and verify (§10).

## 3. Step 1 — The agent manifest (field-by-field)
One file per agent, validated against `registry/agent-manifest.schema.json`. Required + key optional fields:

| Field | Meaning |
|---|---|
| `agent_id` | Stable dotted id, `^[a-z0-9]+(\.[a-z0-9_]+)+$` — e.g. `meridian.wealth.holdings`. First segment = tenant/namespace. |
| `name`, `description` | Human-readable. |
| `version` | Semver (`1.0.0`). |
| `provider` | `{ organization, contactEmail? }`. |
| `domain` | The business domain — must resolve to a loaded domain manifest (not a hardcoded list). |
| `sub_domain` | The sub-domain within it (resolves to a loaded sub-domain manifest). |
| `audience` | `segment` (gated by business-segment membership + classification) or `enterprise` (any authenticated user — e.g. HR policy Q&A). |
| `protocol` | `http`, `mcp`, or `a2a`. |
| `connection` | HOW to reach it. HTTP: `{ openapi_url, operation_id }`. MCP: `{ server_url, tool, transport? }`. A2A: `{ agent_card_url }`. **This is the only "how to call me" info — the gateway derives the data shapes itself.** |
| `capabilities` | `{ streaming: bool, … }`. |
| `constraints` | `{ access_mode: read\|write, data_classification: public\|internal\|confidential\|confidential-pii, sla_timeout_ms: 100–60000, rate_limit? }`. Phase-1 agents are `read`. `data_classification` drives the **clearance gate**. |
| `skills[]` | Each `{ id, name, description, tags[], examples[≥3] }`. **`examples` are the routing fuel** — plain-English sample questions, embedded so the router matches user questions to your agent by *meaning*. Write real, varied phrasings. |
| `io` (optional) | The **dataflow contract** that makes your agent composable into multi-step plans — see §8. |
| `output_schema` (optional) | A JSON-Schema **fallback** for your output, used only if the protocol can't be introspected. Prefer real introspection. |

**Do NOT** put input/output wire schemas in the manifest — the gateway **derives** those by introspection.

## 4. Step 2 — Stand up your service (so it can be introspected)
- **HTTP:** expose an OpenAPI doc at `openapi_url`; the `operation_id` in `connection` must exist in it, with a
  request body schema and a 2xx `application/json` **response** schema (`$ref`s resolved). The gateway reads
  both to derive your input AND output shapes.
- **MCP:** your tool must appear in `tools/list` with an input schema and, ideally, an **`outputSchema`**
  (structured output). If your MCP output can't be typed, declare `output_schema` in the manifest as the
  fallback — otherwise your edges can't be validated (§8/§9).

## 5. Step 3 — Coverage service (if your data is client-scoped)
Book-of-business lives at the **coverage layer, never in the gateway.** Your coverage service answers, for a
principal, "which entities (e.g. relationships) may they access?" (`discover`) and "is entity X in their book?"
(`check`). **RESOLVE is principal-agnostic; the coverage CHECK is the only gate** — resolution searches all
entities; entitlement is decided solely by the book. The coverage service is the **single source of truth**
for the book.

## 6. Step 4 — Domain / sub-domain manifests (only if a new domain)
- `registry/domains/<domain>.json` and `registry/domains/<domain>/<sub-domain>.json` declare:
  - `entity_types` — the entities the domain deals in (key, `id_pattern`, display, `required`).
  - `resource_scoped: true` + `required_context: ["<entity>"]` if queries are scoped to a specific client
    (this is what makes the coverage gate apply).
  - the **coverage service URLs** for the domain.
  - `agents[]` — the member agent ids of the sub-domain.

## 7. Step 5 — Authorization (config, not code)
Three independent gates protect every request; you configure them as data:
1. **Structural (Cerbos):** segment→domain mapping in `infra/cerbos/policies/…` — can this user's segment
   reach this domain at all.
2. **Classification:** your manifest `constraints.data_classification` vs the user's per-segment clearance.
3. **Coverage:** your coverage service — is this specific client in the user's book.
Plus **per-hop identity**: the gateway forwards the end-user's JWT to your service; **verify it and fail
closed** (correct JWKS, check signature/issuer/audience/expiry — no fail-open fallbacks).

## 8. Making your agent composable — the `io` contract
This is what lets the gateway **build multi-step plans at runtime** including your agent. All optional; absent
`io` = a single-node (flat) agent.
- **`io.produces: [{ name, type }]`** — a symbolic output `type` other agents can depend on, published under
  `name` on the blackboard.
- **`io.consumes: [...]`** — each item is EITHER:
  - `{ entity: "<sub-domain entity key>" }` — a leaf input satisfied by entity resolution (e.g. `relationship_id`), OR
  - `{ from: "<a produced type>", select: "<JMESPath>" }` — depend on another agent's output; `select`
    **projects** that output into exactly what you need. This creates a producer→consumer DAG edge.
- **`io.condition: "<JMESPath boolean>"`** — run this node **only if** the predicate over its merged input is
  true; otherwise it's cleanly skipped (branching).
- **`io.map: { over, item_select?, max_items?, max_concurrency? }`** — run this node **once per item** of an
  upstream collection, bounded and aggregated (iteration).
Types are matched by **string equality**; keep them namespaced (`domain.thing`). The gateway validates your
`select`/`condition`/`map` against the introspected schemas **at boot** — a mapping referencing a field your
producer doesn't emit **fails to load**, with a precise error.

## 9. What the gateway does for you automatically at boot (no code change)
1. **Validates** your manifest against the schema.
2. **Introspects** your service (OpenAPI / `tools/list`) → derives your input AND output schemas.
3. **Embeds** your `skills[].examples` (MiniLM) into the vector index → your agent becomes routable by meaning.
4. **Wires** your `io` contract into the DAG resolver (your `produces.type` → others' `consumes.from`) and
   **boot-validates** every `select`/`condition`/`map`.
5. Registers you: `Registry bootstrap complete: N loaded, 0 failed`.

## 10. Verify (the checklist)
- Manifest validates against `agent-manifest.schema.json`.
- `scripts/world-b-check.sh` → CRITICAL 0 (you added no domain knowledge to the gateway).
- Boot log: `N loaded, 0 failed` and your `agent_id` is present; `select validation: … 0 UNVALIDATED`.
- Ask a question the router should route to you (matching your examples) → it reaches your agent.
- If entitlement-gated: an out-of-book user is denied; an in-book user is served.
- If composed: the `plan_graph` shows your node wired to its producers/consumers.

## 11. Common pitfalls
- **Weak `examples`** → the router won't find you. Write ≥3 varied, realistic phrasings (not the literal test
  strings). Negation ("…*not* X…") is a known routing weakness.
- **No introspectable output schema** (esp. MCP) → your `io` edges can't be validated; add `output_schema`.
- **Putting book/client data in the manifest or gateway** → World-B violation. Book lives in the coverage
  service; domain vocabulary lives in the domain manifest.
- **A `select` referencing a field you don't emit** → boot rejects it. Fix the select, not the validator.
- **An agent that trusts the caller** → fails security review. Verify the forwarded JWT, fail closed.

## 12. Worked example (a composable HTTP analytics agent)
```json
{
  "agent_id": "meridian.wealth.concentration",
  "name": "Wealth Concentration Analysis",
  "description": "Computes single-issuer and asset-class concentration and flags breaches.",
  "version": "1.0.0",
  "provider": { "organization": "Meridian Demo Bank" },
  "domain": "wealth-management",
  "sub_domain": "private-banking",
  "audience": "segment",
  "protocol": "http",
  "connection": { "openapi_url": "http://wealth:8000/openapi.json", "operation_id": "concentration" },
  "capabilities": { "streaming": false },
  "constraints": { "access_mode": "read", "data_classification": "confidential-pii", "sla_timeout_ms": 8000 },
  "skills": [{
    "id": "concentration", "name": "Concentration analysis",
    "description": "Is a portfolio over-concentrated?",
    "tags": ["concentration", "risk", "HHI"],
    "examples": [
      "Is the Whitman Family Office portfolio over-concentrated?",
      "Which single issuers are driving concentration risk?",
      "Are any positions above the single-name limit?"
    ]
  }],
  "io": {
    "consumes": [{ "from": "wealth.holdings", "select": "{ positions: positions, total_value: total_value }" }],
    "produces": [{ "name": "concentration_analysis", "type": "wealth.concentration_analysis" }]
  }
}
```
This agent: routes on the three example questions; depends on `wealth.holdings`' output (projected by `select`);
publishes `wealth.concentration_analysis` for downstream nodes (e.g. a conditional review step); is gated by
segment + `confidential-pii` clearance + the wealth coverage book. **Zero gateway code was written to add it.**

---
*Extending Conduit is a config exercise, by design. If you find yourself needing a gateway code change to
onboard an agent, that's a bug in the manifest model — raise it, don't work around it.*
