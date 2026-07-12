# Codex task — observability, the metrics pillar: gateway SLO metrics + operator dashboard + alerts

> Companion to `T-observability-e2e.md` (traces+logs). This adds the THIRD pillar — **metrics** — plus the
> place an operator actually looks, and the early-warning. **Run AFTER `T-observability-e2e.md` lands** (they
> touch overlapping files: `application.yml`, `docker-compose.yml`, gateway code, Grafana). GATEWAY + infra;
> World-B unaffected (generic) — run `scripts/world-b-check.sh`, CRITICAL 0. Do NOT commit. Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. If ambiguous, STOP.

## Why
Observability = traces + logs + **metrics**. Traces/logs are handled by the companion; this is the metrics an
operator pages on, the dashboard they watch, and the alerts that fire *before* an incident. It matters
concretely: the load test found the gateway **livelocks at ~25 concurrent** — and there is **no saturation
metric today** to warn before that happens. Metrics is where "we found out when it wedged" becomes "we got
paged at 20." (Some Micrometer metrics exist from the D9 work — `conduit.dag.plan/fallback`, `agent.latency`,
`authz.decisions`, `fanout.duration` — build on them, don't duplicate.)

## Fix 1 — the operator SLO metrics (Micrometer → Prometheus)
Ensure these exist, correctly tagged (path=flat|dag, domain, outcome), on `/actuator/prometheus`:
- **RED at the request level:** request **Rate** (req/s), **Errors** (rate + %, by status class / reason),
  **Duration** (latency p50/p95/p99) — split flat vs DAG. (`http.server.requests` percentile histograms may
  already give latency — confirm the tags + that DAG vs flat is distinguishable.)
- **★ SATURATION — the missing, load-test-critical one:** a gauge of **in-flight requests** (and, if
  obtainable, active virtual-thread tasks / executor queue depth / carrier-pool utilization). This is the
  signal that rises toward the livelock ceiling — it's what an alert watches to page before saturation.
- **Per-stage latency:** routing, resolution, each agent (`agent.latency` exists), synthesis, `fanout.duration`
  (exists) — so a slow stage is attributable.
- **Failure signals:** `dag.fallback` (exists), coverage/entitlement denials, LLM-call errors + latency, agent
  timeouts, 5xx rate.
- **JVM/runtime:** heap, GC pause, thread counts (Micrometer JVM binders — enable if not on) — for the resource
  view the load incident needed.

## Fix 2 — the operator health/SLO dashboard (Grafana)
Add a dedicated **"Gateway Health / SLO"** dashboard (distinct from the D9 "Smart Orchestration" *business*
board — this is the *ops* board): request rate, error rate %, latency p50/95/99 (the RED panels), **the
saturation gauge with a threshold marker near the known ceiling**, per-stage latency, fan-out, failure/denial
rates, and JVM heap/GC/threads. Provisioned like the existing dashboards (`infra/grafana/provisioning/...`) so
it loads automatically.

## Fix 3 — the trace ↔ log pivot in Grafana (depends on the traceId-in-logs fix)
Wire the Tempo datasource's **derived field / trace-to-logs** correlation so a span in Tempo links to its
Loki logs by `traceId`, and Loki's `traceId` label links back to the Tempo trace. This turns the three planes
into one navigable surface — the operator's actual workflow. (Requires the companion's `traceId`-in-logs +
Promtail-running fixes to be in.)

## Fix 4 — Prometheus alert rules (early warning)
Add alert rules (`infra/prometheus`/alerting): **saturation approaching the ceiling** (the pre-livelock page),
error-rate spike, latency-SLO breach (p95 over a budget), `dag.fallback` rate spike (the smart path silently
stopped firing), coverage-service / JWKS unreachable, gateway down/health-fail. Each with a one-line "what it
means / what to check" so it's actionable.

## Fix 5 — a gateway-level SLO check ("gateway eval for metrics", separate from the domain eval)
A small, runnable gateway-SLO check (reuse `tests/load/coldstart-load-test.js`) that drives a fixed load and
reports the gateway's OWN numbers — latency percentiles, throughput, error rate, peak saturation — as a
gateway performance snapshot, DISTINCT from the domain/DeepEval scoring. So "how is the gateway performing"
is answerable on demand and comparable across builds, without polluting the domain eval.

## HARNESS / GATE
- The SLO metrics (esp. the **in-flight saturation gauge**) are present on `/actuator/prometheus` with correct
  tags; a small test asserts the saturation gauge **moves** under a couple of concurrent requests and returns
  to zero when idle (proves it tracks real in-flight, not a static number).
- The Gateway Health/SLO dashboard loads (Grafana provisioning valid) and its panels bind to real metrics
  (drive a little traffic, confirm non-empty).
- The Tempo↔Loki pivot resolves (a trace links to its logs by traceId).
- Alert rules validate (`promtool check rules` or equivalent); the saturation alert has a sane threshold.
- The gateway-SLO check runs and prints the numbers.
- `mvn test` green; `scripts/world-b-check.sh` CRITICAL 0.

## Constraints / anti-gaming
- The saturation metric must reflect REAL in-flight work (assert it moves), not a placeholder — it's the one
  that guards the livelock. Alerts must have real thresholds, not disabled. Don't duplicate the D9 metrics;
  extend. World-B clean. Do NOT commit.

## Report
Metrics added (names + tags, esp. saturation); the Gateway Health/SLO dashboard; the Tempo↔Loki pivot config;
the alert rules + thresholds; the gateway-SLO check + a sample run; the harness evidence (saturation gauge
moves, dashboard binds, pivot resolves); mvn/World-B. STOP and report anything unanticipated.
