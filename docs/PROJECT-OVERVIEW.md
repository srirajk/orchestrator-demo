# Conduit — Project Overview

> The one doc that explains **what this project is, why it matters, how it works, and what
> every module does.** Start here. For running/demoing it, see
> [`OPERATOR-RUNBOOK.md`](OPERATOR-RUNBOOK.md); for the deep product spec, see
> [`WORLD-B-LOCKDOWN.md`](WORLD-B-LOCKDOWN.md).

---

## 1. The one-liner

**Conduit is an enterprise AI gateway for a bank.** A banker asks one plain-English question;
Conduit figures out which specialist systems hold the answer, checks the banker is allowed to
see that data, calls those systems in parallel, and streams back **one grounded, attributed
answer** — while showing the entire decision live in a glass-box panel.

It is a thin, OpenAI-compatible front door over an **orchestration brain**. The chat UI is
LibreChat (run as-is via config); the brain is a single Java service.

---

## 2. The problem & the product (the GTM story)

### The problem a bank actually has
A relationship manager's question — *"Give me a full picture of the Whitman relationship"* —
touches a dozen systems: portfolio holdings, performance, settlements, custody, cash, risk,
goals. Today that means swivel-chairing across portals, or waiting on ops. Every one of those
systems has different access rules, different protocols, and different owners. Generic chatbots
can't be trusted here: they hallucinate numbers, they don't enforce entitlements, and they're a
black box to compliance.

### What Conduit delivers
- **One question, one answer.** Natural language in; a single synthesized, **grounded** answer
  out — every number traceable to a source system, nothing invented.
- **It respects entitlements.** A banker only ever sees data for relationships in their book of
  business. Out-of-book requests are denied *before* any data is fetched.
- **It's a glass box, not a black box.** Compliance and the user can watch the live decision:
  what was asked, how it routed, what it was allowed to see, which systems answered, how long
  each took. This is the trust story that makes AI shippable in a bank.
- **It degrades gracefully.** If a system is down, the answer still comes back from the
  survivors and *says what's missing* — never silently wrong.

### Why we win — the differentiator
Most "AI gateway" projects hardcode each business line into the gateway. Adding insurance means
a new release of the core. **Conduit is "World B": the gateway carries zero domain knowledge.**
A new business domain — insurance, lending, anything — is onboarded by **adding manifest files +
a CRM/coverage service URL, with no change to gateway code.** That is the moat: the platform
team ships once; domain teams self-onboard. We prove it in this very build — insurance was added
to a wealth-and-servicing gateway by manifest alone.

### The maturity path (the forward story for the PO)
Today Conduit is **read-only** (answer questions, enforce who-can-see-what). The same
architecture — manifest-described agents, entitlement-gated, glass-box-audited — extends to
**write** actions (initiate a trade, open a case) by adding mutating agents behind the same
guardrails. The read product earns the trust; the write product captures the workflow.

---

## 3. How it works — the request lifecycle

Every question flows through six stages. The glass-box renders them live, one panel each.

