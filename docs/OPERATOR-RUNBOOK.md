# Conduit Gateway — Operator & Demo Runbook

> The single "where is everything, what is it, how do I log in, how do I demo it" doc.
> Everything below was verified live against the running stack.

---

## 1. What Conduit is

Conduit is a **custom enterprise AI gateway for a bank**. A user types one plain-English
question into a chat UI; the gateway:

1. classifies intent and extracts the entities referenced (no fabricated IDs),
2. semantically routes to the specialist agents the question needs — **across two protocols
   (HTTP + MCP) and multiple business domains**,
3. enforces that the user is entitled to see the data (structural role gate + data-aware
   book-of-business),
4. fans out to the agents in parallel, tolerating failures,
5. synthesizes one grounded, attributed answer and streams it back,
6. shows the entire decision live in a **glass-box** panel.

### The product thesis — "World B"

The gateway carries **zero embedded domain knowledge**. A new business domain is onboarded
by adding **manifest JSON + a CRM/coverage service URL — never by changing gateway Java**.
This is proven in the stack today: alongside **wealth** and **asset-servicing**, an
**insurance** domain was added by manifest alone (2 agents, its own coverage service, one
line of authz config) and it routes, resolves, answers, and enforces entitlements with no
gateway code change.

The deterministic gate `scripts/world-b-check.sh` greps `gateway/src/main/java` for any
domain coupling and must report **CRITICAL: 0** (it does).

---

## 2. Architecture at a glance

```
  LibreChat (chat UI)                         Glass-Box (live decision trace)
        │  POST /v1/chat/completions (SSE)            ▲  GET /trace/stream (SSE)
        ▼                                             │
 ┌───────────────────────────── Gateway (Java 21, Spring Boot, virtual threads) ──────────┐
 │  Intent+Entity (LLM) → Resolve (vector route) → Entitlement (Cerbos + coverage) →       │
 │  Fan-out (HTTP + MCP adapters, Resilience4j) → Synthesis (LLM, grounded) → SSE stream    │
 └─────────┬───────────────┬───────────────┬───────────────┬───────────────┬───────────────┘
           │ vector route   │ identity       │ structural     │ book-of-       │ telemetry
           ▼                ▼                ▼ authz           ▼ business       ▼
        Redis Stack      IAM (OIDC,        Cerbos PDP      Coverage services  OTel → Langfuse
        (HNSW + JSON)    RS256/JWKS)                       (wealth, insurance)  + Tempo/Loki/Prom

   Agents:  Wealth (FastAPI, HTTP) · Asset-Servicing (FastMCP, MCP) · Insurance (FastAPI, HTTP)
```

The glass-box renders six stages per request: **Request Received → Intent Classification →
Agent Routing (selected + not-selected) → Entitlement Gate → Agent Fan-out (per-agent
latency, HTTP/MCP) → Answer Synthesis**, then a Request-Complete summary (total latency,
N/M agents succeeded).

---

## 3. Service map — URLs, ports, what each is, how to log in

> Host ports as mapped by `docker-compose.yml`. "Login" = what you need to get in.

### The screens you actually open during a demo

| Service | URL | What it is | Login |
|---|---|---|---|
| **LibreChat** (Conduit-branded) | http://localhost:3080 | The chat UI the banker uses | OIDC via IAM — sign in with a seeded user, e.g. **`rm_jane` / `Meridian@2024`**. A "Demo User" session is typically already active. |
| **Glass-Box** | http://localhost:4000 | Live decision trace for each request | None. Open it, confirm top-right says **"Connected"**, then send a prompt in LibreChat. |
| **Langfuse** | http://localhost:3030 | LLM traces, sessions (conversation = session, 1→many turns), eval scores | **`admin@meridian.bank` / `changeme`** (`LANGFUSE_ADMIN_PASSWORD`). |
| **Grafana** | http://localhost:3000 | Dashboards: JVM/CPU/mem (Prometheus), logs (Loki), distributed traces (Tempo) | **`admin` / `changeme`** (`GRAFANA_ADMIN_PASSWORD`). |
| **Admin UI** | http://localhost:5180 | Agent/manifest registry admin | Served static; admin actions need a `domain_admin`/`platform_admin` token. |

### Supporting services (usually no need to open directly)

