# PERF FIX — gateway virtual-thread carrier pinning / concurrency livelock

> **Root-caused by two independent expert reviews (Fable = perf/Java concurrency; Opus = Java/perf
> verifier), both against shipped code.** This is the executable fix spec. Companion finding doc:
> `PERF-vt-carrier-pinning.md` (the original load-test discovery). GATEWAY code + infra + runtime image.
> World-B unaffected (all generic) — run `scripts/world-b-check.sh` before/after, CRITICAL 0. Do NOT commit
> (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
> **Built in phases; diagnose empirically FIRST, do not guess-fix.** If ambiguous, STOP and report.

## The finding (from a live cold-start + load test)
Cold start is clean (18/0 registry, first query correct). Under concurrency the gateway serves ~20 flat
requests fine, then at **25 concurrent enters an unrecoverable livelock**: CPU pinned ~250%, its own health
check unresponsive for 7+ min with zero client traffic, only `docker compose restart gateway` recovers it.
Thread dump signature: `ForkJoinPool-1-worker` carriers permanently "Carrying virtual thread" with shallow
continuation stacks → **virtual-thread carrier pinning / carrier exhaustion**, not a draining backlog.

## Root cause — confirmed against code (both reviewers)
The VT architecture is **conceptually right** (fan-out on VTs, harness `Semaphore` bulkheads that park, zero
`synchronized` in gateway source, the raw `java.net.http.HttpClient` LLM sites are VT-safe and *timed*). The
livelock is a handful of **legacy blocking clients that pin carriers on JDK 21** + **no admission control** +
**no end-to-end deadline** + a **synchronous per-request Redis write amplification** that Fable's first pass
underweighted.

### The pins (carrier-pinning — the primary bug)
1. **`RestTemplate` → `SimpleClientHttpRequestFactory` → `HttpURLConnection`.** `HttpURLConnection.getInputStream0()`
   is `synchronized`; blocking the socket read *inside the monitor* pins the carrier for the entire round trip
   (JDK 21, pre-JEP-491). **Three sites, all on the request hot path, all with infinite read timeout**
   (`SimpleClientHttpRequestFactory` default = -1):
   - `EntityExtractor.java:115-121` → Z.AI GLM extraction (seconds), pipeline stage 1. Uses the **shared bean**
     (`config/AppConfig.java:26-29`, `new RestTemplate()`).
   - `EntityResolver.java:88-89` → CRM resolve endpoint, pipeline stage 2. Uses the **shared bean**.
   - `RemoteEmbeddingClient.java:36,63-67` → embeddings sidecar, fires on **every routing search**
     (`registry/index/VectorIndex.java:164`). **Constructs its OWN `new RestTemplate()` at :36 — NOT the shared
     bean.** ⚠ A bean-only swap silently misses this one (the routing-path client). Active because
     `CONDUIT_EMBEDDING_PROVIDER: remote` (`docker-compose.yml:164`).
   - **Infinite read timeout is the reason it never self-heals** — a stalled peer pins a carrier *forever*.
2. **The multiplier:** carriers = host CPUs; the container has no cpu pin / no `jdk.virtualThreadScheduler.parallelism`.
   On a ~4-vCPU demo host that is **~4 carriers** — so **~4 concurrent extraction calls saturate every carrier**.
   That is why it collapses *well under* 25, not at 25.
3. **Client-facing SSE response emitter** (`ChatCompletionsController.java:130`): `ResponseBodyEmitter.send` is
   `synchronized` in spring-webmvc → each streamed chunk pins a carrier for the socket-write duration. Per-request
   (no cross-request contention) but universal. Real, secondary.

### NOT the load-test driver, but bites the live demo
4. **Glass-box `SseEmitter` bus** (`TraceEventPublisher.java:93`, `new SseEmitter(Long.MAX_VALUE)`, 45 publish
   sites in `ChatService`): the `synchronized` `send` pins, and shared emitters make requests contend on one
   monitor. **BUT `publish()` early-returns when no panel is subscribed (`:127`)** — so in a headless load test
   it never fires. Fixed for free by JDK 25; do not treat it as the benchmark's cause.

### The co-driver Fable MISSED (Opus) — the real headless-test bottleneck
5. **`TraceEventPublisher.publish()` calls `storage.save()` UNCONDITIONALLY at `:125`, BEFORE the
   `subscribers.isEmpty()` early-return.** `RedisTraceStorageAdapter.save()` (`:44-64`) does **up to 4 synchronous
   Jedis round-trips** (`rpush`/`expire`/`zadd`/`expire`). With **45 publish sites/request** ≈ **90-180 blocking
   Redis ops/request**, all borrowing from **one shared `JedisPooled` of 8** (`RedisConfig.java:20`, defaults
   `maxTotal=8, maxWait=-1` → blocks forever), also shared with routing `FT.SEARCH` + registry. **This fires on
   every request whether or not a panel is attached** — i.e. it IS present in the headless 25-VU test where the
   SSE pin (4) is dormant. Jedis parks (not a carrier pin) → this is **pool starvation / throughput ceiling**,
   the likely co-driver of the livelock the SSE framing obscured.

### No graceful degradation (both reviewers)
6. No global admission gate (bulkhead is **per-agent** only, `AgentHarness.java:179-182`); nothing around
   `ChatService.handleChat`.
7. No end-to-end deadline / cancellation: `ChatCompletionsController.java:139` discards the pipeline
   `CompletableFuture`; the 120s emitter timeout completes the emitter but **never cancels the running VT** →
   orphaned pipelines accumulate under load.

## EXECUTION ORDER (updated — 25-first-as-diagnostic)
Per decision: lead with the JDK 25 upgrade as an **isolated, reversible step**, then let it act as the
diagnostic — on 25, carrier pinning is impossible (JEP 491), so whatever still fails under load is the
un-maskable remainder (infinite-hang, Jedis-pool starvation, no admission, orphaned futures) that MUST be
fixed in code. **The JDK bump is NOT the fix — it strips pinning away so we measure what's left.**
- **Step 1 = Phase 4 (JDK 25 upgrade), done first + isolated.** Gate it green before any code change.
- **Step 2 = re-measure on 25** (the load test becomes the real Phase 0 — pins should be ~0; the remaining
  ceiling names the code work).
- **Step 3 = Phases 1-3 code fixes** (timed clients, async trace persistence + Jedis pool, admission gate
  [+ JWT/rate-limit on the chat endpoint = closes security finding #1], e2e deadline + cancellation).
- **Step 4 = the acceptance gate.**
The phase sections below keep their original numbering; execute in the Step order above.

## Phase 0 — EMPIRICAL CONFIRMATION (name the culprit before touching code — now folded into Step 2)
Run the gateway with `-Djdk.tracePinnedThreads=full` **and** a JFR recording capturing `jdk.VirtualThreadPinned`,
reproduce with the load test at **N > carrier count** (pin `-Djdk.virtualThreadScheduler.parallelism` low, e.g.
2-4, to force exhaustion on any host), and read off the exact pinning stacks. **Expected:**
`HttpURLConnection.getInputStream0` under `EntityExtractor` / `EntityResolver` / `RemoteEmbeddingClient`. Also
capture `jcmd <pid> Thread.dump_to_file -format=json` (SIGQUIT can't show mounted VT frames — that shallow-stack
artifact is why the original dump looked like a tiny loop). Record pool-wait via Jedis pool metrics to confirm
bottleneck 5. **Gate: a pinning trace naming the three RestTemplate stacks; do not proceed to fix on hypothesis.**

## Phase 1 — kill the pins + the infinite hang (SHIPS ON ANY JDK — highest leverage)
Replace **all three** untimed `RestTemplate` sites with a **VT-safe client carrying explicit connect + read
timeouts** (RestClient over the JDK `HttpClient` factory, or `RestTemplate` with a timed `JdkClientHttpRequestFactory`
— the point is *parks-not-pins* + *finite read timeout*). Include `RemoteEmbeddingClient`'s own instance (`:36`),
not just the shared bean. Timeouts from config (`@Value`/env), sane defaults (connect ~2-5s; read tuned per
call — LLM extraction needs a larger read budget than embeddings/CRM). **This is the single highest-leverage
change: it removes the pin AND the never-self-heals infinite hang. Untimed I/O is a bug on JDK 25 too.**
- While here: the safe `agentRestClient` (`AppConfig.java:40-42`) also sets **no read timeout** — add one bounded
  by the harness SLA so a half-dead agent can't hold a pipeline VT past its deadline.

## Phase 2 — take the trace persistence off the request thread
Move `RedisTraceStorageAdapter.save()` off the pipeline VT: `publish()` enqueues onto a **bounded MPSC queue**
(drop-oldest on overflow, with a dropped-count metric — never block the request to persist a trace event),
drained by a small dedicated VT/executor pool that does the Redis writes. Removes the ~45 synchronous Redis
round-trips/request from the hot path. **This is the correctly-scoped "decouple the trace bus" — do NOT
re-architect the SSE pub/sub (over-building; the SSE pin dies with JDK 25 and is guarded by the empty-subscriber
check).**
- Configure `JedisPooled`: raise `maxTotal` (~32), set a finite `maxWait` (~200ms → fail fast, not block
  forever), from config.

## Phase 3 — backpressure + self-heal (SHIPS REGARDLESS OF JDK)
- **Global admission gate** at ingress (Resilience4j `Bulkhead` around `handleChat`): reject over-capacity with an
  honest **`503` + `Retry-After`** in OpenAI/SSE shape (must not break the byte-contract, hard-rule a). **Derive
  the ceiling from measured capacity AFTER Phases 1-2 (esp. the raised Jedis pool) — do NOT hardcode 16; that
  number is incoherent with a pool of 8 and both reviewers flagged it as the most-likely-over-built literal.**
- **One end-to-end deadline per request** (~90s, from config), threaded through the pipeline the same way
  `callerToken`/`otelContext` already are; every stage checks remaining budget; a shared retry budget.
- **Cancellation chain:** keep the pipeline `CompletableFuture` and cancel it from
  `emitter.onTimeout/onError/onCompletion` so a client disconnect actually stops the work (no orphaned executions).
- Self-heal: it must recover once load drops with **no `docker restart`**.

## Phase 4 — JDK 25 (defense-in-depth; kills the whole pin class incl. the live-demo SSE pin)
Upgrade the runtime image to **`eclipse-temurin:25-jre-alpine`** — JEP 491 makes `synchronized` no longer pin,
eliminating the entire pin class (RestTemplate reads + both SseEmitters). The stack table already says "25
preferred." **Gate BEFORE bumping:** confirm the pinned Spring Boot 3.5.x patch officially supports JDK 25 and
the temurin 25 alpine JRE exists; compiling to `maven.compiler.target=21` on a 25 JRE is fine. **Not a substitute
for Phases 1-3** — JDK 25 fixes none of: the infinite-read hang, the Jedis-pool ceiling, admission control, or
orphaned futures. Without them, "livelock via pinning" just becomes "slow hang on infinite reads + pool
starvation."

## GATE — the acceptance test (assert MORE than "survives 25 VUs")
Re-run the cold-start load test (`tests/load/coldstart-load-test.js`) on a clean rebuild, at **N > carrier
count with parallelism pinned low** so exhaustion is actually reproducible (not masked on a fat host), on BOTH
flat and DAG paths. Assert:
1. **Zero pins:** JFR `jdk.VirtualThreadPinned` events == 0 on the routing/extraction path during the run (the
   direct proof, independent of throughput).
2. **Fail-fast, no starvation:** with a deliberately black-holed dependency (Z.AI or embeddings sidecar never
   responds), requests fail at the configured read timeout — they do NOT hang — and healthy sibling requests
   still complete.
3. **Sheds, doesn't wedge:** over-admission returns `503 + Retry-After` (correct SSE/OpenAI shape), not a hang;
   the admission ceiling is consistent with the Redis pool size.
4. **Cancellation works:** client disconnect / emitter timeout actually cancels the pipeline — assert agent calls
   cease after disconnect (no orphaned execution continues).
5. **Self-heals:** after the load stops, the gateway returns to healthy with no `docker restart`.
6. `mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; the 3 verticals still answer live; SSE byte-shape
   unchanged (assert — Phases 1/3 touch the response path).
Capture the new load table + the pinning trace showing zero hot-path pins.

## PACKAGE PLACEMENT (mandatory — read `GATEWAY-PACKAGE-STRUCTURE.md` before writing any Java)
The gateway is **package-by-feature / hexagonal** (`ai.conduit.gateway`): `api/v1/*` → `domain/*` →
`adapter/{http,mcp}` → `infrastructure/*` → `config/` (wiring only). **Adapters depend on domain; domain never
depends on adapters. `config/` wires, it does not think. `domain/` stays framework-light. No business-domain
names anywhere (World-B).** Do NOT restructure existing packages as part of this fix — the three known
structural warts (`admin/` controllers, flat `insights/`, controller placement) are a SEPARATE cleanup task.

| Change | Package |
|---|---|
| Timed `RestClient` builder bean + timeout properties | `config/` |
| Fix `RemoteEmbeddingClient`'s own `new RestTemplate()` (`:36`) | `registry/index/` (in place) |
| Fix `EntityExtractor` / `EntityResolver` clients | `synthesis/input/` (in place) |
| `agentRestClient` read timeout | `config/` |
| Async trace persistence (bounded queue + drain workers) | `infrastructure/telemetry/` |
| `JedisPooled` sizing (`maxTotal`, finite `maxWait`) | `infrastructure/redis/` + properties |
| Admission-control policy (Resilience4j bulkhead) | `infrastructure/resilience/` **(the only new package)** |
| Admission filter / 503 + `Retry-After` shaping | `infrastructure/web/` |
| End-to-end deadline | extend existing per-request context (`domain/auth/RequestContext`) — no parallel concept |
| Cancellation chain | `api/v1/chat/ChatCompletionsController` + `orchestration/executor/` |

All timeouts, pool sizes, deadlines, and the admission ceiling are **config properties** (`@Value` /
`application.yml` / env) — never `private static final` constants. Prefer package-private where possible.

## Anti-gaming / constraints
- Diagnose empirically (Phase 0) before fixing — name the pin, don't assume. Timeouts/admission/deadline are
  from config, never hardcoded constants. The admission number is DERIVED from measured capacity, not a literal.
  Do not weaken the acceptance assertions to pass. World-B clean. Do NOT commit.

## Report
Phase 0 pinning trace (the named stacks); files changed per phase; the timed-client swap (all three sites incl.
`RemoteEmbeddingClient`'s own instance + `agentRestClient` timeout); the async trace-persistence + Jedis pool
config; the admission gate + the DERIVED ceiling (show the capacity measurement) + deadline + cancellation; the
JDK 25 gate check + bump; the acceptance evidence (JFR zero-pins, black-hole fail-fast, 503-not-hang, cancel-on-
disconnect, self-heal) + the new load table; mvn/World-B/SSE results. STOP and report anything unanticipated.
