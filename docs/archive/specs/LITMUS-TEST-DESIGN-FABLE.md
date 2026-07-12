# Litmus-Test Design Review — Faithfulness Audit & Minimal Set (Fable)

> Design consult, read-only. No test or product code was modified.
> Principle under audit: **TRACE-AS-GROUND-TRUTH** — a litmus asserts on *deterministic
> routing/entitlement outcomes* (agent ids, `overallDisposition`, coverage verdict, primary
> domain/subDomain), never on the non-deterministic LLM answer prose. Asserting HTTP 200 or
> grepping the answer for a word is UNFAITHFUL: it false-greens on a degraded/partial answer.

---

## 0. The one surface that makes faithfulness cheap

`POST /debug/route` (config-gated `conduit.debug.route-decision.enabled=true`, ON in the demo
compose — `docker-compose.yml:152`; `authenticated()` for any persona token —
`SecurityConfig` :95) runs the **real** pre-routing pipeline via `ChatService.decideRoute`
(`gateway/.../domain/chat/ChatService.java:1232`) — `IntentClassifier.classify` → `RoutePreparer.prepare`
→ `resolveContextual` → `buildRequestedPlan` → per-group `EntitlementService.filterAgents` — and
STOPS before any agent is invoked. It returns a fully deterministic `RouteDecision`:

- `overallDisposition` ∈ {SERVED, PARTIAL, STRUCTURAL_DENIED, COVERAGE_DENIED, COVERAGE_UNAVAILABLE, ABSTAIN} (`ChatService.java:1304`)
- `resolver.primaryAgentId` / `primaryDomain` / `primarySubDomain`, `fallback`, `topScore`, `margin`, `rerankFired`
- `candidates[]` (agentId, domain, subDomain, score, selected)
- `disposition[]` per requested group (allowed/denied capability ids, SERVED/PARTIAL/DENIED)
- `maskMode`, `maskDiagnostics`, `grounded[]`

**No LLM synthesis runs**, so there is no prose to grep and no reasoning-model latency on the
routing decision (only the intent/extract calls — gpt-4.1-nano/mini — run; the gpt-5-mini synth
does not). This is the correct assertion target for routing/entitlement litmus. `measure_goal_pick.py`
already drives it correctly and is the model to copy.

The **SSE chat path** (`POST /v1/chat/completions`) is only needed where the assertion is about
something `/debug/route` cannot see: (a) the byte-exact SSE contract (role delta / content deltas /
`data: [DONE]` / `finish_reason:stop`), and (b) end-to-end trace-event emission
(`plan_graph`, `agent_start/agent_complete`, `check_denied`, `grounded_figures`, `request_complete`)
which the security-harness reads from `GET /trace/{requestId}`. Those are faithful **because they
assert on trace events, not answer prose** — that is the pattern to preserve.

---

## 1. Per-asset verdict table

### Shell / curl smoke & e2e

