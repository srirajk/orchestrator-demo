# Codex roadmap — production-grade (PRESSURE-TESTED: Fable + Opus pre-flight applied)

> Run **in order, one Codex session each, committed + reviewed between each.** They touch shared
> hot-path files — do NOT parallelize. Each gate below was hardened by two independent architect
> reviews specifically so a build **cannot pass the gate while still leaking** (the failure mode that
> almost shipped T2/T3). If a gate fails, STOP and report — never push a partial change. Deep rationale:
> `docs/orchestration-architecture/DECISION-LOG.md` (D-numbers) + `GURU-TEARDOWN-AND-FIXPLAN.md`.
> Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull before
> each). Stack: docker compose `orchestrator-demo`. Do NOT commit (reviewer commits); no uac/backend;
> World-B CRITICAL 0.

## Reviewer-resolved pre-reqs (not Codex tasks)
- **O5 (servicing coverage scope) — RESOLVED:** servicing is now relationship-scoped (settlement/custody/
  cash consume `relationship_id`; nav/corporate_actions stay `fund_id`); V9 seeds servicing-ops coverage.
  So per-producer coverage (T4) is testable across all three domains.
- **O1 (canonical data model) — DEFERRED:** declared JMESPath translators are production-correct at
  ~16 agents / ~5 edges (both reviews concur). Canonical is a scale seam; revisit when edge count grows
  or a second team onboards. LLM-authored translators (D7) likewise stay a designed seam — NOT built now.

## T1 — Rename `meridian.* → meridian.*`  (spec: `rename-acme-to-meridian.md`, gate PATCHED)
First (rewrites ids everywhere). Its Move-Safety Gate now requires the **full 16-agents × all-seeded-
principals authz cross-product** (scripted, not a sample) captured before/after, and a **"Cerbos restarted
+ renamed policy count confirmed loaded"** step before the after-matrix. `git grep acme\.` == 0.

## T1.5 — Measure goal-pick accuracy on the SHIPPED model  (read-only; do right after T1)
The deterministic middle hinges on one probabilistic step: the router picking the goal. Tests run on hash
embeddings; the demo runs MiniLM (M-embeddings). Build a labeled query set (≥the demo questions + near-miss
paraphrases per domain), measure top-1 goal accuracy + abstain rate on the **shipped MiniLM**, and add a
routing-confidence panel to the D9 dashboard. **Gate:** a number exists for goal-pick accuracy; if it's
materially below the hash-test assumption, STOP and report (it changes the whole risk model). Nothing
downstream depends on this — measure before investing T2–T7.

## T2 — Per-hop identity + fail-closed + JWKS  (F-IDENTITY; D13; SECURITY P0) — GATE HARDENED
Restore `git stash@{0}` and finish. **Do NOT thread identity via any ThreadLocal** (`RequestContext` is
itself a ThreadLocal — the same bug): pass the user token as immutable DATA on the invoke path
(`PlanNode`/an `invoke` argument), fixing BOTH `HttpAdapter` AND `McpAdapter` AND the coverage-service
calls. Agents fail closed. Fix the JWKS 301 (`mock-agents/*/shared/jwt_verify.py`: real JWKS is
`<iam>/oauth2/jwks`, not `.well-known/jwks.json`; add `follow_redirects` or the correct URL; REMOVE the
"JWKS unreachable → allow" fallback so RS256 signatures are truly verified).
**Gate (all must pass — this is the anti-rubber-stamp set):**
1. Parametrize `test_no_token_rejected` + `test_tampered_signature_rejected` over **every agent service —
   including the MCP servicing server and BOTH coverage services** (not just wealth-http).
2. NEW `test_hop_carries_end_user_sub`: the agent echoes its **verified `sub`**, and the harness asserts it
   equals the turn's end-user `sub` — on the **flat path AND a DAG downstream node** (the concentration hop,
   which runs on `DagPlanExecutor`'s VT where thread-locals die). A gateway service token or wrong-user token
   passing = FAIL.
