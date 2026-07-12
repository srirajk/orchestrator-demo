# Smart Orchestration — Dependent Multi-Step Plans (DAG) Spec

> Status: **DESIGN / FOR DEBATE** — no code until this is agreed.
> Scope: turn the gateway from a *flat fan-out router* into a *manifest-driven, dependent-step
> orchestrator* — read-only first, action later. This is an **additive** change: the flat path
> stays; the DAG path is opt-in per request.
> Companion docs: `execution-orchestration-layer.md` (current executor), `WORLD-B-LOCKDOWN.md`
> (invariants), `domain-manifest-and-memory.md` (manifest model), `input-synthesis-deep-spec.md`.

---

## 0. The one-paragraph thesis

Today a question fans out to N independent agents in parallel and one synthesis prompt stitches
the answers together. That is a **router**, not an orchestrator — no step consumes another step's
output. This spec closes that gap: the LLM maps a question to a **goal capability** (its only job),
a **deterministic resolver builds the execution DAG** by matching each capability's declared
outputs to downstream inputs (build-system style), a **blackboard** threads upstream results into
downstream inputs, and the existing per-agent harness runs each node. Every gate we enforce today
(entitlements, grounding, no-fabricated-IDs, partial-result tolerance) carries over unchanged — the
orchestrator just chains gated steps. Zero domain knowledge enters the gateway: dependency
structure lives in **manifests**, not Java.

---

## 1. Why this shape, and not the obvious one (evidence)

The obvious approach — "ask the LLM to output the plan graph" — is empirically the unreliable one.
The design below is the researched, corroborated alternative (deep-research pass, 27 sources, 21
verified claims, 2026-07).

