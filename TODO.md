# Conduit — Open TODO / Backlog

> Status of the observability/hardening pass. **Stack is currently DOWN** (intentionally — working
> on another project). Items marked ⏳ have their **file edits done** but need a live `conduit`
> stack to rebuild + verify. Items marked ▶ need the live stack (and some, the user).

## Real fixes (functional)

### 1. ⏳ Convert iam-service Dockerfile to multi-stage source build — **EDIT DONE**
`iam-service/Dockerfile` rewritten to build from source inside Docker (Maven stage → JRE stage),
mirroring `gateway/Dockerfile`, preserving ZGC flags + port 8084 + wget. No more prebuilt-jar trap.
- **Verify on next up:** `docker compose build iam-service` from a clean `target/` succeeds and SSO
  still works.

### 2. ⏳ Enable gateway histogram buckets — **EDIT DONE**
Added `management.metrics.distribution.percentiles-histogram` for `http.server.requests`,
`http.client.requests`, and the custom `intent.classify.duration` timer in
`gateway/src/main/resources/application.yml`. This fixes **both** the p50/p95/p99 latency panels
(Gateway — Performance, Conversation Trace) **and** the Intent Classify Latency panel.
- **Verify on next up:** rebuild gateway, generate traffic, confirm
  `*_seconds_bucket` series exist and the p95 panels populate.

### 3. ▶ Reconcile dashboard metric names (was "verify intent metric") — **DO LIVE, DON'T DO BLIND**
Bigger than first scoped: several panels query metric names that don't match what's emitted. Root
cause = the collector's `conduit_` prefixing is **inconsistent** (custom app metrics get
`conduit_` e.g. `conduit_agent_calls_total`, but Spring's `http_server_requests_*` stay
unprefixed). Confirmed mismatches so far:
- `chat_intent_total` (by `intent`) → real is `conduit_chat_intent_total` (tag is **`type`**, not
  `intent`). [gateway-performance, conversation-trace]
- `intent_classify_duration_seconds_bucket` → `conduit_intent_classify_duration_seconds_bucket`
  (+ needs #2's buckets). [gateway-performance]
- `container_cpu_usage_seconds_total`, `container_memory_usage_bytes` → cAdvisor metrics; **confirm
  cAdvisor is even in the stack**, else replace/remove those Resource-Usage panels.
- `resilience4j_circuitbreaker_calls_seconds_count`, `resilience4j_circuitbreaker_state` → confirm
  the R4j micrometer binder emits these (and under what prefix). [conduit-demo]
- **Must be done with a live Prometheus to query exact names + verify each panel.** Editing blind
  risks making dashboards worse.

## Minor

### 4. ▶ Replace the timelimiter panel (was "bind timelimiter metrics") — **DO LIVE**
The gateway **doesn't use Resilience4j TimeLimiter at all**, so `resilience4j_timelimiter_calls_total`
will never exist. Fix = **edit the `conduit-demo` panel** to a metric that does exist (circuit
breaker / bulkhead / fanout) or remove it. Do alongside #3.

### 5. ⏳ Filter Langfuse health-check trace noise — **EDIT DONE**
The otel `filter/drop-actuator` only matched `/actuator.*`, but the noise was `GET /health`
(FastAPI agents use `/health`). Broadened `infra/otel-collector.yaml` to also drop `/health*`,
`/metrics`, and bare `GET /`.
- **Verify on next up:** restart otel-collector, generate traffic, confirm Langfuse shows only
  `chat-turn` traces (no `GET /health`).

## Validation pending (need live stack + user)

### 6. ▶ Visual Grafana dashboard validation (all 7) via Chrome
Needs the stack up **and** the user to log into Grafana in the automated tab (assistant can't type
the admin password). Do after #2/#3/#4 so panels are actually populated.

### 7. ▶ Full teardown → rebuild dry run (`down -v` → up)
Prove reproducibility: `docker compose down -v` → `up -d` → `seed-users.sh` → smoke. Confirms
Flyway seed, Langfuse self-seed, Grafana provisioning, and OIDC SSO return with no manual steps.
Do **after** #1. Host prereqs: `/etc/hosts` `127.0.0.1 host.docker.internal`; real LLM key in `.env`.

---

## Next time `conduit` is up (the live pass)
1. `docker compose build gateway iam-service` (both now source-built) → `up -d`.
2. `bash scripts/seed-users.sh`, generate a little traffic.
3. **Re-run e2e** — `scripts/verify.sh` (build→up→smoke→e2e→eval) or
   `cd tests/e2e && npx playwright test --timeout=240000`. Confirms the **SSO fix** + the
   observability/build changes didn't regress (last full run was 89/89, *before* those). Watch the
   `03-jwt` / `09-cerbos` auth specs especially.
4. Query Prometheus for exact metric names → fix dashboard panels (#3, #4).
5. Confirm buckets/latency panels populate (#2), Langfuse health-noise gone (#5).
6. User logs into Grafana → screenshot all 7 (#6).
7. Optional `down -v` reproducibility run (#7).

## Done earlier this session (do not redo)
- OIDC SSO fixed (`client_secret_post` + id_token email/name) + committed
- Conduit rename validated (89/89 Playwright e2e)
- README rewritten as the master doc; PROJECT-OVERVIEW stubbed; DIAGRAM-PROMPTS.md added
- Tracing verified (Langfuse `chat-turn` w/ output, Loki, Tempo — keyed by `convId`)