3. NEW `test_jwks_outage_fails_closed`: stop `iam-service`, call a data endpoint with an uncached-kid token → 401/503, never 200.
4. Decide `aud`: either assert an agent rejects a valid token minted for the wrong audience, OR waive `aud`
   with a one-line DECISION-LOG entry (no silent omission).
Note: leg (a)'s log-count signal is weak (`HttpAdapter` only logs when token!=null) — rely on the `sub`-echo
assertion, not the log. mvn green; World-B 0; 3 verticals still answer.

## T3 — Output-schema contract + boot-time validation + entity-typed-output declaration  (D2 "must build"; the real F3 closure; ENABLES T4)
Two stubs make the translator validation hollow: MCP output is `createObjectNode()` "best-effort"
(`AgentIntrospector:209`) AND OpenAPI output is a stub too (`buildOutputSchemaFromResponse` ~175-183). Fix
**both** protocols: derive a real output schema (FastMCP output schema / OpenAPI response schema; where the
protocol can't provide one, allow a manifest-declared `output_schema`). Then: (a) **validate every declared
`select` against the producer's output schema at REGISTRY LOAD / boot** (not discovered dead at request time
— fail-safe stays the runtime backstop, not the contract); (b) add the manifest mechanism **`io.produces[].entities`**
= `[{type, select}]` (a JMESPath to the id field(s) + the entity type; coverage service + id_pattern come from
the domain manifest) so the gateway can identify entity-typed values in producer output **without pattern-
scanning JSON in Java** (World-B). Promote the completeness keys (`_complete`/`truncated`) from a Blackboard
hardcode into the manifest/output contract. **Gate:** a manifest whose `select` references a field absent from
the producer's output schema is REJECTED at boot; `io.produces[].entities` parses + validates; mvn green;
World-B 0; 3 verticals still work.

## T4 — Coverage per-producer, row-level runtime filter  (F1/D12; SECURITY P0) — GATE HARDENED (depends on T3)
For every resource-scoped plan node, resolve its entity input(s) and run `coverageClient.check` against THAT
node's coverage service before releasing it; for entities DISCOVERED in an upstream output (via T3's
`io.produces[].entities`), filter to the covered subset before the downstream node/synthesis sees them.
**Gate (anti-rubber-stamp):**
1. **Discovered-entity filter:** an upstream producer outputs N entities, K covered → the downstream node AND
   synthesis see EXACTLY K; the trace shows N−K per-entity coverage denials.
2. **No count/existence leak:** the partially-covered answer must NOT contain the unfiltered set size (filter
   BEFORE any aggregation/count).
3. **Explicit deny, not silent flat:** an uncovered request-named entity ends in a coverage DENY with the
   "not in your coverage" reason (match `test_coverage_denial`) — NEVER a quiet flat answer.
4. **Fail CLOSED on coverage-service outage** (do NOT copy the fail-open precedent at `ChatService:381-383`):
   stop the coverage container → deny, not answer.
5. **Unresolved/blank id → DROP, not allow** (`EntitlementService.checkRelationship` fails OPEN on null/blank,
   `:51-53`) — the filter must treat unresolved as uncovered.
The raw principal-agnostic output must never cross un-filtered. 3 verticals (single shared entity) still work.

## T5 — Grounding: deterministic number rendering  (D15) — GATE FIXED to ATTRIBUTION
Render load-bearing figures from agent output via a **manifest-declared template per `produces.type`** (generic
renderer in Java — no user-facing domain copy in Java, World-B); LLM writes only prose. **Gate:** assert
**attribution** (value ↔ label ↔ source triples) with the declared formatting transforms — NOT naked
byte-identity (which false-fails `0.314` vs `31.4%` and passes a right-number-wrong-label answer). Do NOT weaken
the checker to make an answer pass. Eval-layer grounding stays as the after-the-fact monitor.

