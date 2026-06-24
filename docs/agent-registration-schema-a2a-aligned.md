# Agent Registration Schema — Grounded in the A2A Agent Card Standard

*What to capture so an agent is discoverable, based on the de-facto industry standard (the
A2A Agent Card) rather than an invented schema. Model your registry record as a **superset
of the Agent Card**: standards-compatible, future-proof for real A2A agents, and defensible
to the bank's architects.*

---

## The finding

The A2A Agent Card is the recognized standard for discoverable-agent metadata — a
JSON "business card" declaring identity, capabilities, skills, endpoint, and auth, which
registries index so clients can find agents by skill or tag. It is, by design, the agent
analog of an OpenAPI spec. Aligning to it means:

- your HTTP/MCP agents get a **derived** card (from OpenAPI / `tools/list`),
- your future A2A agents **self-publish** a real card you ingest directly,
- and discovery works two ways: **structured** (tags, skills, domain) + **semantic**
  (examples → embeddings).

---

## The canonical Agent Card fields (and what each is for in discovery)

| Field | Purpose | In your design |
|---|---|---|
| `name` | human-readable identity in UIs/logs | `display_name` |
| `description` | LLM/orchestrator parses it to route | `description` |
| `version` (semver) | compatibility selection | `version` |
| `url` | service endpoint | your `connection` / resolved endpoint |
| `provider {organization, url, contactEmail}` | ownership, contact | `owner`, `oncall` |
| `capabilities {streaming, pushNotifications, …}` | what interaction modes work | **add `streaming`** (does the agent stream?) |
| `securitySchemes` + `security` | OpenAPI-aligned auth declaration | your `auth` → model as `securitySchemes` |
| `defaultInputModes` / `defaultOutputModes` | MIME types accepted/produced | default `["application/json"]` |
| `skills[]` | the unit of discovery (see below) | your `skills[]` |
| `constraints` (rate limits, spend caps, **scope-of-authority**) | autonomous vs human-approval | your `is_mutating` + `sla_timeout_ms` + `rate_limit` → model here |
| `lastUpdated` | freshness for caching/versioning | your `registered_at` |

### The skill object (the discovery-critical part)

| Skill field | Purpose | In your design |
|---|---|---|
| `id` | stable skill identifier | **add** (distinct from name) |
| `name` | display | `name` |
| `description` | LLM reads to decide routing | `description` |
| `tags[]` | **structured/faceted discovery** — registries query by tag | **add — you're missing this** |
| `examples[]` | representative prompts | your `example_prompts` (exact match) |
| `inputModes` / `outputModes` | per-skill MIME override | default text/json |
| `input_schema` / `output_schema` | invocation contract | **derived** (OpenAPI / `tools/list`) |

---

## The two upgrades this makes to your schema

1. **Add `tags` to skills.** Example prompts drive *semantic* routing; tags drive
   *structured* discovery (filter by capability/category). Registries are designed to be
   queried by skill **or** tag, so capturing both gives you faceted + semantic discovery
   instead of semantic alone.

2. **Model governance as a `constraints` / scope-of-authority block**, not a lone flag.
   The standard already has the concept "what the agent may do autonomously vs what needs
   human approval" — which is precisely your Phase-1 read-only vs Phase-2 human-in-the-loop.
   Folding `is_mutating`, rate limits, and data classification into a `constraints` block
   aligns you with the standard and tells the entitlement layer + planner exactly what's
   safe.

---

## Recommended capture schema (Agent-Card-aligned)

What the **domain team submits** (still tiny — schemas derived):

```jsonc
{
  "agent_id": "acme.wealth.holdings",
  "name": "Portfolio Holdings",
  "description": "Current holdings and allocation for a relationship.",
  "version": "1.0.0",
  "provider": { "organization": "wealth-platform", "contactEmail": "wealth-oncall@corp" },
  "domain": "wealth-management",

  "protocol": "http",                                   // gateway extension (http|mcp|a2a)
  "connection": { "openapi_url": "...", "operation_id": "getHoldings" },

  "capabilities": { "streaming": false },

  "securitySchemes": {                                   // OpenAPI-aligned
    "corpOAuth": { "type": "oauth2", "audience": "wealth-api" }
  },

  "skills": [{
    "id": "get_holdings",
    "name": "Get Holdings",
    "description": "Fetch current positions and allocation for a relationship",
    "tags": ["holdings","allocation","portfolio","positions"],   // structured discovery
    "examples": [                                                // semantic discovery
      "What are the current holdings for the Whitman relationship?",
      "Show me the portfolio allocation for this client",
      "What is this account invested in right now?",
      "Give me the position breakdown for the relationship"
    ]
  }],

  "constraints": {                                       // scope-of-authority
    "is_mutating": false,
    "data_classification": "confidential-pii",
    "sla_timeout_ms": 2500,
    "rate_limit": { "requests": 50, "per_seconds": 1 }
  }
}
```

What the **gateway derives and adds** (stored record = submission + these):
- `skills[].input_schema` / `output_schema` — from OpenAPI operation or MCP `tools/list`.
- `resolved_connection` — base URL + method + path (HTTP) or session + tool (MCP).
- `defaultInputModes` / `defaultOutputModes` — defaulted.
- intent vectors — `examples` embedded.
- `lastUpdated` / `indexed`.

---

## The discovery model (two mechanisms, both standards-backed)

- **Structured / faceted:** filter candidates by `domain`, `tags`, and the
  `constraints.is_mutating` flag — fast tag/attribute filtering at search time.
- **Semantic:** embed `skills[].examples` → vector search for intent match with a
  confidence floor.

Your resolver already does the semantic half; the standard's `tags` give you the
structured half for free, and the two together are exactly how A2A registries do
capability-based discovery.

---

## Enterprise / bank-relevant extras the standard already accounts for

- **Authenticated / extended cards** — serve a minimal public card and a fuller card
  behind auth for sensitive agents. Maps to your `data_classification` + entitlement layer.
- **Scope-of-authority** in `constraints` — the read-only / human-approval boundary, now a
  first-class, declared, auditable field rather than an implicit rule.
- **Semantic versioning + `lastUpdated`** — MAJOR/MINOR/PATCH discipline so consumers know
  when a change is breaking; supports caching via ETag.
- **Card signing / canonicalization** *(future)* — A2A supports signed cards for provenance;
  worth noting for a bank's trust review, not needed for the demo.

---

## Bottom line

Capture the **A2A Agent Card field set** as your registry record, add your three gateway
extensions (`protocol` + `connection` for non-A2A agents, the derived schemas, and the
`constraints` governance block), and you get: standards alignment, drop-in future A2A
agents, faceted + semantic discovery, and an entitlement-ready scope-of-authority
declaration — all from one schema.
