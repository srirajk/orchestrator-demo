# Codex task spec — D9: Smart-Orchestration metric dashboards

> Self-contained build spec. Hand to Codex. Goal: make the multi-step orchestration **visible and
> measured** on the dashboards, now that three domains run live (wealth `concentration`, insurance
> `renewal_risk`, servicing `settlement_risk`). Do NOT commit — a reviewer commits after review.
> Do NOT fabricate data: if a metric isn't emitted, either wire the (trivial) emission in the gateway
> OR omit the panel with a clear note. World-B CRITICAL 0. Don't touch uac/backend.

## Repo / environment
- Repo: `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
- Stack running: docker compose project `orchestrator-demo`. Grafana (`grafana` container, host **:3000**, admin/changeme), Prometheus (`conduit-prometheus`, host **:9090**, scrapes the gateway via `infra/prometheus.yml`), Tempo (traces), Langfuse (**:3030**), and the client-facing **Insights** app (`apps/insights`, served ~**:5175**). The gateway exposes Micrometer metrics at `/actuator/prometheus`.

## What already exists (READ FIRST, ground yourself)
- **Retired Grafana dashboards** to revive/adapt: `infra/grafana/_retired/*.json` (conduit-gateway, conduit-demo, gateway-performance, resource-usage, compaction). Check `infra/grafana/` provisioning (datasource + dashboard provider yaml) — figure out where a live dashboard must live to be auto-loaded.
- **The Insights client app**: `apps/insights` — explore its structure, its DATA SOURCE (does it read Prometheus, the gateway trace API `/trace/*`, Langfuse, or its own aggregation?), and its existing panels. Upgrade THIS for the client-facing story only if its data plumbing supports the new metrics; otherwise say so and focus on Grafana.
- **My panel plan**: `docs/EVAL-AND-DASHBOARDS-MULTISTEP.md` (the D8/D9 intent).

## Real metrics + trace events you have to work with (verify names against the code/`/actuator/prometheus`)
Micrometer counters/timers (prefix `conduit.` → Prometheus `conduit_*`): `conduit.agent.calls`, `conduit.agent.denials` (tag agentId), `conduit.agent.latency`, `conduit.authz.decisions` (tags decision/resource_type/source), `conduit.fanout.duration` (tag agent_count; p50/p95/p99), `conduit.chat.routing`, `conduit.assistant.domain`, `conduit.intent` (tag type FETCH_DATA/FOLLOW_UP/CLARIFY), plus resilience (`conduit.bulkhead.*`) and auth (`conduit.authz.revocations`).
Glass-box trace events (gateway `/trace/*`, also drive the demo): `plan_graph` (nodes + dependsOn edges + status — THE multi-step signal), `agent_start`, `agent_complete` (durationMs, status), `gate`, `entitlement_check`, `check_denied`, `request_complete`.

## Deliverable — a "Smart Orchestration" Grafana dashboard (primary) + Insights panels (if supported)
Build panels that tell the multi-step story. Prefer real Prometheus metrics; where the signal only exists in `plan_graph`/trace events, note what gateway emission (a small Micrometer counter, e.g. `conduit.dag.plan` tagged domain/goal/node_count, and `conduit.dag.fallback` tagged reason) would make it a first-class metric — and add that emission in the gateway if it's a few lines (World-B clean, generic; e.g. increment where `tryDag` builds the plan / falls back). Panels:
1. **Multi-step vs single-step share** — how many answers used a DAG vs flat (from a `conduit.dag.plan` counter vs total `conduit.intent{type=FETCH_DATA}`).
2. **Fan-out depth / node count** — distribution of plan node counts (histogram from the plan counter's `node_count` tag).
3. **Per-agent + per-step latency** — `conduit.agent.latency` and `conduit.fanout.duration` p50/p95/p99, split by agent (so a slow producer is visible).
4. **Entitlement decisions & denials by gate** — `conduit.authz.decisions{decision}` and `conduit.agent.denials{agentId}` (proves the entitlement story live).
5. **Answers by domain** — `conduit.assistant.domain` (wealth/insurance/servicing) so the three-domain reach is visible.
6. **(If wired) DAG fallbacks by reason** — `conduit.dag.fallback{reason}` (ambiguous-goal / unmet-input / regate-prune) — surfaces "the smart path silently didn't fire," a known gap.
Revive the still-relevant retired panels (gateway health, fanout duration) into the same board so it's one coherent dashboard, not five dead ones.

## Generate real data, then PROVE it populates (don't screenshot an empty board)
- Drive the three live verticals through the BFF (http://localhost:8099, real OIDC — reuse `scripts/seed-conversations-via-bff.py` login; users: `rm_jane` wealth, `uw_sam` insurance, the servicing-ops user) so the metrics have real values: a concentration question, a renewal question, a settlement question, plus one entitlement-denied question (a book the user doesn't cover).
- Confirm Prometheus is scraping the gateway (`http://localhost:9090` → targets up; query `conduit_agent_latency_*`, `conduit_authz_decisions_*`).
- Confirm the Grafana dashboard loads and every panel shows **non-empty** data from that traffic. Capture evidence (panel titles + a value from each, or a screenshot path).
- If you upgraded Insights: confirm its panels render the new data too.

## Constraints / report
- Any new gateway metric emission: World-B clean (no domain literals; tags come from manifest/trace data), `scripts/world-b-check.sh` CRITICAL 0, `cd gateway && mvn test` green. Rebuild gateway if you added emissions.
- Do NOT commit. Report: the dashboard JSON path + provisioning, any gateway metric added (and why), the exact panels + their PromQL, and the proof each panel populated from live 3-vertical traffic. If a panel can't be backed by real data without heavy work, OMIT it and say so — never a fabricated/placeholder panel.