## T6 — Conditional edges  (D9 control-flow tier 1) — GATE HARDENED
Optional declared predicate on a `from`-edge (JMESPath/CEL boolean) decides release; deterministic, never LLM.
Predicate may reference ONLY the edge's declared, **coverage-filtered** upstream inputs (else it re-opens T4's
leak and, post-T8, becomes timing-nondeterministic). **Gate:** predicate-true fires; predicate-false is a
`plan_graph` status `skipped_predicate_false` + an `edge_predicate` trace event (verifiable "honest skip") and
is NOT a failure (no `request_partial`, no "data unavailable" in the answer); a malformed predicate → a distinct
visible `predicate_error` status, NEVER a silent skip. Build ONE demo conditional vertical; finance from
`DOMAIN-KNOWLEDGE-VERIFIED.md` with any threshold a firm-configured env parameter. (Map/scatter + loops = LATER.)

## T7 — Audit/replay record  (M2; regulator-grade) — GATE HARDENED
Persist per request: the resolved `plan_graph`, **content hashes** of every manifest + domain manifest + Cerbos
policy bundle used, the **registry snapshot id**, routing scores, extraction output, coverage/entitlement
decisions, AND **each node's bound input** (so replay stays defined even after T8 changes execution). Written for
DENIED / CLARIFIED / every `dag_fallback` request too, to a NAMED append-only store (Redis KV is not immutable —
name the store + document the WORM/retention property as a seam if not natively append-only). **Gate:** an offline
harness test feeds the record (manifest set + available entities + goal) back through `DagResolver.resolve` and
gets a **byte-identical `plan_graph`**; a denied request also produced a retrievable record.

## T8 — Serious hardening — SPLIT INTO THREE SESSIONS (one gate each)
- **T8a Dataflow release:** drop the layer barrier in `DagPlanExecutor` — release a node when ITS deps finish.
  MUST establish a per-node "all deps projected happens-before bind" invariant (today the barrier guarantees the
  1-vs-N merge is a pure function of plan+success, `Blackboard:133-155`; eager release can stay deterministic
  because the resolver already lists all producers in `dependsOn`, but you must design for it). **Gate:** a
  determinism test — same plan, RANDOMIZED agent latencies, 100 runs → identical per-node bound inputs and
  identical `plan_graph` statuses. Per-critical-path deadline; if you stream partial synthesis, an
  **unchanged-SSE-byte-shape** assertion (hard rule 4a).
- **T8b Type versioning:** namespaced/versioned produced-type strings + declared priority tie-break for ambiguous
  producers. **Gate:** two producers of the same type → deterministic priority pick (not refusal-to-flat); mvn green.
- **T8c Plan-size cap + operability:** a config `max plan nodes` + `dag_fallback{reason="plan-too-large"}`;
  alert rules on `conduit.dag.fallback` spike, JWKS-fetch failure, coverage-service outage; a "smart path stopped
  firing" runbook. **Gate:** an over-cap plan falls back with the reason; alerts defined.

## Also missing (fold in where cheap; not blocking the demo)
- **Multi-turn DAG backstop:** the flat path has an extractor-drop backstop; `tryDag` doesn't → multi-turn
  silently degrades the flagship. Add the same backstop (cheap; do it inside T2 or T4). 
- **Partial-failure UX test:** kill a producer container mid-run → the answer names what's missing and survivors'
  numbers stay grounded (one harness test; fold into T4).
- **Token-lifetime vs deadline:** a user JWT expiring mid-plan turns tail nodes into 401s that look like agent
  failures — check `exp` at bind, fail the node as `auth_expired` (fold into T2).
- **Registry reload atomicity:** versioned atomic swap of router index + registry snapshot (T7 needs the snapshot
  id anyway).
- **FINANCE-VERIFY (#18):** reviewer research, not Codex — concentration denominator + client-facing math sweep
  vs `DOMAIN-KNOWLEDGE-VERIFIED.md`.

## Order
T1 → T1.5 → T2 → T3 → T4 → T5 → T6 → T7 → T8a → T8b → T8c. One session each, pull latest, run the gate, hand
the diff to the reviewer, reviewer commits, then next. Stop-and-report on any gate failure. **T2 and T3 are the
tasks most likely to come back green-and-wrong — do not relax their gates.**
