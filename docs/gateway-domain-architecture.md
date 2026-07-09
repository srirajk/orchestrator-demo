# Conduit AI Gateway — Domain Architecture

> **Status:** Agreed design — decided 2026-06-28. Build order at bottom.
> This document supersedes the earlier design sketches in `domain-manifest-and-memory.md`
> for anything where they conflict. That doc covers the same ideas; this is the authoritative, complete version.

---

## Core Principle

**The gateway is a generic manifest interpreter. It owns zero domain knowledge.**

Domain teams ship manifests. The gateway reads them and reacts accordingly.
No gateway code changes to onboard a new business domain.

```
Domain team provides:  domain manifest + agent manifests + CRM endpoint
Gateway provides:      the interpreter, the session store, the synthesis engine
```

---

## The Two-Level Manifest Hierarchy

### Domain Manifest  (`registry/domains/{domain-id}.json`)
Cross-cutting concerns for an entire business domain.

```yaml
domain_id: wealth-management
display_name: Wealth Management

required_context:
  - key: relationship_id
    description: Which client relationship is this about?
    source: entity_extraction        # gateway tries session first, then extracts
    fallback: missing_context_workflow

entity_registry:
  url: "${WEALTH_CRM_URL}/entities/resolve"
  resolves: [relationship, fund]
  cache_ttl_seconds: 300             # entity IDs are stable

authorization_contract:
  type: http_get
  url: "${WEALTH_CRM_URL}/books/{principal_id}/relationships/{relationship_id}/access"
  allow_field: allowed
  cache_ttl_seconds: 30              # cached per session, reset on revocation signal

workflows:
  missing_context_workflow:
    steps:
      - action: clarify
        ask: "Which client would you like information about?"
        resolves: client_name

      - action: resolve_entity
        from: client_name
        to: relationship_id
        using: entity_registry        # calls entity_registry.url

      - action: authorize
        contract: authorization_contract
        on_deny: "I can't access that client's information — this client is not in your book."

      - action: proceed

memory_compaction:
  must_preserve:
    - field: relationship_id          # all agent calls depend on this
    - field: client_name              # needed for clarification and denial messages
    - field: time_period              # agent queries are time-specific
    - field: domain                   # needed to restore domain context on next turn
  can_drop:
    - raw_agent_outputs               # re-fetchable; biggest token consumers
    - routing_decisions               # internal, never user-facing
    - intermediate_workflow_steps
```

### Agent Manifest  (`registry/manifests/{agent-id}.json`)
Per-agent execution contract. Gateway derives call parameters from this.

```json
{
  "agent_id": "meridian.wealth.holdings",
  "domain": "wealth-management",
  "protocol": "http",
  "connection": { "url": "${WEALTH_HTTP_URL}/holdings" },
  "capabilities": ["portfolio", "holdings", "allocation"],
  "example_prompts": ["what are the holdings for...", "show me the portfolio for..."],
  "max_response_tokens": 2000
}
```

**`max_response_tokens`** — the gateway reads this during synthesis input preparation
and truncates the agent's response to this budget before building the synthesis prompt.
The agent is never told about this limit; enforcement is at the gateway synthesis layer.

---

## Three-Layer Authorization Model

| Layer | System | Question answered | When |
|---|---|---|---|
| **Identity** | JWT (Axiom) | Who is this? | Every request — `sub`, `roles`, `tenant_id` only |
| **Structural** | Cerbos | Can RM role invoke wealth agent class? | After intent resolve, before domain validation |
| **Data-aware** | Domain API (mock-crm) | Does this RM manage this specific client? | Inside input pipeline — Resolve → Authorize → Bind |

**Critical distinctions:**
- **Axiom (IAM) is standalone.** Issues JWTs with stable identity only. No `book`, no `segments`, no domain data.
- **Cerbos handles role × capability** — not data. It doesn't know which clients an RM manages.
- **Domain API owns the book.** The gateway calls it at runtime via `authorization_contract`. Never duplicated in JWT or gateway Redis.
- **Revocation:** iam-service writes `SET revocation:{userId}:{relId} "1" EX 60` to the shared Redis instance on book change. Gateway checks this before using any cached authorization decision. This is an instance-level channel — both services connect to the same Redis server but use separate key namespaces. No shared data structures.

