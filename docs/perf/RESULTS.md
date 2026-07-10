# Perf results — Phase 1 (deterministic LLM)

Harness: `docker-compose.perf.yml` (AIMock pinned by digest + Toxiproxy). LLM replayed from 27 recorded
fixtures; **zero upstream calls, zero fixture misses** in every run below. Load: `tests/load/coldstart-load-test.js`,
flat path, content assertions on (`conduit_degraded_rate`).

Run 2026-07-09. Gateway: Java 25.0.3, Boot 3.5.16.

---

## The one-line result

**On 2 CPUs and 2 carrier threads, the gateway serves 50 concurrent requests with flat latency and zero
errors, and saturates at ~70 req/s.** Halving CPUs from 12 → 2 changed nothing, so it is not CPU-bound.

At 25 VUs against the **real** provider we measured **51.98 % degraded**. With a deterministic LLM: **0.00 %.**
Every degradation observed during the day was upstream — the provider's rate limit, and the agents. **Not
the gateway.**

## Phase 1a — LLM latency 0, 12 CPUs / 12 carriers

| VUs | iters (20s) | p50 | p90 | p95 | err % | degraded % |
|---|---|---|---|---|---|---|
| 1 | 15 | 1.20s | 1.23s | 1.23s | 0.00 | 0.00 |
| 10 | 168 | 1.05s | 1.20s | 1.21s | 0.00 | 0.00 |
| 25 | 423 | 1.05s | 1.22s | 1.25s | 0.00 | 0.00 |
| 50 | 807 | 1.06s | 1.28s | 1.38s | 0.00 | 0.00 |

## Phase 1b — LLM latency 0, **2 CPUs / 2 carriers** (`Effective CPU Count: 2`, quota 200000/100000us)

| VUs | iters | req/s | p50 | p95 | err % | degraded % |
|---|---|---|---|---|---|---|
| 1 | 15 | 0.7 | 1.21s | 1.29s | 0.00 | 0.00 |
| 10 | 169 | 8.0 | 1.06s | 1.22s | 0.00 | 0.00 |
| 25 | 423 | 19.9 | 1.06s | 1.26s | 0.00 | 0.00 |
| 50 | 807 | 38.0 | 1.07s | 1.50s | 0.00 | 0.00 |

**Identical to 12 CPUs.** If the gateway were CPU-bound, a 6× CPU cut would have cut throughput ~6×. It did
not move. 50 concurrent in-flight requests, 2 carriers: the virtual threads park on I/O and release their
carrier, exactly as the model promises. Demonstrated, not asserted.

## Phase 1c — finding the knee (2 CPUs / 2 carriers)

| VUs | iters (25s) | req/s | p50 | p90 | p95 | err % | degraded % |
|---|---|---|---|---|---|---|---|
| 50 | 807 | 38.0 | 1.07s | — | 1.50s | 0.00 | 0.00 |
| 100 | 1848 | **70.0** | 1.18s | 1.47s | 1.59s | 0.43 | 0.43 |
| 200 | 1920 | 69.3 | **2.61s** | 2.92s | 3.06s | 5.31 | 5.31 |
| 400 | 2151 | 71.7 | **5.24s** | 5.55s | 5.66s | 5.99 | 5.99 |

**Throughput saturates at ~70 req/s at ~100 concurrent.** Past the knee, extra load buys only latency —
and it obeys Little's Law almost exactly: 200 ÷ 70 = 2.86s (observed 2.61s); 400 ÷ 70 = 5.7s (observed 5.24s).

**This ~70 req/s @ ~100 concurrent is the number the admission gate must be derived from.** It is measured.
Do not use the `16` the original PERF spec guessed — both expert reviewers flagged that as incoherent, and
it is off by ~6×.

## What breaks at the knee — and it still is not the gateway

`outcome{ERROR} = 239`, all one cause:

```
213 x  Embedding call to http://embeddings:8083/v1/embeddings failed: 500 Internal Server Error
 26 x  Embedding call to http://embeddings:8083/v1/embeddings failed: SocketException: Connection reset
        at RemoteEmbeddingClient.embed(RemoteEmbeddingClient.java:79)
        at VectorIndex.search → AgentResolver.resolveContextual → ChatService.handleFetchData
```

