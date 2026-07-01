# Domain Onboarding Standard
# Conduit AI Gateway

> **Status:** Locked — agreed 2026-06-28.
> This document defines what every domain team must provide before their agents
> can be loaded into the gateway. The gateway executes what is declared here.
> It understands nothing beyond what is written in these manifests.

---

## Core Principles

**1. The gateway is a manifest interpreter. It owns zero domain knowledge.**
Every question it asks, every service it calls, every check it performs comes
from a manifest declaration. If it is not in the manifest, the gateway does
not do it.

**2. Business declares. Gateway executes.**
Domain teams write the manifests. The gateway reads and executes them.
No gateway code changes are needed to onboard a new domain.

**3. The gateway always posts questions back to the user for disambiguation.**
It never guesses. If required context is missing, it asks.
If an entity reference is ambiguous, it asks. It does not proceed until
it has a confirmed, authorized entity.

**4. The coverage service is the system of record. The gateway is the enforcer.**
The domain team owns the coverage service. It answers two questions:
DISCOVER (what can this user see?) and CHECK (can this user see this?).
The gateway calls both generically using URLs declared in the manifest.

**5. Fail-closed. Always.**
Coverage service unreachable → the gateway does not degrade gracefully.
It tells the user it cannot proceed and stops. No stale data. No guessing.

**6. List is not authorization.**
DISCOVER returns options for display. CHECK is the gate. Both must happen.
A user picking from a DISCOVER list does not skip the CHECK.

**7. Context is continuity. Never authorization proof.**
Resolved entity IDs are stored in session/context envelopes for multi-turn continuity.
Authorization is re-checked live (within TTL) on every turn. Session or memory state
is never used as proof of access.

---

## What a Domain Team Must Provide

### Prerequisite Checklist

Before the gateway can load your domain, you must provide all of the following.
The gateway will refuse to start if any required field is missing.

```
□ 1. Domain manifest           (registry/domains/{domain-id}.json)
□ 2. Sub-domain manifest(s)    (registry/domains/{domain-id}/{sub-domain-id}.json)
□ 3. Agent manifest(s)         (registry/manifests/{agent-id}.json)
□ 4. Coverage service          (running, reachable, implementing the contract below)
□ 5. Agent service             (running, reachable — HTTP or MCP)
```

If your sub-domain is role-based (`resource_scoped: false`), item 4 is not required.

---

## Manifest Declarations

### Level 1 — Domain Manifest

**File:** `registry/domains/{domain-id}.json`

**Who writes it:** Domain team.
**When:** Once per business domain. Rarely changes.

```json
{
  "domain_id": "wealth-management",
  "display_name": "Wealth Management",

  "coverage": {
    "discover_url":        "${WEALTH_COVERAGE_URL}/coverage/{principal_id}",
    "check_url":           "${WEALTH_COVERAGE_URL}/coverage/{principal_id}/resources/{id}",
    "resolve_url":         "${WEALTH_COVERAGE_URL}/entities/resolve",
    "cache_ttl_seconds":   30
  },

  "memory_compaction": {
    "envelope_version": "context-envelope.v1",
    "must_preserve": ["relationship_id", "client_name", "period", "domain"],
    "can_drop": ["raw_agent_outputs", "routing_decisions"],
    "summary_policy": {
      "owner": "memory-service",
      "max_summary_tokens": 600,
      "refresh_after_turns": 8,
      "ledger_retention_days": 90,
      "include_runtime_events": [
        "gateway.entity_resolved",
        "gateway.coverage_checked",
        "gateway.agent_completed",
        "gateway.response_completed"
      ],
      "redact_fields": ["raw_agent_outputs"]
    }
  }
}
```

**Field reference:**

