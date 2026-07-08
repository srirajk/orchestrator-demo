# Decision Log — Conduit multi-step orchestration

> Every load-bearing decision, why, and status. **Do not regress these without a new entry that
> supersedes.** Status: ✅ built · 🟡 designed, not built · 🔴 known-wrong, must fix.

---

### D1 — The LLM picks only the GOAL; deterministic code derives the pipeline. ✅
The LLM never draws the plan. It selects the goal capability (the destination) and extracts human
references. Everything else is code. **Why:** frontier LLMs get a whole execution graph right ~18–24%
of the time; deterministic/typed derivation ~98%. Letting the model plan is the flaky "camp-1"
approach; we deliberately took planning away from the model. Lineage: LLMCompiler, OrchDAG, LLM-Modulo.

### D2 — Two contracts, kept separate: semantic `io` metadata vs introspected wire schema. 🟡
`io.produces/consumes` are **semantic port-types** (metadata, human-declared) — they decide *topology*
(what connects). The **wire schema** (real field structure) is **introspected from the protocol**
(MCP `tools/list` / OpenAPI / A2A card) and drives *binding*. **Why:** you declare the cheap metadata;
the machine learns the expensive structure. `io` = the socket type; wire schema = the pin layout.
*Status:* input introspection exists; **output-schema introspection is a stub (must build).*

### D3 — The pipeline is constructed at RUNTIME from type-matching, per question. ✅
The DAG is derived by matching `produces.type → consumes.from` (build-system style), topo-sorted
(Kahn), per request. No workflow is ever authored or stored. **Why:** onboarding = declare an agent's
`io`; every valid composition "lights up" for free. Combinatorial over what exists, never open-ended
invention → always explainable, never surprising.

### D4 — LLM only at the two edges; ZERO LLM between layers. ✅
Exactly two model calls per request: (1) intent+entity extraction/goal-pick at the front, (2) grounded
synthesis at the back. Between layers = deterministic data flow. **A reasoning step is a declared agent
NODE, never a hidden inter-layer LLM call.** **Why:** one-in/one-out (not one-per-hop) bounds latency,
removes per-step hallucination, and keeps numbers verbatim through the middle.

### D5 — Layered (topological) execution: independent parallel, dependent sequential. ✅
Kahn layers: in-degree-0 nodes run in parallel (virtual threads) via the per-agent resilience harness;
a node is released only when its deps are satisfied. Partial-failure tolerant: a failed node skips its
dependents, harvests survivors, never aborts. *Refinement (🟡): drop the strict layer BARRIER — release
each node the instant ITS deps finish — to kill tail latency (see teardown S4/S6).*

### D6 — A Blackboard threads producer outputs into consumer inputs. ✅/🔴
The carrier between nodes. **Current impl is naive** (verbatim pass-through for 1 producer, merge-by-name
for N) and has **no data contract** — this is a FATAL (F3): it works only when shapes coincide. Target =
the translator layer (D7).

