# Implementation Checklist — Pre-Build Validation Spec

> **Purpose:** Close-the-loop engineering. Every item here must be decided, built,
> and verified before the implementation is considered complete.
> Work through sections in order. Do not skip to code until §1 decisions are made.

---

## §1 — Decisions Required Before Writing Code

These are open questions that produce ambiguous or wrong behavior if left unresolved.

### 1.1 Effective Manifest Merge Semantics

When domain, sub-domain, and agent all declare the same field, the merge rule must be explicit:

| Field | Merge Rule | Reason |
|---|---|---|
| `required_context` | **UNION** — all declared fields across all levels | Agent may need fields domain didn't anticipate |
| `authorization_contract` | **MOST SPECIFIC WINS** (agent > sub-domain > domain) | Different sub-domains may use different CRM systems |
| `entity_registry` | **MOST SPECIFIC WINS** | Different sub-domains may use different MDM systems |
| `clarification_schema` | **MERGE + OVERRIDE** — sub-domain extends domain; agent adds hints | Each level narrows/enriches the question |
| `memory_compaction.must_preserve` | **UNION** | Never lose a field any level declared critical |
| `memory_compaction.can_drop` | **UNION** | Any level can declare something droppable |
| `max_response_tokens` | **AGENT ONLY** — no inheritance | Per-agent concern, not domain-wide |

**Decision:** confirm the above rules are correct before implementing `EffectiveManifest.merge()`.

---

### 1.2 `options_source: agent_derived` — Two-Phase Call

To show a user available quarters (agent_derived options), the gateway must know what options
the agent has BEFORE asking the user. This implies a two-phase protocol:

**Phase 1 — Options probe:**
```
Gateway → Agent:  { "relationship_id": "REL-00042", "query": "available_periods" }
Agent   → Gateway: { "status": "options", "field": "time_period", "options": ["Q1 2024", "Q2 2024", "Q3 2024"] }
```

**Phase 2 — Real call (after user picks):**
```
Gateway → Agent: { "relationship_id": "REL-00042", "time_period": "Q3 2024" }
Agent   → Gateway: { "status": "ok", "data": { ... } }
```

**Alternative (simpler for demo):** let the fan-out proceed. Agent returns `needs_clarification`
with its own options. Gateway surfaces them. User responds. Gateway re-calls only that agent.
Slower (extra round trip), but no new protocol needed.

**Decision required:** two-phase probe or fan-out-then-reclarify?
For demo, recommend: **fan-out-then-reclarify** (simpler, agent stays stateless).

---

### 1.3 Authorization Cache Key Must Be Sub-Domain-Scoped

The same `relationship_id` (REL-00042) may be authorized in `private-banking` but denied
in `institutional` if they use different authorization_contracts (different CRM systems).

**Wrong:** `auth_cache: { "REL-00042": { allowed: true } }`
**Correct:** `auth_cache: { "wealth-management/private-banking:REL-00042": { allowed: true } }`

Cache key format: `{domain_id}/{sub_domain_id}:{entity_type}:{entity_id}`

---

### 1.4 Session Domain Workflow State — Multi-Sub-Domain

Session currently tracks `domain_workflow_state: { "wealth-management": "satisfied" }`.
A query spanning two sub-domains needs per-sub-domain state:

```json
"domain_workflow_state": {
  "wealth-management/private-banking": "satisfied",
  "wealth-management/institutional": "awaiting_time_period",
  "asset-servicing/custody-operations": "context_missing"
}
```

Key format: `{domain_id}/{sub_domain_id}`

---

### 1.5 Ambiguous Entity Resolution — Intersection Rule

When entity_registry returns multiple candidates AND principal.book has some of them:

```
entity_registry returns: [REL-00042 Whitman Family Office, REL-00200 Whitman Andersen Trust]
principal.book contains: [REL-00042, REL-00099]   (NOT REL-00200)

Clarification options shown to user: [Whitman Family Office]  ← intersection only
REL-00200 is NOT shown — user has no access to it
```

**Rule:** `clarification_options = entity_registry_candidates ∩ principal.book`
If intersection is empty → "I found clients matching that name but you do not have access to any of them."
If intersection has one → resolve directly, no clarification needed.
If intersection has multiple → show the filtered list.

---

### 1.6 Multiple Agents Signal `needs_clarification` for Different Fields

Agent A (performance) needs `time_period`. Agent B (goal_planning) needs `fund_id`.

**Rule — ask one, defer the other:**
- Define field priority order in sub-domain manifest:
  `clarification_priority: [relationship_id, time_period, fund_id]`
- Ask for the highest-priority missing field first
- Session marks the lower-priority field as `deferred_clarification`
- On next turn (after resolving time_period), check deferred → ask for fund_id

