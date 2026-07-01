# Domain Manifest + Contextual Memory Architecture

> **Status:** Design — not yet built. Close user-mgmt refactor first.
> These ideas represent the next major architectural layer after user-mgmt is clean.

---

## The Core Idea

When a business domain onboards to the AI gateway, it brings more than just agents.
It brings a **contract** that tells the gateway:

1. What context is required before any agent can be invoked
2. Where to verify authorization for that context (external domain API — NOT Cerbos, NOT our DB)
3. What to do when context is missing (business workflow steps)
4. What facts must survive conversation compaction (domain-defined memory schema)

The gateway is generic. It interprets these contracts. It never hardcodes domain knowledge.

---

## Two-Level Resolution

```
Level 1 — Gateway Intent Resolver
  Input:  natural language prompt
  Output: which domains + agents are relevant
  How:    vector search against agent catalog

Level 2 — Domain Prerequisite Validator  (NOT YET BUILT)
  Input:  matched domain manifest + current session context
  Output: proceed / run workflow / deny
  How:    check required_context → call authorization_contract → run workflow if needed
```

These run sequentially. Level 1 is about intent. Level 2 is about prerequisites and access rights.

---

## Domain Manifest — Extended Schema

```yaml
domain_id: wealth-management
display_name: Wealth Management

# Already exists
agents:
  - portfolio-analytics-agent
  - relationship-data-agent

# NEW — what context must exist before any agent is invoked
required_context:
  - key: relationship_id
    description: Which client relationship is this about?
    source: entity_extraction   # gateway tries to extract from the prompt first
    fallback: missing_context_workflow

# NEW — external API to verify access (not Cerbos, not our DB)
# Domain team owns this endpoint — could be CRM, portfolio system, HR system
authorization_contract:
  type: http_get
  url: "${WEALTH_CRM_URL}/books/{principal_id}/relationships/{relationship_id}/access"
  allow_field: allowed
  cache_ttl_seconds: 30       # cache per session to avoid hammering the CRM

# NEW — business steps when required context is missing
# Not Temporal. Not a workflow engine. Declarative steps the gateway interprets.
workflows:
  missing_context_workflow:
    steps:
      - action: clarify
        ask: "Which client would you like information about?"
        resolves: client_name

      - action: resolve_entity
        from: client_name
        to: relationship_id
        using: entity_registry

      - action: authorize
        contract: authorization_contract
        on_deny: "I can't access {client_name}'s information — this client is not in your relationship book."

      - action: proceed

# NEW — domain-defined memory compaction schema
# Gateway uses this when summarizing long conversations
# Ensures critical facts survive LibreChat's compaction
memory_compaction:
  must_preserve:
    - field: relationship_id
      reason: All agent calls depend on this
    - field: client_name
      reason: Human-readable reference for clarification and denial messages
    - field: time_period
      reason: Portfolio queries are time-specific — Q3 2024 ≠ Q4 2024
    - field: domain
      reason: Needed to restore domain context on next turn
  can_drop:
    - raw_agent_outputs       # numbers can be re-fetched
    - routing_decisions       # internal, not user-facing
    - intermediate_workflow_steps
```

---

## Domain Authorization Contract — Key Design Decisions

**Why NOT Cerbos for this:**
Cerbos handles structural authorization — "can an RM role invoke a wealth agent class?"
It cannot query an external CRM to check if a specific RM manages a specific client.
Cerbos is a policy evaluator, not a data-aware entitlement resolver.

**Why NOT our database:**
The book (which clients an RM manages) lives in the domain's CRM system.
We should never duplicate it. We call it at runtime.

**Why the domain team owns the URL:**
Different banks use different CRM systems (Salesforce, Microsoft Dynamics, custom).
Different hospitals use different EHR systems.
The pattern is universal. The implementation is domain-specific.

**The contract interface (always the same shape):**
```
Request:  GET {url with principal_id and resource_id substituted}
Response: { "allowed": true/false, "reason": "optional string" }
```

---

## Session Context — Gateway-Maintained Layer

The gateway maintains session context independently of LibreChat's conversation history.
When LibreChat compacts old messages, the gateway's session context survives.

**Storage:** Redis, TTL 30 minutes of inactivity
**Key:** `session:{conversation_id}`

```json
{
  "established": {
    "relationship_id": "REL-00042",
    "client_name": "Jane Whitman",
    "time_period": "Q3 2024",
    "domain": "wealth-management"
  },
  "authorization_cache": {
    "REL-00042": {
      "allowed": true,
      "verified_at": "2026-06-27T14:32:01Z",
      "expires_at": "2026-06-27T14:32:31Z"
    }
  },
  "domain_workflow_state": {
    "wealth-management": "context_satisfied"
  },
  "turn_count": 7
}
```