### D7 — The translator/predicate layer: LLM PROPOSES the transform, an interpreter EXECUTES it. 🟡
The LLM's *second* job: describe **how the glue behaves** — the per-edge transform and the conditional
predicates — as a **CEL expression** (predicates, extraction) or **JMESPath/CEL-object-construction**
(structural reshaping). A **sandboxed, non-Turing-complete, deterministic** interpreter evaluates it.
**The LLM is never in the runtime loop:** the expression is generated + **schema-validated** at
*onboarding / first-use* and **cached** as a deterministic artifact, keyed by the (producer-schema,
consumer-schema) **type-pair** — NOT by the question. **Why:** flexible (adapts to real shapes), safe
(can't do arbitrary harm), verifiable (type-checked against both schemas). This is "propose-then-verify."

### D8 — Canonical Data Model to collapse N² → N translators. 🟡
Translators keyed by agent↔agent pairs are O(N²) and a cross-team governance problem. Instead: each
agent maps to/from a **canonical type once** (depends only on its own schema + the canonical schema) →
O(N), fully precomputable at onboarding, **zero runtime generation**. A runtime edge = producer→canonical→consumer
via two precomputed mappings. Fallback if not adopting canonical: generate-on-first-use + cache (LLM in
the loop **once** per novel edge, then cached forever — not per request).

### D9 — Control-flow tiers, all DECLARED and BOUNDED (never LLM-freeform). 🟡
A pure DAG can't branch or loop. Add, in order: **(1) conditional edges** = CEL predicates (reuses D7);
**(2) dynamic map/scatter** over a runtime-discovered collection = the DAG expands at runtime (still
acyclic); **(3) bounded, guarded loops** (max-iterations + termination predicate) = state-machine
territory, the real departure. **Invariant:** predicates are code, loops have hard bounds — the LLM
never decides whether to loop.

### D10 — Request-response is primary and in-JVM; durable/long-running is a Temporal SEAM. 🟡 (durable side PARKED)
> **PARKED (2026-07-08):** the durable / action-taking side (Temporal, ServiceNow) is out of scope for
> now — those are pre-built, certified, action-taking workflows we *route to* by question-mapping.
> Current focus is exclusively the **read-only dynamic req/response engine**. Clean risk split: **read =
> dynamic engine; write/action = certified durable workflow.** Leave the routing/`ExecutionBackend`
> seam; build nothing durable now.

The chat path derives + interprets a **plan-as-data**, synchronously, deadline-bounded, streamed — this
is what we build first. Temporal/Step-Functions assume *authored* workflows and are async/durable →
wrong for the request path. Long-running / human-approval / durable-side-effect plans hand the *same
plan* to a durable backend (Temporal), return async, notify. **One planner, two execution backends;
don't let durable contaminate request-response.** ("Build the simple path, leave the seam.")

### D11 — The EDGE is the unit of enforcement + transformation: two halves. 🟡
Every edge between two nodes carries: **(a) a STATIC half — the transform** (schema-driven,
precomputable at onboarding: canonical or first-use-cached); **(b) a DYNAMIC half — the coverage
filter** (data-driven, inherently runtime); **plus the identity hop.** This reconciles "translator at
compile time" (true for shape) with "coverage can't be upfront" (true for authorization) — they're the
two halves of the same edge.

### D12 — Coverage = row-level security on the data flow, interleaved with execution. 🔴→🟡
Upfront coverage only works for entities **named in the request**. Entities **discovered mid-DAG** (an
upstream node produces them) must be coverage-checked **at runtime, per entity, at the edge**, before
the downstream node (or synthesis) sees them. Rules: **RESOLVE is principal-agnostic, the CHECK is the
gate** (applied at runtime now); the **raw principal-agnostic output never crosses un-filtered**; and
**filter before any aggregation/count** (else you leak the size of the set outside the user's book).
Which coverage service = manifest-declared (World-B clean). *Current impl only checks the routed goal
entity — FATAL bypass (F2).*

### D13 — Per-hop identity: propagate the user's token as DATA; agents fail CLOSED. 🔴
Identity must be asserted and verified at every agent hop. *Current impl relies on thread-local
`SecurityContext`, which is LOST on the virtual-thread pipeline → no token sent → agents fail OPEN.*
FATAL (F1). Target: capture the token into an immutable request-context object at ingress, thread it
through PlanNode/harness/adapters as data, agents fail closed, e2e asserts a verified JWT per hop.

### D14 — Zero fabricated IDs; deterministic resolution. ✅
The LLM extracts a human reference; deterministic lookup (regex id_pattern short-circuit or a resolve
endpoint) produces the canonical ID. The model never emits an ID; an unresolved reference clarifies,
never guesses.

### D15 — Grounding: split PROVENANCE (runtime hard gate) from CORRECTNESS (the agent's job). 🔴→🟡
"Every number in the answer appears in an agent output" proves *transcription*, not *correctness* — and
today it's diagnostic-only. Target: (i) provenance = a **runtime hard gate** checking **attribution**
(number ↔ label ↔ source), and render load-bearing numbers **deterministically** (template), LLM writes
only prose; (ii) correctness belongs to the *agent's* own tests/contract — the gateway can't verify a
number it's forbidden to compute. Stop calling provenance "grounding" as if it implied correctness.

### D16 — World-B: the gateway carries zero domain knowledge. ✅ (invariant)
Every domain string comes from manifests; the gateway is a generic interpreter. Onboard a domain by
adding manifest JSON (+ coverage URL + Cerbos mapping), never by changing gateway Java. `world-b-check.sh`
CRITICAL must stay 0. This constrains every decision above: the resolver, translator, coverage filter,
and control-flow are all generic mechanisms over manifest-declared contracts.

---

## Decisions still OPEN (need a call)
- **O1** — Canonical Data Model (D8) vs first-use-cached translators: adopt canonical now (governance
  cost, O(N)) or defer with first-use caching?
- **O2** — Ambiguous-producer tie-break (D3): manifest priority + type versioning/namespacing vs
  bounded clarify.
- **O3** — Compound / multi-goal questions: support multiple sinks, or clarify-and-split?
- **O4** — Reproducibility/replay for a regulator: persist per-request the resolved plan + manifest
  versions + routing scores + extraction as an immutable audit record (teardown M2).
- **O5** — Servicing entity scope (F1 from earlier): make servicing relationship-scoped in manifests
  (changes coverage behavior) — validate coverage config first.
