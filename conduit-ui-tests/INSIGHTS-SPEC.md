# Conduit Insights — Build Spec (native, governed analytics)

## Goal
A native, **admin-gated "Insights"** section inside Conduit: 7 executive-grade dashboards (per the
approved mockup) fed by Conduit's own **Insights API** that aggregates **Prometheus** (ops) + **Langfuse**
(cost/eval), gated by the **same Axiom/ABAC engine** as chat. Grafana/Langfuse remain for engineering.

## Architecture (decided)
- The Insights API is a **cleanly-bounded module INSIDE the gateway** — `gateway/src/main/java/ai/conduit/gateway/insights/`,
  exposed at **`/v1/insights/*`**. NOT a new microservice (reuse the gateway's JWT/Axiom auth, virtual
  threads, Resilience4j, config). **Seamed behind interfaces** for later extraction (hard-rule g).
- `MetricsSource` interface → `PrometheusMetricsSource` (PromQL over the Prometheus HTTP API) +
  `LangfuseMetricsSource` (Langfuse API for cost/eval).
- `InsightsController` → `GET /v1/insights/boards/{boardId}` returns that board's panels as clean JSON
  (`{panels:[{id,title,type,value|series|rows,unit,status}]}`). One call = one board.
- The Conduit UI renders native React boards consuming this via the BFF (`/api/insights/...`).

## Virtual threads — the discipline (this is the point: use them *extremely* well)
- Each board's panel queries **fan out concurrently** via **`StructuredTaskScope`**, joined with a **deadline**.
- **Partial-tolerant harvest** — survivors render, a failed/slow panel is marked `status:"unavailable"`;
  a board NEVER hangs on one query. Mirror the proven `AgentHarness` fan-out pattern.
- **Blocking `java.net.http.HttpClient`** per query — the VT parks on I/O; no reactive/CompletableFuture code.
- **No pinning** — NO `synchronized` around the blocking I/O (use `ReentrantLock`); carriers stay free.
- **Bounded downstream concurrency** — a `Semaphore` caps concurrent outbound queries (backpressure to
  Prometheus/Langfuse). VT makes *waiting* free; the *upstream* still needs protection.
- **Per-query timeout** (Resilience4j timelimiter) + **short-TTL cache (15–30s)** on the Langfuse side.
- Dedicated **bounded VT executor** for insights, isolated from the request pipeline.

## RBAC — admin-gated via the same engine (the governance flex)
- Add a role/permission **`conduit_admin`** (or `insights:read`) in the IAM seed + a Cerbos policy.
- Gate **`/v1/insights/*`**: a `chat_user` → **403**; an admin → data. Enforce through the **same
  Cerbos/ABAC path** chat uses. **Additive only — do NOT change any existing auth decision.**

## Panel → metric map
**Live in Prometheus today:** `conduit_request_outcome_total`, `conduit_authz_decisions_total`,
`conduit_agent_calls_total`, `conduit_agent_latency_seconds` (histogram), `conduit_fanout_duration_seconds`,
`conduit_circuit_breaker_state`, `conduit_bulkhead_executing/queued`, `intent_classify_duration_seconds`,
`chat_intent_total`, `http_server_requests_seconds` (p50/95/99), `jvm_threads_*`.
**Live in Langfuse:** token cost, token counts, eval scores (groundedness/faithfulness/relevancy).
**To ADD (small, additive Micrometer instrumentation):**
- TTFT timer (server-side, or derive from Langfuse).
- Resolver **selection** counter per agent (distinct from `agent_calls`).
- Per-agent **denial** dimension (label on `authz_decisions` or a companion counter).
- Adoption counter by **role** (not per-user — cardinality).

## World-B compliance (hard gate)
- **NO hardcoded domain/client/entity names in the insights module.** "Cost by domain" / "questions by
  domain" aggregate by the **domain label carried on the metric** (dynamic), never a hardcoded list.
- Run `scripts/world-b-check.sh` — CRITICAL must stay **0**.

## Config (no hardcoding)
- `conduit.insights.prometheus-url`, `conduit.insights.langfuse-url` + API keys via env / `application.yml`.

## Build split
- **Backend (Opus agent):** the `insights` module + `MetricsSource` + the VT/structured-concurrency
  engine + RBAC + the metric additions + the panel queries. Verify via `curl /v1/insights/boards/{id}`.
- **UI (Codex):** the 7 native React boards in `apps/chat/web` matching the mockup, consuming
  `/api/insights/*` via the BFF, on an admin-gated route.

## Definition of done
- `curl` each `/v1/insights/boards/{1..7}` returns real JSON (survivors + any `unavailable` panels).
- `chat_user` token → 403 on `/v1/insights/*`; admin token → 200.
- `world-b-check.sh` CRITICAL 0; gateway builds + healthy; committed on `feat/conduit-chat`,
  "Approved by Sriraj.", no AI attribution.