| Asset | Verdict | Why / weak assertion |
|---|---|---|
| `scripts/smoke.sh` §D "World B scenarios" | **UNFAITHFUL** | Every scenario greps answer prose: `grep -qiE "whitman|holding|...|[0-9],[0-9]{3}"` (:57), denial via `grep -qiE "denied|deny|..."` (:58), clarify via `grep -qiE "which|clarif|..."` (:59). A degraded/partial/wrong-agent answer that happens to contain "holding" or a comma-number **false-greens**. This is the exact bug the mandate calls out. No agent-id, no disposition, no coverage verdict is ever checked. Bug-261 (capability-vs-entity) is not covered at all. |
| `scripts/smoke.sh` §A/§B/§C (health, JWT path, SSE framing) | **FAITHFUL** | §C asserts the byte-exact SSE contract deterministically (`^data: \{`, `^data: \[DONE\]$`, `"finish_reason":"stop"` :52-54). §A/§B are liveness/auth, legitimately HTTP-code-based. Keep these; they are the SSE guardrail. |
| `scripts/smoke.sh` §E/§F (Langfuse, Grafana, world-b, personas) | **FAITHFUL (but out of litmus scope)** | Telemetry/ops assertions, not routing litmus. Fine as an ops smoke; belongs in a telemetry gate, not the routing litmus. |
| `scripts/smoke-ui.sh` | **FAITHFUL** | Pure integration liveness: URL 200s, CORS preflight non-reject, four personas mint JWTs. Fast, deterministic, no prose. Keep as Tier-1. |
| `scripts/e2e-matrix.sh` | **UNFAITHFUL** | The whole matrix is prose grep (`ask()` assembles answer text, `run()` greps a denial-phrase regex + a data regex :36-40). The header comment even concedes the ambiguity of "not in your coverage" and tries to disambiguate deny-vs-partial *by more prose grep*. Twelve rows, zero deterministic routing/entitlement assertions. Personas `analyst_amy` are not in the verified persona set. Superseded wholesale by a `/debug/route` matrix. |
| `scripts/verify-telemetry-e2e.sh` | **FAITHFUL** | Asserts on Langfuse structure (one session → ≥2 traces, span tree `chat.handle/intent.classify/agent.invoke/llm.synthesize` :64-74), not answer prose. This is a telemetry litmus and a good one. Keep (telemetry suite, not routing). |
| `scripts/verify.sh` | **FAITHFUL (thin)** | Build + compose + `/v1/models` + SSE `[DONE]` + world-b gate. Legitimate build gate; its "smoke" is only SSE framing, which is faithful. Keep as the orchestrator; point its routing step at the new `/debug/route` smoke. |
| `scripts/integration-test.sh` | **FAITHFUL (thin)** | Health/models/SSE-shape curl checks. Redundant with `smoke.sh` §A-C and `verify.sh`. Consolidate. |
| `scripts/routing-measurement-gate.sh` | **FAITHFUL** | Thin wrapper over `measure_goal_pick.py` (the gold-standard `/debug/route` harness). Keep. |
| `scripts/eval-gate.sh` / `eval/eval_deepeval.py` | **FAITHFUL (different axis)** | LLM-judge release gate (F1 on agent selection + judge validation). Not a litmus; a release gate. Out of scope, keep. |
| `scripts/probe-recency-insurance.py` | **UNFAITHFUL (as a gate) / OK as a manual probe** | Drives the BFF conversation correctly (real multi-turn), but only **prints** answers truncated to 260 chars (:53) — it asserts *nothing*. The recency binding (POL-77002 vs POL-77001) is meant to be eyeballed. Its own docstring intent (`must_bind_entity`) is exactly what `/debug/route` + `expected_disposition` could assert deterministically. Superseded. |

### Multi-turn JSON fixtures

| Asset | Verdict | Why |
|---|---|---|
| `eval/multiturn-routing.json` | **STALE / ORPHANED** | 11 richly-specified turns with `expected_domain` / `expected_outcome` / `must_not_route` — but **no runner consumes it** (grep: referenced only by docs and `.wolf/*`, never by a `.py`/`.sh`/`.ts` executor). The good routing intent it encodes (keyword-less follow-up, focal-entity supersede, facet-carry, anaphora) has already migrated into `eval/goal-pick/routing_edge_cases.json` (`multi_turn` rows) which *is* gated by `measure_goal_pick.py`. Dead fixture. |
| `eval/multiturn-recency-insurance.json` | **STALE / ORPHANED** | Same shape, insurance recency (bug-234). Its `must_bind_entity` assertion has no runner; the only related executable is `probe-recency-insurance.py`, which asserts nothing. The routing content should live as gated `/debug/route` rows, not an un-run JSON. |
| `conduit-ui-tests/07-multiturn-memory.md` | **FAITHFUL-BY-HUMAN (manual)** | A human-run checklist (context carry + compaction recall). Legitimately prose-judged because a human is the judge and compaction recall genuinely needs a semantic read. Keep as a manual UX script — but it is **not** an automated litmus and must not be counted as routing coverage. |

### Playwright e2e (`tests/e2e/tests/`)

