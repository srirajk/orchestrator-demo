# Agent Registry — Demo Spec

*The spine of the demo: the visible "cause" that resolution turns into effect. With the
introspection decision, registration captures **less** from humans, not more — schemas are
derived from each agent's own spec.*

---

## The one idea: three distinct things

| | What | Who |
|---|---|---|
| **Submitted** | minimal metadata + a pointer to the agent's self-describing spec + example prompts + governance flags | the domain team (tiny) |
| **Derived** | `input_schema`, `output_schema`, resolved connection, intent vectors | the gateway, at registration |
| **Stored** | submitted + derived, in Redis | the registry |

The domain team hand-writes almost nothing. The schemas — the part that used to be the
chore — come from OpenAPI (HTTP) or `tools/list` (MCP). The **only** thing a human must
provide that no spec contains is **example prompts** (routing anchors) and the
**governance flags** (`is_mutating`, classification).

---

## What the team submits (the registration contract)

```jsonc
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["agent_id","domain","display_name","description","protocol",
               "connection","skills","is_mutating","data_classification",
               "sla_timeout_ms","owner","version"],
  "properties": {
    "agent_id":   { "type":"string", "pattern":"^[a-z0-9]+([.-][a-z0-9]+)*$" },
    "domain":     { "enum":["wealth-management","asset-servicing"] },
    "display_name":{ "type":"string" },
    "description":{ "type":"string", "minLength":10 },
    "protocol":   { "enum":["http","mcp"] },                 // a2a deferred
    "connection": { "type":"object" },                       // conditional, see below
    "skills": {
      "type":"array","minItems":1,
      "items":{
        "type":"object","additionalProperties":false,
        "required":["name","description","example_prompts"],
        "properties":{
          "name":{"type":"string","pattern":"^[a-z0-9_]+$"},
          "description":{"type":"string","minLength":10},
          "example_prompts":{"type":"array","minItems":3,"items":{"type":"string"}}
        }
      }
    },
    "is_mutating":        { "type":"boolean" },
    "data_classification":{ "enum":["public","internal","confidential","confidential-pii","restricted"] },
    "sla_timeout_ms":     { "type":"integer","minimum":1,"maximum":60000 },
    "owner":   { "type":"string" },
    "oncall":  { "type":"string" },
    "version": { "type":"string","pattern":"^\\d+\\.\\d+\\.\\d+$" }
    // NOTE: input_schema / output_schema are intentionally ABSENT — they are derived.
  },
  "allOf": [
    { "if":   { "properties": { "protocol": { "const":"http" } } },
      "then": { "properties": { "connection": {
        "required":["openapi_url","operation_id"],
        "properties":{ "openapi_url":{"type":"string","format":"uri"},
                       "operation_id":{"type":"string"} } } } } },
    { "if":   { "properties": { "protocol": { "const":"mcp" } } },
      "then": { "properties": { "connection": {
        "required":["server_url","tool_name"],
        "properties":{ "server_url":{"type":"string","format":"uri"},
                       "tool_name":{"type":"string"} } } } } }
  ]
}
```

### Worked submission — HTTP (Wealth, OpenAPI-described)
```jsonc
{
  "agent_id":"acme.wealth.holdings",
  "domain":"wealth-management",
  "display_name":"Portfolio Holdings",
  "description":"Current holdings and allocation for a relationship.",
  "protocol":"http",
  "connection":{ "openapi_url":"http://mock-agents:8081/wealth/openapi.json",
                 "operation_id":"getHoldings" },
  "skills":[{ "name":"get_holdings",
              "description":"Fetch current positions and allocation for a relationship",
              "example_prompts":[
                "What are the current holdings for the Whitman relationship?",
                "Show me the portfolio allocation for this client",
                "What is this account invested in right now?",
                "Give me the position breakdown for the relationship"] }],
  "is_mutating":false, "data_classification":"confidential-pii",
  "sla_timeout_ms":2500, "owner":"wealth-platform", "version":"1.0.0"
}
```

