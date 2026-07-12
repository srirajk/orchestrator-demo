# Control-Flow Design Basis — conditionals & iteration (foundation for T6)

> Status: agreed design basis (not yet built). This is the foundation the **T6 (conditional edges)** spec —
> and later the iteration tier — must build on. Captured from a design discussion drawing on how Temporal
> and AWS Step Functions build reliable dynamic workflows. It keeps the control-flow layer honest to the
> same **declared + deterministic + World-B** spine as the rest of the gateway.

## The problem
Today the DAG is a **static fan-in**: gather producers in parallel → one analyzer. No branching, no
iteration. To prove the full dynamic-composition thesis we need conditionals ("run Y only if X") and
bounded iteration ("for each item, do Z") — WITHOUT reintroducing the "LLM improvises a plan on live
financial data" risk we deliberately rejected.

## What we steal, and from where

### From AWS Step Functions (Amazon States Language) — the DECLARATIVE model
- **`Choice` state = conditionals as DATA.** A list of rules (comparisons over the JSON state —
  `NumericGreaterThan`, `BooleanEquals`, `And`/`Or`/`Not`) each pointing to a next state, plus a default.
  No code, no model call. → Our conditional edge should carry a **declared predicate in the manifest**, from
  a **closed comparator set** over JMESPath-extracted blackboard values — the same "declared, diffable,
  reviewable" property as our `select`.
- **`Map` state = bounded iteration over a FINITE collection** with a concurrency cap — terminates by
  construction. → Our iteration tier should be a declared Map (item-collection ref + per-item sub-plan +
  max-items/concurrency cap), NOT a raw loop. Step Functions has no raw loops on purpose; you build them
  from `Choice` + a counter, capped.
- **`Parallel` state** ≈ our existing fan-in.

### From Temporal — the DETERMINISM discipline
- Workflows must be **deterministic and replayable**; all non-determinism (I/O, randomness, external calls)
  is quarantined into "activities." → Our control-flow must be **pure/deterministic**; ALL non-determinism
  (LLM, agent data) stays inside the **agent nodes** — which is already our "LLM at the edges, deterministic
  middle." A predicate is deterministic code over already-fetched, coverage-filtered data.
- **No unbounded loops** — always an explicit exit + hard cap. Determinism is also what makes replay/audit
  (T7) actually work.

## The design for OUR conditionals (T6)
1. **Declared predicate.** A conditional edge carries a boolean expression from a CLOSED comparator set
   (`eq/neq, gt/lt/gte/lte, exists/absent, and/or/not`) applied to JMESPath values over the blackboard.
   Never arbitrary code; never an LLM call.
2. **Deterministic + coverage-filtered inputs.** Evaluated by the gateway over the edge's declared,
   **coverage-filtered** upstream inputs only (this is the dependency to re-verify when T4/coverage lands).
3. **Errors are explicit, visible states.** A malformed/failed predicate → a distinct visible failure
   status (like ASL `Catch`/`Fail`), NEVER a silent skip; a predicate-false skip is NOT a failure (no
   `request_partial`, no "data unavailable" in the answer).
4. **Iteration = declared, finite Map first.** Item collection + per-item sub-plan + concurrency and
   max-items caps; terminates by construction. Bounded loops only later, with an explicit exit predicate +
   hard iteration cap. Prefer Map over loops.

## The deep principle (both enforce it; we already have it)
**Separate the deterministic, DECLARED orchestration graph from the non-deterministic work isolated in
agents.** We are closer to **Step Functions (a declarative graph declared in the manifest)** than Temporal
(imperative code) — and that declarative fit is correct for our **read / request-response** path: it keeps
World-B (no domain logic in gateway Java), stays diffable/reviewable/auditable, and slots into the existing
declared-`select` translator model. Temporal-style **imperative durability** is the **write/action** seam we
deliberately parked (D10) — not this path.

## Open questions for the T6 spec to resolve
- Exact predicate grammar + where it lives in the manifest (`io.consumes[].when`?), and its evaluator.
- `plan_graph` statuses for `skipped_predicate_false` vs `predicate_error`.
- How a skipped branch interacts with `DagPlanExecutor`'s BLOCKED/FAILED dependent classification (a
  predicate-false skip must not pollute the partial-degradation metric).
