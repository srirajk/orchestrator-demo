# Phase 5 — Governance & the Glass-Box

**Goal:** Entitlements are enforced (an RM sees only their own book) and the **glass-box**
panel shows the whole decision live. This is the "bank-grade + transparent" beat.

**Milestones:** M8, M9
**Read first:** `docs/authorization-abac-cerbos-deep-spec.md` (Cerbos, prune-before-fan-out,
identity seam), `docs/harness-and-telemetry-deep-spec.md` (spans + glass-box events),
`docs/master-build-plan-consolidated.md` §8 (guardrails).

## Build (scope — simple path only)
- **Cerbos PDP** sidecar + `relationship` and `agent` ABAC policies. Seed principal
  attributes in Redis (e.g. `rm_jane` with her book of relationships).
- **Prune-before-fan-out** via `PlanResources` (entitlement as a routing filter), plus an
  entity-level check after resolution. Identity comes from the LibreChat-forwarded `user_id`
  (stubbed seam — no real IdP yet).
- **Telemetry:** one OTel root span per request, child spans per stage/agent; a
  `/trace/stream` SSE feed.
- **Glass-box:** a standalone single-page panel subscribing to `/trace/stream`, showing
  routing, per-agent protocol + latency + outcome, and the entitlement decision.
- **Guardrail:** in the synthesis prompt, **separate data from instructions** (agent outputs
  are untrusted); PII-aware trace logging (redact per `data_classification`).

## Do NOT build
Clarification, resilience demo wiring, rebrand. (Grafana/Prometheus stay off — `scale`
profile only.)

## Automated acceptance (you run these)
- As `rm_jane`: the **Whitman** relationship (REL-00042) is allowed; the **Okafor**
  relationship (REL-00188, out of book) is **denied/pruned**.
- The glass-box receives events for a hero request (≥7 agents, both protocols, latencies).
- An agent output containing an injected instruction does **not** change behavior.

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. Open the **glass-box** page beside LibreChat.
2. Type the hero prompt → watch the panel light up: routing, ≥7 agents across both protocols,
   latencies, and the entitlement decision.
3. As `rm_jane`, ask about the **Okafor** relationship → confirm it is **denied / filtered**
   (not answered), and shown denied in the glass-box.

**PASS =** the human can see governance working and the live decision panel.
Write steps + status to `BUILD_REPORT.md`, post the `PHASE 5 COMPLETE` banner, and **halt**
until "proceed to Phase 6".
