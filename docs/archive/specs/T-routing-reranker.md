# Codex task — LLM re-ranker for close/negation routing (the last routing-intelligence gap)

> GATEWAY code — **World-B applies**: the re-ranker prompt is COMPILED from the candidate manifests
> (descriptions/skills), never hardcoded domain strings; run `scripts/world-b-check.sh` before/after,
> CRITICAL 0. Do NOT commit (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`,
> branch `feat/conduit-chat`. Stack: docker compose `orchestrator-demo`. If ambiguous, STOP and report.

## Why (the documented limitation)
Semantic routing is embedding-based, and embeddings **cannot model negation** ("…*not* insurance renewal
risk…" still pulls toward `renewal_risk`) and struggle when two agents are a near score-tie. Today the
router abstains on some of these (good) but mis-routes others (the flat-exact accuracy sits ~85%, capped by
exactly these cases). A **second-pass LLM re-ranker** reads the actual candidate agent descriptions + the
query and picks — far better than cosine similarity for close/negated intent. This is the last
routing-intelligence gap; grounding/security are already closed, so this is purely making the *goal pick*
smarter on the hard queries.

## Design — a CONDITIONAL, candidate-bounded, fail-safe re-rank
1. **Trigger only when needed (keep the fast path fast).** After embedding routing produces the ranked
   candidates, invoke the re-ranker ONLY when the pick is uncertain — the top-1/top-2 **score margin is below
   a configured threshold** (near-tie), or the top score is in the abstain-adjacent band. A clear winner
   (large margin) skips the re-ranker entirely — no added latency/cost for the common case. Threshold via
   `application.yml` (`conduit.routing.rerank.*`, `@Value`), NOT a magic constant.
2. **Candidate-bounded (safety).** The re-ranker picks from the **already-shortlisted, structurally-valid
   candidate set only** (the top-N embedding candidates that passed structural filtering) — it CANNOT invent
   an agent or reach outside the valid set. Entitlement/coverage are still enforced downstream exactly as
   today; the re-ranker only reorders the goal choice among valid candidates.
3. **The call.** Compile a prompt (from the candidate manifests — each candidate's id, name, description, and
   representative skill intent) + the user query, and ask the LLM (via the existing `LLMClient`) to pick the
   single best candidate id **or abstain**, with a one-line reason. Structured output (a candidate id from the
   provided set, or `abstain`). Low temperature for consistency. The re-ranker may also strengthen abstain:
   if none genuinely fit, it returns `abstain` → the existing clarify path.
4. **Fail-safe (never fail the request on the re-ranker).** If the LLM errors / is unavailable / returns
   something not in the candidate set → **fall back to the embedding top-1** (today's behavior) and log it.
   The re-ranker only *improves* close cases; if it's down you get the current baseline, never an error and
   never an ungrounded pick.
5. **Determinism posture.** This is at the EDGE (goal selection is already the probabilistic step) — it does
   not touch the deterministic middle. Low temp + structured output; same query + same candidates should give
   the same pick in practice.

## Prove it — the negation case must now route correctly
1. **★ Negation:** a query that names a topic to EXCLUDE it — e.g. "what is the client's risk tolerance, not
   the insurance renewal risk?" — now routes to the correct agent (`risk_profile`), NOT `renewal_risk`.
   (Add this and 1–2 more negation/close cases to `eval/goal-pick/labeled_queries.json`.)
2. **★ Routing gate improves, no regression:** re-run the routing measurement-gate
   (`scripts/routing-measurement-gate.sh`) — the confuser/near-miss/flat-exact accuracy IMPROVES vs the
   pre-re-ranker baseline, and NO previously-correct clear query regresses, and zero poaching. Report
   before/after numbers.
3. **Fast path preserved:** a clear-winner query does NOT invoke the re-ranker (assert via a trace/log or a
   unit test that the LLM re-rank call is skipped when the margin is large).
4. **Fail-safe:** with the re-ranker LLM stubbed to error, routing still returns the embedding top-1 (a unit
   test) — the request never fails on the re-ranker.

## HARNESS (first-class)
Unit (JUnit): trigger fires on near-tie / skips on clear winner; re-ranker pick is constrained to the
candidate set (a returned non-candidate → fallback); LLM-error → embedding top-1 fallback; abstain path.
Live/eval: the negation queries route correctly; the routing measurement-gate before/after shows improvement
with no regression + no poaching; determinism (same query → same pick across repeats).

## GATE
- Negation queries route correctly; routing-measurement-gate accuracy improves with no clear-query
  regression and zero poaching; clear-winner queries skip the re-ranker; LLM-error falls back to top-1.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; existing verticals/regression +
  the routing gate all pass.

## Constraints / anti-gaming
- Candidate-bounded (can't invent an agent or bypass entitlement); World-B (prompt compiled from candidate
  manifests, no hardcoded domain strings); config-driven threshold (no magic constant); fail-SAFE to
  embedding top-1 (never error, never ungrounded). Do NOT overfit the labeled set to the re-ranker — the
  held-out paraphrases must still generalize. Do NOT weaken the routing gate to claim improvement.
- Do NOT commit.

## Report
Files changed; the trigger + threshold; how the prompt is compiled from candidate manifests (World-B); the
candidate-bounded pick + abstain + fail-safe fallback; the negation-case evidence; the routing-gate
before/after (improvement, no regression, no poaching); the fast-path-skip proof; mvn / World-B results.
STOP and report anything the spec didn't anticipate.
