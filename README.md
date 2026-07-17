# Conduit — Enterprise AI Gateway

> One plain-English question → the right specialist systems, across HTTP and MCP → one grounded,
> attributed answer — with every routing and access decision visible live.
>
> **This is the master document.** It covers what Conduit is, why it matters, how it works, what
> every module does, how to run and demo it, and how to extend it. Deeper references are linked at
> the end.

---

## The 30-second version

A bank relationship manager asks, in plain English: *"Give me a complete overview of the Whitman
relationship — holdings, performance, settlement status, and cash."* That one question touches a
dozen systems, each with its own protocol, owner, and access rules.

**Conduit** is a single Java service that sits behind a chat box, figures out which specialist
systems hold the answer, checks the banker is entitled to that data, calls those systems in
parallel (over HTTP **and** MCP), and streams back **one synthesized answer where every number is
real** — while showing the entire decision live in a glass-box trace rail built into the chat UI.

And the thing that makes it a platform, not a demo:

> **World B — the gateway carries zero domain knowledge.** A new business line (insurance,
> lending, anything) is onboarded by adding **manifest files + a coverage-service URL** — with
> **no change to gateway code.** We prove it here: insurance was bolted onto a wealth-and-
> servicing gateway by manifest alone.

---

## Why this is hard (and why generic chatbots fail here)

A relationship manager's question fans out across portfolio holdings, performance, settlements,
custody, cash, risk, and goals. Today answering it means swivel-chairing across portals or waiting
on ops. Drop a generic chatbot on top and you get three dealbreakers for a bank:

- **It hallucinates numbers.** A portfolio value that's *almost* right is worse than no answer.
- **It ignores entitlements.** A banker must only ever see relationships in their own book.
- **It's a black box.** Compliance can't ship what it can't audit.

Conduit is built to kill all three:

| The bank's fear | Conduit's answer |
|---|---|
| "It'll make up a number." | Agent outputs are the **only** ground truth. The LLM summarizes; it never computes, recalls, or invents — and it never produces an ID. |
| "It'll leak data across books." | Entitlements are checked **before** any data is fetched — structural (role × resource) *and* data-aware (is this entity in your book?). |
| "We can't audit it." | A live **glass box** shows the whole decision: what was asked, how it routed, what it was allowed to see, which systems answered, how long each took. |
| "It'll silently break." | Partial-result tolerant: a dead agent never cancels its siblings; the answer comes back from survivors and **states what's missing**. |

<details>
<summary><b>📸 Screenshots — what it looks like when it comes up</b> (click to expand)</summary>

<br>

**The Conduit Chat home** (React SPA + Spring BFF; the glass-box is the collapsible trace rail on the right):

![Conduit Chat home](docs/images/librechat-home.png)

**A grounded answer to the hero prompt** — one synthesized reply, every number traceable to a
source system (relationship `REL-00042`, `$1,967,000`, the actual positions):

![Conduit Chat — grounded Whitman portfolio answer](docs/images/librechat-answer.png)

> Captured live by the screenshots e2e spec (`tests/e2e/tests/11-screenshots.spec.ts`) — run it to
> regenerate them so this README never goes stale.

</details>

---

## Architecture at a glance