| Service | Host port | What it is |
|---|---|---|
| Gateway | 8080 | The one JVM service (`/v1/chat/completions`, `/v1/models`, `/trace/**`, `/admin/**`, `/debug/**`) |
| IAM service | 8084 | Identity provider — OIDC, RS256/JWKS; issues the signed token verified at every hop |
| Cerbos PDP | 3594 (HTTP) / 3595 (gRPC) | Structural authorization (role × resource class) |
| Wealth agents (HTTP) | 8081 | FastAPI; auto-serves `/openapi.json`; 4 agents (holdings, performance, risk_profile, goal_planning) |
| Asset-Servicing agents (MCP) | 8082 | FastMCP/SSE; 5 tools (custody_positions, settlement_status, cash_management, nav, corporate_actions) |
| Insurance agents (HTTP) | 8087 | FastAPI; 2 agents (policy_details, claim_status) — the World B proof domain |
| Wealth coverage | 8086 | Book-of-business for wealth (DISCOVER/CHECK/RESOLVE) |
| Insurance coverage | 8088 | Book-of-business for insurance |
| Embeddings | 8083 | In-JVM-adjacent MiniLM (384-dim) for routing |
| Redis Stack | 6379 (+ RedisInsight 8001) | RediSearch HNSW vector index + RedisJSON (agents, principals, sessions) |
| Langfuse worker / DB | — | Async ingestion + Postgres/ClickHouse/MinIO backing Langfuse |
| OTel Collector | 4317/4318 (+ 8889) | Spans → Langfuse + Tempo |
| Tempo / Loki / Prometheus | 3200 / 3100 / 9090 | Traces / logs / metrics stores behind Grafana |
| cAdvisor | 8090 | Container CPU/mem (note: limited on Docker Desktop macOS — see §9) |

### Seeded identities

