# Test Evidence — proving the orchestration build is solid

> How we know it works, by technique, with numbers. Two adversarial batteries each found a real
> problem (a win — better tonight than in a demo); both are fixed and guarded. All green as of this
> writing. The honest "needs the live stack" list is at the end.

## Bugs the battery FOUND and we FIXED
1. **Silent wrong number (concentration).** A `NaN`/`+inf` position value passed every guard
   (`NaN < 0` is False) and returned `HHI = nan` with no error — violating "never a silent wrong
   number." Found by adversarial + Fraction-oracle property tests. Fixed: `math.isfinite` guard;
   former defect-tests now pass as regressions.
2. **Untested determinism path (resolver).** PIT mutation testing found the ambiguous-producer
   candidate `sort()` was unasserted (a mutant deleting it survived). Fixed: added ordering
   assertions; DagResolver mutation kill → 100%.
3. **Pre-existing registry drift** (surfaced by the Move-Safety Gate, not the move): stale duplicate
   root schema; `hr-knowledge` missing `required_context`; two enterprise knowledge agents unwired
   from the sub-domain hierarchy. All fixed; registry contract test 3-failing → 8/8 green.

## Static gates (deterministic)
- **World-B coupling:** CRITICAL **0** — the engine carries zero domain knowledge.
- **Schema:** 14 agents + 7 sub-domains **all valid** against the pinned schemas.
- **io dataflow-graph integrity:** every `from`-edge resolves to exactly one producer; **zero**
  ambiguous types — the real registry is a clean, resolvable graph.
- **Build/regression:** gateway suite **90/90**, 0 failures; flat path byte-for-byte unchanged
  (flag off), proven by the full suite staying green.

## DagResolver (deterministic graph derivation)
- **Property-based / fuzz + full-DAG oracle:** 1000 random graphs (5 seeds × 200), each with its
  correct DAG **known by construction**; asserted **exact node set == oracle** and **exact edge set
  == oracle** (not just "a valid topo order"). All held.
- **Determinism / order-independence:** 1200 resolves under shuffled candidate order → byte-identical
  plan every time.
- **Metamorphic:** duplicate producer → AMBIGUOUS; remove sole producer → MISSING; unrelated
  capability → no change. All held.
- **Adversarial:** self-loop / 2-/3-cycle → CYCLE; diamond dedupes; orphan required entity → UNMET;
  unknown goal → UNKNOWN_NODE. Ambiguity = deterministic sorted refusal (no guess).
- **Scale:** 200-node chain + 150-wide fan-in resolve <2 s, **no StackOverflow** (iterative).
- **Mutation (PIT):** DagResolver **38/38 = 100%** kill; DAG engine overall 85% (survivors are inert
  telemetry/log calls).

## DagPlanExecutor + Blackboard (concurrent execution)
- **Concurrency correctness — parallel == serial reference:** 300 executions (30→1 fan-in ×200 +
  3-layer ×100) on real virtual-thread harness, random sleeps, **unique per-producer payloads**.
  Each run compared to a single-threaded oracle: exact bound input per node + exact results.
  **0 races, 0 exceptions, 0 lost/swapped/stale/duplicated values.** (This is the "right results
  under concurrency" proof — not just "it didn't crash.")
- **Chaos / partial-failure:** 110 runs failing k-of-n producers → survivors all produce correctly,
  the consumer is marked unmet and **never invoked with partial data**, a poisoned branch never
  cancels an independent branch. Executor never throws (Hard-rule d).
- **Honest note:** blackboard writes are currently projected sequentially on the caller thread after
  the layer joins, so its `ConcurrentHashMap` is defensive, not yet load-bearing. Flagged for if that
  projection ever moves inside the parallel section.

## concentration agent (analytics math)
- **Reference oracle:** independent `fractions.Fraction` recomputation (HHI from raw values, exact
  flag decisions) matched the agent on ~3,700 random portfolios/run — proves the numbers are *the
  correct value*, not just in-range.
- **Property invariants:** weights sum to 1; HHI ∈ (0,1]; eff == 1/HHI; equal-weight HHI == 1/n.
- **Metamorphic:** scale-invariance, permutation-invariance, threshold-monotonicity — all held.
- **Golden known-answers:** hand-computed 70/20/10, 50/30/15/5, four-equal, single-position — exact.
- **Grounding / no-fabrication:** every output number derivable from input; only thresholds are
  config. Totals: **55 tests green.**

## Needs the LIVE stack (cannot be proven offline — flip the flag + rebuild; see GO-LIVE-RUNBOOK)
1. **Entitlement re-gate (security-critical):** a resolver-pulled producer the principal isn't
   entitled to must block the whole plan; denied producers never appear in trace/answer. Matrix test
   of principal × relationship × plan.
2. **Grounding of streamed answers:** every number in the synthesized multi-step answer appears in an
   agent output; inject a fabricated number → grounding fails.
3. **Multi-turn:** turn-2 carried entity derives the correct DAG (follow-up → fetch fallthrough).
4. **Load/latency (k6), chaos (kill a container mid-request), SSE byte-correctness** on the DAG path.