| Field | Required | Description |
|---|---|---|
| `domain_id` | YES | Unique identifier. Matches agent manifest `domain` field. |
| `display_name` | YES | Human-readable name shown in glass-box. |
| `coverage.discover_url` | if resource_scoped sub-domains exist | Gateway calls this to get the user's accessible resources. Must include `{principal_id}` template. |
| `coverage.check_url` | if resource_scoped sub-domains exist | Gateway calls this to verify access to a specific resource. Must include `{principal_id}` and `{id}` templates. |
| `coverage.resolve_url` | if resource_scoped sub-domains exist | Gateway calls this to resolve a human reference to a canonical ID. |
| `coverage.cache_ttl_seconds` | NO | How long a CHECK result is cached per principal+tenant+entity. Default: 30. |
| `memory_compaction.envelope_version` | YES | Context envelope schema version the memory service returns to the gateway. |
| `memory_compaction.must_preserve` | YES | Fields the memory service must preserve in governed summaries/envelopes. |
| `memory_compaction.can_drop` | NO | Fields safe for the memory service to omit from summaries. |
| `memory_compaction.summary_policy.owner` | YES | Must be `memory-service`; the gateway does not own compaction or summary prompts. |
| `memory_compaction.summary_policy.include_runtime_events` | YES | Gateway event types eligible as summary inputs. |
| `memory_compaction.summary_policy.ledger_retention_days` | YES | Retention target for the compaction ledger. |

**No coverage block needed for role-based domains (e.g. asset-servicing).**

---

### Level 2 — Sub-Domain Manifest

**File:** `registry/domains/{domain-id}/{sub-domain-id}.json`

**Who writes it:** Domain team.
**When:** Once per sub-domain. Changes when the context model or agent list changes.

```json
{
  "sub_domain_id":   "private-banking",
  "parent_domain":   "wealth-management",
  "display_name":    "Private Banking",

  "resource_scoped": true,

  "required_context": ["relationship_id"],

  "clarification_schema": {
    "relationship_id": {
      "question":       "Which client relationship are you asking about?",
      "options_source": "discover",
      "priority":       1
    },
    "period": {
      "question":       "Which period would you like to review?",
      "options_source": "agent_derived",
      "default":        "most recent quarter",
      "priority":       2
    }
  },

  "agents": [
    "acme.wealth.holdings",
    "acme.wealth.performance",
    "acme.wealth.risk_profile"
  ]
}
```

**Role-based sub-domain (no coverage needed):**

```json
{
  "sub_domain_id":   "custody-operations",
  "parent_domain":   "asset-servicing",
  "display_name":    "Custody Operations",

  "resource_scoped": false,

  "required_context": [],

  "agents": [
    "acme.servicing.custody_positions",
    "acme.servicing.settlement_status"
  ]
}
```

**Field reference:**

| Field | Required | Description |
|---|---|---|
| `sub_domain_id` | YES | Unique within the parent domain. Matches agent manifest `sub_domain` field. |
| `parent_domain` | YES | Must resolve to a loaded domain manifest. Validated at boot — FAIL FAST. |
| `resource_scoped` | YES | `true` = DISCOVER + CHECK required before fan-out. `false` = role gate only. |
| `required_context` | YES | List of session fields that must be present before the gateway fans out. Missing → clarification. |
| `clarification_schema` | if required_context non-empty | Per-field: question text, options_source, priority order. |
| `clarification_schema.*.question` | YES if field listed | Exact text the gateway posts to the user. Written by domain team. |
| `clarification_schema.*.options_source` | YES if field listed | `discover` (call coverage.discover_url) \| `agent_derived` (agent signals options after fan-out) \| `none` (open text). |
| `clarification_schema.*.priority` | YES if multiple fields | Lower number asked first. Higher-priority missing field blocks lower-priority ones. One question per turn. |
| `clarification_schema.*.default` | NO | Value used if user skips. |
| `agents` | YES | Agent IDs belonging to this sub-domain. Validated at boot. |

---

### Level 3 — Agent Manifest

**File:** `registry/manifests/{agent-id}.json`

**Who writes it:** Domain team (agent owner).
**When:** Once per agent. Changes when the agent's interface or capabilities change.

```json
{
  "agent_id":    "acme.wealth.holdings",
  "domain":      "wealth-management",
  "sub_domain":  "private-banking",

  "protocol":    "http",
  "connection":  { "url": "${WEALTH_HTTP_URL}/holdings" },

  "capabilities":     ["holdings", "allocation", "portfolio"],
  "example_prompts":  [
    "what are the holdings for this client",
    "show me the portfolio allocation",
    "what is the asset breakdown"
  ],

  "max_response_tokens": 2000
}
```

**MCP agent variant:**

```json
{
  "agent_id":    "acme.servicing.settlement_status",
  "domain":      "asset-servicing",
  "sub_domain":  "custody-operations",

  "protocol":    "mcp",
  "connection":  { "url": "${SERVICING_MCP_URL}/sse" },
  "tool_name":   "get_settlement_status",

  "capabilities":    ["settlements", "settlement-status", "post-trade"],
  "example_prompts": [
    "what is the settlement status",
    "show me pending settlements",
    "are there any failed settlements"
  ],

  "max_response_tokens": 1500
}
```

