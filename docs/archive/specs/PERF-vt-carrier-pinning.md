# PERF — gateway virtual-thread carrier pinning / concurrency livelock (SAVED, fix later)

> Status: **found by a live cold-start + load test; NOT yet fixed.** Highest-priority operator/production
> readiness gap. Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`. This doc captures the finding +
> the fix plan so it can be executed later without re-discovering it.

## The finding (from a live load test on a cold-started stack)
- **Cold start is clean:** `down -v` → fresh `up` → seeded → ready in ~2m20s; `Registry bootstrap complete:
  18 loaded, 0 failed`; first real query correct. No unhealthy/crash-looping services.
- **The gateway wedges under concurrency:** up to ~**20 concurrent** full-pipeline chat requests it serves
  fine (0% errors; tail latency rising but bounded, p95 ~32s from the sequential per-request LLM calls). At
  **25 concurrent** (single-agent "flat" path) it does NOT degrade gracefully — it enters an **unrecoverable
  livelock**: CPU pinned ~250% (multiple full cores), `/v1/models` + its own health check unresponsive, for
  **7+ minutes with ZERO client traffic** after the run ended. It never self-heals; only `docker compose
  restart gateway` recovers it (healthy in ~8s, re-loads 18/0).
- **Signature (thread dump / SIGQUIT):** several `ForkJoinPool-1-worker` carrier threads each burning ~74s of
  CPU while permanently "Carrying virtual thread" with shallow continuation stacks → **virtual-thread carrier
  pinning / carrier exhaustion**, not a draining backlog.
- **Isolated:** all 26 other services (Redis, IAM, Cerbos, agents, coverage, Langfuse…) stayed healthy — a
  gateway-process failure, not a cascading stack failure.
- **Ceiling:** clean+recoverable at 20 VUs, total unrecoverable hang at 25 VUs (flat). DAG path clean at ≤10
  VUs; not pushed to 25 to avoid another hang, but holds resources longer per call so likely hits the wall at
  or below 25.

Load numbers (recovered instance, clean runs):
| Path | VUs | Err | TTFT p50/p90/p95 |
|---|---|---|---|
| flat | 10 | 0% | 4.6 / 18.2 / 19.1 s |
| flat | 20 | 0% | 3.8 / 13.8 / 32.2 s |
| flat | **25** | **100%** | **hung — livelock** |
| dag | 10 | 0% | 9.9 / 14.6 / 20.4 s |

Load script written for this: `tests/load/coldstart-load-test.js` (flat vs DAG, JWT auth). Raw logs were in
`/tmp/coldstart-load-results/` (ephemeral).

## Root-cause hypothesis (confirm by diagnosis, do NOT guess-fix)
Virtual-thread **carrier pinning**: a VT pins its carrier platform thread (classic causes: a `synchronized`
block/method held across a blocking call, or a native/JNI blocking call). Enough VTs pinning simultaneously
exhausts the ForkJoinPool carriers (~#cores) → livelock. **Prime suspects** (verify, don't assume):
- The **in-JVM DJL + MiniLM embedding** (`EmbeddingClient`) — native inference runs on EVERY routing call; a
  `synchronized`/native pin here would trip on every concurrent request.
- A `synchronized` hot-path lock (trace store, Redis client, the tracer, a cache).
- The LLM/agent HTTP clients under contention.

## Fix plan (when we pick this up)
1. **Diagnose the exact pin — empirically.** Run the gateway with `-Djdk.tracePinnedThreads=full` (or a JFR
   recording capturing `jdk.VirtualThreadPinned` events), reproduce with ~25 concurrent, and read off the
   EXACT stack(s) that pin the carrier. Name the culprit before touching code.
2. **Fix the pin.** Replace `synchronized`-over-blocking with `ReentrantLock` (which doesn't pin on JDK 21+),
   or move the offending blocking/native call off the VT critical path (e.g. a bounded platform-thread pool
   for native embedding inference), per whatever the pinning trace names.
3. **Add backpressure / load-shedding (defense in depth).** Even unpinned, an unbounded gateway has a ceiling
   — it must SHED past it, not wedge: a concurrency limiter / Resilience4j **bulkhead** on the request path
   that returns an honest `503`/queues rather than saturating, and a per-request deadline that frees
   resources. It must **self-heal** (recover once load drops) with no manual restart.
4. **Re-run the load test as the GATE:** survive well past 25 concurrent on BOTH flat and DAG paths — either
   holding, or degrading with honest `503`s — and **self-heal after the load stops, no `docker restart`**.
   Capture the new load table + a pinning trace showing zero pins on the hot path.

## Why it matters
A stateless service that livelocks at 25 concurrent chats and can't self-heal is not deployable to any org.
This is the top operator/production-readiness gap — fix it before the observability polish.