**Result:** user always gets exactly ONE clarification question per turn. Never a multi-question prompt.
The session `domain_workflow_state` tracks which agent's clarification is deferred.

---

### 1.7 Hybrid BM25 + Vector Confidence Threshold

Current vector-only routing uses a cosine similarity confidence floor (e.g., 0.70).
RRF scores are on a different scale (sum of 1/(k + rank) values, typically 0.0–0.03).

**Decision:** instead of a score threshold on RRF, use a **minimum hit count** rule:
- An agent must appear in EITHER the top-N vector results OR top-N BM25 results to be a candidate
- N = configurable (default: 5 per modality)
- If an agent appears in both → strong signal, higher merged rank
- If fewer than 1 candidate survives → fall back to CLARIFY intent

---

## §2 — Build Checklist (Foundation Layer)

### 2.1 Manifest Classes

- [ ] `DomainManifest.java` — record with all domain-level fields
- [ ] `SubDomainManifest.java` — record with all sub-domain-level fields + `parentDomain` + `agents` list
- [ ] `EffectiveManifest.java` — merged view; static `merge(domain, subDomain, agent)` implementing §1.1 rules
- [ ] `ClarificationSchema.java` — record: field → question, options_source, priority_order, default
- [ ] `AuthorizationContract.java` — record: url template, allow_field, cache_ttl
- [ ] `EntityRegistry.java` — record: url, resolves list, cache_ttl

### 2.2 DomainManifestStore (Boot + Validation)

- [ ] Loads `registry/domains/*.json` → domain manifests
- [ ] Loads `registry/domains/*/*.json` → sub-domain manifests
- [ ] Validates: every sub-domain's `parent_domain` resolves to a loaded domain — FAIL FAST
- [ ] Validates: every agent's `domain` resolves to a loaded domain — FAIL FAST
- [ ] Validates: every agent's `sub_domain` resolves to a loaded sub-domain under that domain — FAIL FAST
- [ ] Validates: every sub-domain's `agents` list entries resolve to registered agents — WARN (not fail, agents may register later)
- [ ] `getEffective(agentId)` → resolves and merges all three levels
- [ ] `getSubDomainsForDomain(domainId)` → list of sub-domain manifests
- [ ] All manifests in-memory; zero Redis lookups at request time

### 2.3 ConversationSession Extension

- [ ] Add `clientName: String`
- [ ] Add `timePeriod: String`
- [ ] Add `domain: String`
- [ ] Add `subDomain: String`  (primary — for single-sub-domain turns)
- [ ] Add `domainWorkflowState: Map<String, String>` (key: `{domain}/{subDomain}`)
- [ ] Add `authorizationCache: Map<String, AuthCacheEntry>` (key: `{domain}/{subDomain}:{type}:{entityId}`)
- [ ] Add `deferredClarifications: Map<String, String>` (agentId → field awaiting answer)
- [ ] Backward-compatible Jackson defaults on all new fields
- [ ] `ConversationSessionStore` reads/writes new fields

### 2.4 Registry / Manifests

- [ ] Add `sub_domain` field to all 9 agent manifests in `registry/manifests/`
- [ ] Add `domain` + `sub_domain` filter tags to `VectorIndex` HNSW schema (FT.CREATE)
- [ ] Wire hybrid BM25 + vector search with RRF merge in `AgentResolver`
- [ ] Add `max_response_tokens` to all 9 agent manifests (sensible defaults)
- [ ] Create `registry/domains/wealth-management.json`
- [ ] Create `registry/domains/wealth-management/private-banking.json`
- [ ] Create `registry/domains/asset-servicing.json`
- [ ] Create `registry/domains/asset-servicing/custody-operations.json`
- [ ] Create `registry/domains/asset-servicing/corporate-actions.json`
- [ ] Create `registry/domains/asset-servicing/cash-management.json`

### 2.5 mock-crm Service

- [ ] `mock-agents/crm/data.py` — canonical entity data, reconciled with `canned_data.py`
  - [ ] Verify: REL-00099 = Calderon Trust (NOT chen — fix the drift)
  - [ ] Include: REL-00042 Whitman, REL-00099 Calderon, REL-00188 Okafor, REL-00200 Andersen
- [ ] `POST /entities/resolve` — name→ID + candidates (ambiguous case covered)
- [ ] `GET /books/{principal_id}/relationships/{relationship_id}/access` — authz check + `?_fail=true`
- [ ] `GET /health` — compose healthcheck
- [ ] `docker-compose.yml` — add mock-crm service, port 8085, WEALTH_CRM_URL env for gateway
- [ ] `pytest` tests: resolved / not-found / ambiguous / fault-knob cases

---

## §3 — Test Cases

### 3.1 Unit Tests — Manifest Merge (no network)