```
   Relationship                    ┌──────────────────────────────────────────────┐
   Manager                         │               CONDUIT GATEWAY                 │
     │  "Give me a full picture    │  Java 25 (bytecode 21) · Boot 3.5.16 · VTs    │
     │   of the Whitman …"         │     World B: a manifest interpreter with      │
     ▼                             │               zero domain knowledge           │
 ┌──────────┐   OpenAI  /v1        │                                               │
 │ Conduit  │ ───────────────────▶ │  ① Intent + entity extraction   (LLM)         │
 │ Chat     │ ◀───── SSE stream ── │  ② Capability route             (Redis HNSW)  │
 └──────────┘                      │  ③ Entitlement gate             (Cerbos +     │
     ▲                             │                                  coverage)    │
     │ trace events (SSE)          │  ④ Parallel fan-out             (HTTP + MCP)  │
     ▼                             │  ⑤ Grounded synthesis           (LLM, stream) │
 ┌──────────┐                      │  ⑥ Observe                      (OTel + glass)│
 │Trace Rail│                      └───┬──────────────┬──────────────┬─────────────┘
 │ (in the  │            HTTP (OpenAPI)│    MCP (HTTP) │       verify │ JWT (RS256)
 │ chat UI) │                          ▼              ▼              ▼
 └──────────┘              ┌───────────────────┐ ┌──────────┐ ┌──────────────┐
                           │ Wealth · Insurance│ │ Asset-   │ │  Axiom IAM   │
                           │ agents (FastAPI)  │ │ Servicing│ │ (OIDC issuer,│
                           └─────────┬─────────┘ │(FastMCP, │ │  RS256/JWKS) │
                                     │            │ Str.HTTP)│ └──────────────┘
        "is this entity in the       ▼            └──────────┘
         user's book of business?"  ┌────────────────┐   ┌──────────────────────────┐
                                    │ Coverage svcs  │   │ Observability             │
                                    │ (book-of-biz)  │   │ Langfuse · Grafana ·      │
                                    └────────────────┘   │ Tempo · Loki · Prometheus │
                                                         └──────────────────────────┘
```

The gateway is **the only thing that's "the product."** The agents are Python stand-ins for what a
bank's domain teams would expose — they're external systems on the request path, not part of the
brain.

---

## See it in 5 minutes

```bash
cp .env.example .env                       # then set your OpenAI-compatible / Z.AI key(s)
docker compose -p orchestrator-demo up -d  # the no-profile core stack (the everyday demo)
bash scripts/seed-users.sh                 # demo identities (rm_jane, uw_sam, …)
docker compose --profile eval up -d eval-worker   # optional: continuous quality scoring
```

Then open the chat and ask the hero question:

| Surface | URL | Login |
|---|---|---|
| **Conduit Chat** (SPA + BFF; glass-box trace rail built in) | http://localhost:8099 | `rm_jane` / `Meridian@2024` — or **"Login with Meridian SSO"** |
| **Conduit Insights** (admin analytics boards) | http://localhost:5175 | admin (Cerbos-gated: `conduit_admin` / `platform_admin`) |
| **Admin UI** (agent/manifest registry) | http://localhost:5180 | admin |
| **Langfuse** (traces + scores) | http://localhost:3030 | `admin@meridian.bank` / `changeme` |
| **Grafana** (metrics/logs/traces) | http://localhost:3000 | `admin` / `changeme` |
| **Gateway** (OpenAI API) | http://localhost:8080/v1 | Bearer JWT (Axiom OIDC) — no header-identity path |
| **Axiom** (identity) | http://localhost:8084 | OIDC issuer |

> **Two host prereqs** (not in the repo): a real LLM key in `.env`, and — for browser SSO — an
> `/etc/hosts` line `127.0.0.1 host.docker.internal`.

---

## The four things to demo

All live, all on the same stack, all visible end-to-end (chat + glass-box + traces + scores):

1. **Ask the hero question** → one grounded answer fanned out across HTTP + MCP agents.
   > *"Give me a complete overview of the Whitman relationship: holdings, performance, settlement
   > status, and cash position."* Then follow up — *"which holding is largest, and how much cash is
   > unsettled?"* — and it answers from memory without you restating the client.
2. **Kill an agent mid-question** (`docker compose stop <agent>`) → the answer still comes back,
   honestly stating what's missing.
3. **Ask about a client you don't cover** (*"show me the Okafor relationship"* as `rm_jane`) → it's
   denied **before** any data is fetched.
4. **Ask something ambiguous** (*"what's the latest on my client?"*) → a scoped clarifying question,
   not a hallucination.

---

## How a question flows — the six stages

The glass-box renders these live, one panel each.