---

## The Authorization Check IS the Input Guardrail

This is not two separate concerns. It is one:

```
Extract    → LLM pulls entity references from prompt verbatim ("Whitman Family Office")
Resolve    → call entity_registry (domain CRM) → human name → canonical ID (REL-00042)
Authorize  → call authorization_contract with resolved ID → allowed: true/false
Bind       → ONLY if authorized → bind ID into agent input parameters

             If denied at Authorize: workflow handles it (deny message or clarification)
             The agent NEVER receives an unauthorized input
```

The `authorization_contract` check between Resolve and Bind is the guardrail.
No unauthorized entity_id ever reaches an agent. This is prune-before-fan-out at input level.

The entity_registry and authorization_contract are both owned by the agent's domain team.
The domain manifest declares both URLs. The gateway calls them generically — it doesn't
know what "wealth" or "relationship" means. It just follows the manifest.

---

## Request Lifecycle — Full Picture

```
1.  JWT validation (Spring Security / JWKS from Axiom)

2.  Load gateway session (Redis, key: session:{conversationId})
    → relationship_id, client_name, time_period, domain,
      auth_cache, domain_workflow_state

3.  Intent classification (IntentClassifier → FETCH_DATA / FOLLOW_UP / CLARIFY / CHITCHAT)
    Short-circuits: title request → short-circuit | summarization call → Gap 4 branch

4.  Level 1 — Agent resolver
    → vector search against agent catalog
    → returns candidate agents (by domain + capability match)

5.  Structural authz (Cerbos filterAgents)
    → "can this role invoke this agent class?" (role × capability class)
    → removes structurally unauthorized agents

6.  Level 2 — Domain prerequisite validator  ← NEW LAYER (not yet built)
    For each matched domain:
      a. Check required_context against session (established on prior turns?)
         YES (session has entity ID) → check auth_cache → cache hit + not revoked → AUTHORIZED
         YES → cache miss or revoked → go to input pipeline (step 7) to re-verify
         NO  → CONTEXT_MISSING → run domain workflow (clarify → resolve → authorize → proceed)
    Result: per-domain { AUTHORIZED | DENIED | CONTEXT_MISSING }

7.  Input pipeline — Extract → Resolve → AUTHORIZE → Bind   ← guardrail lives here
    a. Extract:   LLM pulls entity references verbatim from prompt
    b. Resolve:   call entity_registry (domain CRM) → canonical ID
    c. Authorize: call authorization_contract with resolved ID → allowed?
                  check revocation key first (EXISTS revocation:{userId}:{relId})
                  → DENIED: do not bind; workflow handles denial or message
                  → ALLOWED: cache decision in session auth_cache
    d. Bind:      write authorized IDs into per-agent input parameters
    
    The agent NEVER receives an unauthorized entity ID.
    Entity registry and authorization contract are both domain-owned, manifest-declared.
    Gateway calls both generically — knows nothing about what "wealth" or "relationship" means.

8.  Fan-out — ONLY authorized agents with bound inputs (parallel, virtual threads)
    DENIED domains/entities → acknowledged in synthesis
    CONTEXT_MISSING domains → workflow runs (may span multiple turns via session state)

9.  Answer synthesis (AnswerSynthesizer)
    System prompt includes: established session context (compact, ~60 tokens)
    DATA blocks: authorized agent outputs (truncated to max_response_tokens each)
    MISSING blocks: denied/unavailable domains explicitly acknowledged
    Numeric grounding check: every number in answer must appear in agent output

10. Update gateway session
    → store newly resolved entities (relationship_id, client_name, time_period, domain)
    → update auth_cache with new decisions
    → update domain_workflow_state

11. Stream response (OpenAI SSE)
```

---

## Session Model

**`conversationId` = session key. One concept, not two.**

