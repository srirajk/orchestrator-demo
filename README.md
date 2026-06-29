# Meridian — Enterprise AI Gateway

> One plain-English question. Multiple specialist AI agents across HTTP and MCP. One grounded, attributed answer. Live authorization decisions. Everything visible in real time.

This is a **production-architecture demo** of an enterprise AI gateway built for a bank. A relationship manager types a question into the Meridian-branded chat UI; the gateway figures out which specialist agents to call, checks entitlements, fans out in parallel, synthesizes one answer, and streams it back — showing every routing and access decision in a live glass-box panel beside the chat.

It is not a prototype. Every component follows patterns you would ship to production: virtual threads, circuit breakers, ABAC authorization, JWT-secured agent calls, an immutable audit trail, and a complete OIDC identity layer.

---

## What makes this interesting

**You type one question. The system does everything else.**

```
"What's the current portfolio allocation and any pending settlements for Jane Whitman?"
```

The gateway:
1. Classifies intent — wealth query touching two domains
2. Vector-searches the agent registry — finds portfolio-analytics + settlements agents
3. Checks Cerbos — is this RM allowed to invoke these agent classes at this classification level?
4. Calls the domain authorization contract — does rm_jane have REL-00042 in her book?
5. Extracts entities — resolves "Jane Whitman" → REL-00042 (never guesses)
6. Fans out in parallel — HTTP to Wealth agents, MCP to Asset Servicing agents
7. Synthesizes one grounded answer via Z.AI GLM — every number sourced from agent output
8. Streams the answer back to the chat UI
9. Kills one agent mid-request — answer still comes back, gap explicitly stated

All of this is visible live in the glass-box panel: which agents were selected, which were denied, per-agent latency, the authorization decision, the synthesis.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Meridian (LibreChat — branded)           Admin UI (localhost:5180)          │
│  localhost:3080                           IAM, users, roles, policies, audit │
└────────────────┬────────────────────────────────────────────────────────────┘
                 │  POST /v1/chat/completions  (OpenAI-compatible, SSE)
                 ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│  Meridian Gateway  ·  Java 21  ·  Spring Boot 3.5  ·  Virtual Threads       │
│                                                                             │
│  ┌─────────────┐  ┌──────────────────┐  ┌────────────────────────────────┐ │
│  │  Intent     │  │  Capability      │  │  Entity Extractor              │ │
│  │  Classifier │→ │  Resolver        │→ │  Extract → Resolve → Bind      │ │
│  │  (GLM-4.6)  │  │  Vector search   │  │  Zero fabricated IDs           │ │
│  └─────────────┘  │  + Cerbos gate   │  └────────────────────────────────┘ │
│                   └──────────────────┘                ↓                    │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  Fan-Out Executor  ·  Semaphore bulkhead  ·  Virtual threads           │ │
│  │  ┌───────────────────────────┐  ┌─────────────────────────────────┐   │ │
│  │  │  HTTP Adapter             │  │  MCP Adapter                    │   │ │
│  │  │  Wealth Management agents │  │  Asset Servicing agents         │   │ │
│  │  │  FastAPI · OpenAPI-driven │  │  FastMCP · SSE transport        │   │ │
│  │  └───────────────────────────┘  └─────────────────────────────────┘   │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                               ↓                                             │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  Synthesizer  ·  Z.AI GLM streaming  ·  Grounding check               │ │
│  │  Agent outputs are DATA, not instructions. Every number verified.      │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                             │
│  Cross-cutting: Resilience4j circuit breakers  ·  OTel spans  ·  Micrometer│
└──────────┬────────────────┬───────────────────────┬────────────────────────┘
           │                │                       │
           ▼                ▼                       ▼
    Redis Stack        Cerbos PDP             IAM Service
    Vector index       ABAC policies          OIDC provider
    Agent registry     Agent + IAM            JWT · JWKS
    Session context    resource policies      Users · Roles
    Auth cache         Derived roles          Audit log
