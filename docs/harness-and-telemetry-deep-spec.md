# Harness & Telemetry — Deep Spec

*Your two headline selling points. Not backend plumbing — the layers that make this a
governed platform rather than a clever router, and the part competitors can't cheaply copy.
Both are cross-cutting: they wrap every outbound agent call.*

---

## 1. Why these two are the product

- A router picks an agent. A **platform** guarantees that every call — across every domain
  team, over every protocol — is traced, authorized, credentialed, circuit-broken, retried,
  rate-limited, and audited, **without the domain team writing any of it.**
- And because every enterprise AI query flows through one place, the platform becomes an
  **AI observability and governance plane** — something the Copilot it replaces could never
  be.

That sentence — "domain teams write zero gateway code and still get all of this" — is the
sale. The harness is what makes it true; telemetry is what makes it *visible*.

---

## 2. The Harness pipeline

Every outbound agent invocation passes through one ordered interceptor chain. Order is
deliberate: deny cheaply and early; protect only the network call; audit after, async.

| # | Stage | Does | Short-circuits on |
|---|---|---|---|
| 1 | **Entitlement gate** | Is this user allowed to invoke this agent **and** access the resolved entity? | not entitled → deny |
| 2 | **Auth hydration** | Obtain the downstream machine credential (token exchange) for the target's ecosystem *(stubbed in PoC)* | credential unavailable |
| 3 | **Trace propagation** | Inject W3C `traceparent` + correlation IDs (`request_id`, `conversation_id`, `user_id`) onto the outbound call | — |
| 4 | **Resilience gate** | Circuit breaker + per-agent SLA timeout + bulkhead isolation, wrapping the adapter call | breaker open / timeout |
| 5 | **Retry** | Bounded retry on transient failure — **safe because Phase 1 is read-only/idempotent** | retries exhausted |
| 6 | **Rate limit** | Respect the agent's declared `rate_limit` | over limit |
| 7 | **Invoke** | The protected `ProtocolAdapter.invoke(...)` | adapter error |
| 8 | **Audit (async)** | Non-blocking offload of call metadata to an immutable sink | — (never blocks) |

**Two-level authorization (this is the subtlety your question points at):**
- **Request-level** entitlement runs *early*, before the LLM extraction even fires — can
  this user use the platform / this domain at all?
- **Entity-level** entitlement (stage 1 above) can only run *after* resolution, because you
  can't check "may this user see the Whitman relationship" until "Whitman Family Office" has
  resolved to `REL-00042`. So entitlement is **coupled to resolution** — which is exactly
  why it belongs here, after Stage C, not at the front door alone.

### Interface

```java
interface HarnessStage {
  // returns the (possibly enriched) call, or throws to short-circuit
  OutboundCall apply(OutboundCall call, RequestContext ctx) throws HarnessDenied;
}

// The pipeline runs stages 1..6, invokes the adapter, then fires stage 8 async.
AgentResult execute(AgentDefinition def, JsonNode input, RequestContext ctx);
```

### Resilience4j specifics
- **CircuitBreaker** keyed per `agent_id` (one bad agent trips only its own breaker).
- **TimeLimiter** from the agent's `sla_timeout_ms`.
- **Bulkhead** — a **semaphore bulkhead per agent** caps concurrent in-flight calls to that
  agent. Critical even with virtual threads: Loom gives you near-unlimited cheap threads, so
  without a per-agent bulkhead one slow agent could accumulate unbounded parked calls. The
  bulkhead is the backpressure boundary.
- **Retry** — max 2, transient/timeout only, enabled only because `is_mutating == false`.
  (This becomes conditional in Phase 2: never auto-retry a mutating call.)

---

## 3. Telemetry — three levels

### Level 1 — Operator health (Grafana)
Latency (p50/p99), throughput, error rate, per-agent breaker state, SLA adherence, token
cost, and the **virtual-thread vs OS-thread** gauges (the Loom proof).

### Level 2 — Live glass-box (the demo panel)
The routing decision, the selected agents, each protocol, per-agent latency bars, the
entitlement decision, and the merge — streamed live beside the chat.