```
Redis key: session:{conversationId}

Fields:
  relationship_id       REL-00042
  client_name           Jane Whitman
  time_period           Q3 2024
  domain                wealth-management
  fund_id               (optional)
  auth_cache            { "REL-00042": { allowed: true, expires: ... } }
  domain_workflow_state { "asset-servicing": "awaiting_fund_context" }
  turn_count            7
```

**Why this model works:**

- LibreChat compaction fires when message history gets long — but `conversationId` is unchanged. The gateway session is untouched. Session context survives compaction automatically.
- New conversation in LibreChat → new `conversationId` → clean gateway session. Correct — it IS a new conversation.
- No "session expiry" problem to solve. If a conversation is abandoned and the Redis key expires, starting a new conversation means a new convId anyway.
- TTL resets on every write (activity-based), not a fixed window.

**What the gateway injects into every synthesis call (from session):**
```
ESTABLISHED CONTEXT:
  Client: Jane Whitman (REL-00042) | Period: Q3 2024
  Domain: wealth-management | Access: verified this session
```
~60 tokens. Never grows. Replaces thousands of tokens of conversation history for context continuity.

---

## Token Strategy

The architecture solves token growth at the design level, not by throwing bigger context windows at it.

| Problem | Solution |
|---|---|
| Raw agent outputs are large (2-8K per agent × 7 agents) | `can_drop: raw_agent_outputs` in domain manifest — not carried in LibreChat history |
| Per-agent output can still bloat synthesis prompt | `max_response_tokens` on agent manifest — gateway truncates before synthesis |
| Multi-turn conversation grows token cost linearly | Gateway session injects ~60-token compact context instead of full history |
| LibreChat compaction loses domain facts | Compaction intercept injects `must_preserve` fields into the summary |

**Compaction intercept (Gap 4):**
LibreChat's internal summarization call (a `POST /v1/chat/completions` with a system-role message containing the summarization instruction) passes through the gateway. The gateway detects it by role + phrase match, loads the domain manifest's `must_preserve` fields and the current session, enriches the summarization system prompt with these facts, then calls GLM. The resulting summary carries the critical facts that all subsequent turns depend on.

---

## mock-crm Service

Stands in for the wealth domain's CRM system. For demo purposes only.
In production: each bank's domain team provides their own CRM endpoint matching this contract.

**Port:** 8085  
**Location:** `mock-agents/crm/`  
**Gateway env:** `WEALTH_CRM_URL=http://mock-crm:8085`  
**Source of truth:** `mock-agents/crm/data.py` — reconciled with `canned_data.py`

| Endpoint | Purpose | Gap |
|---|---|---|
| `POST /entities/resolve` | Human name → canonical ID + disambiguation candidates | 1 |
| `GET /books/{principal_id}/relationships/{relationship_id}/access` | Data-aware authz check | 3 |
| `GET /health` | Compose healthcheck | infra |

**Entity resolution contract:**
```
Request:  POST /entities/resolve
          { "type": "relationship", "reference": "Whitman Family Office", "principal_id": "rm_jane" }

Response (resolved):
          { "resolved": true, "id": "REL-00042", "canonical_name": "Whitman Family Office", "candidates": [] }

Response (ambiguous):
          { "resolved": false, "id": null, "candidates": [
              {"id": "REL-00042", "name": "Whitman Family Office"},
              {"id": "REL-00200", "name": "Whitman Andersen Trust"}
            ]}

Response (not found):
          { "resolved": false, "id": null, "candidates": [] }
```

**Authorization contract:**
```
Request:  GET /books/rm_jane/relationships/REL-00042/access
Response: { "allowed": true, "reason": "in-book" }
          { "allowed": false, "reason": "relationship out of book" }
          ?_fail=true → { "allowed": false, "reason": "crm-fault-injected" }  (fault knob)
```

**Hard invariant:** entity resolution is principal-agnostic for ID lookup.
`principal_id` is passed for audit only. Access control is the authorization_contract's job, not entity resolution's.

---

## Three-Level Domain Hierarchy

Business domains contain sub-domains. Sub-domains contain agents.
Each level declares its own contract. Specific beats general (agent > sub-domain > domain).