| # | Test | Expected |
|---|---|---|
| U1 | `EffectiveManifest.merge()` — sub-domain overrides domain `authorization_contract` | Sub-domain's URL used |
| U2 | `EffectiveManifest.merge()` — agent has no `authorization_contract` → inherits sub-domain | Sub-domain's URL used |
| U3 | `EffectiveManifest.merge()` — `required_context` is union of domain + sub-domain + agent | All fields present |
| U4 | `EffectiveManifest.merge()` — `must_preserve` is union across all levels | No field dropped |
| U5 | `EffectiveManifest.merge()` — `max_response_tokens` only from agent, not inherited | Agent value only |
| U6 | `DomainManifestStore` — agent declares unknown `sub_domain` → exception at boot | FAIL FAST |
| U7 | `DomainManifestStore` — sub-domain declares unknown `parent_domain` → exception at boot | FAIL FAST |
| U8 | `DomainManifestStore` — missing domain manifest → agent registration rejected | FAIL FAST |
| U9 | Clarification schema merge — sub-domain question overrides domain question for same field | Sub-domain question used |
| U10 | Auth cache key — same relId under two different sub-domains → two separate cache entries | No cross-contamination |

### 3.2 Unit Tests — Invariants (no network)

| # | Test | Expected |
|---|---|---|
| I1 | Entity_registry returns unresolved → `needsClarification=true`, `relationshipId=null` | NEVER a guessed ID |
| I2 | Entity_registry times out / 5xx → same as unresolved, not an exception | Clarification, not error |
| I3 | Authorization denied → entity_id NOT bound to any agent input | Agent never called with denied entity |
| I4 | Ambiguous candidates ∩ principal.book = empty → "no accessible clients match" message | Not shown: unauthorized candidates |
| I5 | Two agents signal `needs_clarification` for same field → one question emitted | De-duplicated |
| I6 | Two agents signal for different fields → lower-priority field deferred | One question per turn |
| I7 | Agent response exceeds `max_response_tokens` → truncated before synthesis | Truncated, not rejected |

### 3.3 Integration Tests — With Redis (Testcontainers, no LLM)

| # | Test | Expected |
|---|---|---|
| R1 | Boot: all 9 agents load with domain + sub_domain references valid | No boot error |
| R2 | Boot: inject agent with unknown sub_domain → boot fails with clear message | FAIL FAST + message |
| R3 | Hybrid search: "show me the Whitman portfolio" → performance + holdings ranked top 2 | BM25+vector beats vector-only |
| R4 | Hybrid search: "cash management" (exact keyword) → cash agent ranked #1 | BM25 keyword match wins |
| R5 | Routing: domain + sub_domain filter tags → only private-banking agents returned when filter applied | Tag filtering works |
| R6 | Session: relationship_id written on turn 1, present on turn 2 (same convId) | Survives across turns |
| R7 | Session: new convId → empty session (no cross-conversation leakage) | Clean slate |
| R8 | Auth cache: sub-domain-scoped key → wealth/private-banking:REL-00042 and wealth/institutional:REL-00042 are independent | No cross-contamination |
| R9 | Revocation: `SET revocation:rm_jane:REL-00188 "1" EX 60` → gateway EntitlementService returns DENY | Revocation works |
| R10 | Revocation: key expired (TTL elapsed) → gateway calls authorization_contract normally | Cache cleared cleanly |
| R11 | Domain workflow state: turn 1 sets `wealth-management/institutional → awaiting_time_period`, turn 2 resolves it | Multi-turn state carry |

### 3.4 API Tests — Gateway Running, Mock-CRM Up (no LibreChat)

| # | Test | Expected |
|---|---|---|
| A1 | `POST /admin/domains` → domain manifest registered | 201, stored in Redis |
| A2 | `POST /admin/domains/{id}/subdomains` → sub-domain registered | 201, stored in Redis |
| A3 | `POST /admin/agents` with valid domain + sub_domain | 201 |
| A4 | `POST /admin/agents` with unknown sub_domain | 400 Bad Request |
| A5 | `POST /v1/chat/completions` — relationship_id missing, no session | 200 with clarification question (not agent data); entitlement-filtered options |
| A6 | `POST /v1/chat/completions` — relationship_id in session, authorized | 200 with grounded data |
| A7 | `POST /v1/chat/completions` — relationship_id in session, revoked mid-session | 200 with denial; glass-box shows revocation-override |
| A8 | `POST /v1/chat/completions` — mock-crm entity_registry returns ambiguous candidates, user has access to one | 200 with that candidate auto-resolved (no question needed) |
| A9 | `POST /v1/chat/completions` — mock-crm entity_registry returns ambiguous, user has access to multiple | 200 with clarification showing only accessible candidates |
| A10 | `POST /v1/chat/completions` — mock-crm entity_registry down (5xx) | 200 with clarification ("couldn't resolve — which client?"), no error thrown |
| A11 | `POST /v1/chat/completions` — agent signals `needs_clarification` for time_period | 200 with data from other agents + clarification question for time_period |
| A12 | `POST /v1/chat/completions` — two agents signal `needs_clarification` for different fields | 200 with higher-priority field asked; lower-priority deferred in session |
| A13 | `POST /v1/chat/completions` — system-role "summarize" message | 200 with domain-aware summary (relationship_id + client_name preserved); no agent fan-out |
| A14 | `POST /v1/chat/completions` — user types "summarize this conversation" (user role) | Treated as FETCH_DATA / FOLLOW_UP, NOT as compaction intercept |
| A15 | `GET /admin/domains/{id}/subdomains` | Lists sub-domains with agent counts |
| A16 | `POST /v1/chat/completions` — sub-domain A authorized, sub-domain B CONTEXT_MISSING | Data from A returned; B acknowledged as pending; one clarification question |
| A17 | Agent response 3000 tokens, `max_response_tokens: 2000` | Synthesis receives truncated response; no error |

