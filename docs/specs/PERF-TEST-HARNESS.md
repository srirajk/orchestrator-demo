# Codex task — the performance test harness: make the LLM a knob, then measure the gateway

> **Scope: test infrastructure only.** Python + compose + k6 + scripts. **No gateway Java changes.**
> Every LLM call site is already env-configurable (`CONDUIT_LLM_<SITE>_BASE_URL`) — that is the World-B
> seam, and this task exists to use it. **If you believe a gateway Java change is required, STOP and
> report** — that is a finding, not a licence.
>
> Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
> Run `scripts/world-b-check.sh` before/after (must stay **CRITICAL 0**). `mvn test` must stay green
> (142/142). **Do NOT commit** — the reviewer commits. If anything is ambiguous, STOP and report.

---

## 1. Why (measured, not assumed)

We have **never measured the gateway**. Every load run so far was bounded by something else:

- At **25 VUs** Conduit issues **~26 chat-completions/sec** (2–3 per request + one per agentic agent tool
  call). A live run produced **622 × HTTP 429** and then exhausted the OpenAI account's quota
  (`insufficient_quota`), taking the whole demo stack down. **The load test was measuring OpenAI's rate
  limiter, and charging us for it.**
- Traced latency budget of one healthy request (agent = 2 ms): IntentClassifier **~1.80 s** (LLM #1),
  resolver 39 ms, agent 2 ms, AnswerSynthesizer **~2.25 s** (LLM #2) → **4.12 s total**. Two sequential
  LLM round trips are **~98 % of latency; the gateway's own work is ~50 ms.** That 50 ms has never been
  isolated or stress-tested.
- The gateway container sees **12 CPUs → 12 carriers** (no `cpus:`/`deploy.resources` in compose).

**Rule this harness enforces: in a load test, only the system under test is real. Everything we do not
own is stubbed.** (Standard practice — service virtualization / record-replay: WireMock, Hoverfly, VCR,
LiteLLM `mock_response`, Toxiproxy for latency.)

**The prize:** with a deterministic LLM, `conduit_degraded_rate` becomes a *real gateway metric*. Today a
degraded answer might be OpenAI's fault. After this, **any degradation is our bug.**

## 2. Non-goals (do NOT build these here)

- The PERF fixes themselves — timed `RestClient`s, Jedis pool sizing, async trace persistence, the
  admission gate, the end-to-end deadline + cancellation chain. Those are `PERF-vt-carrier-pinning-FIX.md`
  Phases 1–3 and land **after** this harness exists to measure them.
- Any change to gateway Java, agent business logic, or manifests.
- Replacing the real-LLM path. The default stack must keep calling the real provider; the stub is an
  **opt-in overlay**.

## 3. Starting point (committed scaffold — keep, harden, or replace)

Committed alongside this spec as a sketch by the reviewer. You may keep and finish it, or rewrite it if you
can justify better. It must in any case satisfy §5–§8. It is **not** yet exercised: it has never recorded a
cassette, has no `/configure`, no fault injection, no latency distribution, and is untested.

- `mock-agents/stub-llm/server.py` — record/replay cassette proxy (FastAPI), ~180 lines.
- `mock-agents/stub-llm/{Dockerfile,requirements.txt}`
- `mock-agents/stub-llm/cassettes/.gitignore`
- `docker-compose.perf.yml` — overlay repointing all five call sites at the stub. Host port **8092**
  (8090 is taken by cadvisor); in-network the stub stays `stub-llm:8090`.

**Design reference worth reading:** `~/projects/poc-jd/cadence/docker/fake_upstream/app.py` — a hermetic
fake for all external deps. Three ideas to steal, and the reasons:
1. **`POST /configure`** — per-test overrides (latency, error probability) without restarting. 
2. **A catch-all route that fails loudly** (it returns 400 on any unregistered path) — a new outbound call
   must never silently escape to the internet.
3. **Fault injection as a first-class knob** (`error_probability`).
Do **not** copy its OpenAI route: it is non-streaming, has no `tool_calls`, and no latency model.

## 4. Why record/replay rather than hand-written fixtures

The four gateway call sites expect four different shapes, and the fields the classifier emits are derived
from the **domain manifests** (`entity_type.extract_as`). Hand-written fixtures would bake domain knowledge
into the fake and rot silently when a manifest changes. Recording a real response once and replaying it
byte-for-byte is valid by construction, for every call site, forever — and a cassette **miss must fail
loudly** rather than fabricate. (Fabricating a plausible response is precisely the class of bug this
codebase spent a week removing: see `IntentClassifier`'s deleted `fetchDataFallback()`.)

## 5. The stub — required behaviour

### 5.1 Call-site contracts it must satisfy
Dispatch on **request shape**, never on model name (call sites must stay swappable):

| Call site | Request marker | Response it must produce |
|---|---|---|
| `IntentClassifier` | `response_format:{type:json_object}`, `stream:false` | `choices[0].message.content` = JSON string |
| `EntityExtractor` | `tools[]` present (function calling) | `choices[0].message.tool_calls[0].function.arguments` |
| `AnswerSynthesizer` | `stream:true` | OpenAI SSE deltas + `data: [DONE]` |
| `ClarificationComposer` | plain, `stream:false` | `choices[0].message.content` |
| Servicing agents | OpenAI Agents SDK via `AsyncOpenAI` | whatever the SDK sends — must round-trip |

The servicing agents call through the **same** endpoint (`SERVICING_AGENT_LLM_BASE_URL`). If the Agents SDK
uses `/responses` rather than `/chat/completions`, support both (cadence's fake converts between them —
read `_chat_to_responses_format`). **Verify empirically which it uses; do not assume.**

### 5.2 Modes (`STUB_MODE`)
- `record` — forward upstream, return the real response, persist it to the cassette.
- `replay` — serve from the cassette. **A miss is a hard `503` with `code:"cassette_miss"`, logged with the
  key and model.** Never synthesise.
- `passthru` — forward, persist nothing (debugging).

### 5.3 Cassette
- Keyed by a SHA-256 over the request's **semantic** fields only: `model`, `stream`, `messages[].{role,content}`,
  `tools`, `response_format`. Exclude ids/timestamps so replay hits.
- Streaming responses store the **concatenated content**; replay re-chunks it into OpenAI SSE deltas.
- JSON on disk, stable key order, committed to git (it is the fixture set). **It must contain no API keys**
  — assert this in a test.
- Provide `scripts/perf-record-cassette.sh`: brings the stack up in `record` mode, drives every prompt the
  load test and the e2e matrix use (flat + DAG + the clarify path), then reports the entry count. Recording
  must be **cheap and bounded** (tens of completions, not thousands) and idempotent.

### 5.4 Latency model — this is the point of the whole exercise
`POST /configure` (and env defaults) must control:
- `latency_ms` — applied before a non-streaming response.
- `latency_p50_ms` / `latency_p95_ms` — sample a realistic distribution (log-normal is fine) rather than a
  constant. **Constant latency is a lie**: it collapses queueing behaviour.
- `jitter_ms`.
- For **streams**: a `ttft_ms` (time to first token) and an inter-chunk delay, so the total spreads across
  chunks. TTFT and total must be independently settable — the gateway's SSE contract cares about the first
  byte, the load test measures both.

**Default for realism:** `ttft_ms≈600`, `latency_p50_ms=1800`, `latency_p95_ms=4000` (measured from the real
system). **Zero latency is a special profile, not the default.**

### 5.5 Fault injection (`/configure`)
- `error_probability` + `error_status` (429/500/503) with an optional `Retry-After` header. The gateway
  currently **retry-amplifies** into a 429 storm (308 requests produced 622 × 429: `209 × retry 1/2`,
  `25 × retry 1/3`, `15 × retry 2/3`) — this knob is how we prove that, and later prove a fix.
- `hang_probability` — accept the request and **never respond**. This is the only way to prove the untimed
  `RestTemplate` read-timeout bug (`EntityExtractor`, `EntityResolver`, `RemoteEmbeddingClient` all use
  `SimpleClientHttpRequestFactory`, whose read timeout defaults to **-1 = infinite**), and later to prove the
  timeouts, the end-to-end deadline, and the cancellation chain.
- `slow_body_probability` — headers fast, body trickled (tests read timeouts distinctly from connect timeouts).
- `POST /configure/reset` restores defaults. `GET /health` reports mode, entry count, hit/miss counters, and
  the active latency/fault config.

### 5.6 Non-negotiables
- The stub must **never be the bottleneck**: an async event loop, no blocking calls, no sync handlers.
  (The servicing agent serialized precisely because a *sync* handler blocked the uvicorn event loop —
  see `mock-agents/servicing/*/tool.py` and commit `4ceb975`. Do not repeat it. A load test must confirm the
  stub sustains ≥ 500 concurrent requests at a flat latency.)
- Its SSE output must be **byte-correct OpenAI shape** — role delta, content deltas, `finish_reason:"stop"`,
  then `data: [DONE]` (note the space after the colon). Hard rule (a). Assert it.
- An **unregistered path returns a loud error**, never a silent 200.

## 6. Compose overlay

`docker-compose.perf.yml` adds `stub-llm` and repoints, with **no edit to `docker-compose.yml`**:

```
CONDUIT_LLM_{INTENT_CLASSIFIER,ENTITY_EXTRACTOR,SYNTHESIZER,CLARIFICATION_COMPOSER}_BASE_URL=http://stub-llm:8090/v1
SERVICING_AGENT_LLM_BASE_URL=http://stub-llm:8090/v1
```

Also add a way to pin the gateway's **carrier count** for reproducible saturation, since the container
currently sees all 12 host CPUs:
`JAVA_TOOL_OPTIONS=-Djdk.virtualThreadScheduler.parallelism=${CARRIERS:-12} -Djdk.virtualThreadScheduler.maxPoolSize=${CARRIERS:-12}`
(the gateway `Dockerfile` ENTRYPOINT is `java -jar app.jar` with no `JAVA_OPTS`, so `JAVA_TOOL_OPTIONS` is
the clean lever — verify the JVM logs it picked it up). Optionally `cpus:` as a cross-check.

## 7. Load-test changes (`tests/load/coldstart-load-test.js`)

Keep the content assertions and `conduit_degraded_rate` (added in `9968487`) — do **not** weaken them.
Add:
- `__ENV.PROFILE` (`gateway-only` | `realistic` | `fault`) which drives the stub via `/configure` in `setup()`.
- Report **TTFT and total separately** (they are currently identical because k6 buffers the body). Use a
  streaming read so TTFT is real.
- Emit a machine-readable summary (`handleSummary` → JSON) so the sweep can table it.
- A `stub_cassette_miss` counter: scrape `/health` in `teardown()` and **fail the run if misses > 0** — a
  miss means the run silently exercised a different path than intended.

New `scripts/perf-sweep.sh`:
- Sweeps `VUS` (1, 5, 10, 25, 50, 100, 200) × `PROFILE` × `CARRIERS` (2, 12).
- After each run, scrapes `/actuator/prometheus` for `conduit_request_outcome_total`,
  `conduit_request_no_data_total`, `conduit_request_partial_total`, `conduit_stage_duration_*`,
  `conduit_agent_latency_*`, JVM/GC, and any in-flight/saturation gauge.
- Prints one markdown table: VUs · profile · carriers · throughput · TTFT p50/p95 · total p50/p95 ·
  error% · degraded% · outcomes · **gateway overhead = total − stub latency**.
- Writes raw JSON under `docs/perf/` for diffing across builds.

## 8. The runs to perform and report

1. **Stub self-test.** The stub alone sustains ≥ 500 concurrent at flat latency without its own queueing.
   If the stub is the bottleneck, every later number is void.
2. **Phase 1 — gateway overhead.** `PROFILE=gateway-only` (latency 0). Sweep VUs, `CARRIERS=2` and `12`.
   Total latency now ≈ gateway overhead. **We predict ~50 ms/request; confirm or refute.** Find the knee.
3. **Phase 2 — realistic.** `PROFILE=realistic` (ttft 600 ms, p50 1.8 s, p95 4 s). Sweep VUs. Report where
   throughput plateaus, where latency knees, and what in-flight count it corresponds to. This is the number
   the admission gate will later be derived from — **do not invent a ceiling; measure one.**
4. **Phase 3 — faults.** `PROFILE=fault`: (a) `error_probability=0.3, error_status=429` — quantify retry
   amplification (requests in vs upstream calls out). (b) `hang_probability=0.1` — **expect requests to hang**;
   record exactly where and for how long. This is the reproduction of the untimed-read bug. Do **not** fix it
   here; capture the evidence.
5. **Phase 4 — agents isolated.** Re-run Phase 2 with `MCP_FAULT_TOOL` set (the existing knob) to confirm a
   failing agent now yields `outcome{FAILED}` + `conduit_request_no_data_total`, not `ANSWERED` (fixed in
   `694fafd`).
6. **Canary.** One short run at low VUs against the **real** provider, to prove the stub is not lying:
   TTFT/total distributions should be in the same ballpark as the `realistic` profile.

## 9. Acceptance gate

- Cassette replay is deterministic: two identical runs produce identical outcome counts, **zero cassette misses**.
- `PROFILE=gateway-only`, VUs ≤ knee: **`conduit_degraded_rate == 0` and `conduit_error_rate == 0`.** With a
  deterministic LLM there is no excuse for a degraded answer. (This is the gate that makes the metric mean
  something.)
- The full stack still works **without** the overlay (real LLM path untouched): `integration-test.sh` 13/13,
  `e2e-matrix.sh` 12/12.
- Stub SSE is byte-identical in shape to the real provider's: same role delta, `finish_reason`, `data: [DONE]`.
  Assert against a recorded real stream.
- The cassette contains **no API keys** (assert).
- `mvn test` 142/142; `scripts/world-b-check.sh` CRITICAL 0; **zero gateway Java files changed**.
- The sweep tables for Phases 1–4 are produced and committed under `docs/perf/`.

## 10. Anti-gaming

- A cassette miss must **fail the run**, never fall back to a synthesised answer. If you find yourself
  generating a plausible LLM response, stop: that is the exact bug class we removed.
- Do not weaken `conduit_degraded_rate` or the content assertions to make a profile pass. If `gateway-only`
  shows degradation, **that is a real gateway bug** — report it, do not tune the threshold.
- Do not set `latency=0` as the "realistic" default to make throughput look good.
- Do not "fix" the hang in Phase 3(b). Capturing the failure is the deliverable.
- Do not touch gateway Java. If the harness seems to require it, STOP and report why.

## 11. Report

The stub as built (modes, `/configure` schema, latency + fault model, how call sites are dispatched, whether
the Agents SDK needed `/responses`); the cassette (entry count, size, how recorded, key-miss behaviour, the
no-keys assertion); the compose overlay + how carriers are pinned and the proof the JVM honoured it; the
load-test/sweep changes; **the six runs of §8 with their tables**, including the measured gateway overhead,
the measured concurrency knee, the retry-amplification ratio, and the hang evidence; the acceptance-gate
results. Call out anything that contradicts §1's numbers — those are the reviewer's measurements and they
may be wrong. STOP and report anything unanticipated.