```

---

## Components

### Gateway (`gateway/`) — Java 21 · Spring Boot 3.5
The brain. Receives the chat message and orchestrates everything.

| Layer | What it does |
|---|---|
| Intent Classifier | Single LLM call: classify + extract entities simultaneously |
| Capability Resolver | Vector search (DJL + MiniLM-L6-v2) against agent registry in Redis |
| Cerbos Gate | ABAC check before fan-out — role × clearance × domain segment |
| Entity Extractor | Extract → Resolve → Bind: human names become canonical IDs, never guessed |
| Protocol Adapters | `HttpAdapter` (OpenAPI-driven) + `McpAdapter` (MCP Java SDK) |
| Harness | Dual Semaphore bulkhead + virtual threads + Resilience4j circuit breaker per agent |
| Synthesizer | Z.AI GLM streaming; agent outputs are delimited DATA; numeric grounding check |
| Glass-box | OTel spans → SSE stream → live panel in the browser |

### IAM Service (`iam-service/`) — Java 21 · Spring Boot 3.5
A standalone, multi-tenant Identity and Access Management service. Replaceable with any OIDC provider in production.

- **Spring Authorization Server** — full OIDC: JWT issuance, JWKS endpoint, discovery document
- **Virtual threads** (not reactive) — Spring MVC + blocking JPA, same concurrency without the complexity
- **No Redis for JWT** — RS256 validation is stateless; JWKS cached in-memory
- **Product RBAC** — `platform_admin`, `tenant_admin`, `domain_admin`, `policy_author`, `policy_approver`, `auditor`, `member`
- **Cerbos self-authorization** — the IAM service uses Cerbos (`resource: iam-resource`) to authorize its own API operations
- **Immutable audit log** — every write recorded with before/after state; `UPDATE`/`DELETE` revoked at DB level
- **Flyway migrations** — V1 schema + V2 demo seed data

### Mock Agents (`mock-agents/`)
Stand-ins for real domain-team services. Return canned data keyed by `relationship_id`. Support fault knobs for resilience demos.

**Wealth Management** (HTTP / FastAPI · port 8090)
| Agent | What it returns |
|---|---|
| `holdings` | Portfolio positions, allocations, asset class breakdown |
| `performance` | Returns vs. benchmark, time-weighted, attribution |
| `goal-planning` | Progress toward retirement/education goals |
| `risk-profile` | VaR, Sharpe ratio, drawdown, concentration risks |

**Asset Servicing** (MCP / FastMCP · SSE transport · port 8091)
| Agent | What it returns |
|---|---|
| `custody` | Custodian breakdown, account structures |
| `settlements` | Pending and recent settlement instructions |
| `corporate-actions` | Dividends, splits, rights issues |
| `cash` | Cash balances, sweeps, currency positions |
| `nav` | Net Asset Values for fund-linked portfolios |

### Authorization (`infra/cerbos/policies/`)
Five Cerbos resource policies, all using derived roles — no inline CEL in rules.

| Policy file | Governs |
|---|---|
| `agent_resource.yaml` | Who can invoke which agent class (role × domain × clearance) |
| `relationship_resource.yaml` | Personal entitlement checks (RM's book) |
| `domain_resource.yaml` | Domain admin operations |
| `iam_derived_roles.yaml` | Reusable derived roles for IAM self-authz |
| `iam_resource.yaml` | IAM API self-authorization (create users, deploy policies, etc.) |

### Admin UI (`admin-ui/`) — React · TypeScript · Tailwind CSS
A real enterprise IAM console served at `localhost:5180`.

| Page | What you see |
|---|---|
| Dashboard | Live stats (users, roles, teams, policies) + recent activity feed |
| Users | All principals with classification badges, role assignments, relationship books |
| Teams | Groups with domain scoping and member management |
| Roles | Role definitions with permission sets |
| Policies | Policy lifecycle — Draft → Approved → Deployed |
| Audit Log | Immutable audit trail, before/after state, filterable, exportable |

### Glass-box (`glassbox/`)
A standalone HTML/JS page at `localhost:8080/glassbox` that subscribes to the gateway's `/trace/stream` SSE endpoint and renders the live decision tree: which agents were selected, which denied, per-agent latency, the authorization verdict, synthesis timing.

---

## Prerequisites

| Tool | Version | Why |
|---|---|---|
| Docker + Compose v2 | Docker 24+ | All services run in containers |
| JDK | 21+ | Gateway + IAM service (25 preferred — JEP-491 removes virtual-thread pinning) |
| Maven | 3.9+ | Builds gateway and IAM service |
| Python | 3.11+ | Mock agents (FastAPI + FastMCP) |
| Node | 20+ | Admin UI + Playwright tests |

---

## Quick Start

```bash
# 1. Copy env file and set your Z.AI API key
cp .env.example .env
# Set ZAI_API_KEY in .env

# 2. Build the Java services
cd gateway  && mvn clean package -DskipTests -q && cd ..
cd iam-service && mvn clean package -DskipTests -q && cd ..

# 3. Start the core stack
docker compose up -d --build

# 4. Wait for all services to be healthy
./scripts/wait-for-healthy.sh