```
Business Domain  (wealth-management)
    │
    ├── Sub-Domain  (private-banking)
    │       ├── Agent  (holdings)
    │       ├── Agent  (performance)
    │       └── Agent  (risk-profile)
    │
    └── Sub-Domain  (institutional)
            ├── Agent  (holdings)
            └── Agent  (goal-planning)

Business Domain  (asset-servicing)
    │
    ├── Sub-Domain  (custody-operations)
    │       ├── Agent  (custody-positions)
    │       └── Agent  (settlements)
    │
    ├── Sub-Domain  (corporate-actions)
    │       ├── Agent  (corporate-actions)
    │       └── Agent  (nav)
    │
    └── Sub-Domain  (cash-management)
            └── Agent  (cash)
```

### Inheritance Model

Each level inherits from its parent and can override anything:

| Concern | Business Domain | Sub-Domain | Agent |
|---|---|---|---|
| `required_context` | common fields (e.g. relationship_id) | extends / adds sub-domain-specific fields | agent-specific additions |
| `authorization_contract` | default CRM system | override if sub-domain uses a different system | — |
| `entity_registry` | default MDM lookup | override if sub-domain uses different reference data | — |
| `clarification_schema` | broad questions | narrowed, sub-domain-contextual questions | specific hints + option sources |
| `memory_compaction` | must_preserve common fields | additional sub-domain fields | — |
| `max_response_tokens` | — | — | per-agent budget |

Missing at a level → inherits from parent. Agent wins over sub-domain wins over domain.

### Manifest File Structure

```
registry/
  domains/
    wealth-management.json              ← business domain manifest
    wealth-management/
      private-banking.json              ← sub-domain manifest
      institutional.json
    asset-servicing.json
    asset-servicing/
      custody-operations.json
      corporate-actions.json
      cash-management.json

  manifests/
    meridian.wealth.holdings.json           ← agent (declares domain + sub_domain)
    meridian.wealth.performance.json
    meridian.servicing.custody_positions.json
```

### What Each Level Declares

**Business Domain** (`registry/domains/wealth-management.json`):
```json
{
  "domain_id": "wealth-management",
  "display_name": "Wealth Management",
  "entity_registry": { "url": "${WEALTH_CRM_URL}/entities/resolve", "resolves": ["relationship", "fund"] },
  "authorization_contract": { "url": "${WEALTH_CRM_URL}/books/{principal_id}/relationships/{relationship_id}/access" },
  "memory_compaction": {
    "must_preserve": ["relationship_id", "client_name", "domain"],
    "can_drop": ["raw_agent_outputs", "routing_decisions"]
  }
}
```

**Sub-Domain** (`registry/domains/wealth-management/private-banking.json`):
```json
{
  "sub_domain_id": "private-banking",
  "parent_domain": "wealth-management",
  "display_name": "Private Banking",
  "required_context": ["relationship_id", "time_period"],
  "clarification_schema": {
    "relationship_id": {
      "question": "Which UHNW client relationship are you asking about?",
      "options_source": "principal_book"
    },
    "time_period": {
      "question": "Which quarter would you like to review?",
      "options_source": "agent_derived",
      "default": "most recent quarter"
    }
  },
  "agents": ["meridian.wealth.holdings", "meridian.wealth.performance", "meridian.wealth.risk_profile"]
}
```

**Agent** (`registry/manifests/meridian.wealth.holdings.json`):
```json
{
  "agent_id": "meridian.wealth.holdings",
  "domain": "wealth-management",
  "sub_domain": "private-banking",
  "protocol": "http",
  "connection": { "url": "${WEALTH_HTTP_URL}/holdings" },
  "max_response_tokens": 2000,
  "capabilities": ["holdings", "allocation", "portfolio"],
  "example_prompts": ["what are the holdings for...", "show me the portfolio allocation..."]
}
```

### How Level 2 Validation Changes

With the hierarchy, validation runs **per sub-domain**, not per domain:

```
Matched agents
  → group by sub_domain
  → for each sub-domain:
      load sub-domain manifest
      inherit from parent domain where not overridden
      check required_context against session
      run clarification_schema if context missing
      call authorization_contract (sub-domain's, or inherited from domain)
      → AUTHORIZED / DENIED / CONTEXT_MISSING
  → fan-out to authorized agents across all sub-domains
  → synthesis acknowledges denied/missing sub-domains
```

A prompt spanning `private-banking` AND `institutional` within `wealth-management`
gets validated independently per sub-domain — they may use different CRM systems
and require different clarification questions.

---

## Clarification Flow — Complete Model

Three sources, merged by the gateway into one coherent question per turn:

```
Source 1: Sub-domain manifest (clarification_schema)
          → "Which client?" with options from principal.book (entitlement-filtered)

Source 2: Agent signal (needs_clarification response)
          → domain-specific gaps the manifest couldn't predict
          → e.g. "Which quarter?" with options the agent knows it has data for

Source 3: Principal book (from session / JWT)
          → filters clarification options to only what the user is authorized to see
```

**Agent clarification signal shape:**
```json
{
  "status": "needs_clarification",
  "field": "time_period",
  "question": "Which quarter would you like to review?",
  "options": ["Q1 2024", "Q2 2024", "Q3 2024", "Q4 2024"]
}
```

**Gateway merge rule:**
- Sub-domain manifest handles known required fields (relationship_id) before fan-out
- Agent signals handle domain-specific missing context after fan-out
- All pending clarifications are merged into ONE question per turn — never multiple questions at once
- Session tracks what's pending per sub-domain across turns

**Multi-turn example:**
```
Turn 1:  "Show me portfolio performance"
         → relationship_id missing (sub-domain manifest)
         → ask: "Which client?" [Whitman Family Office] [Calderon Trust]

Turn 2:  User picks "Whitman Family Office"
         → entity_registry: REL-00042
         → authorization_contract: allowed
         → fan-out to performance agent
         → agent signals: needs time_period → options [Q1] [Q2] [Q3] [Q4]
         → ask: "Which quarter?"

Turn 3:  User picks "Q3 2024"
         → session: relationship_id=REL-00042, time_period=Q3 2024 (now established)
         → full fan-out, grounded answer returned
         → session carries both for all subsequent turns in this conversation
```

---

## Implementation — Storage, Search & Runtime

### Redis Key Namespaces (flat, not nested)

```
domain:{domain_id}                     → business domain manifest (JSON)
subdomain:{domain_id}:{sub_domain_id}  → sub-domain manifest (JSON)
agent:{agent_id}                       → agent manifest (JSON, already exists)
session:{conversationId}               → conversation session state
revocation:{userId}:{relId}            → revocation signal (TTL 60s)
```

No nested structures. Hierarchy is resolved in gateway code, not in Redis.

### Manifest Loading — Boot Sequence

```
1. Load all domain manifests       → registry/domains/*.json      → Map<String, DomainManifest>
2. Load all sub-domain manifests   → registry/domains/*/*.json    → Map<String, SubDomainManifest>
3. Load all agent manifests        → registry/manifests/*.json    → Map<String, AgentManifest>
4. Validate relationships:
     every agent.domain must resolve to a loaded DomainManifest
     every agent.sub_domain must resolve to a loaded SubDomainManifest under that domain
     every sub_domain.agents entry must resolve to a loaded AgentManifest
   → FAIL FAST on broken references at startup, never at request time
5. Store all three levels in Redis (JSON)
6. Build HNSW + BM25 index over agent example_prompts
```

Everything loaded in-memory at boot. Zero manifest lookups at request time.

### Routing Search — Hybrid Vector + BM25

Redis Stack (RediSearch) supports both HNSW vector search and BM25 full-text in one query.
Run hybrid, merge results with RRF (Reciprocal Rank Fusion):

