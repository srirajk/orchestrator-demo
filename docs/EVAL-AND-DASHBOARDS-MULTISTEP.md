# Eval + Dashboards for Multi-Step Orchestration (D8 / D9)

> Premise (agreed with Sriraj): for a bank, the METRICS are the product differentiator — a chatbot
> says "trust me"; Conduit shows grounding, entitlement, and now **plan-correctness**, measured.
> Honesty note: grounding/entitlement scoring for the NEW multi-step flows needs the live flow
> (one flag flip — see GO-LIVE-RUNBOOK.md). **Plan-correctness, however, is provable OFFLINE today**
> and already partially proven.

## The new metric: PLAN-CORRECTNESS (offline, deterministic — proven now)
Definition: for a scenario (goal capability + available entities), the DagResolver-derived DAG must
equal the expected DAG (node set + edges). Because derivation is deterministic, this needs **no LLM
and no live stack** — it runs in unit tests over the real registry.
- **Already proven:** `DagRealManifestTest` derives `holdings → concentration` from the REAL manifest
  registry (goal = `acme.wealth.concentration`, entity `relationship_id` available) — pass.
- **Scenario matrix to extend** as analytics agents are added (each row = goal, available entities,
  expected DAG):
  | scenario | goal | expected DAG |
  |---|---|---|
  | Wealth concentration | acme.wealth.concentration | holdings → concentration ✅ proven |
  | Wealth rebalance | (drift_rebalance) | (holdings ∥ risk_profile ∥ market_research) → drift_rebalance |
  | Wealth review brief | (review_brief) | (performance ∥ holdings ∥ risk_profile ∥ goal_planning ∥ market_research) → review_brief |
  | Insurance renewal | (renewal_risk) | (policy_details ∥ claim_status) → renewal_risk |
  | Servicing settlement risk | (settlement_risk) | (settlement_status ∥ custody_positions ∥ cash_management) → settlement_risk |
  Report plan-correctness as % of scenarios whose derived DAG == expected. Ambiguous/missing-producer
  results are correctness FAILURES surfaced with their `ResolutionError` code.

## Grounding + entitlement (reuse the existing harness once live)
No new machinery — the multi-step flows feed the SAME continuous-eval path:
- **Grounding:** the deterministic check (`eval/langfuse_continuous.py` — every number in the answer
  must appear verbatim in an agent's output) applies unchanged; for a fan-in answer it checks against
  ALL upstream agent outputs on the blackboard. The concentration agent's no-invent design + the
  "numbers trace to holdings" property make it grounding-robust.
- **Entitlement:** the request-path authz (structural filter + coverage + the D7 Step-A6 re-gate over
  resolver-pulled producers) already emits `conduit.authz.decisions` / `conduit.agent.denials`
  counters and `gate` trace events — the multi-step flows reuse them.
- **Relevance/safety:** existing LLM-judge (glm-4.6) unchanged.

## Dashboard panels to add (populate on live flip)
Extend the existing Insights/Langfuse/Grafana boards (do NOT fabricate data — these light up when the
flag is on and multi-step traffic flows):
1. **Plan-correctness rate** — % scenarios deriving the expected DAG (offline eval can publish this
   now as a CI metric; live can add per-question).
2. **Multi-step share + fan-out depth** — how many answers used a DAG, and the node count / depth
   distribution (from `plan_graph` events).
3. **Grounding by domain** — grounding pass-rate split wealth/insurance/servicing.
4. **Entitlement denials by gate** — including the new resolver-pulled-producer re-gate (proves
   cross-agent authz holds under orchestration).
5. **Per-step latency** — data-agent vs analytics-agent latency from the DAG execution.

## What ships tonight vs on live flip
- TONIGHT (offline, real): plan-correctness metric defined + proven for the built vertical; scenario
  matrix; grounding/entitlement reuse path documented; panel spec.
- ON LIVE FLIP (~15 min, runbook): grounding/entitlement/latency scores for the multi-step flows;
  dashboard panels populate with real traffic.