### Level 3 — Enterprise AI observability plane (the differentiator)
Because every query transits the gateway: what the firm asks, which domains/agents are used
and *under*-used, **where routing fails** (low-confidence/no-match = agent-coverage gaps to
fund next), latency hotspots, cost, and adoption by segment. This is governance + roadmap,
not monitoring.

### OTel span model
```
gateway.request                 {user_id, conversation_id, request_id, model}
├─ resolver.route               {candidates[], scores[], confidence, fallback}
├─ resolver.synthesize          {entities, resolved_ids, llm_tokens, latency}
├─ authz.check                  {scope: request|entity, decision, reason}
├─ agent.invoke   (per agent)   {agent_id, domain, protocol, sla_ms, breaker_state,
│                                retries, latency, outcome, sla_breached}
└─ resolver.answer              {llm_tokens, ttft_ms, total_ms, fanout_width}
```
Export: OTel collector → Tempo (traces) + Prometheus (metrics) → Grafana. The glass-box
reads the same trace events, so there is **one** source of truth for both ops and demo.

### Metric/signal list (Micrometer)
`gateway.requests`, `gateway.request.latency`, `gateway.ttft`,
`resolver.route.latency`, `resolver.route.confidence`, `resolver.fallback`,
`agent.invoke.latency{agent_id}`, `agent.invoke.errors{agent_id,type}`,
`agent.breaker.state{agent_id}`, `agent.sla.breaches{agent_id}`, `agent.retries{agent_id}`,
`llm.tokens{phase}`, `llm.latency{phase}`, `fanout.width`,
`authz.denials{reason}`, `jvm.threads.virtual`, `jvm.threads.os`.

### Glass-box event schema (SSE the dashboard consumes)
```jsonc
{ "type":"route",      "candidates":[{"agent_id":"...","score":0.71}], "selected":["..."], "confidence":0.71, "fallback":false }
{ "type":"authz",      "agent_id":"...", "decision":"allow", "reason":"rm_owns_relationship" }
{ "type":"synth",      "entities":{"relationship_reference":"Whitman Family Office"}, "resolved":{"relationship_id":"REL-00042"} }
{ "type":"agent_start","agent_id":"...", "protocol":"http" }
{ "type":"agent_end",  "agent_id":"...", "latency_ms":180, "outcome":"ok", "breaker_state":"closed", "retries":0 }
{ "type":"answer",     "ttft_ms":420, "total_ms":1900, "fanout_width":7 }
```

---

## 4. Sequencing

**Instrument from M3/M4, not M5.** The trace context must thread through the harness from
the very first outbound call; you cannot bolt correlated tracing on afterward. Treat the
harness chain and the OTel root span as foundational scaffolding, built before the fan-out,
not as a finishing layer.

---

## 5. Authorization — the next dimension (what we dig into next)

The harness has the *enforcement point* (stage 1). The next spec defines the *policy* behind
it. First, the three things people wrongly merge into "auth":

| Concern | Question | Where |
|---|---|---|
| **Authentication** | Who is this user? | LibreChat login → gateway receives/validates `user_id` (a JWT in real life) |
| **Credential hydration** | How do we get a credential to call the agent? | Harness stage 2 (token exchange) — *machine* auth |
| **Entitlement (authorization)** | What is this user allowed to see/do? | Harness stage 1 — *the "what an individual can do"* |

The entitlement piece — the one you're bringing in — is the rich one for a bank, and the
next spec will cover:

- **Enforcement points:** request-level (may you use this domain at all), **entity-level**
  (may you see *this* relationship — only checkable post-resolution), and **action-level**
  (Phase 2 — may you *trigger* this write).
- **Entitlements as a routing filter, not just a gate:** prune the candidate agent set to
  what the user is allowed to use *before* invocation — so an RM never even fans out to an
  agent or relationship outside their book. Cheaper, safer, and it shapes the answer.
- **Policy model:** attribute/role + resource-scoped (e.g., an RM sees only their book; a
  compliance officer sees more), evaluated by a policy engine (OPA/Cedar-style) fed user
  attributes + the resolved resource's attributes.
- **Phase-2 weight:** entitlement is *advisory* for reads but **load-bearing** for writes —
  it's the gate that makes "trigger & fix" safe to turn on. Building the model now, during
  the read phase, is what earns the right to write later.
