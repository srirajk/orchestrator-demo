# Codex task T6 — conditional nodes (declared, deterministic branching)

> GATEWAY code — **World-B applies** (no domain/client/entity/ID literals in Java; predicates are DATA in
> manifests; the evaluator is generic). Run `scripts/world-b-check.sh` before AND after; report CRITICAL
> count (0). Do NOT commit (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`,
> branch `feat/conduit-chat` (pull latest — T3 is committed). Stack: docker compose `orchestrator-demo`.
> **READ FIRST:** `docs/orchestration-architecture/CONTROL-FLOW-DESIGN-BASIS.md` — this task implements the
> "conditionals" section of it. Honor that design. If anything is ambiguous, STOP and report (do not guess —
> that instruction just saved T3).

## Goal
Today the DAG is a static fan-in — every resolved node runs. Add **declared, deterministic conditional
nodes**: a node runs only if a manifest-declared predicate over its (already-bound) inputs is true;
otherwise it is **cleanly skipped** (honest, not a failure). This proves dynamic composition beyond fan-in
WITHOUT letting an LLM improvise control flow. Scope is **conditionals only** — NOT map/iteration/loops
(those are later).

## Design decision (node-level condition; declarative; JMESPath — consistent with `select`)
Add an OPTIONAL **`io.condition`** to the manifest: a single JMESPath expression that MUST evaluate to a
boolean, over the node's **merged, bound input** (the same object the consumer receives — producers merged
by `io.produces[].name`, exactly as `Blackboard.bind`/T3 build it). Node-level (one predicate per node),
NOT per-edge — it cleanly answers "does THIS node run?" and matches the design doc's example ("run the
rebalance/review step only if concentration breached"). It is DATA in the manifest; the gateway evaluates
it with the existing `io.burt:jmespath-jackson` runtime (same lib as `select`). No LLM. No arbitrary code.

Example (illustrative — confirm real produced-names/fields at build time):
`"io": { "consumes": [ {"from":"wealth.concentration_analysis","select":"..."} ],
         "condition": "concentration_analysis.breach_count > `0`",
         "produces": [ ... ] }`

## What to build
1. **Manifest schema** (all 3 copies — root `agent-manifest.schema.json`, `gateway/src/main/resources/...`,
   `registry/...`, keep in sync): optional `io.condition` (string, JMESPath). Update `AgentManifest`
   model (back-compat: absent = unconditional, today's behavior).
2. **Boot-time validation (extend T3's `SelectContractValidator` or a sibling):** for a node with
   `io.condition`, build the merged-input synthetic sample (reuse T3's machinery), then: (a) the expression
   COMPILES; (b) it evaluates to a **boolean** on the sample (a non-boolean result → reject); (c) it
   references only fields present in the merged-input schema (a reference to an absent field → reject).
   A malformed/underivable condition **fails at REGISTRY LOAD** with `agentId` + the expression + reason —
   never reaches runtime. (Runtime is defense-in-depth, below.)
3. **Executor (`DagPlanExecutor`) — the runtime semantics (this is the subtle part, get it exactly right):**
   After a node's inputs are bound (post-merge, pre-dispatch), if it has `io.condition`, evaluate it over
   the bound input:
   - **true →** run the node normally.
   - **false →** **SKIP cleanly.** `plan_graph` node status = `skipped_condition_false`. Emit a trace event
     (e.g. `node_condition`) carrying the expression and the verdict (so "honest skip" is *verifiable*).
     A clean skip is **NOT a failure**: it must NOT fire `request_partial`, must NOT be classified like a
     FAILED/BLOCKED node, and the synthesis prompt must treat a condition-false node as **"not applicable"**,
     NOT as "data unavailable / missing." Nodes that depend on a skipped node also skip cleanly (same
     status), not fail.
   - **evaluation error** (shouldn't happen post-boot-validation, but defense-in-depth) → status
     `condition_error` — a **distinct, visible FAILURE** (never a silent skip).
   Study `DagPlanExecutor.classifyDeps` / the BLOCKED/FAILED logic and `emitRequestPartial` — you MUST
   distinguish a clean condition-skip from a dependency failure so the partial-degradation metric and the
   answer are not polluted. This distinction is the crux of the task.
4. **`DagResolver`:** carry `io.condition` through onto the `PlanNode` so the executor can see it. The
   resolver still builds the plan structurally (a conditional node is still wired by its `from` edges); the
   condition only gates *execution*, not *resolution*.
5. **Determinism:** the predicate is pure JMESPath over already-bound data — no wall-clock, no randomness,
   no LLM, no sibling-arrival dependence. Same plan + same data → same branch, always (this also keeps the
   future audit/replay honest).

## Coverage dependency (note now, re-verify later)
The design doc requires predicates to evaluate over **coverage-filtered** inputs. Coverage per-producer
(T4) is deferred, so build now over the current bound inputs, and **add a `// T4-REVERIFY` note** at the
evaluation site: when T4 lands, confirm the condition sees post-coverage-filter data (so a predicate can't
reveal facts about uncovered entities). Do not build coverage here.

## Demo vertical (prove BOTH branches — this is the point)
Build ONE minimal conditional node to demonstrate it — copy the reference-agent pattern (pure `compute.py`
+ handler + manifest). Suggested: a lightweight **downstream node off `wealth.concentration`** that fires
only when there's a breach (`io.condition` on `breach_count > 0`) and, when it fires, simply surfaces the
already-computed breach for review (a review FLAG, not investment advice). **Finance discipline:** any
threshold is **firm-configured** (env parameter, labeled firm policy) per `docs/DOMAIN-KNOWLEDGE-VERIFIED.md`
— invent NO universal cutoff, and do not emit personalized investment advice; the node reflects the breach
concentration already found. Prove BOTH paths live:
- **fires:** the over-concentrated Whitman Family Office (6 breaches) → the conditional node RUNS, its
  output appears in the answer.
- **skips (honest):** a NON-breaching relationship → the node is `skipped_condition_false`, and the answer
  does NOT claim anything is missing/unavailable. **If the seed has no well-diversified relationship, add
  one** (mock holdings data whose weights are all under the limits) so the skip path is demonstrable — a
  feature isn't proven until you've shown it NOT firing, honestly.

## GATE (prove it; do not weaken to pass)
1. **BOOT-REJECT:** a manifest/fixture with a malformed `io.condition` (non-boolean result, or referencing
   an absent field) is REJECTED at registry load with a precise error. (Mirror T3's reject test.)
2. **FIRES:** condition-true node runs live (BFF), output in the answer, `plan_graph` shows it ran.
3. **SKIPS HONESTLY:** condition-false node → `skipped_condition_false` in `plan_graph`, a `node_condition`
   trace event with the expression+verdict, `request_partial` NOT fired, and the answer does NOT say data is
   missing — it simply omits the not-applicable branch. **This honest-skip assertion is the core proof.**
4. **DETERMINISM:** same query/data → same branch across repeated runs.
5. **NO REGRESSION:** the 3 existing verticals (all unconditional) still answer live, unchanged.
6. `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0.

## Constraints / anti-gaming
- Predicate is DETERMINISTIC and declared — no LLM, no arbitrary code, World-B clean (generic evaluator).
- A condition-false SKIP must be HONEST — never surfaced to the user as "missing data," never counted as a
  partial failure. A malformed condition must be a VISIBLE error, never a silent skip. Do NOT weaken
  validation or the skip/fail distinction to make the gate pass.
- Do NOT build map/scatter/loops. Do NOT build coverage. Do NOT commit.

## Report
Files changed; the `io.condition` grammar + where it's evaluated; the executor skip-vs-fail handling (how a
clean skip is distinguished from a dependency failure in `classifyDeps`/`emitRequestPartial`); the
boot-reject test; the live proof of BOTH branches (fires + honest skip) with `plan_graph` + trace evidence
(conversation/request ids); determinism check; mvn / World-B / 3-vertical-regression results; and the
`T4-REVERIFY` note location. STOP and report anything the spec didn't anticipate.