The **embeddings sidecar** falls over at ~70 req/s. `mock-agents/embeddings/main.py` serves
`@app.post("/v1/embeddings")` with a **sync `def embed`** running `SentenceTransformer.encode` — a CPU-bound
model call on the per-request routing path. (FastAPI offloads sync handlers to a 40-thread pool, so this is
not the event-loop block we fixed in the servicing agent, but it is the same family: a heavy dependency in
the hot path.) Its 500s were not root-caused here.

So the binding constraint has moved three times today, and never once landed on the gateway:
**OpenAI's rate limit → the servicing agents' serialization → the embeddings sidecar.**

## Consequences

1. **We still have not found the gateway's own ceiling.** Every measured limit belongs to a dependency.
   To find it, remove the embedding from the hot path (below) and re-run.
2. **The routing embedding is recomputed on every request.** The same query yields the same vector; a cache
   would eliminate essentially all of these calls and the failure mode with them. This is a real gateway
   improvement, not a test-harness workaround.
3. **`RemoteEmbeddingClient` is untimed** — one of the three `SimpleClientHttpRequestFactory` sites (read
   timeout default `-1` = infinite). Today it surfaced as a connection reset; a *hang* would park the request
   forever. That is Phase 3b, and Toxiproxy's `timeout{timeout:0}` is how we will prove it.
4. **The spec's gate is not yet met.** It requires `degraded == 0` below the knee with a deterministic LLM;
   we see **0.43 % at 100 VUs**. The LLM can no longer be blamed — so that 0.43 % is ours to explain.

## Not yet run

Phase 2 (`realistic` 1.8s, `slow` 4s latency), Phase 3 (429 storm; **hang**; mid-stream truncation and
malformed SSE), Phase 4 (agents isolated), and the **canary** against the real provider — which is mandatory,
because AIMock is three months old, ships ~4 releases/week, and is now our measurement instrument. If its SSE
framing regresses, our conformance test passes against a lie.

---

# Phase 1d — after taking the trace write off the request path

Same harness, `CPUS=2 CARRIERS=2`, deterministic LLM. Change: per-request buffering + one pipelined
flush on a bounded async queue, telemetry on its own Jedis pool.

| VUs | req/s before | req/s after | p50 before | p50 after | degraded before | degraded after |
|---|---|---|---|---|---|---|
| 50 | 38.0 | 38.4 | 1.07s | 1.06s | 0.00 | 0.00 |
| 100 | 70.0 | 71.2 | 1.18s | 1.16s | 0.43 | 0.26 |
| 200 | 69.3 | 73.2 | 2.61s | 2.46s | 5.31 | 4.75 |

**The ceiling barely moved (~2–5 %). Say it plainly: the trace write was not the binding constraint
at this load.** The spec required reporting this rather than quietly shipping.

The async path itself works exactly as designed: **43,394 events flushed, 0 dropped, queue depth 0**,
and zero Redis pool-starvation errors. The errors at the knee are unchanged and unrelated — still
`Embedding call to http://embeddings:8083/v1/embeddings failed` (93 × HTTP 500, 8 × connection reset).

## A measured correction to the diagnosis

The expert review said "~45 publish sites × up to 4 Jedis ops ⇒ ~92 Redis round-trips per request",
and I repeated it without counting. **Measured: 43,394 events ÷ 4,911 requests ≈ 8.8 events per request.**
The 45 was a count of *code call sites*, not of events emitted per request. A flat request emits ~10
trace events, so the real cost was **~20 round-trips per request, not ~92** — now collapsed to one
pipelined round-trip (4 commands).

That is exactly why the throughput gain is small, and it is the honest reason to keep the change anyway:

- ~20 → 1 round-trip per request, and none of it on the request thread.
- The queue is **bounded** and drops are **counted** (`conduit.trace.dropped`); an unbounded `@Async`
  queue is a slower way to fall over.
- Telemetry no longer shares the pool that routing depends on, and both pools now have a **finite
  `maxWait`** — the Jedis default of `-1` meant a starved caller blocked forever. That latent failure
  mode is gone whether or not it was today's bottleneck.

Verified no behaviour change: the live glass-box stream and the persisted replay return the **identical
10 events in the identical order** for the same request; `/trace/{requestId}` still works.

**The bottleneck remains the embeddings sidecar.** It is the next thing to fix, and the routing embedding
should be cached (same query text ⇒ same 384-dim vector) rather than recomputed per request.