# 5. Open everything
open http://localhost:3080   # Meridian chat UI (LibreChat)
open http://localhost:5180   # Admin UI
open http://localhost:8080/glassbox  # Live trace panel
```

Default credentials (from seed data):
| Username | Password | Role |
|---|---|---|
| `admin` | `Meridian@2024` | Platform Admin |
| `rm_jane` | `Meridian@2024` | Relationship Manager |
| `rm_carlos` | `Meridian@2024` | Senior RM |
| `auditor` | `Meridian@2024` | Compliance Auditor |
| `policy_author` | `Meridian@2024` | Policy Author |
| `policy_approver` | `Meridian@2024` | CISO / Policy Approver |

---

## Services

| Service | URL | What it is |
|---|---|---|
| Meridian Chat | http://localhost:3080 | LibreChat, Meridian-branded |
| Admin UI | http://localhost:5180 | IAM console |
| Gateway API | http://localhost:8080 | OpenAI-compatible endpoint |
| Gateway Health | http://localhost:8080/actuator/health | |
| IAM Service | http://localhost:8084 | OIDC provider + user management |
| IAM JWKS | http://localhost:8084/.well-known/jwks.json | Public key set |
| OIDC Discovery | http://localhost:8084/.well-known/openid-configuration | |
| Wealth Agents | http://localhost:8090 | FastAPI HTTP agents + OpenAPI spec |
| Servicing Agents | http://localhost:8091 | FastMCP SSE agents |
| Cerbos PDP | http://localhost:3592 | Authorization policy decision point |
| Redis Stack | http://localhost:8001 | RedisInsight dev UI |
| Glass-box | http://localhost:8080/glassbox | Live trace panel |

---

## The Demo

### Hero prompt
Type this in the Meridian chat (as `rm_jane`):

```
What is the current portfolio allocation and any pending settlements for Jane Whitman?
```

**What happens:**
- Routes to ~7 agents across HTTP (Wealth) and MCP (Asset Servicing)
- Returns one grounded answer with allocation percentages and settlement references
- Glass-box shows the routing decision, per-agent latency, and authorization verdict

### The authorization story
`rm_jane` has REL-00042 (Whitman) and REL-00099 (Calderon Trust) in her book.
She does **not** have REL-00188 (Okafor Family Account) — that's Carlos's client.

```
"Tell me about the Okafor account."
```

The gateway resolves "Okafor" → REL-00188 → checks the domain authorization contract → denied. The glass-box shows the prune decision. Jane never sees Okafor's data.

### Resilience demo
```bash
# Kill the settlements agent mid-request
curl "http://localhost:8091/fault?agent=settlements&fail=true"

# Ask the hero prompt again
# → Answer still returns, explicitly states settlement data is unavailable
# → Glass-box shows the failed node
```

### Policy lifecycle demo
1. Open Admin UI → Policies
2. See `restricted-trading-policy` in **Draft** state (Emma Watson is authoring it)
3. See `compliance-access-policy` in **Approved** state (waiting for James Kim to deploy)
4. See `wealth-agent-policy` in **Deployed** state (live, enforced by Cerbos)

---

## Authorization Model

Seven authorization points, applied in order on every request:

```
1. JWT validation          — RS256 signature, expiry, issuer (IAM Service)
2. Structural RBAC         — role × agent class × domain segment (Cerbos)
3. Clearance gate          — principal.classification >= agent.min_classification (Cerbos derived role)
4. Domain scope filter     — RM's segment must include the agent's domain
5. Personal entitlement    — live call to domain authorization contract (not cached in JWT)
6. Entity binding          — zero fabricated IDs; unresolved reference → clarification, not guess
7. Response grounding      — every number in synthesis must appear in an agent output
```

**Two Cerbos resource types, one sidecar:**
- `resource: agent` — gateway routing authorization
- `resource: iam-resource` — IAM service self-authorization (create users, deploy policies, etc.)

---

## The IAM Service

The IAM service (`iam-service/`) is designed to be **reusable across any enterprise tenant** — not specific to banking.

| Feature | Detail |
|---|---|
| OIDC provider | Spring Authorization Server — JWKS, discovery, authorization code, client credentials |
| Multi-tenant | One IAM instance serves multiple tenants; policies namespaced by `tenant_id` |
| Classification tiers | Tenant-defined named tiers (`public → internal → confidential → restricted`); not hardcoded numbers |
| Product RBAC | IAM uses Cerbos to authorize its own API — same pattern as the gateway |
| Audit trail | Immutable Postgres table; `UPDATE`/`DELETE` revoked at DB level; before/after state on every write |
| Domain agnostic | `attributes JSONB` on principal; `personal_resources` table replaces hardcoded "book" concept |

Hospital would use `patient` resources. Law firm would use `matter` resources. The table is the same.

---

## Tech Stack

| Concern | Technology |
|---|---|
| Gateway runtime | Java 21 · Spring Boot 3.5 · Virtual threads |
| IAM service | Java 21 · Spring Boot 3.5 · Spring Authorization Server 1.3 · JPA · Flyway |
| Mock agents (HTTP) | Python 3.11 · FastAPI · uvicorn (auto-serves OpenAPI spec) |
| Mock agents (MCP) | Python 3.11 · FastMCP · SSE transport |
| Routing + state | Redis Stack — RediSearch HNSW vector index + RedisJSON |
| Embeddings | DJL + `all-MiniLM-L6-v2` (in-JVM, 384-dim, no external API) |
| LLM | Z.AI GLM-4.6 — OpenAI-compatible, 200K context, function-calling, streaming |
| Authorization | Cerbos PDP sidecar — ABAC, derived roles, CEL conditions |
| Identity | Spring Authorization Server (RS256 JWT, JWKS, OIDC discovery) |
| Resilience | Resilience4j circuit breaker + dual Semaphore bulkhead |
| Telemetry | Micrometer + OpenTelemetry — feeds the glass-box panel |
| Admin UI | React · TypeScript · Tailwind CSS · Vite |
| E2E tests | Playwright (Chromium, headless) |
| Load tests | k6 |
| Orchestration | Docker Compose v2 |

---

## Verification

```bash
# Full automated suite: build → start → health check → API smoke → Playwright E2E
./scripts/verify.sh