```
Query: "show me the Whitman portfolio Q3 performance"

BM25 hits:   holdings  (keyword: portfolio)
             performance (keyword: performance)

Vector hits: holdings  (semantic: portfolio allocation)
             performance (semantic: portfolio performance)
             goal_planning (semantic: portfolio planning)

RRF merged:  performance #1, holdings #2, goal_planning #3

Filter tags: domain=wealth-management sub_domain=private-banking
→ final candidates: performance, holdings, goal_planning
```

Domain and sub-domain are **filter tags** on the index, not separate indexes.
Agent-level HNSW stays the single routing index. Sub-domain grouping happens after search.

```
FT.SEARCH agent_idx
  "@domain:{wealth-management}"          ← tag filter
  HYBRID KNN 5                           ← vector KNN
  RETURN 3 agent_id domain sub_domain    ← fields
```

### Inheritance Resolution at Request Time

```java
// In-memory, no Redis calls — all loaded at boot
AgentManifest agent = manifestStore.getAgent(agentId);
SubDomainManifest sub = manifestStore.getSubDomain(agent.domain(), agent.subDomain());
DomainManifest domain = manifestStore.getDomain(agent.domain());

// Merge: agent wins > sub-domain wins > domain
EffectiveManifest effective = EffectiveManifest.merge(domain, sub, agent);

// effective now has:
//   required_context  (most specific declared)
//   authorization_contract (sub-domain override, or domain default)
//   entity_registry (sub-domain override, or domain default)
//   clarification_schema (sub-domain, merged with domain)
//   max_response_tokens (agent-level)
```

### Hot Path — What's Fast, What's Not

| Step | Where | Speed |
|---|---|---|
| JWT validation | In-process (cached JWKS) | <1ms |
| Manifest lookup (domain, sub-domain, agent) | In-memory at boot | <0.1ms |
| Session load | Redis GET | ~1ms |
| Vector + BM25 routing search | Redis HYBRID query | ~5-10ms |
| Auth cache check (revocation + session) | Redis EXISTS + session | ~1ms |
| Authorization contract (first call per entity) | Domain CRM (mock-crm) | ~10-50ms, cached after |
| Agent fan-out | Parallel, virtual threads | agent latency (dominant) |
| Synthesis | GLM streaming | ~1-3s |

**The only meaningful latency is agent response time and synthesis.**
The manifest hierarchy, session, and auth decisions are all sub-millisecond or single-digit ms.

### Registration APIs

```
POST /admin/domains                          → register business domain manifest
POST /admin/domains/{domainId}/subdomains    → register sub-domain manifest
POST /admin/agents                           → register agent (existing)

GET  /admin/domains                          → list domains + sub-domain counts
GET  /admin/domains/{domainId}               → domain + its sub-domains
GET  /admin/domains/{domainId}/subdomains    → sub-domains + their agents
GET  /admin/agents?domain=X&subDomain=Y      → filter agents by domain/sub-domain
```

---

## What Belongs Where — Decision Table

| Concern | Where | NOT in |
|---|---|---|
| Who you are | JWT — `sub`, `roles`, `tenant_id` | JWT claims beyond these |
| Can RM role invoke wealth agents? | Cerbos structural policy | Domain API, JWT |
| Does RM manage this client? | Domain API (`authorization_contract`) | Cerbos, JWT, gateway Redis |
| Human name → canonical ID | Domain API (`entity_registry` / mock-crm) | Gateway code, gateway Redis |
| What context any agent in a domain needs | Domain manifest (`required_context`) | Gateway code |
| What context a specific sub-domain needs | Sub-domain manifest (`required_context`) | Gateway code |
| What to ask when context is missing | Sub-domain manifest (`clarification_schema`) | Gateway code |
| What domain-specific gaps need filling | Agent signal (`needs_clarification` response) | Domain manifest |
| Clarification options the user can see | `principal_book` filtered by entitlements | Unfiltered lists |
| What survives compaction | Domain manifest (`memory_compaction`) | LibreChat config |
| Per-agent token budget | Agent manifest (`max_response_tokens`) | Agent service itself |
| Session / conversation state | Gateway Redis, key: `session:{conversationId}` | LibreChat MongoDB |
| Routing search index | Agent manifest `example_prompts` (HNSW + BM25) | Domain or sub-domain level |
| Domain/sub-domain routing filter | Agent manifest `domain` + `sub_domain` tags | Separate indexes |

