# Technical Architecture — Clear Boundaries

*One picture of the whole system, with the LibreChat / gateway line drawn explicitly —
plus where memory, harness, and telemetry live, and an honest difficulty map.*

---

## 1. The whole system, one diagram

```
┌──────────────── YOU RUN AS-IS (config + rebrand only) ───────────────────┐
│  LibreChat  (Meridian branded)                                     │
│  • chat UI   • user login   • conversation history (Mongo)               │
│  • renders the streamed answer                                           │
└───────────────────────────────┬──────────────────────────────────────────┘
                                 │  POST /v1/chat/completions
                                 │  • full conversation in messages[]   • stream=true
                                 │  • headers: conversation_id, user_id
══════════════════════════ THE BOUNDARY (one HTTP call) ════════════════════
                                 ▼
┌──────────────────────── YOU BUILD (the actual work) ─────────────────────┐
│  GATEWAY   (Spring Boot + virtual threads)                               │
│                                                                          │
│   Ingress (OpenAI-compatible SSE)                                        │
│        │                                                                 │
│        ▼                                                                 │
│   RESOLVER:   A route ──► B filter ──► C invoke ──► D synthesize ──► SSE  │
│                                          │                               │
│                                          ▼                               │
│                               ProtocolAdapter  (HTTP now · MCP/A2A later)│
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │ HARNESS  (wraps EVERY outbound call — cross-cutting)              │  │
│   │ trace · auth · circuit-breaker · timeout · isolation · retry · audit│ │
│   └──────────────────────────────────────────────────────────────────┘  │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │ TELEMETRY  (OpenTelemetry — cross-cutting)                        │  │
│   │ one trace/request → Grafana (ops) · glass-box (demo) · AI plane   │  │
│   └──────────────────────────────────────────────────────────────────┘  │
└──────────┬─────────────────────────────────────────────┬─────────────────┘
           │ Redis Stack                                  │ HTTP
           ▼                                              ▼
  vector index + agent JSON                    Mock Agents  (Wealth 4 / Servicing 5)
  + ephemeral context summary                  canned data + latency/fault knobs
```

Everything above the boundary is configuration. Everything below it is what you build —
and would build regardless of which UI you chose.

---

## 2. One turn, step by step (this resolves the "where's the memory?" question)

1. User types in LibreChat. LibreChat sends the **entire conversation** in `messages[]`
   with `stream=true`, plus `conversation_id` and `user_id` headers.
2. Gateway ingress accepts the request on a **virtual thread**. It does **not** look up
   session history — the full thread is already in the request.
3. **Context shaping:** the gateway takes that thread and, if it's long, compresses older
   turns to a short summary (cached in Redis by `conversation_id`) so it never forwards raw
   15-turn history to a domain agent. This is *shaping*, not *storage*.
4. **Resolver runs:** A route → B filter → C invoke (synthesize each agent's input, fire
   them in parallel through the harness) → D synthesize the merged answer.
5. Tokens **stream back** as SSE → LibreChat renders them → LibreChat saves the turn to its
   own history.

The key insight: **your endpoint is stateless.** Multi-turn works because LibreChat replays
the thread every turn — so you get conversation memory for free without building a session
store.

---

## 3. Who owns what

| Concern | Owner | Notes |
|---|---|---|
| Chat UI + answer rendering | **LibreChat** | config + rebrand only |
| User login / accounts | **LibreChat** | free |
| Conversation history (the durable thread) | **LibreChat** (Mongo) | the system of record for *conversations* |
| Replaying the thread each turn | **LibreChat** | sends full `messages[]` |
| Context **shaping** for agents (summarize/trim) | **Gateway** (ephemeral Redis) | optimization; skippable for PoC |
| Intent routing | **Gateway** | semantic route + confidence floor |
| Agent invocation / protocols | **Gateway** (adapters) | HTTP now; MCP/A2A behind same interface |
| Resilience (breaker, timeout, retry, isolation) | **Gateway** (harness) | a headline selling point |
| Telemetry / observability | **Gateway** (OTel) | a headline selling point |
| Agent registry | **Gateway** (Redis) | manifests + vector index |

**There is no split brain as long as you keep this rule:** LibreChat owns *conversations*;
the gateway owns *resolution* and only ever **shapes** context, never stores it as a second
source of truth.

---

## 4. Where your two selling points live

Both are **cross-cutting layers inside the gateway**, not separate services — they wrap
every single outbound agent call:

- **Harness** — the uniform pipeline every agent call passes through: trace propagation,
  auth hydration (stubbed now, real later), circuit breaker + per-agent SLA timeout,
  bulkhead isolation (one slow agent can't starve the fan-out), retry (safe because Phase 1
  is read-only/idempotent), and async immutable audit. *The pitch: domain teams write zero
  gateway code and still get all of this on every call.*
- **Telemetry** — one OpenTelemetry trace per request, child spans per resolver stage and
  per agent call, exported to Grafana (ops health), the glass-box (the live demo panel),
  and — the differentiator — an **enterprise AI observability plane**: because every query
  flows through here, the bank sees what's asked firm-wide, which agents are used, and *where
  routing fails* (the gaps to fund next). The old Copilot gave them none of that.

Instrument these from the **first** outbound call (M3/M4), not as an M5 add-on — the trace
context has to thread through the harness from the start.

---

## 5. Honest build-difficulty map

| Component | Difficulty | Why |
|---|---|---|
| **LibreChat integration** | **Easy** | config + cosmetic rebrand; hours |
| Mock agents | Easy | canned JSON + fault knobs |
| Registry + vector index | Easy–Medium | Redis Stack does the heavy lifting |
| Ingress SSE | Medium | the chunk format must be byte-correct |
| Resolver (route + filter) | Medium | semantic routing + confidence floor |
| **Input synthesis** (prompt → agent schema) | **Hard (the one real risk)** | the LLM-in-the-loop step; test in isolation first |
| Harness | Medium | known Resilience4j patterns |
| Telemetry | Medium | known OTel wiring |

**The headline:** the LibreChat box is the easiest thing on the page. The difficulty of
this project lives in the gateway internals — which exist independent of the UI. So
LibreChat isn't a risk; it's the single biggest risk-*reducer* available, because it
deletes the entire frontend workstream.

---

## 6. The only LibreChat gotchas (each ~1 hour, not architecture)

1. **Auto-title call** — LibreChat fires a separate "name this conversation" LLM call to
   your endpoint; it will try to route to agents and misfire. Short-circuit title requests
   in the gateway, or disable/redirect titling.
2. **SSE correctness** — role delta, content deltas, `[DONE]`. Test against the OpenAI
   shape early or LibreChat shows a blank reply.
3. **Header propagation** — have LibreChat forward `conversation_id` + `user_id`; you need
   them as telemetry correlation keys.
4. **Its stack** — LibreChat brings Mongo + Meilisearch; bring them up via its
   docker-compose. Not hard, just don't be surprised by the extra containers.
