# Take the trace write off the request path

> GATEWAY code. World-B unaffected (pure counts, no domain literals) — run `scripts/world-b-check.sh`,
> CRITICAL 0. `mvn test` stays green. Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
> **Package placement is binding** — see `docs/specs/GATEWAY-PACKAGE-STRUCTURE.md`.

## The rule

**Nothing that does not produce the answer belongs on the request path.** Telemetry, trace events, and
panel/analytics persistence are written asynchronously, on a **bounded** queue, with an explicit drop policy
and a dropped-count metric.

## What is actually happening today (measured)

`infrastructure/telemetry/TraceEventPublisher.publish()` calls `storage.save(event)` **synchronously and
unconditionally at `:125`** — before the `subscribers.isEmpty()` early-return at `:127`.

- `ChatService` has **~45 publish sites** per request.
- `RedisTraceStorageAdapter.save()` does `rpush` + `expire`, plus `zadd` + `expire` on a request's first
  event → **~92 Redis round-trips per request**.
- They all borrow from **one shared `JedisPooled`** (`infrastructure/redis/RedisConfig.java:18`), whose Jedis
  defaults are `maxTotal=8` and `maxWait=-1` (**blocks forever**). The same pool serves the routing KNN
  search (`registry/index/VectorIndex.search` → `jedis.ftSearch`) and the revocation check.

**And it serves nothing the user is looking at.** The live glass-box panel calls
`/api/conversations/{id}/trace/stream` → `TraceStreamController:35` → `publisher.subscribe(...)`, an
**in-memory SSE fan-out**. It never reads Redis. The stored copy backs only `InsightsController:126,130` and
the `/trace/{requestId}` + `/trace/history` endpoints, which the chat UI never calls.

So: ~92 Redis round-trips per request, on the hot path, through the pool that routing depends on, to serve an
analytics page. Measured ceiling today: **~70 req/s at ~100 concurrent on 2 CPUs** (`docs/perf/RESULTS.md`).

## Why an `@Async` event listener is the wrong fix

It moves the work off the request thread but still performs ~92 round-trips per request, and Spring's default
executor queue is **unbounded** — so under load you trade a slow request for a memory blowup. Moving work off
a thread does not fix *doing 92 round-trips*. Fix the work first, then move it.

## The change

1. **Buffer per request, flush once.** Accumulate a request's events in memory (they are already ordered) and
   write them in **one pipelined operation** at `request_complete`. ~92 round-trips → ~3. Cap the per-request
   buffer and the total buffered bytes; on overflow, drop with a counter (never grow without bound).
2. **Flush asynchronously on a bounded queue** drained by a small number of dedicated virtual threads.
   Drop-oldest on overflow. Emit `conduit.trace.dropped` — a silent drop is a lie.
   **Never block a request to persist a trace event.**
3. **Give telemetry its own Jedis pool** (sized from config), so a trace-write burst cannot starve routing.
   While there: give the *shared* pool a real `maxTotal` and a **finite `maxWait`** — `-1` means a starved
   caller waits forever.
4. **Do not change the live panel.** `publisher.subscribe(...)` fan-out stays exactly as it is; it is what the
   UI consumes and it is already in-memory.
5. **Keep the `TraceStorageAdapter` seam.** The panel/analytics store stays best-effort. A T7 audit store must
   be strict, durable and fail-closed — different guarantees, same interface. Do not collapse them here.

**Placement** (per `GATEWAY-PACKAGE-STRUCTURE.md`): the buffer, the bounded queue and the drain workers go in
`infrastructure/telemetry/`, beside `TraceEventPublisher` and `RedisTraceStorageAdapter`. The pool config goes
in `infrastructure/redis/`. All sizes, caps and timeouts are **config properties** (`@Value` /
`application.yml` / env) — never `private static final` constants.

## Accept­ance gate

- **The number moves.** Re-run the Phase 1c sweep (`docs/perf/RESULTS.md`, harness in
  `docker-compose.perf.yml`, `CPUS=2 CARRIERS=2`, deterministic LLM) and report throughput at the knee before
  and after. If ~70 req/s does not improve, the diagnosis was wrong — say so; do not quietly ship it.
- **No behaviour change.** The live glass-box panel still streams every event for a request (assert against
  `/trace/stream`). `/trace/{requestId}` still returns the full, **correctly ordered** event list after the
  request completes. `InsightsController` still works.
- **Bounded, and honest about it.** Under sustained overload the queue drops rather than grows; the dropped
  count is non-zero and exported. Assert with a test that fills the queue.
- **Redis pool.** `maxWait` is finite; telemetry and routing use different pools; prove routing still succeeds
  while a trace-write burst is in flight.
- `mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; SSE byte-shape unchanged.

## Anti-gaming

- Do not weaken the panel to make the numbers look good: every event a request emits must still reach a
  connected subscriber.
- Do not make the flush silently best-effort-and-forget. A drop must be **counted**.
- Do not fix this by simply deleting the Redis write. `InsightsController` reads it. If we later decide Tempo
  or Langfuse should back Insights instead, that is a separate, deliberate decision — not a side effect.
- Report the before/after throughput honestly, including if it does not move.

## Open question worth deciding deliberately

Now that the OTel spans carry the decision attributes (routing score/margin, coverage decision, grounding
verdict) and the glass-box `requestId` **is** the OTel `traceId`, Insights could plausibly read from
Tempo/Langfuse and the Redis copy could disappear. Do not do this blind — but "why does this exist" deserves a
better answer than "it always has."