### Worked submission — MCP (Asset Servicing, tools-described)
```jsonc
{
  "agent_id":"acme.servicing.settlement_status",
  "domain":"asset-servicing",
  "display_name":"Settlement Status",
  "description":"Pending and failed settlements for a relationship's accounts.",
  "protocol":"mcp",
  "connection":{ "server_url":"http://mock-agents:8082/sse",
                 "tool_name":"get_settlements" },
  "skills":[{ "name":"get_settlements",
              "description":"List pending and failed trade settlements for the relationship",
              "example_prompts":[
                "Are there any pending settlements on this account?",
                "Show me failed trades for the relationship",
                "What's the settlement status for this client?",
                "Any unsettled trades I should know about?"] }],
  "is_mutating":false, "data_classification":"confidential",
  "sla_timeout_ms":2500, "owner":"servicing-platform", "version":"1.0.0"
}
```

Note how small both are — and that neither carries an input/output schema.

---

## What the gateway derives (introspection)

- **HTTP:** fetch `openapi_url`, find `operation_id` → `input_schema` from the operation's
  parameters + requestBody; `output_schema` from the success response; resolved connection
  (base URL from the spec's `servers`, plus method + path) from the operation.
- **MCP:** open the session, `tools/list`, find `tool_name` → `input_schema` from the
  tool's `inputSchema` (already JSON Schema). Output may be untyped; store best-effort.

Then embed each `example_prompt` → intent vectors.

---

## The full stored manifest (submitted + derived)

```jsonc
{
  // ...everything submitted above, plus:
  "input_schema":  { "type":"object","required":["relationship_id"],
                     "properties":{ "relationship_id":{"type":"string"} } },   // derived
  "output_schema": { "type":"object",
                     "properties":{ "positions":{"type":"array"},
                                    "allocation_by_class":{"type":"array"} } },// derived
  "resolved_connection": { "base_url":"http://mock-agents:8081",
                           "method":"POST", "path":"/wealth/holdings" },        // derived
  "indexed": true,
  "registered_at": "2026-06-23T14:00:00Z"
}
```

---

## Registration flow (the gate)

1. **Submit** → `POST /admin/agents`.
2. **Validate** the submission against the contract above (reject incomplete / wrong
   connection-for-protocol).
3. **Introspect** the spec (OpenAPI fetch / MCP `tools/list`) → derive schemas + resolved
   connection. **Fail if** unreachable, or operation/tool not found.
4. **Health check** — synthetic ping / no-op call within `sla_timeout_ms`.
5. **Govern** — `is_mutating` present; Phase-1 read-only allowed; injection scan on
   descriptions + example prompts.
6. **Persist** — store the full manifest as RedisJSON; embed example prompts → upsert
   vectors.
7. **Mark routable** → `201`, returning the **derived schemas** so the team sees exactly
   what was captured.

Same pipeline for update (`PUT`) and deregister (`DELETE`).

---

## Storage model (Redis Stack)

- `agent:{agent_id}` — full manifest as **RedisJSON**.
- `registry:agents` — set of agent_ids (for listing).
- **`intent_idx`** — HNSW vector index (DIM matching the embed model, COSINE) over example-
  prompt vectors, each with payload tags `{agent_id, skill, domain, is_mutating}`. The
  tags are what let routing hard-filter (domain, `is_mutating==false`) at search time.
- Entitlement attributes for Cerbos (`domain`, `is_mutating`, `data_classification`) are
  read from the manifest and passed into the check request at runtime — not stored
  separately.

---

## Registration API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/admin/agents` | register (runs the full gate) |
| `GET` | `/admin/agents` | list registered agents |
| `GET` | `/admin/agents/{id}` | fetch one (with derived schemas) |
| `PUT` | `/admin/agents/{id}` | update + re-introspect |
| `DELETE` | `/admin/agents/{id}` | deregister (remove vectors + manifest) |

---

## Bootstrap

The 9 manifests live as files in the repo and load through the **same pipeline** at gateway
startup — so the bootstrap path and the live-registration path are identical code. An
invalid manifest is logged and skipped, not fatal.

---

## The live-registration demo beat

This is the registry's moment on stage:

1. Show the registry has 9 agents.
2. **Register a 10th agent live** — `POST /admin/agents` with a tiny submission; the
   gateway introspects its spec, derives the schema, indexes it, returns the derived
   schema. No redeploy.
3. Ask a prompt that routes to the new agent → it answers.
4. Talk track: *"That's how a domain team onboards — they point us at their existing
   OpenAPI or MCP spec, give a few example prompts, and walk away. We derive the rest. Zero
   gateway code, zero glue."*

That single beat proves the entire "protocol-agnostic, zero-touch onboarding" thesis in
under a minute — the thing the old Copilot could never do.