| User | Password | Segment(s) | Book highlights |
|---|---|---|---|
| `rm_jane` | `Meridian@2024` | wealth + servicing | Whitman **REL-00042** (in book) · Okafor **REL-00188** (NOT in book → denied) |
| `rm_carlos` | `Meridian@2024` | wealth | wealth-private-banking |
| `rm_guest` | `Meridian@2024` | wealth | no domain membership |
| `uw_sam` | `Meridian@2024` | insurance | **POL-77001/77002** (in book) · POL-88003 (uw_dana's → denied) |

---

## 4. Run & verify

```bash
# 0. Prereqs: Docker + Compose v2, open egress to OpenAI (and Docker Hub/GHCR).
cp .env.example .env           # then set CONDUIT_LLM_SYNTHESIZER_API_KEY (OpenAI)

# 1. Bring up the core stack (everyday demo)
docker compose up -d

# 2. Seed the demo principals (idempotent)
REDIS_HOST=localhost REDIS_PORT=6379 bash scripts/seed-users.sh

# 3. Optional: start the continuous eval sidecar (scores live turns every 5 min)
docker compose --profile eval up -d eval-worker

# 4. Verify everything is wired (build → up → smoke → e2e → eval)
./scripts/verify.sh            # includes scripts/world-b-check.sh as a hard gate
```

Open **LibreChat** (3080) + **Glass-Box** (4000) side by side and you're ready to demo.

---

## 5. The demo script — four beats

> Open Glass-Box (confirm "Connected") and LibreChat side by side. Log in as `rm_jane`.

### Beat 1 — Hero (cross-protocol fan-out + grounded synthesis)
**Prompt:** *"Give me a complete overview of the Whitman relationship: holdings, performance,
settlement status, and cash position."*
**Watch:** one streamed, grounded answer (REL-00042, $1,967,000, allocations, the
settlement, cash). Glass-box shows intent 95%, **5 agents selected** (and which were *not*),
entitlement **ALLOWED**, fan-out across **HTTP + MCP** with per-agent latencies, synthesis
complete. Then ask a follow-up — *"Which single holding is the largest, and how much cash is
unsettled?"* — answered from session memory (multi-turn carry-forward).

### Beat 2 — Resilience (kill an agent mid-request)
```bash
docker stop conduit-servicing-mcp        # kills the MCP agents
```
Re-ask the hero prompt. **Watch:** the answer still returns from the HTTP survivors and
**states the missing pieces** ("Settlement Status: Data unavailable (status: FAILED)").
Glass-box shows the two MCP nodes in **red "failed"**, **3/5 succeeded**. Restart:
```bash
docker start conduit-servicing-mcp
```

### Beat 3 — Entitlement (out-of-book denial)
**Prompt (as `rm_jane`):** *"Show me the portfolio and holdings for the Okafor relationship."*
**Watch:** *"Access denied for this client relationship."* — Okafor (REL-00188) is not in
rm_jane's book. Glass-box shows **0 agents succeeded** (pruned before fan-out).
*Same model on the second domain:* as **`uw_sam`**, `POL-77001` answers but `POL-88003`
(another underwriter's) is denied — proving World B authz, not just wealth.

### Beat 4 — Clarification (ambiguous prompt)
**Prompt:** *"Show me the latest holdings and performance."* (no relationship named)
**Watch:** *"Which client relationship are you asking about?"* — a scoped, deterministic
clarification (`extracted ∩ required_context = ∅`). Answer *"The Whitman relationship"* and
it proceeds to the grounded answer.

---

## 6. Observability & eval — the single-pane story

- **Glass-Box (4000):** the live, per-request decision narrative (the demo's hero view).
- **Langfuse (3030):** every turn is a trace; **a conversation is a session** (1→many
  traces). Each trace carries the prompt (input) **and** the streamed answer (output), the
  agent spans (HTTP + MCP), token usage, and `langfuse.metadata.domain` for cost-by-domain.
- **Grafana (3000):** JVM/CPU/memory (Prometheus), gateway logs (Loki — filter by the
  `conv-…` id from the gateway logs), and distributed traces (Tempo).
- **Continuous eval (`eval` profile):** every 5 min the worker scores recent **chat-turns**:
  *grounding* + *partial-honesty* (deterministic) and *relevance* + *safety* (LLM judge),
  posted back to Langfuse as scores. It **dedups** already-scored turns and **samples** under
  load. Grounding is **N/A** for follow-up/clarify/denial turns (no agent data to ground
  against) rather than a misleading 0.
- **Release gate (DeepEval):** `scripts/eval-gate.sh` runs the routing-accuracy + faithfulness
  gate offline (CI / pre-ship), separate from the always-on continuous loop.

### Model / provider strategy (performance-driven)

| Use | Provider | Why |
|---|---|---|
| Gateway intent/extraction/synthesis | **OpenAI** `gpt-4o-mini` | realtime, concurrency-critical |
| Mock agents (wealth/servicing/insurance) | **OpenAI** `gpt-4o-mini` | per-request narratives under fan-out |
| Continuous eval judge | **OpenAI** `gpt-4o-mini` | async + low-volume; reliable structured output |
| DeepEval release gate | offline batch | not realtime |

Every call site is provider-swappable via env (`CONDUIT_LLM_*`, `JUDGE_*`). To move the
async judge back to GLM, point `JUDGE_BASE_URL/JUDGE_API_KEY/JUDGE_MODEL` at Z.AI and raise
`EVAL_JUDGE_THROTTLE_MS` (the funnel — throttle + backoff — is retained either way).

---

## 7. Scale proof (k6)

Light concurrent test (ramp to 10 VUs, full pipeline on OpenAI):
```bash
docker run --rm --network orchestrator-demo_default -e GATEWAY_URL=http://gateway:8080 \
  -v "$(pwd)/tests/load:/scripts" grafana/k6 run /scripts/load-test-light.js
```
Last run: **error rate 0.00%** (0/146), **TTFT p95 7.5s** (median 1.72s), 292 checks 100%
passed — virtual-thread gateway handled concurrent streaming with zero failures.

---

## 8. Verified state (this stack)

- **World B clean** — `scripts/world-b-check.sh`: CRITICAL 0; 11 agents across 3 domains
  (4 wealth + 5 asset-servicing + 2 insurance).
- **Four demo beats** pass end-to-end (hero, resilience, deny, clarification) + multi-turn.
- **Insurance entitlement** (uw_sam allow/deny) proves the authz model on a second domain.
- **Glass-box** renders the full live trace (Connected, per-agent latencies, HTTP+MCP).
- **Langfuse** traces carry input **and** output; **continuous eval** posts real
  relevance/safety/grounding scores.
- **k6** light load: 0% errors at 10 concurrent VUs.

---

## 9. Known limitations & backlog (none block the demo)

- **Glass-box deny rendering:** on a full entitlement denial the Entitlement Gate panel stays
  "WAITING" rather than showing an explicit "DENIED" (the outcome is still clear — 0 agents
  ran). Cosmetic.
- **Grounding on synthesized aggregates:** data turns can score <1.0 when the answer includes
  computed totals/projections not verbatim in any single agent output. This is a real signal,
  not hallucination.
- **cAdvisor on Docker Desktop macOS:** only reports limited container labels (no per-name
  CPU/mem) — a LinuxKit limitation. Deferred to k8s.
- **GLM judge:** the Z.AI account was out of balance (`code 1113`); the async judge runs on
  OpenAI until recharged (one env flip to switch back).
- **Single environment:** this stack is "prod" only — no separate test/staging env tagging.

---

## 10. Troubleshooting quick reference

| Symptom | Cause / fix |
|---|---|
| LibreChat shows a blank reply | SSE format / gateway down — `docker compose ps gateway`; check `/v1/models`. |
| Everything denied for a user | Principals not seeded — run `scripts/seed-users.sh` (Redis was wiped). |
| Glass-box stuck "Connecting/Reconnecting" | Gateway restarting, or `CONDUIT_GLASSBOX_ALLOWED_ORIGINS` too strict (default `*`). Reload after gateway is healthy. |
| Glass-box "Waiting for a request" after sending | It only shows traces that arrive *after* it connects — confirm "Connected", then send. |
| Langfuse trace shows no logs for a convo | LibreChat doesn't forward a conversation id; the gateway derives its own `conv-…`. Grab it from gateway logs and search Langfuse/Loki by that. |
| Grafana "No data" on first run | Click "Run query" a second time. |
| Continuous eval scores all 0.50 | LLM judge failing (rate limit / balance) — check `JUDGE_*`; the funnel falls back to 0.5 only after exhausting retries. |
| Insurance queries denied for uw_sam | Re-seed (`uw_sam`) + ensure Cerbos loaded the insurance segment line (`docker compose restart cerbos`). |
