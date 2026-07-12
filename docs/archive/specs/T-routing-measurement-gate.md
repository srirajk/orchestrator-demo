# Codex task — routing measurement-gate (a new agent can't silently poach a neighbor)

> Eval/CI tooling + a small amount of harness wiring. World-B unaffected (this measures; it doesn't add
> domain knowledge to the gateway) — but run `scripts/world-b-check.sh` to confirm CRITICAL 0. Do NOT commit
> (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
> Stack: docker compose `orchestrator-demo`. If ambiguous, STOP and report.

## Why (the durable fix for the collision problem)
Routing is semantic, so as the agent fleet grows, a new or edited agent can quietly **poach a neighbor's
questions** (this actually happened: `trade_penalty`'s over-broad examples stole `settlement_risk`'s
queries; we only caught it by a full regression run). The fix is to make routing health a **measured gate**
that a team runs whenever they add or change an agent — so a poaching agent **fails the gate** instead of
shipping. Build on the existing goal-pick harness (`eval/goal-pick/measure_goal_pick.py`,
`eval/goal-pick/labeled_queries.json`) which already measures routing against the booted gateway with the
real embedding model.

## Build — the gate
1. **Broaden the labeled set to fleet coverage.** Extend `labeled_queries.json` so EVERY routable agent has:
   its canonical intent queries, ≥2–3 realistic paraphrases, and (where it has close neighbors) a couple of
   cross-agent confusers. Keep the T1.6 discipline: realistic phrasings, NOT the literal strings used as
   skill examples; include HELD-OUT paraphrases (marked) so the gate rewards generalization, not memorization.
2. **Compute two things, per run (against the booted gateway / real embeddings):**
   - **Routing accuracy** per the existing per-shape method (flat → top agent; analytics → resolved goal;
     out-of-scope → abstain), overall and per-domain.
   - **★ POACHING DETECTION (the new signal):** for each agent A, of A's own canonical-intent queries, how
     many were won by a DIFFERENT agent B — and name (A → B) with the count. A neighbor stealing an agent's
     own intent is the failure mode this gate exists to catch.
3. **The gate verdict (make it a runnable check + a test):**
   - PASS iff overall domain-level accuracy ≥ a configured floor (use the established ~90% as the default,
     `@Value`/config, not a magic constant) AND no agent has ≥1 of its canonical-intent queries poached by a
     neighbor (or a configured small tolerance). Otherwise FAIL, printing the accuracy table AND the explicit
     poaching list (`A → B: N queries`).
   - Expose it as: a script/entry a team runs when onboarding an agent, and a harness test
     (`tests/e2e/security_harness/` or the eval runner) that runs it. Document the one-line "run this before
     you ship a new agent" in the onboarding handbook's measurement section (append, don't restructure it).

## Prove it — including FAULT INJECTION (the anti-gaming teeth)
1. **Healthy fleet PASSES:** with the current agents, the gate passes (accuracy ≥ floor, zero poaching).
2. **★ FAULT INJECTION — the gate must CATCH a poaching agent:** temporarily re-introduce over-broad examples
   on one agent (e.g. add generic "settlement"/"failed settlement" phrasings back to `trade_penalty`, OR add a
   throwaway synthetic agent whose examples overlap a neighbor), re-embed, run the gate → it must **FAIL and
   name the collision** (`trade_penalty → settlement_risk` or the synthetic → victim), with the poached
   queries listed. Then REVERT the injected examples/agent and show the gate passes again. This proves the
   gate has real teeth and isn't a rubber stamp.
3. Determinism/honesty: the gate reads the booted gateway's real routing (not stubs); note validated vs
   any-unmeasured queries; do NOT weaken the floor or drop hard queries to force a pass.

## GATE (for THIS task)
- The gate script + test exist and run against the booted gateway; healthy fleet passes.
- The fault-injection case FAILS with the correct named collision, and reverting restores PASS (evidence:
  the gate's output in both states).
- Held-out paraphrases are included and pass (real generalization).
- `scripts/world-b-check.sh` CRITICAL 0; `cd gateway && mvn test` green; the existing verticals/regression
  unaffected.

## Constraints / anti-gaming
- The gate measures REAL routing (booted gateway, real embeddings). Poaching detection must actually flag a
  neighbor winning an agent's own intent. Do NOT weaken the floor/tolerance or paste test strings as examples
  to pass. The fault-injection case must genuinely fail.
- Do NOT commit.

## Follow-on (NOT this task — note only)
The complementary piece is an **LLM re-ranker** for genuinely close/negation queries (e.g. "…*not* renewal
risk…" which embeddings can't model): when the top candidates are within a small score margin, a second-pass
model reads the candidate agent descriptions + the query and picks. That's a separate spec (it adds a
conditional LLM call on the routing path) — do NOT build it here; this task is the measurement-gate.

## Report
The expanded labeled set (counts by category, held-out included); the gate's accuracy + poaching output on
the healthy fleet; the FAULT-INJECTION evidence (failed-with-named-collision, then reverted-and-passed); how
it's exposed (script + test) and the handbook line added; mvn / World-B results. STOP and report anything
the spec didn't anticipate.
