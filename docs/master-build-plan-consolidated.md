# Enterprise Intelligence Platform — Master Build Plan (Meridian)

*The single build-from document. Consolidates every decision and spec, fills the three
gaps a bank's architects will probe, captures the two thin specs, registers the deferred
gaps explicitly, and draws the **demo-now / scale-later** line through every component so
nobody accidentally builds the 300-agent platform for a 9-agent demo.*

---

## 0. Honest framing

This category exists (Glean, Copilot, Moveworks, Vertex/Bedrock stacks). That's *good*:
the patterns and failure modes are known, so you de-risk by learning from them rather than
discovering live. Your edge is **not novelty** — it's fit to the bank's two segments, bank-grade
entitlement, transparent uncertainty, and the read→write trajectory. Sell fit and trust,
not invention.

---

## 1. Locked decisions

| Area | Decision |
|---|---|
| Backend | OpenAI-compatible API + **SSE**; stateless per request |
| UI | **LibreChat** (config + shallow rebrand); experience layer only |
| Runtime | **Spring Boot 3 + Java 21 virtual threads** (one JVM service) |
| Protocol wiring | **Build in-JVM wrappers** behind `ProtocolAdapter`; **no agent gateway** (future option for the wire leg only) |
| Protocols pass 1 | **HTTP (OpenAPI) + MCP (`tools/list`)**; A2A deferred behind the interface |
| Schema capture | **Introspect the spec**; align registry record to the **A2A Agent Card** standard |
| The brain | **Custom**: resolver + input synthesis + Plan executor (no LangGraph/LangChain) |
| Routing | Semantic over example prompts now; **layered/hierarchical seam** for scale |
| Entitlement | **Cerbos ABAC** (agent + relationship), two-plane authz, prune-before-fan-out |
| Embeddings | In-JVM DJL MiniLM (no egress) behind an interface |
| LLM | Anthropic Claude for input extraction + answer synthesis |
| Scale proof | k6 — **last** |

---

## 2. Architecture

```
LibreChat (Meridian-branded)  ── OpenAI /v1 + validated identity ──►  EDGE (thin: ingress, SSE, authN, rate-limit, trace start)
                                                                      │
                                                                      ▼
        ORCHESTRATION CORE (the IP)  ── harness wraps every call · telemetry spans all ──
        resolve ─► entitle ─► synthesize input ─► fan-out (Plan executor) ─► synthesize answer ─► ground-check ─► stream
            │          │             │                  │                         │
         Redis      Cerbos         Claude         ProtocolAdapter             Claude
        (vector+    (PDP            (extract)      (HTTP/OpenAPI · MCP)        (synthesis)
         JSON+      sidecar)
         context)                                       │
                                                   Domain Agents: Wealth (HTTP) · Asset Servicing (MCP)
```

---

## 3. Component inventory (with status + where the detail lives)

| Component | Status | Detail |
|---|---|---|
| Vision & maturity path | ✅ spec | `platform-vision-and-maturity-path.md` |
| Technical architecture / boundaries | ✅ spec | `technical-architecture-clear-boundaries.md` |
| Registration & discovery schema | ✅ spec | `agent-registration-schema-a2a-aligned.md`, `agent-registry-demo-spec.md` |
| Input synthesis (Extract→Resolve→Bind) | ✅ spec | `input-synthesis-deep-spec.md` |
| Harness & telemetry | ✅ spec | `harness-and-telemetry-deep-spec.md` |
| Authorization (Cerbos ABAC) | ✅ spec | `authorization-abac-cerbos-deep-spec.md` |
| **Execution / orchestration layer** | ▶ captured §4 | the Plan + executor |
| **Resolution & uncertainty model** | ▶ captured §5 | capability profiles + clarification |
| **Answer synthesis & grounding** | ▶ NEW §6 | researched gap-fill |
| **Evaluation & accuracy** | ▶ NEW §7 | researched gap-fill |
| **Output safety / guardrails** | ▶ NEW §8 | researched gap-fill |
| Known gaps (deferred-by-choice) | ▣ register §9 | named, scoped, not built |

---

## 4. Execution / orchestration layer

- **Plan = a DAG.** Fan-out = flat plan; chaining = plan with edges; dynamic fork/join =
  planner-shaped plan. **One executor walks all three.** Define `Plan` now; emit only flat
  plans in Phase 1.
- **Two layers, separated:** the *harness* wraps one call (Resilience4j-decorated supplier:
  `circuitBreaker → timeLimiter → bulkhead → retry`); the *executor* composes those per the
  plan (virtual-thread-per-task + `CompletableFuture`: `allOf` joins, `thenCompose` chains).
- **Two timeout levels:** per-call (`sla_timeout_ms`) + an overall per-request deadline.
- **Per-agent-global circuit breakers** (keyed by `agent_id`).
- **Partial-result tolerant:** a failed node never cancels siblings — join to the deadline,
  harvest survivors, synthesize from what came back. (This is the agent-kill demo beat.)
- **Demo-now:** flat executor (~50 lines). **Scale-later:** topological walk for chains.

