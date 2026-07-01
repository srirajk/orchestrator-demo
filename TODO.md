# Conduit — Open TODO / Backlog

> **Live pass done on a clean-slate rebuild (2026-07-01).** Stack was `down -v` → rebuilt →
> `up` → seeded → verified. Almost everything closed; see status below.

## ✅ Done + verified live

### 1. iam-service Dockerfile → multi-stage source build
Rebuilt from source inside Docker (no prebuilt-jar). Clean `docker compose build iam-service`
succeeds **and SSO round-trips** (`login success` for rm_jane) on the new image.

### 2. Gateway histogram buckets
`percentiles-histogram` enabled for http.server/http.client/intent.classify. Verified live:
`chat p95 = 22.6s`, `outbound agent p95 = 5.4ms`, `intent-classify p95 = 3.9s` — the p95/p99
latency panels compute.

### 3. Intent-distribution panels
`chat_intent_total` is tagged **`type`** (not `intent`). Fixed `by (intent)` → `by (type)` +
legend on conversation-trace / conduit-gateway / gateway-performance. Verified: breakdown
returns (FETCH_DATA=7, …).

### 4. Live-Demo circuit-breaker panel
Gateway uses no Resilience4j TimeLimiter → `resilience4j_timelimiter_calls_total` never exists.
Replaced that dead "timeouts" series with `resilience4j_circuitbreaker_slow_calls` ("slow calls").

### 5. Langfuse health-check noise
Broadened the otel `filter/drop-actuator` to also drop `/health*` / `/metrics` / bare `GET /`.
Verified: **no more `GET /health` traces** in Langfuse — only real `chat-turn` / `agent.*` spans.

### 7. Teardown → rebuild reproducibility
The clean-slate `down -v` → build → `up` → `seed-users` sequence itself proved reproducibility:
Flyway seed, Langfuse self-seed, Grafana provisioning, and OIDC SSO all came back with **no
manual steps**. ✅

## ⏳ In progress / needs you

### e2e re-validation — RUNNING
Full Playwright suite launched on the rebuilt stack (13 specs / ~89 tests). Auth specs
(`03-jwt-identity`) already passing → SSO fix didn't regress. Final tally pending (~50 min run).

### 6. Visual Grafana screenshots — needs one of:
The **substance is already validated via API** (every panel's metric confirmed to return data;
broken ones fixed above). For actual pixel screenshots, pick one:
- **(a)** log into Grafana (`admin`/`changeme`) in the automated Chrome tab → assistant screenshots
  all 7 (zero config change); **or**
- **(b)** assistant enables Grafana anonymous viewer (small reversible config + Grafana restart),
  then screenshots headlessly; **or**
- **(c)** install the `grafana-image-renderer` plugin for headless PNG export.
(The image-renderer plugin is **not** installed, so the render API only returns a placeholder.)

## Minor follow-ups (not blocking, found during the pass)
- Two edge panels reference metrics that don't exist: `resolver_fallback_total` (Live-Demo
  "Resolver fallbacks") and `spring_security_authentications_seconds_count` (Live-Demo "Security
  filter chain"). They degrade gracefully (other series still render). Fix or drop those targets.
- Langfuse still has some empty-named (`∅`) spans (agent internal spans) — separate from the
  health noise; could be named/filtered later.

## Done earlier this session
- OIDC SSO fixed (`client_secret_post` + id_token email/name) + committed
- Conduit rename validated (89/89 Playwright e2e)
- README rewritten as master doc; PROJECT-OVERVIEW stubbed; DIAGRAM-PROMPTS.md added