| Asset | Verdict | Why / weak assertion |
|---|---|---|
| `07-multi-turn.spec.ts` | **UNFAITHFUL** | Every assertion is `reply.length > 30`, `status()===200`, `raw` contains `data: [DONE]`, or `!lower.includes('which client')` (:36-52, :118-145). "A non-trivial context-carrying answer" is asserted as *length > 30*. A wrong-agent or degraded answer of 31 chars passes. Context carry is never verified against a resolved entity or agent id. The UI-DOM checks (two bubbles, trace panel shows "Intent:") are weakly faithful at best. |
| `02-hero-prompt.spec.ts` | **PARTIALLY FAITHFUL** | `streams a grounded answer` asserts the trace panel shows `Intent:` + `Resolved N Agent` (:32-33) — faithful (trace-derived). But `reply contains grounded facts` ORs six prose substrings (`whitman|holdings|%|performance|settlement|corporate` :45-52) — that OR is so wide it is nearly always true = false-green. Split: keep the trace-panel assertions, drop the prose OR. |
| `04-entitlements.spec.ts` | **PARTIALLY FAITHFUL** | Best of the DOM specs: asserts the trace panel shows `Coverage Denied` (:33) and that Okafor data did not leak (:39). But leans on denial-phrase prose (`not in your coverage|access denied`) for allow-cases (:50-51). The trace-panel + no-leak assertions are the faithful core; the prose denial checks are redundant with `/debug/route` disposition. |
| `08-domain-authz.spec.ts` | **UNFAITHFUL** | Segment×domain ABAC asserted by grepping the streamed `raw` for denial phrases and `!isDenied` (:38-40). This is exactly what `/debug/route` `overallDisposition == STRUCTURAL_DENIED` + `candidates[].domain` answers deterministically. Replace with `/debug/route` rows. |
| `09-cerbos-authz.spec.ts` | **FAITHFUL (keep)** | 65 assertions against the Cerbos decision directly (ALLOW/DENY per role×resource×action), not prose. This is the structural-authz unit litmus and is correctly targeted. Keep as-is. |
| `10-coverage-flow.spec.ts` | **PARTIALLY FAITHFUL** | Mixes a faithful UI clarification-options check + no-leak with prose denial greps and `length>20/30`. The coverage allow/deny is better asserted via `/debug/route` (`COVERAGE_DENIED` vs `SERVED`); keep only the UI-affordance checks (clarify chips render, no cross-client leak). |
| `03-jwt-identity.spec.ts` | **FAITHFUL** | JWKS/RS256/tampered-JWT-401/expired-401/book-in-claims. Identity contract, deterministic. Keep. |
| `00-login.spec.ts`, `admin-ui.spec.ts`, `11-screenshots.spec.ts` | **FAITHFUL (UI liveness)** | Login flow, admin console, screenshots. Not routing litmus; keep as UI suite. |
| `05-resilience.spec.ts` | **UNFAITHFUL-ish** | Partial-result tolerance asserted as `raw.length > 100` and `[DONE]` present under an injected fault (:42, :66). Hard-rule (d) — survivor synthesis — deserves a trace assertion: under fault, `/debug/route` still SERVES the survivors and the SSE trace shows `agent_start` for survivors + a faulted agent that never `agent_complete`s. The security-harness already does this better (`test_servicing_settlement_multistep`, `test_insurance_renewal_multistep`). Downgrade to a thin SSE-still-streams check; rely on the harness for the real assertion. |

### Security harness (`tests/e2e/security_harness/`) — the reference standard

| Asset | Verdict | Why |
|---|---|---|
| `test_multiturn_dag_backstop.py` | **FAITHFUL (exemplary)** | Multi-turn over the real BFF conversation, asserting on **trace events**: `plan_graph` node fan-in, `_entity_ids_from_events` carry (Whitman carried at turn 2, Calderon overrides at turn 3, :113-128), `check_denied` on the entitlement-recheck path (:143-156), and honest-clarify = no `plan_graph`/`agent_complete` (:172-173). This is the template the new faithful multi-turn litmus should follow. |
| `test_positive_path.py`, `test_entitlement.py`, `test_grounding.py`, `test_identity.py`, `test_prompt_injection.py`, `test_*_multistep.py`, `test_t4_coverage_per_producer.py`, `test_map_iteration.py`, `test_conditional_concentration_review.py` | **FAITHFUL** | All assert on `plan_graph`/`agent_start`/`check_denied`/`grounded_figures` trace events + no-leak, not answer prose. This suite is the project's real litmus and should be treated as the source of truth. |
| `test_routing_measurement_gate.py` | **FAITHFUL** | Shells `measure_goal_pick.py` against the live gateway. Keep. |

