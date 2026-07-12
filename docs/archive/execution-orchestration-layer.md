# Execution / Orchestration Layer — Spec

*Turns a set of selected capabilities into invoked results. One executor that walks a Plan
(flat fan-out now, topological chains later), with reliability applied per call. This is the
layer that sits above the `ProtocolAdapter` and below answer synthesis.*

---

## The model: Plan = DAG

```java
record PlanNode(
    String nodeId,
    AgentDefinition agent,
    JsonNode input,           // synthesized input (from Input Synthesis)
    List<String> dependsOn    // node ids; EMPTY for Phase 1 (flat fan-out)
) {}

record Plan(List<PlanNode> nodes) {}      // Phase 1: all nodes have empty dependsOn

record NodeResult(
    String nodeId,
    String agentId,
    String protocol,          // for telemetry + answer attribution
    Status status,            // OK | FAILED | TIMEOUT | BREAKER_OPEN
    JsonNode data,            // null unless OK
    long latencyMs
) { enum Status { OK, FAILED, TIMEOUT, BREAKER_OPEN } }
```

Fan-out is a flat plan (all nodes independent). Chaining is a plan with edges. Dynamic
fork/join is a planner-shaped plan. **Same executor for all three** — you only ever change
the plan's shape, never the executor's code.

> **Phase 1 emits only flat plans.** Define the model fully now; the planner that produces
> deeper plans is Phase 2. This is the seam — build the model, not the planner.

---

## The two layers (kept separate)

**Harness** wraps a *single* call. **Executor** composes wrapped calls per the plan. Neither
leaks into the other.

```java
interface PlanExecutor {
    List<NodeResult> execute(Plan plan, RequestContext ctx);
}
```

### Harness (per-call reliability)
Each node's body is a Resilience4j-decorated supplier, composed in this order:

```
circuitBreaker(agentId)  →  timeLimiter(sla_timeout_ms)  →  bulkhead(agentId)  →  retry
                                        ↓
                               adapter.invoke(agent, input)
```

- **CircuitBreaker** keyed per `agent_id`, **shared across all requests** (one bad agent
  trips only its own breaker, for everyone).
- **TimeLimiter** from the agent's `sla_timeout_ms`.
- **Bulkhead** — semaphore bulkhead per `agent_id` caps concurrent in-flight calls. Needed
  even with virtual threads: Loom gives unlimited cheap threads, so the bulkhead is the
  backpressure boundary that stops one slow agent accumulating unbounded parked calls.
- **Retry** — max 2, transient/timeout only, **enabled only because Phase 1 is read-only**.
  (In Phase 2, never auto-retry a mutating call.)

### Executor (orchestration)
Virtual-thread-per-task + `CompletableFuture`. Use `CompletableFuture`, **not**
`StructuredTaskScope`, for the demo — it is stable (not preview) and its API hasn't shifted
across JDK versions.

```java
// Flat fan-out (Phase 1)
List<CompletableFuture<NodeResult>> futures = plan.nodes().stream()
    .map(node -> CompletableFuture.supplyAsync(
        () -> harness.execute(node.agent(), node.input(), ctx),   // returns NodeResult, never throws
        virtualThreadExecutor))
    .toList();

CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
    .orTimeout(OVERALL_DEADLINE_MS, MILLISECONDS)   // request-level deadline (see below)
    .exceptionally(t -> null)                       // overall timeout: DON'T fail the request
    .join();

List<NodeResult> results = futures.stream()
    .map(f -> f.isDone() && !f.isCompletedExceptionally() ? f.join()
                                                          : timedOut(node))   // harvest survivors
    .toList();
```

---

## Two timeout levels (do not conflate)

1. **Per-call** — each agent's `sla_timeout_ms`, enforced by the harness TimeLimiter.
2. **Per-request overall deadline** — a cap on the whole fork/join, so the slowest branch
   can't hang the user (`allOf(...).orTimeout(...)`).

---

## Partial-result tolerance (the critical behavior)

**A failed node never cancels its siblings.** The harness catches everything and returns a
`NodeResult` with a non-OK status — it does not throw. The executor joins to the overall
deadline, then **harvests every node's state** (OK / FAILED / TIMEOUT / BREAKER_OPEN) and
passes the full list to synthesis. Synthesis then answers from the survivors and states
what's missing.

This is exactly the agent-kill demo beat: kill one agent, the answer still returns from the
rest, degraded but graceful. It only works if a failure doesn't tear down the scope — so
**never** use a fail-fast "shut down on first error" join policy here.

---

## What flows downstream

The `List<NodeResult>` carries, per node: the data (or absence), the `agent_id`, the
`protocol`, and `latencyMs`. This feeds two things:
- **Answer synthesis** — grounds each claim in a specific agent's data (attribution).
- **Telemetry / glass-box** — per-agent protocol + latency + outcome, live.

---

## Demo-now vs scale-later

- **Demo-now:** flat executor (~50 lines), per-agent harness, two-tier timeouts,
  partial-result join. That's the whole Phase-1 execution layer.
- **Scale-later:** topological walk — group nodes into dependency levels, run each level as
  one fork/join scope, and a completed level unlocks the next. Same `Plan`, same harness,
  deeper graph. Plus conditional retry (off for mutating nodes) when writes arrive.
