# Phase 7 — Proof: Accuracy & Scale

**Goal:** Produce the two numbers that back the pitch — a **routing-accuracy** figure and a
**flat-p99 / virtual-thread** scale graph.

**Milestones:** M13, M14
**Read first:** `docs/master-build-plan-consolidated.md` §7 (evaluation) and the scale row of
§10 (build sequence); `CLAUDE.md` §6 (compose profiles).

## Build (scope — simple path only)
- **Eval set:** 30–50 golden banker prompts → expected capability set (+ expected entity). A
  script runs them through the resolver, prints **routing accuracy**, and fails under a
  threshold. Add a **faithfulness spot-check** on a few synthesized answers. The golden set
  may be seeded from the user's prompt framework.
- **Scale proof (`scale` profile):** a **k6** script driving many concurrent streaming
  requests; bring up `otel-collector` + `grafana` + `prometheus` under the `scale` profile;
  a Grafana dashboard charting **p99 latency** and **virtual-thread vs OS-thread** counts.

## Do NOT build
Anything new in the request path — this phase only measures.

## Automated acceptance (you run these)
- The eval script prints a routing-accuracy number at or above the threshold.
- `docker compose --profile scale up` succeeds; k6 hits the concurrency target; p99 stays
  flat while virtual threads scale and OS threads stay low.

## ■ HUMAN TEST GATE → STOP (final)
Tell the human to:
1. Run the eval script → read the **routing-accuracy number**.
2. `docker compose --profile scale up -d`, run the k6 script, open Grafana → watch **p99 stay
   flat** under load while **virtual threads climb and OS threads stay low**.

**PASS =** the human has the accuracy figure and the scale graph for the pitch.
This is the last gate — write the final `BUILD_REPORT.md` summary (all phases DONE, the two
metrics, how to run the full demo) and post `BUILD COMPLETE`.
