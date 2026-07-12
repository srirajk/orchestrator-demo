# Codex task T1.5 — measure goal-pick accuracy on the SHIPPED embedding model

> Read-only / additive (a measurement harness + labeled set + one dashboard panel). Do NOT change routing
> logic — this task MEASURES, it doesn't tune. Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull latest — T1 is
> committed). Stack: docker compose `orchestrator-demo`. World-B CRITICAL 0.

## Why this matters (the one number the whole thesis rests on)
The architecture is "probabilistic ONLY at the edges, deterministic in the middle." The single probabilistic
step on the request path is the **semantic router picking the goal capability** from the user's question. Our
gateway unit tests use **hash/stub embeddings**; production uses the **real in-JVM MiniLM** (all-MiniLM-L6-v2,
384-dim, via DJL behind `EmbeddingClient`). We have **never measured goal-pick accuracy on the shipped model.**
If it's weak, every downstream investment (T2–T8) sits on sand. Measure it before we build further.

## Build a labeled evaluation set (`eval/goal-pick/labeled_queries.json` or similar)
For EACH of the three live verticals + the other registered agents (hr policy, market research, and the leaf
data agents), include, each labeled with its EXPECTED outcome (a `meridian.*` goal agent id, or `"abstain"`):
1. **Canonical demo questions** — should route to the right goal (e.g. a Whitman-family concentration ask →
   `meridian.wealth.concentration`; a renewal ask → `meridian.insurance.renewal_risk`; a settlement-risk ask →
   `meridian.servicing.settlement_risk`).
2. **Near-miss paraphrases** (≥3 per goal) — same intent, different words; must still route right.
3. **Cross-domain confusers** — phrasing that looks adjacent but belongs to a DIFFERENT domain (a wealth-ish
   sentence that must NOT grab an insurance/servicing agent). This is where a weak model fails.
4. **Out-of-scope / ambiguous** — questions no agent should answer, or too vague to pick a goal → expected
   `"abstain"` (the router should decline / trigger clarify, NOT force a wrong goal).
Aim for ≥40 labeled queries total, spread across the four categories.

## Run them through the REAL routing path (not the hash double)
Exercise the **shipped MiniLM**, not the test stub. Preferred: a live harness that sends each labeled query
through the gateway/BFF and reads the SELECTED goal from the decision trace (`conduit.chat.routing` +
the routing/plan trace events already emitted — the same trace the glass-box panel shows). If a live path is
impractical for 40 queries, a gateway integration test wired to the **real DJL `EmbeddingClient` bean** (not
the hash test embeddings) is acceptable — but it MUST use the production embedding model. Record, per query:
the picked goal (or abstain), and the routing score/margin.

## Measure + report
- **Top-1 goal accuracy** on categories 1–3 (right goal picked), overall AND per domain.
- **Abstain rate** on category 4 (did it correctly decline instead of forcing a goal?).
- **Confusion list**: every query that routed to the WRONG goal → (query, expected, actual, score). This is the
  most useful output.
- Add a **routing-confidence / goal-pick panel** to the D9 Grafana "Smart Orchestration" dashboard, from
  `conduit.chat.routing` scores (or a new `conduit.routing.confidence` metric if needed — World-B clean, tags
  manifest/trace-derived, no domain literals in Java).

## GATE (this is a decision gate, not a build gate)
Produce the accuracy numbers. **If top-1 accuracy on clear queries (cat 1–2) is materially below assumption
(say < ~90%), OR the router forces goals on the out-of-scope set instead of abstaining — STOP and report.**
That result changes the risk model and we decide before investing T2+. If it's strong, report the numbers and
we proceed to T2. Either way: do NOT tune routing in this task.

## Report
The labeled-set size + category breakdown, the accuracy table (overall + per-domain), the abstain rate, the
full confusion list, and the dashboard panel. Note honestly whether you used the live BFF path or the
real-embedding gateway test, and any query you were unsure how to label.