### 3.5 E2E Playwright Tests (Full Stack)

| # | Test | Expected |
|---|---|---|
| E1 | Hero prompt (full context in session) → 7 agents, both sub-domains authorized | Grounded answer; glass-box shows domain chips green |
| E2 | Missing client → clarification question; options list does NOT include Okafor | Entitlement-filtered |
| E3 | User picks Whitman → answer returned with correct data | Entity resolved, authorized, bound |
| E4 | Agent signals needs_clarification for Q; user picks Q3 2024 → full answer | Two-turn clarification works end to end |
| E5 | Admin revokes Okafor from rm_jane in Axiom → next message → denied | Glass-box: revocation-override; JWT still has Okafor but denied |
| E6 | Long conversation → LibreChat compaction fires → follow-up works without restating client | must_preserve preserved in summary |
| E7 | New conversation → no context from prior conversation | Clean session |
| E8 | Mock-CRM fault knob on → entity resolution fails → clarification shown, no fabrication | Invariant I1 holds end to end |
| E9 | Mock-CRM `?_fail=true` on authorization → sub-domain DENIED → other sub-domain still answers | Partial fan-out; denial acknowledged in synthesis |
| E10 | Cross-sub-domain query → private-banking authorized, institutional CONTEXT_MISSING → one question | Mixed result handled correctly |

---

## §4 — Hard Invariants (Non-Negotiable, Must Have Tests)

These must never be violated. Each has at least one test from §3:

| Invariant | Tests |
|---|---|
| **A — Zero fabricated IDs** — entity_registry unresolved/down → clarification, never a guessed ID | I1, I2, A10, E8 |
| **B — Zero unauthorized inputs** — authorization denied → entity_id never bound, never sent to agent | I3, A7, E5 |
| **C — No cross-conversation leakage** — new convId = empty session, no prior context | R7, E7 |
| **D — Entitlement-filtered clarification** — principal.book filters what the user sees in options | I4, A5, A9, E2 |
| **E — Partial honesty** — denied sub-domains acknowledged in synthesis, never silently dropped | A16, E9 |
| **F — One question per turn** — multiple clarification signals merged into one question | I5, I6, A12 |
| **G — Agent response budget** — agent output truncated to max_response_tokens before synthesis | I7, A17 |
| **H — No domain knowledge in gateway code** — grep check: no client names, no REL-IDs, no entity maps in gateway source | CI lint rule |

---

## §5 — CI / Lint Rules

Add these as automated checks that run on every build:

```bash
# H — No domain knowledge hardcoded in gateway
grep -r "REL-0\|whitman\|calderon\|okafor\|RELATIONSHIP_TABLE\|FUND_TABLE" \
  gateway/src/main/java/ && echo "FAIL: domain knowledge in gateway" && exit 1

# Manifest validation — every agent must have domain + sub_domain
for f in registry/manifests/*.json; do
  jq -e '.domain and .sub_domain' "$f" > /dev/null || (echo "FAIL: $f missing domain/sub_domain" && exit 1)
done

# data.py reconciliation — CRM data must match canned_data
python3 scripts/validate_crm_consistency.py  # REL-IDs and names must match across both files
```

---

## §6 — Definition of Done

The implementation is complete when:

- [ ] All §1 decisions recorded in this doc or in `gateway-domain-architecture.md`
- [ ] All §2 build items checked off
- [ ] All §3 tests passing (unit + integration + API + E2E)
- [ ] All §4 invariants have at least one passing test
- [ ] All §5 CI rules green
- [ ] `grep` on gateway source: zero occurrences of hardcoded entity names or IDs
- [ ] `DomainManifestStore` boot validation runs in < 500ms for 9 agents, 6 sub-domains, 2 domains
- [ ] Hot path latency (excluding agent response + synthesis): < 20ms measured end-to-end