# Playwright only (requires services running)
cd e2e && npx playwright test

# Gateway unit + integration tests
cd gateway && mvn test

# IAM service tests
cd iam-service && mvn test

# Mock agent tests (Python)
cd mock-agents && pytest tests/ -v
```

---

## Seed Data

The environment seeds 8 personas, 5 groups, 3 example policies at different lifecycle stages, and 12 pre-seeded audit log entries — so every screen has real data the moment the stack starts.

**The core authz story:**
- `rm_jane` has Whitman (REL-00042) + Calderon Trust (REL-00099)
- `rm_carlos` has Whitman (shared) + **Okafor** (REL-00188) + Sterling Capital
- Ask as Jane about Okafor → denied, shown in glass-box
- Ask as Carlos about Okafor → allowed

**Policy lifecycle already populated:**
- `wealth-agent-policy` — Deployed (live)
- `compliance-access-policy` — Approved (awaiting deploy)
- `restricted-trading-policy` — Draft (Emma Watson's work in progress)

---

## Scale Profile (Phase 7)

```bash
# Adds Prometheus, Grafana, OTel collector, k6
docker compose --profile scale up -d
open http://localhost:3000   # Grafana (admin / meridian)
open http://localhost:9090   # Prometheus
```

---

## Repository Layout

```
/
├── gateway/          Java — ingress, resolver, synthesis, harness, adapters, telemetry
├── iam-service/      Java — OIDC provider, user management, product RBAC, audit log
├── mock-agents/
│   ├── wealth/       Python FastAPI — 4 HTTP agents (holdings, performance, goals, risk)
│   └── servicing/    Python FastMCP — 5 MCP agents (custody, settlements, CA, cash, nav)
├── admin-ui/         React/TS — IAM console (users, roles, teams, policies, audit log)
├── glassbox/         Standalone HTML/JS — live trace panel
├── infra/
│   ├── cerbos/       5 Cerbos policy files
│   └── ...
├── registry/         Agent manifests + JSON schema
├── e2e/              Playwright tests
├── eval/             DeepEval + Langfuse evaluation scripts
├── loadtest/         k6 scripts
├── docs/             Architecture specs, authz model, domain manifest design
├── phases/           Build phase files (loop protocol)
├── scripts/          verify.sh, wait-for-healthy.sh
├── docker-compose.yml
├── .env.example
└── BUILD_REPORT.md   Per-phase status + what to run at each human test gate
```

---

## Documentation

| Doc | What it covers |
|---|---|
| `docs/authorization-model.md` | The 7-point authorization model, industry examples, ASCII diagrams |
| `docs/authz-architecture-brief.md` | Architecture decisions — Cerbos vs OpenFGA, IAM schema design |
| `docs/clearance-tiers-and-agent-metadata.md` | Classification tier design, two-place rule, Cerbos derived roles |
| `docs/domain-manifest-and-memory.md` | Domain manifest schema, session context, memory compaction |
| `docs/input-synthesis-deep-spec.md` | Extract → Resolve → Bind — zero fabricated identifiers |
| `docs/execution-orchestration-layer.md` | Plan model, executor, harness composition |
| `docs/harness-and-telemetry-deep-spec.md` | Per-call harness pipeline, OTel spans, glass-box events |
| `BUILD_REPORT.md` | Current build status, what passes, what to run at each gate |

---

Built with Java 21 + Spring Boot 3.5 · Python 3.11 · React · Redis Stack · Cerbos · Z.AI GLM · Docker Compose