| Claim | Evidence | Source |
|---|---|---|
| LLMs are bad at emitting a whole execution DAG | pass@1 exact-graph-match GPT-4o **0.18–0.24**, Claude 4 **0.15–0.23** | OrchDAG, Amazon Science, `arxiv 2510.24663` |
| Deterministic planners >> LLM planners on validity | classical **~98%** vs best reasoning LLM **~63%** on PDDL | `arxiv 2507.23589` |
| "Edge = producer output key consumed as downstream input key, then topo-sort" is a named pattern | OrchDAG / LLMCompiler lineage | `arxiv 2510.24663` |
| Type-checked edges, mismatch = hard abort; validate before execute | 7 sequential structural checks (node exists, edge valid, type-compat, acyclic via Kahn's, no orphans, arity, required params) | PlanCompiler, `arxiv 2604.13092` |
| Blackboard (pass keys, runtime substitutes values) beats LLM tracking state | +55pp multi-step tool accuracy (directional, single-source) | Routine, `arxiv 2507.14447` |
| Bounded replanning + deterministic escape hatch prevents runaway | 100% valid within ≤5 reprompts, avg <0.35 | `arxiv 2507.07115` |
| Contract-failure → bounded recovery → harvest partial results (not whole-plan abort) | three-level escalation, no unbounded replanning | `arxiv 2604.11378` |

### The honest caveats (these shape the spec, not decorate it)

1. **Structural validity ≠ semantic correctness.** Constrained decoding drives *execution* error to
   0 but leaves **~37% residual planning error** (TAPE, `arxiv 2602.19633`). Type-matching proves the
   plan is *well-formed and runnable*, **not that the goal was the right goal.** Our deterministic
   CLARIFY (`extracted ∩ required = ∅`) covers *missing context*, not *wrong-goal-for-ambiguous-intent*.
   → **Mitigation:** keep the LLM's surface minimal (pick a goal from the registry, no graph, no IDs);
   rely on deterministic entity resolution + CLARIFY; treat wrong-goal as a **monitored eval risk**,
   not a solved problem. We state this openly.
2. **Ambiguous producers are unsolved by type-matching alone.** If two capabilities both produce
   `holdings`, `produces→needs` cannot choose. No surveyed source gives a deterministic tie-break.
   → **Mitigation:** manifest-declared **priority/specificity** first; **bounded clarify** as escape
   hatch. Never silently pick.
3. **Over-constraining format degrades reasoning** (up to ~27pp, EMNLP 2024 `arxiv 2408.02442`).
   → **Mitigation:** reason in natural language, *then* extract the goal in a separate constrained
   step — exactly what `IntentClassifier` already does. Don't fight it.
4. **Type-matching may get coarse at scale** (10s→100s of capabilities; 5-primitive schemes lean on
   an `ANY` escape hatch that erodes checking). → For phase one (a handful of capabilities per
   domain) this is fine; noted as a scaling limit, not a phase-one problem.

---

## 2. Architecture — five components, mapped to real seams

Current pipeline (confirmed by code map), anchors in `gateway/src/main/java/ai/conduit/gateway`:

```
ChatCompletionsController (api/v1/chat) ──▶ ChatService.handleChat (domain/chat)
  intent(IntentClassifier) ─▶ route(AgentResolver) ─▶ structural authz(EntitlementService.filterAgents)
  ─▶ coverage(check) ─▶ input-synth(InputSynthesizerImpl.bind) ─▶ per-relationship authz
  ─▶ BUILD PLAN [flat] ─▶ FlatPlanExecutor.execute ─▶ AnswerSynthesizer.synthesize ─▶ SSE
```

The plan is built flat at **`ChatService.java:738-743`** — one `PlanNode` per bound agent,
`dependsOn = List.of()` always. `FlatPlanExecutor` ignores `dependsOn`. `PlanNode.dependsOn`
already exists in the model (deliberately left for "a later phase without a schema change").

### 2.1 Goal Planner (NEW) — the only LLM step that's new
- **Input:** the user question + the manifest-compiled catalog of **goal capabilities** (id + human
  description + declared inputs/outputs). **Output:** a single chosen `goal_capability_id`
  (+ optional secondary goals for genuinely compound questions), nothing else. No graph. No IDs.
- **Constraint:** the LLM selects from the registry only — an unregistered goal is rejected and
  reprompted (bounded). Prompt compiled from manifests (World-B): no domain literals.
- **Where:** a new `orchestration/planner/GoalPlanner` sitting between routing and plan-build.
  For phase one it can reuse the routing signal — the resolved agent's capability *is* often the
  goal — so this starts thin.

### 2.2 DAG Resolver (NEW, deterministic) — the heart
- Given the goal capability, walk **backwards**: the goal *needs* input fields; find the
  capability(ies) whose declared **outputs** satisfy each need; recurse until every need is met by
  either (a) an upstream capability's output, or (b) a value already in the blackboard (resolved
  entity / literal). Build edges producer→consumer, **topologically sort (Kahn's)**, reject cycles.
- **Validation (hard aborts, before any execution):** node exists in registry · every edge
  type-compatible · acyclic · no orphan needs · required params present. (PlanCompiler's 7 checks.)
- **Ambiguous producer rule:** if >1 capability produces a needed output → pick by manifest
  `priority` then `specificity` (most-constrained match); if still tied → **CLARIFY** (bounded),
  never guess.
- **Output:** a populated `Plan` with real `dependsOn` edges. **This is deterministic and unit-testable
  without an LLM** — that's how we *know* it's right.
- **Where:** `orchestration/planner/DagResolver`. Emits `Plan(nodes)` — same record as today.

### 2.3 DAG Executor (NEW, additive) — replaces flat fan-out when deps exist
- Topological-layered execution over `dependsOn`. Nodes with no unmet deps run in parallel on the
  **existing** virtual-thread executor via the **existing** `AgentHarness` (bulkhead/CB/SLA) — *no
  change to AgentHarness or ProtocolAdapter.* When a layer completes, project outputs into the
  blackboard, then release the next layer.
- **Partial-result tolerance (Hard-rule d, extended):** a node that FAILED/TIMEOUT fails its output
  contract → its dependents are marked `UNMET` and **skipped**, not retried forever; siblings run to
  the deadline; survivors are harvested; synthesis states what's missing. No whole-plan abort.
  Bounded: overall deadline unchanged (`fan-out-deadline-ms`).
- **Where:** `orchestration/executor/DagPlanExecutor` alongside `FlatPlanExecutor`. Call site
  `ChatService.java:749` branches: deps present → DAG executor; else → flat (zero regression risk).

### 2.4 Blackboard (NEW) — inter-node dataflow
- A per-request map: resolved entities (from `EntityBag`) + each completed node's projected outputs,
  keyed by manifest-declared output names. Before a dependent node runs, its input is (re)bound from
  the blackboard — generalizing `InputSynthesizerImpl.bind` (`:112`) to also read upstream outputs,
  not just `EntityBag`.
- Follows the validated **variable-memory** pattern: the plan references output **keys**; the runtime
  substitutes **values** at bind time. The LLM never sees or juggles intermediate values.
- **No-fabrication rule holds:** a dependent whose required input is still null after blackboard bind
  is **dropped** (existing `InputSynthesizerImpl:141-149` behavior), never invented.

### 2.5 DAG Trace (NEW event, additive) — the glass box grows an edge
- Today the trace is a linear event stream (`agent_start`/`agent_complete` carry `agentId`, no
  edges). Add one event: `plan_graph` (nodes + `dependsOn` edges + per-node status), published once
  after plan-build near `ChatService.java:745`. Backend is additive (`TraceEventPublisher` is
  structure-agnostic). UI rail gains a small DAG view — the real front-end cost, deferrable behind a
  linear fallback.

---

## 3. The one real schema change — typed I/O contracts

Today (`registry/agent-manifest.schema.json`): input schema is derived-but-weak (all params typed
`"string"`); **output schema is a stub** ("left as a seam"); an agent advertises only free-text
`description`/`tags`/`examples`. There is **no declaration of what an agent produces** → nothing for
`produces→needs` to match on. This is the gap that must close.

**Add to each skill/capability in the manifest** (submitted, not introspected):

```jsonc
"io": {
  "consumes": [ { "name": "client_entity", "type": "entity.client", "required": true } ],
  "produces": [ { "name": "holdings",      "type": "list.holding"  } ]
}
```

- Types are **manifest-declared symbolic names** (`entity.client`, `list.holding`, `metric.nav`) —
  a small controlled vocabulary per domain, in the domain manifest. **No domain literals in gateway
  Java** — the resolver matches on strings it reads from manifests (World-B clean).
- `required` drives the CLARIFY / drop-on-missing behavior we already have.
- Backward-compatible: capabilities without `io` are single-node (flat) only — the DAG path lights
  up domain-by-domain as contracts are declared. **Onboarding a multi-step domain = declaring `io`
  in its manifests. Zero gateway change.** (This *is* the World-B payoff for orchestration.)

---

## 4. World-B guardrails the planner must not trip

`scripts/world-b-check.sh` greps `gateway/src/main/java`; CRITICAL must stay **0**. The planner/
resolver/executor code must **derive all of these from manifests, never hardcode**:
- no domain/sub-domain names, no client/entity names, no `REL-`/`FND-` patterns
- no entity-type literals (`"relationship"`, `"fund"`), no user-facing domain copy
- the type vocabulary (`entity.client`, `list.holding`, …) lives in **domain manifests**, referenced
  by string in gateway code — the resolver is a generic graph algorithm over declared contracts.
- IDs never produced by the LLM; entity resolution stays deterministic (`EntityResolver`).
Run before/after; report CRITICAL count. Any new file under `orchestration/planner|executor` is
generic graph/dataflow logic — if a domain word appears in it, the design has leaked and must stop.

---

## 5. Phase-one vertical (read-only, one workflow) — definition of done

Prove the whole spine on **one genuinely dependent, cross-domain, read-only** question. Candidate:

> *"Is the Whitman family office over-concentrated relative to their insurance exposure?"*

- **Goal:** `concentration.compare` (needs `{holdings, policies}`, produces `{analysis}`).
- **Derived DAG:** `resolve(Whitman→REL-…)` → `{ wealth.holdings ∥ insurance.policies }` →
  `concentration.compare` → synthesize. Two producers, no interdependency → parallel layer; compare
  depends on both. **The shape falls out of the contracts — nobody drew it.**
- **DoD:** (1) DAG derived deterministically + unit-tested with a fake registry, no LLM; (2) blackboard
  threads holdings+policies into the compare node; (3) per-node entitlement + grounding unchanged;
  (4) kill one producer (e.g. insurance times out) → compare degrades / or is skipped, answer states
  what's missing, siblings survive; (5) `plan_graph` event emitted, trace shows the DAG;
  (6) `world-b-check.sh` CRITICAL 0 before *and* after.
- **Explicitly out of scope for phase one:** writes/actions (access-mode gate stays dormant),
  dynamic mid-execution replanning (static-DAG-plus-guards only), LLM-emitted graphs (never).

> Note on the "compare" node: today `AnswerSynthesizer` is forbidden from computing across DATA
> sections (Hard-rule c). A `compare`/reduce step is therefore **a real agent/capability** (deterministic
> or a constrained tool), **not** the synthesizer doing math. The synthesizer still only narrates
> grounded values. This keeps the no-compute rule intact.

---

## 6. Build order (each step independently shippable, repo stays runnable)

1. **Schema + one manifest**: add `io.consumes/produces` to the schema and to the wealth-holdings +
   insurance-policies + a new `concentration.compare` manifest. No Java yet.
2. **DagResolver + tests**: deterministic derivation + 7 validations + ambiguous-producer rule.
   Pure unit tests, fake registry, no LLM, no network. *(highest-value, lowest-risk — do first.)*
3. **DagPlanExecutor + Blackboard**: topological execution reusing `AgentHarness`; branch at
   `ChatService:749`. Flat path untouched.
4. **GoalPlanner (thin)**: start by reusing the routing signal; formalize the goal-selection LLM
   step only if routing alone can't pick the goal.
5. **plan_graph trace event** + minimal UI DAG view (linear fallback stays).
6. **Eval**: add the cross-domain vertical to the eval set; watch grounding + a new "plan-correctness"
   check (did the derived DAG match the expected shape?).

---

## 7. Open questions (carry into the debate)

- **Wrong-goal on ambiguous intent** — structural validation can't catch it (~37% residual). Do we
  add a lightweight goal-confirmation clarify for low-confidence goal selection, or accept + monitor?
- **Ambiguous producers at scale** — is manifest `priority`+`specificity` enough, or do we need a
  richer type lattice before capability counts grow?
- **Compare/reduce nodes** — deterministic capability vs constrained LLM tool? (Leaning deterministic
  for anything numeric, to preserve no-compute.)
- **Dynamic replanning** — deferred to phase two; phase one is static-DAG-plus-guards. Agree?
```