| # | Stage | What happens | The guardrail |
|---|---|---|---|
| 1 | **Intent + Entities** | An LLM classifies the ask (fetch / follow-up / clarify) and extracts the *human references* ("the Whitman relationship") | The LLM never invents IDs — it extracts references only |
| 2 | **Resolve / Route** | **Capability-first** — the query (entity names masked out) is embedded and vector-matched against agent *capabilities*, not the entity named, then carried or switched across turns | Confidence floor; shows selected **and** rejected agents |
| 3 | **Entitlement** | Structural check (role × resource class, via Cerbos) + data-aware check (is this entity in the user's book?, via coverage) | Prune **before** fan-out — denied data is never fetched |
| 4 | **Fan-out** | The chosen agents are called in parallel over their native protocols (HTTP + MCP), on virtual threads, behind circuit breakers | A failed agent never cancels its siblings |
| 5 | **Synthesis** | An LLM merges agent outputs into one streamed answer | Agent outputs are the **only** ground truth; every number traces to one; missing data is stated |
| 6 | **Observe** | Spans, metrics, and glass-box events throughout; quality scored asynchronously | PII-aware logging; data and instructions kept separate |

A **deterministic clarify** sits across all of this: if the user's references don't cover what the
agents require (`extracted ∩ required_context = ∅`), Conduit asks a scoped question instead of
guessing — decided in code, not by an LLM.

---

## The module map — what each part is

```
orchestrator-demo/
├── gateway/        ← THE BRAIN (Java 25 / bytecode 21, Spring Boot 3.5.16, VTs) — the only "product".
│                     Run as the request-path service; the SAME image runs the `registry` profile
│                     (registry-service) that ingests manifests + builds the routing index.
├── mock-agents/    ← the specialist systems it orchestrates (Python stand-ins)
├── iam-service/    ← Axiom: identity provider (OIDC, RS256-signed tokens)
├── registry/       ← the manifests that describe domains + agents (World B's DNA)
├── apps/chat/      ← Conduit Chat — React SPA + Spring BFF; glass-box trace rail built in
├── apps/insights/  ← Conduit Insights — admin-gated analytics boards (SPA over the gateway API)
├── admin-ui/       ← agent/manifest registry admin (React)
├── eval/           ← quality scoring (release gate + continuous)
├── infra/          ← Cerbos policies, Grafana, OTel collector configs (config, not code)
├── tests/          ← e2e (Playwright) · load (k6) · integration
├── scripts/        ← run / seed / verify / world-b-check
└── docs/           ← the runbook, the World-B spec, model strategy
```

**`gateway/` — the orchestration brain.** One Java/Spring Boot service. No Python, no LangChain, no
external agent gateway inside it. Its shape mirrors the lifecycle: `domain/intent` (classify +
extract), `domain/manifest` (the source of all domain knowledge), `domain/coverage`
(book-of-business: discover/check/resolve), `domain/auth` (Cerbos + identity seam),
`orchestration/executor` (the flat-plan executor + Resilience4j harness), `adapter/http` +
`adapter/mcp` (one `ProtocolAdapter` interface), `synthesis` (Extract→Resolve→Bind, then grounded
answer), `infrastructure/telemetry` (OTel + glass-box publisher), `api/v1` (the OpenAI-compatible
front door). The gateway is stateless across chat turns: `conversationId` is trace context only,
and the client-sent `messages[]` are the context contract.

**`mock-agents/` — the specialist systems.** Stand-ins for a bank's domain teams; **not** part of
the request brain. **18 agent manifests across 4 domains** (wealth 7, asset-servicing 7, insurance 3,
HR 1):
- `wealth/` + `wealth-market-research/` — FastAPI (HTTP): holdings, performance, risk_profile,
  goal_planning, concentration, market_research
- `servicing/` — FastMCP (**MCP, Streamable HTTP spec `2025-11-25`**): custody, settlement (status +
  risk), cash, nav, corporate_actions, trade_penalty
- `insurance/` — FastAPI (HTTP): policy_details, claim_status, renewal_risk *(the original World B proof)*
- `hr-policy/` — FastAPI (HTTP): internal HR policy Q&A *(4th domain, manifest-only onboard)*
- `crm/` — entity resolution (name → ID, so the LLM never invents IDs)
- `wealth-coverage/` + `insurance-coverage/` — book-of-business services (who covers what)
- `embeddings/` — sentence-transformers (all-MiniLM-L6-v2, 384-dim) vectors for routing

**`iam-service/` (Axiom) — identity.** A Java OIDC provider (RS256 / JWKS). Issues the signed token
that proves who the user is; the gateway verifies it at every hop. Book-of-business is **not** in
the token — it lives in the coverage services, so entitlements are data-aware and current, not
baked into a credential.

**`registry/` — the manifests (World B's DNA).** The pinned `agent-manifest.schema.json` contract,
the agent manifests (`manifests/<domain>/*.json`), and the domain manifests (`domains/*.json`).
Ingestion (validate → introspect the live agent → embed the example corpus → write the HNSW routing
index) runs as a **separate registry-service** (the same JVM image on the `registry` Spring profile);
the gateway only *reads* the index and refuses to start if it's missing, empty, or built by a
different embedding model. **This directory is how you onboard a domain** — see
[Onboard a new business](#onboard-a-new-business).

**Conduit Chat (`apps/chat`) — the chat + trust surface.** A React SPA served by its own Spring Boot
BFF (container `conduit-chat`, http://localhost:8099). The glass box is a **built-in collapsible trace
rail** that subscribes to the gateway's `/trace/**` SSE stream and renders the six-stage decision
live beside the answer — the demo's hero view and the compliance story. (No LibreChat: it's the legacy
integration target only; the OpenAI SSE byte-contract still holds for any compatible client.)

**Conduit Insights (`apps/insights`) — admin analytics.** A React SPA (container `conduit-insights`,
http://localhost:5175) rendering **7 admin-gated boards** (6 ops boards from Prometheus + 1 cost/quality
board from Langfuse) served by the gateway's `/v1/insights/*` API. Access is **Cerbos-gated** through
the same ABAC PDP as chat (`InsightsAuthorizer`): a `chat_user` is denied; `conduit_admin` /
`platform_admin` is allowed.

**`eval/` — quality assurance.** Two distinct jobs: a **release gate** (DeepEval, offline:
routing-accuracy + faithfulness, run pre-ship/CI) and a **continuous** Langfuse worker (async:
grounding + honesty deterministically, relevance + safety via LLM judge, posted back to Langfuse
with sampling + dedup).

**`infra/` — the operational backbone (config, not code).** Cerbos policies, Grafana dashboards,
OTel pipeline, Prometheus/Loki/Tempo wiring. Changing *who can do what* or *what we chart* happens
here, never in the gateway.

---

## The locked stack (and why)

| Concern | Choice |
|---|---|
| Gateway runtime | Java 25 (bytecode target 21), Spring Boot 3.5.16, **virtual threads** (fan-out concurrency without pool exhaustion) |
| Routing + state | Redis Stack (RediSearch HNSW vector index + RedisJSON) |
| Routing index | Built by a **separate registry-service** (`registry` profile); the gateway only reads it |
| Embeddings | Python sentence-transformers sidecar (all-MiniLM-L6-v2, 384-dim) over HTTP via `RemoteEmbedder` (not in-JVM DJL) |
| LLM (gateway + agents) | OpenAI-compatible, provider-swappable per call site (`CONDUIT_LLM_*`) |
| Resilience | Resilience4j (circuit breakers, timeouts, partial join) |
| Authorization | Cerbos PDP (structural) + coverage services (data-aware) |
| Identity | Axiom — OIDC, RS256/JWKS, verified at every hop |
| Telemetry / eval | OTel → Langfuse + Tempo/Loki/Prometheus; DeepEval gate |
| Protocols | HTTP (OpenAPI) + MCP (**Streamable HTTP, spec `2025-11-25`** — not HTTP+SSE), behind one `ProtocolAdapter` |
| Chat UI | **Conduit Chat** (`apps/chat`: React SPA + Spring BFF; glass-box trace rail built in) |
| Admin analytics | **Conduit Insights** (`apps/insights`: 7 Cerbos-gated boards, Prometheus + Langfuse) |
| Orchestration | docker-compose (no-profile **core** set = the everyday demo; `observability`/`eval`/`scale` opt-in) |

---

## Onboard a new business

The whole point of World B: add a domain with **zero gateway Java and zero gateway config** — the
`domain_context` copy, entity types, required context, clarify/denial wording, and coverage-service
URLs all live in the manifest JSON. Top-down, three nested levels — a **domain** (coverage service +
display copy), its **sub-domains** (entity types, required context, clarify/denial copy, agent list),
and the **agents** (how to call each system, its example prompts for routing). The **registry-service**
(the `registry` profile) validates, introspects the live agent, embeds the example corpus, and writes
the HNSW routing index; the gateway then *reads* that index, and from then on the business "exists" —
it routes, resolves, entitles, and answers. (HR was added this way as the 4th domain, manifest-only.)

The step-by-step with file templates is in [`registry/README.md`](registry/README.md). The
deterministic proof you didn't leak domain logic into the gateway:

```bash
bash scripts/world-b-check.sh   # must report CRITICAL: 0
```

---

## Observe everything — the guided tour

- **Glass-box trace rail (in Conduit Chat, http://localhost:8099)** — expand the trace rail beside
  the answer; send a prompt and watch the six stages light up, ending in a summary (total latency,
  *N/M agents succeeded*). This is the "why did it answer that" surface.
- **Conduit Insights (http://localhost:5175)** — 7 admin-gated boards (ops from Prometheus, cost/quality
  from Langfuse). Sign in as an admin (`conduit_admin` / `platform_admin`); a `chat_user` is denied.
- **Langfuse (http://localhost:3030)** — your conversation is one **session**; each turn is a
  **trace** (`chat-turn`) with the prompt (input), the answer (output), and child spans per agent
  call. If the eval worker is running, each trace also carries **grounding / honesty / relevance /
  safety** scores. *Correlation key: the gateway-derived `convId` (e.g. `conv-…`), not the chat URL UUID.*
- **Grafana (http://localhost:3000)** — dashboards for live demo, gateway performance
  (rate/success/latency), agent health, business overview, conversation trace explorer, and
  resource usage. **Logs (Loki):** `{container="conduit-gateway"} |= "conv-…"`. **Traces (Tempo):**
  the same request as a span waterfall, complementary to the glass-box.

---

## What's proven today

- **World B is real.** `world-b-check.sh` = CRITICAL 0. **Four domains live** — 18 agent manifests
  (wealth 7, asset-servicing 7, insurance 3, HR 1); insurance and HR were each added by manifest alone.
- **The four demo beats work end-to-end**, plus multi-turn client-sent context.
- **Trust surfaces are live:** the glass-box trace rail renders the full decision; Langfuse carries
  every turn (prompt + answer + agent spans) grouped by conversation; continuous eval posts real quality
  scores; Conduit Insights serves the admin boards; SSO sign-in works (Axiom OIDC → Conduit Chat).
- **It holds under load:** k6 — 0% errors at 10 concurrent streams on virtual threads.

## The forward story

Today Conduit is **read-only** — answer questions, enforce who-can-see-what. The same architecture
(manifest-described agents, entitlement-gated, glass-box-audited) extends to **write** actions
(initiate a trade, open a case) by adding mutating agents behind the same guardrails. The read
product earns the trust; the write product captures the workflow.

---

## Verify

```bash
./scripts/verify.sh            # build → up → smoke → e2e → eval (world-b-check is a hard gate)
bash scripts/world-b-check.sh  # the "no domain knowledge in the gateway" gate → CRITICAL must be 0
```

---

## Deeper references

| Doc | Read it for |
|---|---|
| [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md) | **Run & demo** — every URL/port/login, the four-beat script, troubleshooting |
| [`registry/README.md`](registry/README.md) | **Onboard a new business** — manifest structure + checklist |
| [`docs/WORLD-B-LOCKDOWN.md`](docs/WORLD-B-LOCKDOWN.md) | The deep product/architecture spec + invariants |
| [`docs/MODEL-SELECTION.md`](docs/MODEL-SELECTION.md) | Model / provider strategy |
| [`CLAUDE.md`](CLAUDE.md) | How an AI agent should work in this repo (the invariants) |
| [`TODO.md`](TODO.md) | Open backlog |