**Field reference:**

| Field | Required | Description |
|---|---|---|
| `agent_id` | YES | Globally unique. |
| `domain` | YES | Must resolve to a loaded domain manifest. FAIL FAST. |
| `sub_domain` | YES | Must resolve to a loaded sub-domain under `domain`. FAIL FAST. |
| `protocol` | YES | `http` or `mcp`. |
| `connection.url` | YES | Agent endpoint. Environment variable substitution supported. |
| `tool_name` | if MCP | MCP tool name to invoke. |
| `capabilities` | YES | Keywords used for routing. Used in BM25 + vector index. |
| `example_prompts` | YES | Representative prompts. Used to build the vector (HNSW) index. More = better routing. |
| `max_response_tokens` | YES | Gateway truncates agent response to this before synthesis. Agent is never told. |

---

## Coverage Service Contract

The domain team must implement this contract exactly.
The gateway calls it generically — it does not know what the response means.

### DISCOVER

```
GET {discover_url}
    e.g. GET /coverage/rm_jane

Headers:
  Authorization: Bearer {gateway-service-token}
  X-Tenant-Id: {tenant_id from validated JWT}

Response 200:
[
  { "id": "REL-00042", "label": "Whitman Family Office", "sub_domain": "private-banking" },
  { "id": "REL-00099", "label": "Calderon Trust",        "sub_domain": "private-banking" }
]

Response 200 (empty — user has no resources):
[]

Response 503 / timeout:
  Gateway fails closed. Posts to user:
  "I'm unable to process your request right now — please try again shortly."
```

**Rules:**
- Return ONLY resources this principal is authorized to see. The list IS the filter.
- Tag each resource with `sub_domain` so the gateway knows which routing bucket it belongs to.
- If a resource spans multiple sub-domains, return one entry per sub-domain with the same `id`.
- The gateway groups by `id` for display (shows label once), routes to all matching sub-domains.

### CHECK

```
GET {check_url}
    e.g. GET /coverage/rm_jane/resources/REL-00042

Headers:
  Authorization: Bearer {gateway-service-token}
  X-Tenant-Id: {tenant_id}

Response 200 — allowed:
{ "allowed": true }

Response 200 — denied:
{ "allowed": false, "reason": "not-covered" }

Reason codes (gateway maps these to user-facing messages):
  not-covered          → "That client is not in your coverage."
  coverage-transferred → "Coverage for that client has been transferred."
  relationship-closed  → "That client relationship is no longer active."
  service-error        → gateway fails closed (same as 5xx)

Response 503 / timeout:
  Gateway fails closed.
```

**Rules:**
- Always call CHECK even if resource came from a DISCOVER result. List ≠ authorization.
- CHECK is the gate. Nothing reaches an agent without a passed CHECK.
- Re-check when TTL from `cache_ttl_seconds` has elapsed since last check.

### RESOLVE

```
POST {resolve_url}
     e.g. POST /entities/resolve

Body:
{ "reference": "Whitman Family Office", "type": "relationship", "principal_id": "rm_jane" }

Response 200 — resolved:
{ "resolved": true, "id": "REL-00042", "canonical_name": "Whitman Family Office", "candidates": [] }

Response 200 — ambiguous:
{ "resolved": false, "id": null, "candidates": [
    { "id": "REL-00042", "name": "Whitman Family Office" },
    { "id": "REL-00200", "name": "Whitman Andersen Trust" }
  ]}

Response 200 — not found:
{ "resolved": false, "id": null, "candidates": [] }
```

**Rules:**
- `principal_id` is passed for audit only. The service must NOT use it to filter.
  Filtering by principal access is CHECK's job, not RESOLVE's job.
- Ambiguous → gateway intersects candidates with a fresh DISCOVER result,
  shows only the accessible ones, posts question back to user.
- Not found → gateway posts: "I couldn't find a resource matching that reference."

---

## Gateway Behaviour — What Happens With Your Declarations

