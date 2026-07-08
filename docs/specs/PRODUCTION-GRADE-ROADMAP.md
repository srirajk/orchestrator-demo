# Codex roadmap — production-grade (close the thesis, done & dusted)

> Run these **in order, one Codex session each, committed + reviewed between each.** They touch the
> same hot-path files (ChatService, executors, manifests), so **do NOT parallelize.** Each task has a
> hard acceptance gate; if a gate fails, STOP and report — never push a partial change. Deep design
> rationale for every item is in `docs/orchestration-architecture/DECISION-LOG.md` (D-numbers cited)
> and `GURU-TEARDOWN-AND-FIXPLAN.md`. Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`,
> branch `feat/conduit-chat` (pull latest before each task). Stack: docker compose `orchestrator-demo`.
> Do NOT commit (a reviewer commits); do NOT touch uac/backend; World-B CRITICAL must stay 0.

## T1 — Rename `acme.* → meridian.*`  (spec: `rename-acme-to-meridian.md`)
Already specified. Do this FIRST (it rewrites ids everywhere; everything after builds on the new ids).
Gate = that spec's Move-Safety Gate (registry loads same count renamed, Cerbos authz matrix identical,
mvn green, live 3 verticals, `git grep acme\.` == 0).

## T2 — Per-hop identity + fail-closed + JWKS fix  (F-IDENTITY; DECISION D13; SECURITY P0)
A near-complete implementation is stashed at **`git stash@{0}`** ("F-IDENTITY deferred … recoverable").
Restore it (`git stash show -p stash@{0}` → re-apply the parts that still fit post-rename) and finish:
capture the user token at ingress as immutable request-context DATA, thread it through adapters/harness/
executors (not thread-local, which the virtual threads drop), agents **fail closed**, and fix the
**JWKS 301** bug in `mock-agents/*/shared/jwt_verify.py` (real JWKS is `<iam>/oauth2/jwks`, not
`.well-known/jwks.json`; remove the "JWKS unreachable → allow" fallback so RS256 signatures are actually
verified). **Gate:** `tests/e2e/security_harness` `test_hop_identity_verified`, `test_no_token_rejected`,
`test_tampered_signature_rejected` all PASS; a bit-flipped-signature token → 401; the 3 verticals still
answer live; mvn green; World-B 0.

## T3 — Coverage per-producer, row-level runtime filter  (F1 deep; DECISION D12; SECURITY P0)
Today `tryDag` re-gates producers only structurally (`filterAgents`); coverage (book-of-business) is
checked only on the routed goal entity. Make it a **runtime, per-edge, per-entity filter**: for every
plan node that is resource-scoped, resolve its entity input(s) and run `coverageClient.check` against
THAT node's coverage service before releasing it; for entities DISCOVERED from an upstream output, filter
to the covered subset before the downstream node/synthesis sees them; the raw principal-agnostic output
must **never cross un-filtered**, and filter **before any aggregation/count** (no set-size leak).
**Gate:** a new test where a plan pulls a producer keyed on an entity the principal does NOT cover → the
DAG is refused (falls back / denies), and the uncovered data never reaches the answer; existing 3
verticals (single shared entity) still work; mvn green; World-B 0.

## T4 — Grounding: deterministic number rendering  (DECISION D15)
Prevent the "LLM typed a number not in the data" class (the 15%-vs-10% wobble) in the request path:
render the load-bearing figures **from the agent output via template**, and let the synthesizer LLM
write only the connective prose (it already may not compute). Keep the eval-layer grounding check as the
after-the-fact monitor (that stays). **Gate:** drive the 3 verticals; every numeral in each answer is
byte-identical to a value in the corresponding agent's output (extend the harness `test_grounding_*`);
mvn green.

## T5 — Control-flow tier 1: conditional edges  (DECISION D9; proves dynamic composition beyond fan-in)
Add an optional declared **predicate** on a `from`-edge (a JMESPath/CEL boolean over available data) that
decides whether the edge/branch releases — e.g. "run a `rebalance` step only if `concentration.breach_count > 0`".
Predicate is code, evaluated deterministically; never an LLM decision. Build ONE demonstrable conditional
vertical to prove it. **Gate:** the conditional fires when the predicate is true and is skipped (honestly)
when false; deterministic; mvn green; World-B 0. (Tier 2 map/scatter and tier 3 bounded loops are LATER —
do not build them in this task.)

## T6 — Audit/replay record  (GURU-TEARDOWN M2; regulator-grade)
Persist, per request, an immutable audit record: the resolved plan (`plan_graph`), manifest versions,
routing scores, extraction output, and coverage/entitlement decisions — so a runtime-only pipeline can be
**replayed and explained** to a regulator. **Gate:** a completed request writes a retrievable record that
reconstructs which plan ran and why; mvn green.

## T7 — Serious hardening (batch, lower risk)  (GURU-TEARDOWN serious list)
- Dataflow release: drop the strict layer barrier in `DagPlanExecutor` — release a node the instant ITS
  deps finish (kills tail latency); per-critical-path deadline; stream partial synthesis.
- Versioned/namespaced type strings + declared priority tie-break for ambiguous producers.
- Goal-selection accuracy measured on the SHIPPED embedding model (not the hash test embeddings).
**Gate per item:** mvn green, World-B 0, the 3 verticals still work, dashboards still populate.

## Also (research, NOT a Codex build) — FINANCE-VERIFY (#18)
Verify the concentration denominator (invested base vs total portfolio incl. cash) + sweep client-facing
figures against `docs/DOMAIN-KNOWLEDGE-VERIFIED.md`. Reviewer handles this with sourced research — do not
fabricate a convention.

---
### Order & rule
T1 → T2 → T3 → T4 → T5 → T6 → T7. One session each, pull latest, run the gate, hand the diff to the
reviewer, reviewer commits, then next. Stop-and-report on any gate failure.
