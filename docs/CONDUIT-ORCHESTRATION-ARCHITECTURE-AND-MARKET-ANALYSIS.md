# Conduit Orchestration at Enterprise Scale

> An evidence-backed assessment of the architecture, the 2026 market direction, what Conduit has
> built, and the roadmap for extending it at enterprise scale.
>
> Research reviewed: 2026-07-10. Implementation assessment is based on the repository at that date.

## Executive verdict

**Conduit applies a state-of-the-art architectural approach to governed enterprise orchestration.** Its
defining position is:

> Conduit is a manifest-compiled, policy-enforced enterprise orchestration gateway that uses models for
> bounded semantic decisions and deterministic software for planning, authorization, execution, and
> auditability.

That architecture is directly aligned with the 2026 direction described by OpenAI, Google, Microsoft,
Anthropic, AWS, and the current planning literature. Conduit made the most important decision correctly:
**the LLM does not invent the execution graph.** It interprets the request and contributes a bounded goal
signal. Typed contracts, deterministic graph resolution, coverage, identity, and execution decide what
actually runs.

## 1. The planning problem the market is solving

An enterprise orchestration system has two different problems that should not be confused:

1. **Semantic interpretation:** What is the user asking for? Which registered business capability is the
   intended goal? Which human references appear in the request?
2. **Execution correctness:** Which capabilities must run, in what dependency order, with which inputs,
   authorization checks, failure behavior, and audit evidence?

Language models are useful for the first problem because language is ambiguous. They are unreliable as
the sole authority for the second problem because execution has invariants that must hold every time.

This distinction is supported by recent evidence:

| Evidence | Finding | Architectural consequence |
|---|---|---|
| [OrchDAG, Amazon Science](https://assets.amazon.science/cf/40/d2e7baf0437385eba4505d0453bc/cam-ready-118-orchdag-complex-tool-orche.pdf) | Exact DAG prediction pass@1 was 15% zero-shot for Claude 4 and 18% for GPT-4o; the best shown GPT-4o result was 24% with three examples. | Do not ask a model to emit the production execution graph. |
| [Can LLM-Reasoning Models Replace Classical Planning?](https://arxiv.org/abs/2507.23589) | Fast Downward reached 97.85% mean planning success; the best reasoning LLMs reached 63.44%, with weaker execution fidelity. | Use formal or deterministic planning once the problem is represented as contracts. |
| [Agentic LLM Planning via Step-Wise PDDL Simulation](https://arxiv.org/abs/2603.06064) | Classical planning reached 85.3%; direct LLM planning 63.7%; agentic step-wise planning 66.7% at 5.7x token cost. | More agent loops do not remove the need for external verification. |

The often-repeated "15%" figure therefore needs precision. It is evidence about exact whole-DAG
generation on OrchDAG, not evidence that a constrained goal classifier is only 15% accurate. Goal
selection is a smaller problem and can perform much better, but it must still be measured on each
enterprise's real vocabulary and neighboring capabilities.

## 2. What orchestration at scale should look like

The scalable model is neither a fully authored workflow for every question nor an autonomous supervisor
that improvises every step. It is a hybrid compiler/runtime architecture.

```text
User request
    |
    v
Probabilistic edge
  intent + references + bounded goal candidates
    |
    v
Deterministic control plane
  registry snapshot + policy + typed manifest compilation
    |
    v
Deterministic runtime
  resolve -> authorize -> bind -> execute -> filter -> validate
    |
    v
Domain-owned capabilities
  fetch facts or perform governed domain computation
    |
    v
Probabilistic edge
  phrase a response over grounded, attributed data
```

### 2.1 Bound the model's authority

The model may interpret natural language, rank registered goals, extract human references, and phrase an
answer. It must not create canonical IDs, invent capabilities, bypass policy, decide whether a required
business step can be skipped, or generate an unvalidated runtime graph.

The safe interface from model to runtime is a small structured decision such as:

```json
{
  "intent": "fetch_data",
  "goal_candidates": ["meridian.wealth.concentration"],
  "human_references": {"relationship": "Whitman Family Office"},
  "confidence": 0.91
}
```

Every value is checked against the registry. Low confidence, small margins, missing context, or unresolved
references lead to abstention or clarification.

### 2.2 Compile domain contracts into a runtime graph

Each capability declares semantic inputs and outputs. A deterministic resolver walks backward from the
goal, matches `consumes.from` to `produces.type`, verifies entity leaves, rejects missing or ambiguous
producers, detects cycles, and topologically orders the result.

This produces dynamic composition without dynamic invention. New valid compositions appear when a domain
team registers compatible contracts, but the runtime can only construct graphs licensed by those
contracts.

At fleet scale, compilation should happen against an immutable `RegistrySnapshot` containing:

- `agent_id -> effective manifest`;
- `semantic_type -> authorized producers`;
- `domain/sub-domain -> candidate capabilities`;
- a prevalidated dependency graph;
- routing index and embedding-model identity;
- content hashes for manifests, policy bundles, and schemas;
- admission status and version for every capability.

The request path should resolve over a domain-scoped snapshot, not scan and reinterpret the entire fleet.
Resolution remains approximately linear in the reachable graph rather than the total enterprise catalog.

### 2.3 Make every edge an enforcement boundary

The edge between a producer and consumer is the correct unit of control. Before data crosses it, the
runtime should:

1. Project only declared fields using a prevalidated transform.
2. Validate the projected value against the consumer's input schema.
3. Coverage-check entity-typed values before the consumer or an aggregate sees them.
4. Carry the verified end-user identity to the next hop.
5. Evaluate declared predicates over already-filtered data.
6. Attach source and manifest-version provenance.

This prevents an upstream agent's output from becoming trusted merely because it came from another agent.
It also keeps authorization independent of prompts and agent implementation.

### 2.4 Separate read reasoning from consequential action

Dynamic read-only orchestration is appropriate when calls are deadline-bounded, idempotent, and
partial-result tolerant. Writes, money movement, approvals, and irreversible actions should route to
certified durable workflows with explicit idempotency, approval, compensation, and replay semantics.

The same goal router can select either class, but the execution backends should remain different:

- **Read/query:** dynamically derived DAG in the gateway.
- **Write/action:** approved workflow definition executed by a durable engine.

### 2.5 Treat evaluation as admission, not a dashboard

A capability should not enter the fleet merely because its manifest is valid JSON. Admission must cover:

- exact leaf-agent goal accuracy;
- exact analytics-goal accuracy;
- domain-level accuracy;
- held-out paraphrase generalization;
- ambiguous-query clarification precision;
- out-of-scope abstention;
- neighboring-agent poaching;
- authorization allow/deny cases;
- coverage outage and out-of-book denial;
- contract and dataflow validation;
- partial failure and completeness behavior;
- latency and cost budgets.

The domain team provides real labeled cases. The platform may propose additional adversarial cases, but
the same generated examples must not be used both as routing examples and held-out evaluation.

## 3. What the 2026 market suggests

The external market is converging on hybrid control rather than pure autonomous planning.

### OpenAI

The [OpenAI Agents SDK orchestration guide](https://openai.github.io/openai-agents-python/multi_agent/)
supports both model-led and code-led orchestration, but explicitly states that code orchestration is more
deterministic and predictable in speed, cost, and performance. It recommends structured model outputs
that code can inspect before selecting the next agent. That is the same boundary Conduit uses between
semantic selection and deterministic execution.

### Google

Google's July 2026 explanation of [ADK 2.0](https://developers.googleblog.com/why-we-built-adk-20/)
argues that routing, scheduling, error handling, and fixed business order are jobs traditional code already
does well. ADK 2.0 separates execution routing from language processing and reserves models for ambiguous
or cognitive steps. This is almost a direct statement of Conduit's "LLM at the edges, deterministic in the
middle" thesis.

### Microsoft

Microsoft says [most real-world applications live in the hybrid middle](https://learn.microsoft.com/en-us/agent-framework/journey/workflows):
workflow graphs define order and gates, while agent executors handle reasoning-heavy steps. Its framework
also emphasizes typed routing, checkpoints, explicit human gates, and durable recovery.

### Anthropic

Anthropic's [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents)
distinguishes workflows, where tools and models follow predefined code paths, from agents, where the model
directs its process. It recommends the simplest composable pattern that meets the need and positions
workflows as the predictable choice for well-defined work.

### AWS

AWS offers autonomous supervisor-agent collaboration, which shows that model-led delegation remains useful
for open-ended scenarios. At the same time, [AgentCore Policy](https://docs.aws.amazon.com/bedrock-agentcore/latest/devguide/policy.html)
places deterministic enforcement outside agent code and intercepts every tool request at the gateway.
AWS's managed gateway, policy, identity, observability, memory, and evaluation portfolio also demonstrates
that these are now baseline enterprise control-plane expectations, not optional additions.

### LangGraph and durable runtimes

[LangGraph](https://docs.langchain.com/oss/python/langgraph/overview) centers production orchestration on
explicit graph state, checkpointing, persistence, human intervention, and observable transitions. The
broader signal is clear: production agent infrastructure is becoming workflow infrastructure with models
inside selected nodes, not a chain of unconstrained prompts.

### Market conclusion

The market does not suggest removing agents or model reasoning. It suggests controlling where model
reasoning has authority:

| Situation | Market-favored control |
|---|---|
| Ambiguous language, summarization, classification | Model |
| Known business ordering and dependencies | Workflow/graph |
| Authorization and policy | External deterministic enforcement |
| Long-running or consequential actions | Durable workflow + human gates |
| Cross-agent state | Typed/checkpointed state |
| Production promotion | Evaluation and admission evidence |

### SDKs solve application composition; Conduit targets domain-scale federation

Agent SDKs and graph frameworks are important, but they usually operate inside one application or one
team's solution boundary. A developer chooses the agents, writes or configures the graph, supplies the
state model, connects tools, and decides how policy and evaluation apply. The SDK can technically call
agents from many business domains, but it does not by itself create the organizational contracts required
to do that safely across independently owned domains.

That distinction is central:

| Application-scoped SDK/framework | Enterprise domain control plane |
|---|---|
| One solution team owns the composition. | Many domain teams independently own capabilities. |
| Graph or delegation logic is authored for the application. | Graph is derived from admitted semantic contracts per goal. |
| Tool schemas describe how to call something. | Manifests also declare ownership, domain placement, audience, classification, semantic dataflow, and limits. |
| Authorization is commonly added in tools or middleware. | Structural policy, entity coverage, and per-hop identity are platform invariants. |
| State belongs to the application run. | Context and memory cross turns under domain and ledger governance. |
| Evaluation proves one agent/application. | Admission must prove a new capability does not damage the existing fleet. |
| A new domain often means editing prompts, graph code, or application configuration. | A new domain is intended to mean manifests, policy/coverage contracts, agents, and evidence, with no gateway code. |

OpenAI Agents SDK, Google ADK, Microsoft Agent Framework, and LangGraph can all be used to implement
excellent domain agents or individual orchestrated applications. Conduit can treat those applications as
registered capabilities behind HTTP, MCP, or A2A. The relationship is therefore complementary: **SDKs help
domain teams build agents; Conduit governs how independently built domains enter, compose, and execute in
the enterprise.**

This is a stronger differentiator than "we support multi-agent." The differentiated claim is:

> Conduit federates independently owned business domains through executable contracts and compiles a
> governed cross-domain plan without centralizing domain logic in the gateway.

### Competitive landscape: similar control planes, different composition model

The category is real and increasingly competitive. No single primitive should be presented as unique.

| Platform | Publicly documented overlap | Difference from Conduit's model |
|---|---|---|
| [AWS Bedrock AgentCore](https://aws.amazon.com/bedrock/agentcore/faqs/) | Gateway, policy, identity, registry, memory, observability, evaluations | Broad control-plane coverage; public multi-agent orchestration is primarily supervisor/model-led rather than typed backward DAG derivation. |
| [MuleSoft Agent Fabric](https://docs.mulesoft.com/general/agent-fabric-overview) | Cross-vendor registry, Agent Broker, YAML networks, trusted identity, runtime gateway policy, observability | Very close category match; the broker balances rules and LLM reasoning, while Conduit derives topology deterministically from semantic producer-consumer contracts. |
| [IBM watsonx Orchestrate](https://www.ibm.com/products/watsonx-orchestrate/multi-agent-orchestration) | Governed catalog, promotion, external agents, gateway, deterministic workflows, agent-led orchestration | Teams select collaborators or author workflows; public docs do not describe Conduit-style per-request graph compilation from independently submitted `io` contracts. |
| [Salesforce Agentforce](https://architect.salesforce.com/docs/architect/fundamentals/guide/get-started-agentforce.html) | Agent Registry, Agent Broker, Flex Gateway, MCP/A2A, visualizer | Similar enterprise surfaces; orchestration centers on broker/primary-agent delegation rather than a deterministic manifest compiler. |
| [Microsoft Agent Framework and Agent 365](https://learn.microsoft.com/en-us/agent-framework/journey/workflows) | Identity-first control plane, registry, typed workflows, human gates, durable execution | Strong governance and workflow model; workflows are generally application-authored rather than discovered across domain contracts. |
| [Google ADK 2.0](https://developers.googleblog.com/why-we-built-adk-20/) | Deterministic workflow graph containing selected LLM nodes | Nearly identical trust philosophy; developers construct the workflow graph rather than the gateway deriving it from the enterprise capability registry. |
| [Camunda](https://camunda.com/de/solutions/agentic-orchestration/) | Deterministic BPMN processes containing governed agentic sections | Optimized for authored, durable end-to-end processes; Conduit targets dynamic, read-oriented request graphs across registered capabilities. |
| [Pega Agentic Process Fabric](https://academy.pega.com/topic/pega-agentic-process-fabric/v1) | Intelligent agent/workflow registry and a control agent that selects delegates | Uses AI reasoning to select an agent or workflow; it does not publicly document typed runtime graph derivation with entity coverage on every edge. |

Based on public documentation, the specific Conduit combination remains differentiated:

1. independently owned domain and agent manifests;
2. bounded semantic goal selection;
3. deterministic DAG derivation from `consumes` and `produces`;
4. structural and entity-level authorization interleaved with execution;
5. per-hop end-user identity;
6. deterministic figure attribution and fallback;
7. ledger-backed governed context;
8. fleet-level admission intended to prevent routing and policy regression.

This is a competitive architecture assessment, not a claim that no private implementation exists and not
a patent novelty opinion.

## 4. How Conduit maps to that market direction

### Already built and strongly aligned

| Market expectation | Conduit implementation |
|---|---|
| Bounded semantic routing | Manifest examples, vector routing, confidence floors, margins, abstention, optional reranking |
| Declarative capability catalog | Domain, sub-domain, and agent manifests |
| Protocol-neutral integration | HTTP/OpenAPI, MCP, and A2A connection contracts |
| Deterministic graph construction | `DagResolver` derives typed producer-consumer DAGs and refuses ambiguity |
| Structured dataflow | `Blackboard` binding with declared JMESPath projections |
| Deterministic control flow | Declared conditions and bounded map/scatter execution |
| Identity on every hop | Caller token passed as request data through DAG/flat execution and verified by agents |
| External authorization | Cerbos structural policy plus domain coverage services |
| Row/entity-level enforcement | Per-node and produced-entity coverage checks before downstream use |
| Resilience | Per-agent bulkheads, circuit breakers, SLA timeouts, overall deadline, partial-result harvesting |
| Grounded presentation | Manifest-declared figures, deterministic substitution, validation, deterministic fallback |
| Observability | Plan graph, routing, coverage, agent, map, condition, and completion trace events |
| Domain independence | Gateway mechanisms interpret manifests; domain vocabulary remains outside gateway Java |
| Governed multi-turn state | Ledger-backed memory and manifest-owned compaction policy |

Primary implementation anchors:

- [`ChatService`](../gateway/src/main/java/ai/conduit/gateway/domain/chat/ChatService.java) owns the
  request lifecycle, bounded goal selection, entitlement re-gate, DAG engagement, and reason-coded trace.
- [`DagResolver`](../gateway/src/main/java/ai/conduit/gateway/orchestration/planner/DagResolver.java) is the
  pure, domain-agnostic graph compiler.
- [`DagPlanExecutor`](../gateway/src/main/java/ai/conduit/gateway/orchestration/executor/DagPlanExecutor.java)
  provides deterministic dependency release, coverage enforcement, conditions, maps, deadlines, and
  partial-failure behavior.
- [`AgentRegistrar`](../gateway/src/main/java/ai/conduit/gateway/registry/service/AgentRegistrar.java) and
  [`SelectContractValidator`](../gateway/src/main/java/ai/conduit/gateway/registry/service/SelectContractValidator.java)
  implement schema validation, protocol introspection, contract validation, persistence, and indexing.
- [`AgentHarness`](../gateway/src/main/java/ai/conduit/gateway/orchestration/harness/AgentHarness.java) applies
  per-agent timeout, bulkhead, circuit-breaker, identity, and telemetry controls.
- [`AnswerSynthesizer`](../gateway/src/main/java/ai/conduit/gateway/synthesis/answer/AnswerSynthesizer.java)
  applies grounded-figure substitution, validation, and deterministic fallback.

### Policy and authorization are part of orchestration, not middleware decoration

Conduit has a layered authorization model whose decisions affect both plan formation and data movement:

```text
JWT verification at ingress
    -> Cerbos structural capability gate
    -> deterministic human-reference resolution
    -> domain coverage CHECK for the requested entity
    -> deterministic DAG derivation
    -> structural re-gate for every resolver-pulled producer
    -> per-node coverage before dispatch
    -> produced-entity coverage before downstream use or aggregation
    -> verified end-user identity on every agent hop
```

This is more specific than tool authorization. It asks not only whether a principal may invoke a tool,
but whether that principal may use this capability in this domain against this entity, and whether each
entity discovered during execution may cross the next dataflow edge.

AWS AgentCore Policy and MuleSoft Omni Gateway validate the market need for deterministic controls outside
agent prompts. Conduit's additional distinction is the domain-owned `DISCOVER/RESOLVE/CHECK` coverage
contract and its placement inside dynamic graph execution. Coverage is current business authorization
data, not a durable claim copied into the prompt or token.

The model is defense in depth:

| Layer | Decision |
|---|---|
| Identity | Is this a verified principal, and is the same user asserted at every hop? |
| Structural policy | May this role/segment/classification invoke this capability? |
| Coverage | May this principal access this particular business entity now? |
| Plan re-gate | May every capability discovered by the resolver participate? |
| Edge filter | May each entity produced mid-plan cross to the consumer? |
| Grounding | Can each load-bearing presented figure be attributed to declared source data? |

Because these gates are outside domain-agent prompts, an agent cannot talk itself around them. The platform
can also fail closed before unauthorized data is fetched or aggregated.

### Observability explains decisions, not only infrastructure

Most observability products can show model latency, tokens, tool calls, errors, and traces. Conduit's more
important objective is **decision observability**: reconstruct why the system chose, allowed, denied,
planned, executed, skipped, grounded, clarified, or fell back.

The trace model includes:

- intent and extracted human references;
- routing candidates, scores, margin, selected goal, and reranker use;
- structural entitlement decisions and withheld domains;
- entity resolution and coverage decisions with reason codes;
- the planned graph before execution and terminal node statuses afterward;
- per-agent start, completion, timeout, breaker, and queue outcomes;
- dependency skips, condition decisions, and bounded map iterations;
- grounded figures with label, rendered value, format, and source agent;
- partial-result and DAG-fallback reasons;
- request outcome, latency, and conversation identity.

That evidence supports distinct users:

| Consumer | Question Conduit should answer |
|---|---|
| End user | Why was the answer partial, denied, or clarified? |
| Domain team | Why did or did not my agent receive this request? |
| Platform operator | Where did latency or failure enter the graph? |
| Security team | Which policy and coverage decisions prevented data movement? |
| Model/routing owner | Which neighboring capability poached the intended goal? |
| Auditor | Which versions, inputs, decisions, and sources produced this outcome? |

The current Redis-backed decision replay is already operationally valuable. The roadmap extends it to
bind traces to immutable registry, manifest, policy, routing-index, and memory-watermark identities and
export a content-addressed audit envelope to an append-only/WORM-compatible sink. That distinction should
remain explicit: **observable and replayable today, with content-addressed immutable evidence on the
forward roadmap.**

The deterministic resolver has unusually strong evidence for a prototype: random-graph oracle tests,
permutation determinism, adversarial and metamorphic tests, mutation testing, real-manifest tests, and
deep/wide scale tests. The security path also has live harnesses for fail-closed identity and per-producer
coverage behavior.

## 5. Why this is state-of-the-art thinking

Conduit's strongest idea is not a novel individual primitive. Vector routing, policy gateways, manifests,
DAG execution, and traces all exist elsewhere. The state-of-the-art quality is how the boundaries combine:

1. **Dynamic composition without free-form planning.** The graph changes per goal, but only by composing
   admitted typed contracts.
2. **Policy and coverage are runtime dataflow controls.** Authorization is not a prompt instruction and
   not merely an upfront tool-list filter.
3. **Domain independence is enforced structurally.** Teams add business capability through manifests and
   services, not gateway branches.
4. **Reasoning is a governed capability.** Domain computation can be an agent node, but it has declared
   inputs, outputs, ownership, policy, and tests.
5. **Grounding has runtime teeth.** Important figures are rendered and validated from source data instead
   of merely scored later by another model.
6. **Multi-turn state is governed state.** Ledger events and compaction policy are separated from mutable
   chat prose.
7. **Admission can become the scaling mechanism.** Each team owns domain truth while the platform enforces
   a common contract and measurable bar.

That combination is defensible and well matched to regulated enterprise use. The careful claim is that
Conduit has a SOTA **control architecture**, not that every remaining production capability is complete or
that no vendor has overlapping components.

## 6. Delivery roadmap

The following roadmap extends the existing architecture across fleet-scale admission, versioning, evidence,
and onboarding. Correct layered execution remains the baseline while lower-latency scheduling is validated
independently.

### Day 1: make probabilistic admission honest and make planning fleet-ready

#### 1. Tighten the routing admission gate

- Add thresholds for exact flat-agent accuracy and exact analytics-goal accuracy.
- Keep domain accuracy, abstention, and zero canonical poaching.
- Report per-domain and per-agent confusion matrices.
- Require held-out paraphrases and ambiguous/clarify cases.
- Produce a dated JSON result containing model id, index id, registry hash, dataset hash, thresholds, and
  every miss.

**Exit gate:** the shipped embedding/reranker stack passes every declared threshold, or the document
records the true misses without changing labels to manufacture a pass.

#### 2. Introduce an immutable compiled registry snapshot

- Compile manifests into `agentById`, `producersByType`, domain/sub-domain candidate sets, and graph
  validation results during registry ingestion.
- Give the snapshot a content-derived id.
- Stamp the routing index with that same snapshot id and embedding model id.
- Resolve DAGs over the selected domain snapshot instead of `registry.listAll()`.
- Add a configurable maximum plan-node count and a reason-coded refusal/fallback.

**Exit gate:** registry and routing index are atomically addressable by one snapshot id; resolution tests
prove unrelated capabilities and manifest ordering cannot change a plan.

#### 3. Version semantic types

- Adopt `owner.domain.type@major` or an equivalently explicit format.
- Validate producer/consumer major compatibility at admission.
- Reject unowned, unversioned new types after a migration window.
- Keep ambiguous producer behavior fail-closed until an explicit deterministic priority contract exists.

**Exit gate:** incompatible types fail registration, and two same-type producers cannot silently change a
running plan.

### Day 2: make evidence durable and make onboarding the product surface

#### 4. Add a replay-grade audit envelope

Persist or export, per request:

- registry snapshot id;
- manifest and policy hashes;
- selected goal and routing scores;
- extracted references and resolved IDs;
- entitlement and coverage decisions;
- resolved plan graph;
- each node's bound-input hash and result status;
- grounded figure provenance;
- fallback/clarification reason;
- conversation memory watermark.

The existing trace store can remain the operational view. The audit envelope must have an append-only or
WORM-compatible sink contract and an offline verifier that re-runs deterministic resolution and compares
the graph.

**Exit gate:** a successful, denied, clarified, and DAG-fallback request each produce retrievable evidence;
the verifier reproduces the deterministic plan from the recorded snapshot.

#### 5. Define the onboarding/admission service backend

The first backend specification should define:

- onboarding session and state machine;
- OpenAPI/MCP/A2A introspection;
- simplified declared-vs-derived input model;
- manifest compiler;
- routing/golden dataset builder;
- coverage and authorization conformance suite;
- admission run and evidence model;
- human approval and promotion workflow;
- immutable domain-pack artifact.

The onboarding agent may ask questions, propose examples, and draft contracts. Deterministic validators and
named humans approve classification, coverage semantics, write authority, and promotion.

**Exit gate:** a domain team can onboard one agent in an existing sub-domain without manually authoring the
full manifest, and the generated artifact must pass the same admission path as a hand-authored submission.

#### 6. Capture one signed end-to-end proof

Run one reproducible proof containing:

- a held-out invented prompt;
- correct exact goal selection;
- deterministic DAG derivation;
- per-hop verified user identity;
- in-book allow and out-of-book deny;
- one killed producer with honest partial-result behavior;
- grounded figure validation;
- replay from the recorded snapshot;
- World-B check and full relevant test results.

**Exit gate:** one evidence bundle links commit, registry snapshot, policy hash, dataset hash, test results,
and trace/audit identifiers.

### Subsequent scale enhancements

These are valuable but should not be rushed merely to improve a presentation:

- eager dataflow release instead of layer barriers;
- per-critical-path deadline allocation;
- multi-goal DAG union and conflict handling;
- canonical data model if translator edges begin trending toward O(N^2);
- durable write/action backend integration;
- fleet-scale soak testing at the actual target number of domains and agents.

## 7. Roadmap completion criteria

The roadmap is complete when:

1. The exact goal-selection gate passes on shipped models with held-out and adversarial domain data.
2. Registry, routing index, policy, and graph resolve against one immutable snapshot.
3. Semantic type compatibility is versioned and admission-enforced.
4. Every request path, including deny, clarify, and fallback, produces replay-grade evidence.
5. Domain onboarding is a governed compiler/admission workflow rather than a documentation exercise.
6. Live failure, identity, coverage, grounding, and multi-turn tests pass against that same snapshot.
7. Measured scale tests meet the declared fleet size, latency, and cost SLOs.

## Final assessment

Through the state-of-the-art lens, the original thinking is sound:

- The model should understand language, not own execution truth.
- The manifest should describe domain contracts, not encode a hand-authored workflow for every question.
- The gateway should interpret, enforce, and explain those contracts without carrying domain knowledge.
- Domain teams should own agents and labeled truth; the platform should own admission and runtime controls.
- An onboarding agent should reduce cognitive load while validators retain authority.

Conduit has already built the difficult spine. The next step is to make its probabilistic boundary measured,
its registry compiled and versioned, its evidence immutable, and its onboarding experience productized.
That is a credible state-of-the-art enterprise orchestration story because it is supported by both current
industry direction and concrete runtime controls, not by autonomy claims alone.