### Goal-pick harness (`eval/goal-pick/`) — the routing gold standard

| Asset | Verdict | Why |
|---|---|---|
| `measure_goal_pick.py` + `labeled_queries.json` (73), `capability_entity_conflict.json` (4), `name_invariance.json` (8), `routing_edge_cases.json` (9) | **FAITHFUL (keep, extend)** | Drives `/debug/route` per row with the row's **own persona token**, asserts exact-capability accuracy, wrong-domain-substitution==0, 100% out-of-scope abstain, per-row `expected_disposition` / `expected_denied_capability`, and hard `path==production` + `preparationVersion` drift guards. This IS the faithful routing litmus. `capability_entity_conflict.json` already encodes bug-261 (uw_sam settlements on Continental Freight → `settlement_status` DENIED, :8-15). The new SMOKE spec is a fast subset of this same harness. |
| `rebaseline.py`, `baselines/`, `REBASELINE.md` | **KEEP** | Threshold calibration tooling. Note: `labeled_queries.json` floors are still placeholders (`capability_accuracy:0.9` marked "placeholder until rebaseline") — the gate is real but the floor needs freezing. |

---

## 2. The right minimal litmus set

Two layers. Everything else is either the existing gold-standard harness (keep) or deleted.

### 2a. SMOKE — `scripts/smoke-route.sh` (NEW; trace-asserting, ~30s, no synthesis)