---

## 5. Resolution & uncertainty model

**Capability is the registration unit**, not the agent (one MCP server = many capabilities).
A **capability profile** captures what the resolver narrows through:
- `domain` — coarse bucket (first cut: 300 → ~8).
- `semantic signature` — action + object + qualifiers (phrasing-independent core).
- `lexical surface` — phrasings, the bank's vocabulary, **tags** (the learned/structured layer).
- `disambiguators` — required entities, preconditions, **negative scope** ("not performance").

**Resolver pipeline:** decompose composite intent → narrow by domain → semantic match
(assisted by lexical) → disambiguate → **absolute confidence floor** (not just top-relative)
→ decision.

**The resolver outputs a decision, not just an agent:**
`resolved · act-with-assumption · clarify(type, scoped-options) · slot-fill · confirm(action) · out-of-scope`.

**Uncertainty spectrum** (gated by *confidence × consequence*):
1. silent best-guess — **never**;
2. act + show the inferred assumption + one-tap undo — default for low-stakes reads;
3. ask a precise question — when readings are plausible *and* meaningfully different;
4. hard confirm — mandatory for any mutating action (Phase 2).

**Clarification is first-class, not an error:** diagnose the failure type (ambiguous →
"A or B?"; underspecified → slot-fill "which relationship?"; vague → offer the menu;
out-of-scope → say what you *can* do). Options are **entitlement-filtered** (never leak an
unentitled client). Every clarification is labeled training data; **clarification rate is a
health metric that should fall over time** — don't lean on asking.

**Context planes** the gateway fills so the user supplies only a sentence: identity (IdP/CRM),
**entity** (conversational state + coreference, scoped by entitlement), prompt, default.

- **Demo-now:** flat semantic over 9 capabilities + confidence floor + one scoped
  clarification beat + entity from the prompt.
- **Scale-later:** hierarchical narrowing, declared fast-paths for high-value/high-risk
  intents, decomposition, learned lexical layer, a **context-provider registry** (identity,
  book, directory, security master) as first-class infra.

---

## 6. Answer synthesis & grounding  *(researched gap-fill)*

The synthesis step is not a footnote — it's where a bank's trust is won or lost.

- **Agent outputs are the only ground truth.** The synthesis prompt presents them as
  delimited **DATA**, and the system prompt forbids outside knowledge or inferred numbers.
  The model summarizes; it does not compute or recall.
- **Attribution.** Every factual claim — especially every number — is traceable to the
  agent that produced it, so a banker can verify *who said what*. Fine-grained attribution
  is the faithfulness mechanism.
- **Post-synthesis grounding check** (before streaming completes): a rule-based pass that
  every number/identifier in the answer appears in some agent's output; optionally an LLM
  faithfulness judge. Catches cross-source contamination and fabrication.
- **Partial-result honesty:** when an agent failed/timed out, the answer **states the gap**
  ("settlement data unavailable") — never silently omits or invents.
- **Prefer structured rendering:** push numeric data into tables/cards with a prose
  summary, rather than free-form prose over figures — smaller hallucination surface.
- **Demo-now:** data-only synthesis prompt + numeric grounding check + partial-result
  honesty. **Scale-later:** LLM faithfulness judge inline, fine-grained citation UI.

---

## 7. Evaluation & accuracy  *(researched gap-fill)*

How you *prove* quality to the bank — a first-class subsystem, not an afterthought.