| # | Stage | What happens | Guardrail |
|---|---|---|---|
| 1 | **Intent + Entities** | An LLM classifies the ask (fetch data / follow-up / clarify) and extracts the *human references* ("the Whitman relationship") | The LLM never invents IDs — it extracts references only |
| 2 | **Resolve / Route** | The prompt is embedded and matched (vector search) against agent capabilities to pick the right subset | Confidence floor; shows selected **and** rejected agents |
| 3 | **Entitlement** | Structural check (role × resource class, via Cerbos) + data-aware check (is this entity in the user's book?, via the coverage service) | Prune **before** fan-out — denied data is never fetched |
| 4 | **Fan-out** | The chosen agents are called in parallel over their native protocols (HTTP + MCP), on virtual threads, behind circuit breakers | A failed agent never cancels its siblings |
| 5 | **Synthesis** | An LLM merges the agent outputs into one answer, streamed token-by-token | Agent outputs are the **only** ground truth; every number must trace to one; missing data is stated, not omitted |
| 6 | **Observe** | Spans, metrics, and glass-box events are emitted throughout; the answer's quality is scored asynchronously | PII-aware logging; data and instructions kept separate |

A **deterministic clarify** sits across this: if the user's references don't cover what the
agents require (`extracted ∩ required_context = ∅`), Conduit asks a scoped question instead of
guessing — and the decision is made in code, not by an LLM.

---

## 4. The module map — what each part is

```
orchestrator-demo/
├── gateway/          ← THE BRAIN (Java 21, Spring Boot, virtual threads)
├── mock-agents/      ← the specialist systems it orchestrates (Python stand-ins)
├── iam-service/      ← identity provider (OIDC, signed tokens)
├── registry/         ← the manifests that describe domains + agents (World B's DNA)
├── glassbox/         ← the live decision-trace UI
├── librechat/        ← the chat UI (config + rebrand only)
├── admin-ui/         ← agent/manifest registry admin
├── eval/             ← quality scoring (release gate + continuous)
├── infra/            ← Cerbos policies, Grafana, OTel collector configs
├── tests/            ← cross-cutting suites: e2e (Playwright) · load (k6) · integration
├── scripts/          ← run / seed / verify / world-b-check
└── docs/             ← this overview, the runbook, the World-B spec
```

### `gateway/` — the orchestration brain (the only thing that's "the product")
One Java/Spring Boot service. No Python, no LangChain, no external agent gateway inside it.
Its internal shape mirrors the lifecycle:
- `domain/intent` — intent classification + entity extraction (manifest-driven prompts)
- `domain/manifest` — loads domain/agent manifests; the source of all domain knowledge
- `domain/coverage` — the book-of-business pipeline (discover / check / resolve)
- `domain/auth` — Cerbos entitlement adapter + identity seam
- `domain/session` — conversation state (a conversation = a session, carried across turns)
- `orchestration/executor` — the flat plan executor + Resilience4j harness
- `adapter/http` + `adapter/mcp` — the protocol wrappers (one `ProtocolAdapter` interface)
- `synthesis` — input synthesis (Extract→Resolve→Bind) + grounded answer synthesis
- `infrastructure/telemetry` — OTel spans + the glass-box event publisher
- `api/v1` — the OpenAI-compatible front door (`/v1/chat/completions`, `/v1/models`, `/trace`)

### `mock-agents/` — the specialist systems (stand-ins for real domain teams)
Python services that simulate what a bank's domain teams would expose. **Not part of the
request brain** — they're the external systems the gateway calls.
- `wealth/` — FastAPI (HTTP); 4 agents: holdings, performance, risk_profile, goal_planning
- `servicing/` — FastMCP (MCP/SSE); 5 tools: custody, settlement, cash, nav, corporate_actions
- `insurance/` — FastAPI (HTTP); 2 agents: policy_details, claim_status *(the World B proof)*
- `crm/` — entity resolution (name → ID lookup, so the LLM never invents IDs)
- `wealth-coverage/` + `insurance-coverage/` — the book-of-business services (who covers what)
- `embeddings/` — MiniLM vectors for routing

### `iam-service/` — identity
A Java OIDC provider (RS256 / JWKS). Issues the signed token that proves who the user is; the
gateway verifies it at every hop. Book-of-business is **not** in the token — it lives in the
coverage services (so entitlements are data-aware and current, not baked into a credential).

### `registry/` — the manifests (World B's DNA)
- `agent-manifest.schema.json` — the pinned, canonical contract every agent manifest validates against
- `manifests/*.json` — the 11 agent manifests (4 wealth + 5 servicing + 2 insurance)
- `domains/*.json` — the domain + sub-domain manifests (entity types, coverage URLs, copy)

**This directory is how you onboard a domain.** Add a domain manifest + agent manifests + point
at a coverage service. No gateway code.

### `glassbox/` — the trust surface
A standalone single-page app that subscribes to the gateway's `/trace/stream` (SSE) and renders
the six-stage decision live. This is the demo's hero view and the compliance story.

### `librechat/` — the chat UI
LibreChat via config + cosmetic Conduit rebrand only — **no code fork**. Points at the gateway's
`/v1` as a custom endpoint.

### `admin-ui/` — registry admin
A UI over the gateway's `/admin/agents` endpoints to register/inspect manifests.

### `eval/` — quality assurance
- **Release gate** (DeepEval, offline): routing-accuracy + faithfulness, run pre-ship / in CI.
- **Continuous** (Langfuse worker, async): scores live conversation turns every few minutes —
  grounding + honesty (deterministic) and relevance + safety (LLM judge) — posted back to
  Langfuse, with sampling + dedup. Two different jobs: one certifies a release, one watches prod.

### `infra/` — the operational backbone (config, not code)
Cerbos authorization policies, Grafana dashboards, OTel collector pipeline, Prometheus/Loki/Tempo
wiring. Changing *who can do what* or *what we chart* happens here, not in the gateway.

### `scripts/` — the control surface
`verify.sh` (build → up → smoke → e2e → eval), `seed-users.sh` (demo principals),
`world-b-check.sh` (the deterministic "no domain knowledge leaked into the gateway" gate).

---

## 5. What's proven today

- **World B is real.** `world-b-check.sh` = CRITICAL 0. Three domains live (wealth +
  asset-servicing + insurance); the insurance domain was added by manifest alone.
- **The four demo beats work end-to-end:** hero (cross-protocol fan-out + grounded answer),
  resilience (kill an agent → honest partial answer), entitlement (out-of-book denial), and
  clarification (ambiguous prompt → scoped question). Multi-turn carry-forward works.
- **Trust surfaces are live:** the glass-box renders the full decision; Langfuse carries every
  turn (prompt + answer + agent spans) grouped by conversation; continuous eval posts real
  quality scores.
- **It holds under load:** k6 — 0% errors at 10 concurrent streams, virtual threads.

---

## 6. The locked stack (why each choice)

| Concern | Choice |
|---|---|
| Gateway runtime | Java 21+, Spring Boot 3.5, **virtual threads** (concurrency without thread-pool exhaustion) |
| Routing + state | Redis Stack (RediSearch HNSW vector index + RedisJSON) |
| Embeddings | DJL + all-MiniLM-L6-v2 (in-JVM, 384-dim) |
| LLM (gateway + agents) | OpenAI-compatible, provider-swappable per call site (`CONDUIT_LLM_*`) |
| Resilience | Resilience4j (circuit breakers, timeouts, partial join) |
| Authorization | Cerbos PDP (structural) + coverage services (data-aware) |
| Telemetry / eval | OTel → Langfuse + Tempo/Loki/Prometheus; DeepEval gate |
| Protocols | HTTP (OpenAPI-driven) + MCP (FastMCP/SSE), behind one `ProtocolAdapter` |
| Chat UI | LibreChat (config + rebrand only) |
| Orchestration | docker-compose (core profile = the everyday demo) |

---

## 7. Where to go next

- **Run it / demo it:** [`OPERATOR-RUNBOOK.md`](OPERATOR-RUNBOOK.md) — URLs, logins, the
  four-beat demo script, troubleshooting.
- **The deep product spec:** [`WORLD-B-LOCKDOWN.md`](WORLD-B-LOCKDOWN.md) — the World B
  architecture, invariants, and definition of done.
- **Add a domain:** [`domain-onboarding-standard.md`](domain-onboarding-standard.md).
- **Model strategy:** [`MODEL-SELECTION.md`](MODEL-SELECTION.md).