A fast `/debug/route` gate: mint each row's persona token, POST the body, assert on
`overallDisposition` + `resolver.primaryAgentId` (+ `disposition[].deniedCapabilityIds` for denies).
This is `measure_goal_pick.py` semantics, hand-picked to the demo-critical cases so it runs in
seconds and is readable in a PR. Pattern (bash, mirrors `smoke.sh`'s `tok()`):

```bash
GW=http://localhost:8080 ; AX=http://localhost:8084 ; PW=Meridian@2024
tok(){ curl -s -X POST $AX/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])'; }
# route <persona> <prompt> -> emits JSON RouteDecision
route(){ local t; t=$(tok "$1"); curl -s -X POST "$GW/debug/route" \
  -H "Content-Type: application/json" -H "Authorization: Bearer $t" \
  -d "{\"model\":\"smoke\",\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"$2\"}]}"; }
# assert helper: assert <label> <json> <jq-ish python expr>
```

Assert with a tiny python projection over the response: `overallDisposition`,
`resolver.primaryAgentId`, `[c.agentId for c in candidates if c.selected]`,
`[d for grp in disposition for d in grp.deniedCapabilityIds]`.

| # | Persona | Prompt | Assert (deterministic, from RouteDecision) |
|---|---|---|---|
| S1 happy-wealth | rm_jane | `Show the securities held in Whitman Family Office's wealth portfolio.` | `overallDisposition == "SERVED"` **and** `resolver.primaryAgentId == "meridian.wealth.holdings"` **and** `resolver.primaryDomain == "wealth-management"` |
| S2 happy-insurance | uw_sam | `Give me the policy details for Continental Freight (POL-77001).` | `overallDisposition == "SERVED"` **and** `resolver.primaryAgentId == "meridian.insurance.policy_details"` |
| S3 **bug-261 capability-vs-entity** | uw_sam | `Show pending and failed settlements for Continental Freight.` | `resolver.primaryAgentId == "meridian.servicing.settlement_status"` (routed on CAPABILITY, not the insurance entity) **and** `overallDisposition == "STRUCTURAL_DENIED"` **and** zero selected candidates in domain `insurance` (`all(c.domain != "insurance" for c in candidates if c.selected)`) **and** `"meridian.servicing.settlement_status" in denied capability ids` |
| S4 coverage-deny | rm_jane | `Show me the Okafor Capital holdings (REL-00188).` | routing lands wealth (`resolver.primaryDomain == "wealth-management"`) **and** `overallDisposition == "COVERAGE_DENIED"` (correct route, book CHECK denies — RESOLVE is principal-agnostic, CHECK is the gate) |
| S5 cross-domain | rm_jane | `Give me Whitman's holdings and their settlement status (REL-00042).` | `overallDisposition == "PARTIAL"` (wealth SERVED, servicing may deny for a wealth-only segment) **and** selected candidates include `meridian.wealth.holdings`; if rm_jane holds servicing, expect `SERVED` with both `holdings` + `settlement_status` selected. (Pin the exact disposition to rm_jane's real segments at author time — the point is a multi-capability plan, asserted on `disposition[]`, not prose.) |
| S6 out-of-scope ABSTAIN | rm_jane | `Write a haiku about compound interest.` | `overallDisposition == "ABSTAIN"` **and** `resolver.fallback == true` **and** no selected candidates |
| S7 CLARIFY (deterministic) | rm_jane | `What is the latest on my client?` | Routes but grounding is unresolved → **assert via SSE trace** (see below): the chat turn emits **no** `agent_complete`/`plan_graph` and the answer is a clarification. On `/debug/route` this surfaces as `grounded[]` empty / focal verdict NONE with no served group; assert `overallDisposition` is not `SERVED` and no data agent is selected. |

**Where SSE end-to-end adds value beyond `/debug/route`:** exactly three things `/debug/route`
cannot witness — (1) the **byte-exact SSE contract** (keep `smoke.sh` §C: `^data: \{`, `^data: \[DONE\]$`,
`"finish_reason":"stop"`); (2) **CLARIFY is a real clarification turn** not a silent empty answer
(S7 — assert the SSE turn produced no `agent_complete` trace event via `GET /trace/{requestId}`,
the harness pattern); (3) **partial-result survivor synthesis under fault** (rule d) — assert the
faulted-agent turn still streams `[DONE]` AND the trace shows survivor `agent_start` without the
faulted agent's `agent_complete`. Everything else is cheaper and stricter on `/debug/route`.

`smoke.sh` should call `smoke-route.sh` in place of its current §D prose block, keeping §A-C
(health + SSE bytes) and §E-F (telemetry/ops).

### 2b. FAITHFUL MULTI-TURN — `tests/e2e/security_harness/test_multiturn_litmus.py` (NEW; or extend the existing exemplar)

Drive **one real conversation** over the BFF (`bff_client.send_message`, same as
`test_multiturn_dag_backstop.py`), and after each turn read `GET /trace/{requestId}` and assert on
trace events + `_entity_ids_from_events` — **never** on `turn.answer_text` beyond a liveness
`len > 20`. Four scenarios:

**M1 — FOLLOW_UP context carry (rm_jane).**
| Turn | User | Deterministic assertion |
|---|---|---|
| 1 | `Show holdings for Whitman Family Office (REL-00042).` | `plan_graph` present; a `holdings` node; `REL-00042 ∈ entity_ids` |
| 2 | `And what's the performance on that portfolio?` (no rename) | `plan_graph` present; a `performance` node (new facet re-routes); `REL-00042 ∈ entity_ids` (carried); **no** `check_denied` |

**M2 — CLARIFY → disambiguate → resolved (rm_jane).**
| Turn | User | Deterministic assertion |
|---|---|---|
| 1 | `And their settlement risk?` (no focal entity, fresh convo) | honest clarify: **no** `plan_graph`, **no** `agent_complete` (copy `test_multiturn_dag_backstop.py:159-173`) |
| 2 | `For Whitman Family Office (REL-00042).` | now resolves: `plan_graph` present, `REL-00042 ∈ entity_ids`, a settlement node present |

**M3 — recency / topic-switch drops stale focus (rm_jane).** (replaces `multiturn-routing.json` mt_06/mt_07 + `probe-recency-insurance.py`)
| Turn | User | Deterministic assertion |
|---|---|---|
| 1 | `Concentration for Whitman Family Office (REL-00042)?` | `REL-00042 ∈ entity_ids` |
| 2 | `What about Calderon Trust (REL-00099)?` | `REL-00099 ∈ entity_ids` **and** `REL-00042 ∉ entity_ids` (explicit name supersedes) |
| 3 | `And their settlement risk?` (anaphor) | `REL-00099 ∈ entity_ids` **and** `REL-00042 ∉ entity_ids` (anaphor binds to most-recent focus, not turn-1) |

**M4 — entitlement carry / recheck across turns (rm_carlos — NOT entitled to REL-00042).** (copy `test_multiturn_carried_entity_rechecks_entitlement`)
| Turn | User | Deterministic assertion |
|---|---|---|
| 1 | `Show holdings for Whitman Family Office (REL-00042).` | `check_denied` present; no position tickers leak in `answer_text` |
| 2 | `And their settlement risk?` (carries the denied entity) | `check_denied` present again (re-checked, not cached-allowed); `REL-00042 ∈ entity_ids`; **no** `agent_complete` |

Every assertion is an event predicate or an entity-id set membership — zero dependence on the
wording of the synthesized answer. M3/M4 fully absorb the intent of the two orphaned JSON fixtures
and the un-asserting insurance probe, but as *gated* assertions.

**Insurance recency variant (optional):** re-run M3 as uw_sam over POL-77002/POL-77001 to prove the
recency rule is domain-generic (the intent of `multiturn-recency-insurance.json`). Assert the
coverage-gate entity in the trace, not prose.

### Reasoning-model latency note (timeouts)

The chat/SSE path now synthesizes with **gpt-5-mini, a reasoning model** → high first-token
latency. Multi-turn BFF turns must budget generously: the existing harness uses `CHAT_TIMEOUT_S=150`
(`config.py`) and Playwright multi-turn specs already set 8–10 min suite timeouts. Keep multi-turn
BFF turn timeouts ≥150s and never assert "answer arrived within N small seconds." **The SMOKE
`/debug/route` layer is immune** — it runs intent+extract (nano/mini) but **no synthesis**, so it
stays sub-second-per-row and is where the bulk of routing coverage should live precisely because it
sidesteps the reasoning-model latency.

---

## 3. Explicit DELETE list

Delete (stale / unfaithful-beyond-repair / superseded):

1. **`scripts/e2e-matrix.sh`** — 12 rows of pure prose grep; superseded by `smoke-route.sh` + `measure_goal_pick.py`. References a non-verified persona (`analyst_amy`). Unfaithful beyond repair.
2. **`eval/multiturn-routing.json`** — orphaned (no runner); its routing intent lives in `eval/goal-pick/routing_edge_cases.json` (`multi_turn` rows, gated) and the new M1-M3.
3. **`eval/multiturn-recency-insurance.json`** — orphaned (no runner); superseded by the M3 insurance variant asserting the coverage-gate entity from the trace.
4. **`scripts/probe-recency-insurance.py`** — asserts nothing (prints truncated prose); its intent becomes gated in M3. If kept at all, keep only as a hand-run demo tool, not in any gate.
5. **`scripts/smoke.sh` §D block (lines 56-62)** — excise the prose-grep "World B scenarios"; replace the call with `bash scripts/smoke-route.sh`. Keep §A-C and §E-F.
6. **`tests/e2e/tests/08-domain-authz.spec.ts`** — segment×domain ABAC by prose grep; fully replaced by S3/S4 disposition assertions on `/debug/route` + the faithful `09-cerbos-authz.spec.ts`.

Rewrite-in-place (don't delete, de-prose):

7. **`tests/e2e/tests/07-multi-turn.spec.ts`** — replace `length>30` / `!includes('which client')` with the M1-M4 trace-event assertions (or delete and rely on `test_multiturn_litmus.py`; the security harness is the better home). At minimum, stop counting it as routing coverage.
8. **`tests/e2e/tests/02-hero-prompt.spec.ts`** — drop the 6-way prose OR (`reply contains grounded facts`, :44-53); keep the trace-panel `Intent:` / `Resolved N Agent` / `Segment Passed` / `Classification Passed` assertions.
9. **`tests/e2e/tests/04-entitlements.spec.ts` & `10-coverage-flow.spec.ts`** — keep the trace-panel (`Coverage Denied`) + no-leak assertions; delete the denial-phrase prose greps (redundant with S4's `COVERAGE_DENIED`).
10. **`tests/e2e/tests/05-resilience.spec.ts`** — downgrade `length>100` to a thin "SSE still streams `[DONE]` under fault"; rely on the security harness `test_*_multistep.py` for the real survivor assertion.
11. **`scripts/integration-test.sh`** — fold its unique health/models checks into `smoke.sh` §A / `verify.sh`; delete the duplicate SSE-shape checks.

Keep untouched (already faithful): `smoke-ui.sh`, `verify.sh`, `verify-telemetry-e2e.sh`,
`routing-measurement-gate.sh`, `measure_goal_pick.py` + its 4 datasets, `rebaseline.py`,
the entire `tests/e2e/security_harness/` suite, `09-cerbos-authz.spec.ts`, `03-jwt-identity.spec.ts`,
`00-login.spec.ts`, `admin-ui.spec.ts`, `11-screenshots.spec.ts`.
Keep as manual (not automated litmus): `conduit-ui-tests/07-multiturn-memory.md`.

---

## 4. Faithfulness gotchas (where a naive test false-greens)

1. **HTTP 200 ≠ correct.** The chat path is `permitAll` and partial-result-tolerant (rule d): a
   fully-denied or all-agents-failed turn still returns **200** and still streams `[DONE]`. Asserting
   status/`[DONE]` proves the SSE contract, never the routing/entitlement outcome.
2. **Prose grep false-greens on degraded answers.** `grep "holding"` passes on "I could not retrieve
   holdings." `grep "[0-9],[0-9]{3}"` passes on any stray number. A wide OR (`whitman|holdings|%|...`)
   is true for almost any answer. This is the exact class the mandate bans — and it is in `smoke.sh`,
   `e2e-matrix.sh`, `07/08/10.spec.ts`.
3. **Denial-phrase ambiguity.** "not in your coverage" appears in BOTH a full denial AND a correct
   PARTIAL answer that harvested survivors and stated what's missing (rule d). You cannot distinguish
   deny from partial by prose — `e2e-matrix.sh` even documents its own struggle here. `/debug/route`
   `overallDisposition` distinguishes `COVERAGE_DENIED` / `STRUCTURAL_DENIED` / `PARTIAL` / `SERVED`
   exactly.
4. **`length > 30` is not "context carried."** A wrong-client or hallucinated 31-char answer passes.
   Context carry must be asserted as `REL-00042 ∈ entity_ids` in the trace (M1/M3).
5. **CLARIFY must be positively asserted.** A silent empty or hallucinated-guess answer must not pass
   as "clarified." Assert **no `plan_graph`/`agent_complete`** on the clarify turn (the harness pattern),
   not the presence of the word "which".
6. **bug-261 needs a NEGATIVE assertion.** It is not enough that settlements routed somewhere; assert
   **zero selected candidates in the insurance domain** — the whole point is the router did not key on
   the "Continental Freight" (insurance) entity. Prose can never prove a negative-domain claim.
7. **Reasoning-model latency.** gpt-5-mini synthesis = high first-token latency; any SSE/BFF turn must
   budget ≥150s and must not assert fast timing. Put the routing bulk on `/debug/route` (no synthesis)
   to stay fast and deterministic.
8. **Placeholder floors.** `labeled_queries.json` `capability_accuracy:0.9` is still a placeholder
   (per its own `_note`); run `rebaseline.py` and freeze the C-baseline so the gate has real teeth.