- **Three layers:** deterministic (routing/schema correctness), rubric (LLM-as-judge:
  faithfulness, answer relevancy), composite/**trajectory** (right capability set selected +
  parameter/entity accuracy).
- **Reference-free triad** (Ragas-style): faithfulness, answer relevancy, routing precision
  — no human-labelled answers, so it runs on real traffic.
- **Golden eval set:** banker prompt → expected capability set (+ expected entities). Run in
  CI; gate changes on it.
- **LLM-as-judge discipline:** clear rubric, **pin judge model version**, shuffle rubric
  order to reduce bias, calibrate the judge on a held-out set before trusting it.
- **Shipping discipline (production):** canary + eval gates + statistical-significance on
  deltas before promoting a change.
- **Wire eval spans to OpenTelemetry** — eval is the measurable face of "domain learning"
  and rides your existing telemetry plane.
- **Demo-now:** a 30–50 prompt golden set + a routing-accuracy number + a faithfulness
  spot-check (a real metric to show the bank). **Scale-later:** online reference-free eval,
  calibrated judge, canary gates, regression suite.

---

## 8. Output safety / guardrails  *(researched gap-fill)*

- **The core threat: tool/agent outputs are UNTRUSTED.** A compromised or buggy agent can
  embed instructions in its response that hijack your synthesis LLM (indirect prompt
  injection). **Separate data from instructions:** agent outputs enter the synthesis prompt
  as clearly delimited data; the system prompt treats them as data only, never commands.
  This is the single most important guardrail for your architecture.
- **Input guardrails:** prompt-injection/jailbreak check on the user prompt; scope check.
- **Output guardrails:** PII handling driven by `data_classification` (an entitled banker
  *should* see client PII, but traces/audit must redact per classification); toxicity/policy
  check; the grounding check (§6) doubles as the factuality guardrail.
- **Irreversible actions require approval** — already covered by authz + human-in-the-loop
  (Phase 2).
- **Defense in depth:** off-the-shelf classifiers (LlamaGuard/PromptGuard) cover
  *conversational* safety, **not** tool-injection — so rely on delimiting + system-prompt
  hardening + output validation, not a classifier alone.
- **Demo-now:** data/instruction separation in the synthesis prompt + PII-aware trace
  logging. **Scale-later:** full input/output guardrail suite, injection classifier,
  scheduled red-teaming.

---

## 9. Known-gaps register (named, scoped, deferred-by-choice)

Not built for the demo, but *known* — so none is an unknown-unknown in the room.

| Gap | Why deferred | When |
|---|---|---|
| Result caching & **data freshness** | stale balances are dangerous; needs per-agent TTL policy | production |
| **Cost / token economics** | synthesis + extraction are LLM calls per query at scale | production |
| **Feedback-loop mechanics** | how corrections are captured, labeled, folded back safely | with learning loop |
| **Answer-level observability** | detecting "wrong answer, all agents succeeded" | post-demo |
| Session/**entity-state engine** vs LibreChat history | reconcile the conversational entity plane with UI-owned history | when multi-turn matures |
| Card **signing / provenance** | A2A supports signed cards; bank trust review may want it | production |
| Real **IdP/JWT + CRM** attribute integration | stubbed for demo | production |
| **A2A** adapter | config-heavy async; behind the interface | pass 3 |
| **Phase-2** planner / chaining / writes | executor + Cerbos already seam for it | Phase 2 |

---

## 10. Build sequence (demo-now path)

| # | Milestone | Demo-now | Seam left for scale |
|---|---|---|---|
| 0 | Scaffold (compose: Redis, gateway, mock-agents, LibreChat) | all up | — |
| 1 | **Thin slice**: prompt → 1 agent → SSE → LibreChat | closes SSE risk first | — |
| 2 | Mock agents: Wealth (HTTP/OpenAPI) + Asset Servicing (MCP) + fault knobs | 9 agents, 2 protocols | A2A stub |
| 3 | Registry: A2A-aligned schema, introspect specs, embed prompts → HNSW | bootstrap = live-reg pipeline | tags/structured discovery |
| 4 | Resolver: semantic route + confidence floor + filter + fan-out decision | flat over 9 | hierarchical + decomposition seam |
| 5 | Input synthesis: Extract→Resolve→Bind, tested in isolation first | relationship_id binding | full context planes |
| 6 | Wrappers + Plan executor + harness | flat plan, both protocols | topological walk |
| 7 | Synthesis + **grounding check** + partial-result honesty | data-only + numeric check | faithfulness judge |
| 8 | Entitlements: Cerbos agent+relationship, prune-before-fan-out, identity seam | RM book scoping | full policy set |
| 9 | Telemetry + glass-box + **guardrail** (data/instruction separation) | live panel + injection-safe | full guardrail suite |
| 10 | Uncertainty: one scoped clarification beat | "did you mean…?" | full spectrum + learning |
| 11 | Resilience beat: agent-kill → answer from survivors | live kill | — |
| 12 | UI rebrand (Meridian) + hide model selector | bespoke feel | — |
| 13 | **Eval set** (30–50 prompts) + routing-accuracy number | a real metric | online eval + canary |
| 14 | **Scale proof (LAST)**: k6 concurrency + vthread graph | the closing flourish | — |

**Discipline:** vertical slice first (0–1) — let reality critique the design before
spec'ing further. Build the simple path at every step; leave the seam, don't build it.

---

## 11. What "complete" means here

Spine: complete and coherent. Pitch-critical gaps (synthesis, eval, guardrails):
researched and specced. Remaining gaps: named and deferred on purpose. There are no
unknown-unknowns left — every component is either built-for-demo, seamed-for-scale, or
registered-as-deferred. That is the definition of not failing in the room: a documented,
honest answer for every question the bank can ask.

---

## References (gap-fill research)

- RAGentA — multi-agent RAG for *attributed* QA (faithfulness via fine-grained citation), arXiv:2506.16988
- Multi-agent hallucination mitigation (verifier/refinement pass), MDPI Information 16(7):517
- AgentHallu — hallucination *attribution* in multi-step agents (errors propagate), arXiv:2601.06818
- Grid-Mind — three-layer anti-hallucination incl. post-response grounding validation, arXiv:2602.20683
- Ragas reference-free triad (faithfulness / relevancy / context), arXiv:2309.15217; LLM-as-judge calibration, arXiv:2506.13639
- Agent evaluation: trajectories vs outputs, LangChain; canary + eval gates, Medium (Rane, 2026)
- Guardrails: treat tool output as untrusted, separate data/instructions (Furmanets, 2026); classifier limits for tool attacks, arXiv:2602.14161
