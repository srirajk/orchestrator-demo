# Codex task — complete e2e observability (make it real, and make it TESTABLE)

> GATEWAY + infra + mock-agent config. World-B unaffected (observability is generic) — run
> `scripts/world-b-check.sh` before/after, CRITICAL 0. Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Stack: docker compose
> `orchestrator-demo`. If ambiguous, STOP and report.

## Why (from a LIVE validation, not grep — the ground truth)
A real request was driven through the BFF and its telemetry inspected in Tempo + Loki. Result: the pieces
are wired in three places but **do not connect for the request data path**. Fixes are all small and named —
no redesign. What works: the glass-box `requestId` **is** the OTel `traceId` (decision-trace ↔ Tempo link is
real), and the LLM + agent-invoke spans carry good attributes. What's broken:

## Fix 1 — `traceparent` must reach the agents (agent spans join the gateway trace)
**Finding:** the agent-calling client is a bare `new RestTemplate()` (`AppConfig.java:~28`) — uninstrumented —
so no `traceparent`/`baggage` is injected; the (correctly-instrumented) agents mint a **fresh, disconnected
root trace** per call instead of nesting under the gateway trace. (The Cerbos/coverage calls use a different,
instrumented client and DO show as spans — proving the mechanism works when the client is instrumented.)
- **Migrate the agent-calling client from `RestTemplate` to `RestClient`** (the modern synchronous client;
  RestTemplate is in maintenance mode). Build it from the **Micrometer-instrumented `RestClient.Builder`**
  (the auto-configured builder / `ObservationRegistry`), which **auto-injects W3C `traceparent`+`baggage`** —
  so the fix is idiomatic, not a hand-rolled interceptor. **Use RestClient, NOT WebClient** — WebClient is
  reactive (WebFlux); we are Spring MVC + virtual-threads-on, where synchronous blocking is cheap on VTs and
  reactive semantics are exactly what we don't want. Rewrite `HttpAdapter`'s calls (getForObject/exchange) to
  the RestClient fluent API. Do the MCP adapter outbound path too if it makes outbound HTTP calls.
- **Virtual-thread caveat:** the agent call runs on the executor's virtual thread. The OTel `Context` (like the
  token) does NOT auto-cross VTs — ensure the captured `parentContext`/span is made current on the VT so the
  RestTemplate call injects the RIGHT trace context (the executors already capture `Context.current()`; make it
  active around the invoke).
- **Must not perturb the SSE byte-contract** (hard-rule a).

## Fix 2 — logs ↔ traces correlation (pivot Tempo ↔ Loki)
**Findings:** (a) the log pattern (`application.yml:~221`) emits `rid/cid/uid` but **no `traceId`/`spanId`**; (b)
there's a *third* id — the MDC `rid` is a separate `UUID.randomUUID()` (`RequestCorrelationFilter`), NOT the
traceId; (c) **Promtail isn't running** — it's behind a `profiles: ["observability"]` gate (`docker-compose.yml:~113`),
so Loki has no live data in the running core stack.
- Add `traceId`/`spanId` to the log pattern (Micrometer Tracing populates the MDC — add `%X{traceId} %X{spanId}`),
  so every log line carries the traceId. (Optionally unify `rid` = traceId to kill the third id.)
- Update `infra/promtail/promtail.yaml` to also extract `traceId` into a Loki label.
- Make **Promtail part of the running stack** (move it into the core profile, or make the running stack include
  the observability profile by default) so logs actually reach Loki without a manual flag. Document it.

## Fix 3 — the "why" belongs on the spans (Tempo self-explains)
**Finding:** routing scores/margin, goal-agent pick, coverage allow/deny+reason, and the grounding verdict are
rich in the glass-box events + DEBUG logs but **absent from OTel span attributes** — an SRE reading Tempo alone
cannot see why an agent was picked or a request denied. Add span attributes (and, where a stage has no span, a
short span) at the decision points — mirror the key fields already emitted to the glass-box events:
- routing: `conduit.routing.top_score`, `.margin`, `.goal_agent`, `.rerank_fired`.
- plan: `conduit.plan.node_count`.
- coverage/entitlement: the decision + reason + stage (allow/deny) per check.
- grounding: the validator verdict (all-grounded / count of figures).

## Fix 4 — proper HTTP root span
**Finding:** the trace root is a hand-built `chat.handle` span with no `http.method/route/status`. Ensure the
SSE endpoint's Spring server span is the root (parent `chat.handle` under it, or set the http attributes on the
root) so a trace opens with the standard HTTP envelope.

## Fix 5 — agent service identity (minor)
**Finding:** agent-side spans show `service.name=unknown_service` because `OTEL_SERVICE_NAME` is set for
`wealth-market-research`/`hr-policy` but **not `wealth-http`** (and check the others). Set `OTEL_SERVICE_NAME`
for every mock-agent service in `docker-compose.yml`. (Also: the agent telemetry INFO logs never fire — no
`logging.basicConfig` — fix if trivial so agent-side trace logging works.)

## HARNESS (first-class — the whole point: observability must be PROVABLE, not hoped)
Add a live observability test (`tests/e2e/security_harness/test_observability_e2e.py`) that drives one real
request and then asserts, against the actual backends:
1. **Agent span joins the trace:** query Tempo by the request's traceId → the agent's own span (e.g.
   `wealth.holdings`) is present AND shares the gateway's traceId (parented, not a fresh root). ★ the core proof.
2. **Log carries the traceId:** query Loki by that traceId → the request's gateway log lines are returned (so
   the log pattern emits traceId AND Promtail is shipping). ★
3. **Decision attributes on spans:** the Tempo trace's spans carry the routing score/margin, the coverage
   decision, and the grounding verdict attributes (query + assert present). ★
4. Agent spans report the correct `service.name` (not `unknown_service`).
Same pytest-availability caveat as prior harness tests is acceptable (run via the seeder runtime), but the file
must exist and the assertions must actually execute against Tempo/Loki.

## GATE
- All four harness assertions pass on a clean rebuild (agent span joins trace; log pivots by traceId; decision
  attributes present; correct service names). `mvn test` green; World-B 0; the 3 verticals still answer live;
  SSE byte-shape unchanged (assert if you touch the agent-call client).

## Constraints / anti-gaming
- The harness must query the REAL Tempo/Loki and assert the REAL linkage (a self-parenting agent span, or logs
  with no traceId, must FAIL the test). Promtail must actually run in the tested stack. Do NOT weaken an
  assertion to pass. World-B clean. Do NOT commit.

## Report
Files changed; the RestTemplate instrumentation + VT-context handling; the log-pattern + Promtail + profile
change; the span attributes added; the service-name fix; the harness with evidence each assertion passes
(agent span parented to the gateway traceId, a Loki query returning the request's logs by traceId, the decision
attributes on the trace); mvn/World-B/SSE results. STOP and report anything unanticipated.