**Authorization cache benefit:**
- Turn 1: verify RM has access to REL-00042 → cache it
- Turns 2-7: skip the book API call → use cached decision
- On expiry (30s): re-verify silently on next turn
- On explicit revocation: evict from cache immediately

---

## Memory Compaction — Three Layers

```
Layer 1: LibreChat → MongoDB
  Full conversation history. LibreChat manages.
  Gets summarized when maxContextTokens exceeded.
  Risk: generic summarization loses domain-critical facts.

Layer 2: Gateway Session → Redis (TTL 30min)
  Facts the gateway extracted this session.
  Survives LibreChat compaction.
  Injected as system context on every synthesis call.
  Domain team's must_preserve fields always kept here.

Layer 3: Domain Compaction Schema → Domain Manifest
  What the domain team declared must never be lost.
  Gateway injects this as explicit instructions when:
    a) Building domain-aware conversation summary
    b) Constructing synthesis prompt after compaction event
```

**Domain-aware summary vs generic summary:**

Generic (LibreChat default):
```
"The user asked about portfolio performance and received information about allocations."
```
→ relationship_id lost, time period lost, next turn re-extracts from scratch

Domain-aware (gateway using must_preserve schema):
```
"User is asking about Jane Whitman (REL-00042), Q3 2024 portfolio in wealth-management domain.
 RM access verified this session. Prior discussion covered equity allocation (67%) and bond exposure."
```
→ All critical context preserved, no re-extraction needed

---

## Request Lifecycle — Full Picture With These Layers

```
Turn N request arrives
        │
        ▼
1.  JWT validation
        │
        ▼
2.  Load session context (Redis)
    → established entities, auth cache, workflow state
        │
        ▼
3.  Gateway intent resolver
    → which domains + agents are relevant?
        │
        ▼
4.  Structural authz (Cerbos)
    → can this role invoke this agent class?
        │
        ▼
5.  Domain prerequisite validator  ← NEW LAYER
    For each matched domain:
      a. Check required_context against session context
      b. If satisfied → check authorization_cache
         - Cache hit + not expired → proceed
         - Cache miss → call authorization_contract API → cache result
      c. If context missing → run domain workflow
         (clarify → resolve → authorize → proceed or deny)
        │
        ▼
6.  Entity extraction (Extract-Resolve-Bind)
        │
        ▼
7.  Fan-out to authorized agents
        │
        ▼
8.  Synthesis
    → inject session context as system context
    → inject domain must_preserve fields that are established
        │
        ▼
9.  Update session context
    → store any newly resolved entities
    → update authorization cache
    → update workflow state
        │
        ▼
10. Stream response
```

---

## Workflow Step Types (Gateway Interprets These)

The gateway has a small interpreter. Domain teams use these step types:

```
clarify       Ask the user a question. Wait for response. Store result.
resolve_entity Convert a human name/reference to a canonical ID using entity_registry.
authorize     Call the domain's authorization_contract. On deny → respond with message.
set_context   Explicitly set a context field to a value.
proceed       All prerequisites met. Continue to agent fan-out.
deny          Stop here. Respond with message. Do not invoke agents.
```

These are simple enough that no workflow engine is needed.
The gateway's DomainWorkflowInterpreter processes them step by step.
State is maintained in the session context between turns.

---

## What Domain Teams Provide at Onboarding

1. Agent manifests (already exists)
2. Domain manifest (required_context, authorization_contract, workflows, memory_compaction)
3. Entity resolver registration (how to resolve "Jane Whitman" → REL-00042 in their system)
4. Optional: authorization contract implementation (if it's not a simple HTTP GET)

Gateway team provides: the interpreter, the session store, the synthesis injection.
Domain team provides: the knowledge of what matters in their domain.

---

## Build Order (After User-Mgmt Refactor)

1. Domain manifest schema extension (add required_context, authorization_contract, workflows, memory_compaction)
2. Session context store (Redis, with TTL and eviction on mutation events)
3. Authorization contract HTTP adapter (generic URL template → GET → parse allow_field)
4. Domain prerequisite validator (runs at step 5 in the lifecycle)
5. Workflow interpreter (simple step-by-step, no engine needed)
6. Memory compaction injection (synthesis prompt enhancement with must_preserve fields)
7. LibreChat compaction config (summarize: true + custom summarization prompt using schema)