---

## Build Order

| Step | What | Key files |
|---|---|---|
| **0 — Foundation** | `DomainManifest` + `SubDomainManifest` + `EffectiveManifest` (merge logic) | New: `orchestration/domain/DomainManifest.java`, `SubDomainManifest.java`, `EffectiveManifest.java` |
| | `DomainManifestStore` — loads `registry/domains/*.json` + `registry/domains/*/*.json`; validates agent→sub-domain→domain references at boot; FAIL FAST | New: `DomainManifestStore.java` |
| | `registry/domains/` — create domain + sub-domain manifests for both domains | New: `wealth-management.json`, `wealth-management/private-banking.json`, `asset-servicing.json`, `asset-servicing/custody-operations.json`, etc. |
| | Update agent manifests — add `sub_domain` field to all 9 manifests | Modify: all `registry/manifests/*.json` |
| | `ConversationSession` extension (add clientName, timePeriod, domain, subDomain, domainWorkflowState, authCache) | Modify: `ConversationSession.java`, `ConversationSessionStore.java` |
| | Update routing search — add domain + sub_domain as filter tags to HNSW index; wire hybrid BM25+vector | Modify: `VectorIndex.java`, `AgentResolver.java` |
| | mock-crm scaffold | New: `mock-agents/crm/`, `docker-compose.yml` wiring |
| **1 — Gap 1** | Delete hardcoded entity maps; `EntityResolver` calls mock-crm; remove hardcoded client names from `EntityExtractor` + clarification message | Modify: `EntityResolver.java`, `EntityExtractor.java`, `InputSynthesizerImpl.java` |
| **2 — Gap 2** | iam-service → shared Redis; `RevocationPublisher` (after-commit on book change); `RevocationChecker` in `EntitlementService` (deny-only override) | New: `RevocationChecker.java`, `iam-service/.../RevocationPublisher.java`; Modify: `EntitlementService.java`, `UserService.java`, iam-service `pom.xml` + `application.yml`, `docker-compose.yml` |
| **3 — Gap 3** | `DomainPrerequisiteValidator`; AUTHORIZED / DENIED / CONTEXT_MISSING per domain; Plan built only from authorized domains; synthesizer told what was withheld; `domain_validated` trace event | New: `DomainPrerequisiteValidator.java`, `DomainValidationResult.java`, `DomainValidatedData.java`; Modify: `ChatService.java`, `AnswerSynthesizer.java`, `ResolverResult.java` |
| **4 — Gap 4** | Summarization detection + `handleSummarization` branch; injects `must_preserve` from domain manifest + session; enable `summarize: true` in LibreChat config | Modify: `ChatService.java`, `AnswerSynthesizer.java`, `ConversationSession.java`, `librechat/librechat.yaml` |

---

## Named Seams (Future Production Hardening)

These are demo-grade decisions that have the right interface but a simplified implementation:

| Seam | Demo | Production |
|---|---|---|
| Revocation channel | Same Redis instance, separate key namespace (`revocation:*`) — clean channel, not shared data | Event bus (Kafka / Redis Streams) for multi-instance deployments |
| Entity registry | mock-crm (single domain) | Per-domain CRM, federated resolution |
| Authorization contract | mock-crm HTTP GET | mTLS, audited, SLA-backed |
| Domain manifest loading | Classpath `registry/domains/*.json` at boot | `POST /admin/domains` runtime registration API |
| Compaction detection | System-role phrase match | Dedicated `POST /v1/summarize` endpoint |
| Session TTL | Activity-based Redis TTL | Tied to LibreChat conversation lifecycle events |

---

## The Pitch

> *Onboard a new bank by editing manifests and a CRM URL — not by recompiling the gateway.*

The gateway reads what domain teams tell it. It never assumes. It never hardcodes.
A new domain is a `registry/domains/new-domain.json`, a `registry/manifests/new-agent.json`,
and a CRM URL in `.env`. The gateway does the rest.