```
TURN ARRIVES
│
├── JWT validated → principal, tenant, roles extracted
│
├── Context envelope resolved → gateway calls the external memory service
│   The envelope may carry prior entities/summaries, but never authorization proof
│
├── Intent classified → FETCH_DATA / FOLLOW_UP / CLARIFY / CHITCHAT
│
├── Agents resolved → vector + BM25 search against example_prompts + capabilities
│   Filtered by domain + sub_domain tags
│
├── Cerbos structural check → can this role invoke this agent class?
│   (role × agent type — not data-aware)
│   Agents failing structural check → removed from plan
│
├── For each sub_domain in remaining agents:
│   │
│   ├── resource_scoped = FALSE
│   │   └── proceed directly to fan-out (role gate was sufficient)
│   │
│   └── resource_scoped = TRUE
│       │
│       ├── Check session for required_context fields
│       │   All present? → CHECK (live, within TTL) → proceed if ALLOW
│       │
│       └── Any missing?
│           → Read clarification_schema from manifest
│           → options_source = "discover"? → call discover_url → get list
│           → Post question + options to user (question text from manifest)
│           → STOP. Wait for next turn.
│
│   (if user answered on this turn)
│   → call resolve_url → canonical ID
│   → call check_url → ALLOW / DENY
│   → DENY? → post denial message (mapped from reason code) → STOP
│   → ALLOW? → store in session, proceed
│
├── Bind → write authorized IDs into per-agent input parameters
│   Agent NEVER receives an unauthorized entity ID.
│
├── Fan-out → parallel, virtual threads, per-agent timeout + circuit breaker
│   Denied sub-domains → acknowledged in synthesis, not silently dropped
│
├── Synthesis → grounded answer, agent outputs are DATA not instructions
│   Numeric grounding check: every number must appear in agent output
│   Missing agents explicitly acknowledged
│
├── Emit governed-memory events → entity_resolved, coverage_checked,
│   agent_completed, response_completed
│
├── Memory service owns compaction → ledger append, summaries, envelope watermark
│
└── Stream response (OpenAI SSE)
```

---

## Validation at Boot

The gateway validates the full manifest tree at startup. It refuses to start if any of the following fail:

```
□ Every agent.domain resolves to a loaded domain manifest
□ Every agent.sub_domain resolves to a loaded sub-domain under that domain
□ Every sub_domain.parent_domain resolves to a loaded domain manifest
□ Every sub_domain.agents entry resolves to a loaded agent manifest
□ Every resource_scoped sub_domain has a parent domain with coverage URLs declared
□ Every required_context field has a corresponding clarification_schema entry
□ No agent manifests reference unknown capability types
□ Every domain memory_compaction block declares envelope_version and summary_policy.owner=memory-service
```

Broken reference at boot = FAIL FAST with a clear message naming the missing piece.
This is intentional — a broken manifest is caught before any request arrives,
not discovered at runtime from a user-facing error.

---

## What the Gateway Never Does

These are hard constraints. If you are tempted to add any of these, do not.

```
✗ No domain names, client names, or entity IDs in gateway Java source
✗ No if/else on domain identity in gateway code ("if domain == wealth...")
✗ No hardcoded question text ("which client?" must come from clarification_schema)
✗ No fallback to stale session auth when coverage service is unreachable
✗ No fan-out before CHECK passes for resource_scoped sub-domains
✗ No agent receiving an entity ID that did not pass CHECK
✗ No fabricated IDs — resolve returns ambiguous/not-found → ask user, never guess
✗ No gateway-owned compaction ledger, domain summary prompt, or domain-specific memory logic
```

CI lint rule enforces the first two on every build.

---

## Open Question (Resolve Before Implementing)

**Does the IAM service include `tenant_id` in the JWT?**

The coverage service contract requires `X-Tenant-Id` on every call.
This comes from the validated JWT. If `tenant_id` is not currently a JWT claim,
either add it to the IAM service token or define an alternative tenant resolution
mechanism before the coverage service is built.

Verify: decode a token from the running IAM service and check for `tenant_id`.

---

## Current Domains

### wealth-management

| Sub-domain | resource_scoped | Agents |
|---|---|---|
| private-banking | true | holdings, performance, risk_profile |
| institutional | true | holdings (inst), goal_planning |

Coverage service: `wealth-coverage-service` (port 8086)

### asset-servicing

| Sub-domain | resource_scoped | Agents |
|---|---|---|
| custody-operations | false | custody_positions, settlement_status |
| corporate-actions | false | corporate_actions, nav |
| cash-management | false | cash_management |

No coverage service. Cerbos structural check only.
