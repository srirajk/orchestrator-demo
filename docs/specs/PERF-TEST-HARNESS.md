# PERF test harness — make the LLM a knob, then measure the gateway

> **Scope: test infrastructure only.** compose + k6 + scripts + fixtures. **No gateway Java changes.**
> Every LLM call site is already env-configurable (`CONDUIT_LLM_<SITE>_BASE_URL`) — that is the World-B
> seam, and this task exists to use it. **If a gateway Java change appears necessary, STOP and report** —
> that is a finding, not a licence.
>
> Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
> `scripts/world-b-check.sh` must stay **CRITICAL 0**; `mvn test` must stay green (**142/142**).
> **Do NOT commit** — the reviewer commits.

---

## 1. Why (measured, not assumed)

We have **never measured the gateway**. Every load run so far was bounded by something we don't own:

- At **25 VUs** Conduit issues **~26 chat-completions/sec** (2–3 per request + one per agentic agent tool
  call). A live run produced **622 × HTTP 429**, then the OpenAI account hit `insufficient_quota` and the
  whole demo stack stopped answering. **The load test was measuring OpenAI's rate limiter — and charging
  us for it.**
- Traced budget of one healthy request (agent = 2 ms): IntentClassifier **~1.80 s** (LLM #1), resolver
  39 ms, agent 2 ms, AnswerSynthesizer **~2.25 s** (LLM #2) → **4.12 s**. Two sequential LLM round trips are
  **~98 % of latency; the gateway's own work is ~50 ms** — never isolated, never stressed.
- The gateway container sees **12 CPUs → 12 carriers** (no `cpus:` / `deploy.resources` in compose), so
  carrier saturation is not reproducible today.

**The rule this harness enforces:** *in a load test, only the system under test is real.*

**The prize:** with a deterministic LLM, `conduit_degraded_rate` becomes a **real gateway metric**. Today a
degraded answer might be OpenAI's fault. Afterwards, **any degradation is our bug**, and the gate means
something.

## 2. Decision: compose two off-the-shelf tools. Do not build a stub.

A hand-rolled stub was written, evaluated, and **deleted**. Everything it would have done exists, and two
things it would *not* have done (mid-stream truncation, malformed SSE) matter to us.

```
  gateway ──► toxiproxy ──► aimock ──► (record only) OpenAI | Ollama
              ▲ network chaos   ▲ LLM semantics
              │ latency, hang   │ SSE, tool_calls, record/replay, strict
```

| Layer | Tool | Job |
|---|---|---|
| LLM semantics | **AIMock** `ghcr.io/copilotkit/aimock` | OpenAI SSE, `toolCalls`, record/replay, strict-miss |
| Network chaos | **Toxiproxy** `ghcr.io/shopify/toxiproxy` | **settable latency**, **true hang**, slow body, reset |

### 2.1 Verified by execution (not by documentation)

Everything below was confirmed by running the containers, on 2026-07-09. Treat vendor blogs as marketing;
these are the facts.

**AIMock** (image `ghcr.io/copilotkit/aimock`, listens on **4010**, entrypoint `node dist/cli.js`):
- ✅ `POST /v1/chat/completions`, non-streaming: correct OpenAI envelope.
- ✅ **Streaming SSE is byte-correct**: role delta → content deltas with `object:"chat.completion.chunk"` →
  `data: [DONE]` **with a space after the colon**. This satisfies hard rule (a). *Verified on the wire.*
- ✅ **Unmatched request → `HTTP 404 {"code":"no_fixture_match"}` by default**, even without `--strict`. It
  fails loudly rather than fabricating. This is the single most important property.
- ✅ `toolCalls` in fixtures (`fixtures/example-tool-call.json`), matched on `toolName` / `userMessage` /
  `toolCallId` — so `EntityExtractor`'s function-calling path is servable.
- ✅ CLI: `--record` (proxy unmatched + save), `--strict`, `--proxy-only`, `--latency <ms>`,
  `--chunk-size <chars>`, `--metrics` (Prometheus at `/metrics`), `--validate-on-load`, `--watch`,
  `--upstream-timeout-ms`, and `--provider-openai` / `--provider-ollama` / others.
- ✅ Chaos config (`llm.chaos`): `dropRate`, `malformedRate`, `disconnectRate` — **mid-stream disconnect and
  malformed SSE**, which we would never have written ourselves and which directly test hard rule (a).
- ✅ Record config: `llm.record.providers.{openai,anthropic}` + `fixturePath`.

**AIMock gaps — the reason Toxiproxy exists in this design:**
- ❌ `--latency` is **inter-SSE-chunk delay only**. There is **no total-latency knob, no independent TTFT,
  no distribution**.
- ❌ **No hang** (accept and never respond).
- ❌ No `429` + `Retry-After` / `x-ratelimit-*` / stateful quota simulation. (A fixture `status` key exists;
  whether it can serve a 429 with headers is **UNVERIFIED** — check before relying on it.)

**Toxiproxy** (`ghcr.io/shopify/toxiproxy`, **v2.12.0**, admin API on **8474**), all verified live:
- ✅ baseline pass-through: **2 ms**.
- ✅ `latency` toxic `{latency: 1800, jitter: 400}` → measured **1.98 s**. A genuinely *settable* latency.
- ✅ `timeout` toxic `{timeout: 0}` → connection **accepted, never answered**; the client hung until its own
  deadline. **A true hang.** This is the only way to prove the infinite-read-timeout bug.
- ✅ Toxics are added/removed at runtime over the admin API, so k6 can toggle them per phase.
- ⚠️ Latency is **fixed + jitter**, not a named distribution (no log-normal). Accept this: sweep a few
  points (e.g. 0 / 600 / 1800 / 4000 ms) rather than pretend to a distribution we cannot configure.

### 2.2 Traps
- **The npm package `aimock` (v0.2.9, published 2023) is a DIFFERENT, unrelated package.** Ours is
  `@copilotkit/aimock` / the `ghcr.io/copilotkit/aimock` image. Do not `npm i aimock`.
- **Maturity risk:** `@copilotkit/aimock` first published **2026-04-03**, now **1.35.1** — *55 versions in
  ~3 months*. That is a young, fast-moving project, and we are making it a **measurement instrument**.
  Therefore: **pin the image by digest**, and keep the §9 canary. If AIMock regresses, our numbers lie.
  Fallback is **MockServer** (mature, LLM mocking + VCR record/replay + redaction) — do not port to it
  unless AIMock actually fails; note it in the report if you do.

## 3. Non-goals

- The PERF fixes themselves — timed `RestClient`s, Jedis pool sizing, async trace persistence, the admission
  gate, the end-to-end deadline + cancellation chain. Those are `PERF-vt-carrier-pinning-FIX.md` Phases 1–3
  and land **after** this harness exists to measure them.
- Any change to gateway Java, agent business logic, or manifests.
- Replacing the real-LLM path. The default stack keeps calling the real provider; this is an **opt-in overlay**.

## 4. Deliverables

1. `docker-compose.perf.yml` — an overlay adding `aimock` + `toxiproxy` and repointing all five LLM call
   sites. **No edit to `docker-compose.yml`.**
2. `mock-agents/aimock/fixtures/` — recorded fixtures, committed (they are the test data).
3. `scripts/perf-record-fixtures.sh` — record once, from OpenAI **or Ollama**.
4. `scripts/perf-toxic.sh` — thin wrapper over the Toxiproxy admin API (`latency`, `hang`, `slow`, `clear`).
5. `scripts/perf-sweep.sh` — runs the matrix, scrapes metrics, prints one markdown table, writes JSON to `docs/perf/`.
6. Updates to `tests/load/coldstart-load-test.js` (§7).
7. `docs/perf/RESULTS.md` — the six runs of §8 with their tables.

## 5. Wiring

### 5.1 Compose overlay
```yaml
services:
  aimock:
    image: ghcr.io/copilotkit/aimock@sha256:<PIN_THIS>   # pin by digest; it moves fast
    command: ["--fixtures","/fixtures","--host","0.0.0.0","--metrics","--strict"]
    volumes: ["./mock-agents/aimock/fixtures:/fixtures"]
    # 4010 is aimock's port. Host 8090 is taken by cadvisor — do not use it.

  toxiproxy:
    image: ghcr.io/shopify/toxiproxy
    ports: ["8474:8474"]      # admin API
    depends_on: [aimock]

  gateway:
    environment:
      CONDUIT_LLM_INTENT_CLASSIFIER_BASE_URL:    http://toxiproxy:9000/v1
      CONDUIT_LLM_ENTITY_EXTRACTOR_BASE_URL:     http://toxiproxy:9000/v1
      CONDUIT_LLM_SYNTHESIZER_BASE_URL:          http://toxiproxy:9000/v1
      CONDUIT_LLM_CLARIFICATION_COMPOSER_BASE_URL: http://toxiproxy:9000/v1
      JAVA_TOOL_OPTIONS: >-
        -Djdk.virtualThreadScheduler.parallelism=${CARRIERS:-12}
        -Djdk.virtualThreadScheduler.maxPoolSize=${CARRIERS:-12}

  servicing-mcp:
    environment:
      SERVICING_AGENT_LLM_BASE_URL: http://toxiproxy:9000/v1
```
The proxy `llm` (listen `0.0.0.0:9000` → upstream `aimock:4010`) is created at startup via the admin API.

**Carrier pinning:** the gateway `Dockerfile` ENTRYPOINT is `java -jar app.jar` with no `JAVA_OPTS`, so
`JAVA_TOOL_OPTIONS` is the clean lever. **Prove the JVM honoured it** (it logs `Picked up JAVA_TOOL_OPTIONS`)
and report the observed carrier count — do not assume.

### 5.2 Recording fixtures
`--record` with `--provider-openai` (fidelity, costs pennies) **or** `--provider-ollama` (free).

- Ollama is installed locally and serves `/v1/chat/completions`. **Use `qwen3`, not `openchat`** —
  `openchat` returns **no `tool_calls`**, so `EntityExtractor` would record garbage. (Verified.)
- **Validation gate:** after recording, assert each fixture parses against its call-site contract —
  the classifier entry is valid JSON with an `intent`; the extractor entry has `toolCalls`; the synthesizer
  entry is non-empty text. A small model can emit malformed JSON, and since `IntentClassifier` now *throws*
  on failure (commit `4b3d9f9`), a bad fixture would surface as a gateway `ERROR` and be misdiagnosed as our
  bug. **If validation fails, re-record from the real provider.**
- **Assert no API keys land in a fixture.** Fail the script if one does.
- Drive: the 3 flat prompts, the 3 DAG prompts, and the clarify path. Recording must be bounded (tens of
  completions) and idempotent.

## 6. The latency + fault model (Toxiproxy admin API)

| Profile | Toxics |
|---|---|
| `gateway-only` | none. Measures the gateway's own cost. |
| `realistic` | `latency {latency: 1800, jitter: 400}` — matches the measured p50. |
| `slow` | `latency {latency: 4000, jitter: 800}` — the measured p95. |
| `hang` | `timeout {timeout: 0}` — accept, never answer. |
| `slow-body` | `bandwidth` (low rate) — separates read timeouts from connect timeouts. |
| `reset` | `reset_peer` |
| `stream-chaos` | AIMock `disconnectRate` / `malformedRate` (mid-stream truncation, malformed SSE) |

Since Toxiproxy offers **fixed latency + jitter, not a distribution**, sweep discrete points. **Do not
report a constant-latency run as if it were a distribution** — constant latency understates queueing.

## 7. Load-test changes (`tests/load/coldstart-load-test.js`)

Keep the content assertions and `conduit_degraded_rate` (added in `9968487`). **Do not weaken them.**
- `__ENV.PROFILE` selects a toxic set; `setup()` applies it via the admin API, `teardown()` clears it.
- **Report TTFT and total separately.** They are currently identical because k6 buffers the body; read the
  stream so TTFT is real. (AIMock's `--latency` sets inter-chunk delay; Toxiproxy's `latency` shifts the
  whole response. Together they let TTFT and total move independently.)
- `handleSummary` → JSON, so the sweep can table it.
- Scrape AIMock's `/metrics` in `teardown()`; **fail the run if any request 404'd with `no_fixture_match`.**
  A miss means the run silently exercised a different path than intended.

`scripts/perf-sweep.sh`: sweep `VUS` ∈ {1, 5, 10, 25, 50, 100, 200} × `PROFILE` × `CARRIERS` ∈ {2, 12}.
After each run scrape `/actuator/prometheus` for `conduit_request_outcome_total`,
`conduit_request_no_data_total`, `conduit_request_partial_total`, `conduit_stage_duration_*`,
`conduit_agent_latency_*`, JVM/GC. Print one markdown table: VUs · profile · carriers · throughput ·
TTFT p50/p95 · total p50/p95 · error % · degraded % · outcomes · **gateway overhead = total − injected latency**.

## 8. The runs to perform and report

1. **Harness self-test.** AIMock + Toxiproxy sustain ≥ 500 concurrent at flat latency. If the harness is the
   bottleneck, every later number is void. (AIMock is Node/event-loop; confirm it doesn't serialize — the
   servicing agent did exactly that, commit `4ceb975`.)
2. **Phase 1 — gateway overhead.** `PROFILE=gateway-only`. Sweep VUs at `CARRIERS=2` and `12`. Total latency
   ≈ gateway overhead. **We predict ~50 ms/request. Confirm or refute.** Find the knee.
3. **Phase 2 — realistic.** `PROFILE=realistic`, then `slow`. Sweep VUs. Report where throughput plateaus,
   where latency knees, and the in-flight count at that point. **The admission-gate ceiling will be derived
   from this number — do not invent a ceiling, measure one.** (Both expert reviewers rejected the original
   spec's `admission=16` as incoherent with a Jedis pool of 8.)
4. **Phase 3 — faults.**
   a. **429 storm.** Serve 429s (via an AIMock fixture `status`, or a sidecar). Quantify **retry
      amplification**: requests in vs upstream calls out. Baseline measured live: **308 requests → 622
      upstream 429s** (`209 × retry 1/2`, `25 × retry 1/3`, `15 × retry 2/3`). We retry *into* a rate limit.
   b. **Hang.** `PROFILE=hang`. **Expect requests to hang.** Record where and for how long. This reproduces
      the untimed-read bug: `EntityExtractor`, `EntityResolver`, `RemoteEmbeddingClient` all use
      `SimpleClientHttpRequestFactory`, whose read timeout defaults to **-1 = infinite**.
      **Do NOT fix it here. Capturing the evidence is the deliverable.**
   c. **Stream chaos.** AIMock `disconnectRate` + `malformedRate`. What does the gateway do when a provider
      truncates mid-token or emits malformed SSE? We have never tested this. Hard rule (a) is at stake.
5. **Phase 4 — agents isolated.** Re-run Phase 2 with `MCP_FAULT_TOOL` set (existing knob) to confirm a
   failing agent yields `outcome{FAILED}` + `conduit_request_no_data_total`, not `ANSWERED` (fixed in `694fafd`).
   *(AIMock ships an MCP example config; whether it can stub our MCP agents is **UNVERIFIED** — investigate,
   don't assume.)*
6. **Canary.** One short run at low VUs against the **real provider**. TTFT/total should sit in the same
   ballpark as `realistic`. **This is what proves the harness isn't lying to us** — mandatory, not optional.

## 9. Acceptance gate

- Replay is deterministic: two identical runs → identical outcome counts, **zero `no_fixture_match`**.
- `PROFILE=gateway-only`, VUs ≤ knee: **`conduit_degraded_rate == 0` and `conduit_error_rate == 0`.** With a
  deterministic LLM there is no excuse for a degraded answer.
- AIMock's SSE is byte-identical in shape to a recorded real stream (role delta, `finish_reason`, `data: [DONE]`).
- No API keys in any committed fixture (asserted).
- The AIMock image is **pinned by digest**; the canary (§8.6) passes.
- Without the overlay the real-LLM path is untouched: `integration-test.sh` 13/13, `e2e-matrix.sh` 12/12.
- `mvn test` 142/142; `world-b-check.sh` CRITICAL 0; **zero gateway Java files changed**.
- `docs/perf/RESULTS.md` contains the six runs with tables.

## 10. Anti-gaming

- **A fixture miss must fail the run.** Never fall back to a synthesised answer. If you find yourself
  generating a plausible LLM response, stop — that is the exact bug class removed in `4b3d9f9` and `694fafd`.
- **Do not weaken `conduit_degraded_rate` or the content assertions to make a profile pass.** If
  `gateway-only` shows degradation, **that is a real gateway bug**: report it, do not tune the threshold.
- **Do not fix the hang in Phase 3b.** Capturing the failure is the deliverable.
- Do not report a fixed-latency run as a distribution.
- Do not touch gateway Java. If the harness seems to require it, STOP and report why.
- Do not trust a README. Every capability claim in §2.1 was verified by running the thing; hold new claims
  to the same bar and mark anything you could not confirm as **UNVERIFIED**.

## 11. Report

The wiring as built (compose overlay, how the toxiproxy `llm` proxy is created, proof the JVM honoured
`JAVA_TOOL_OPTIONS` and the observed carrier count); the recorded fixture set (count, provider used,
validation-gate results, the no-keys assertion); the toxic profiles; the load-test/sweep changes; **the six
runs of §8 with their tables** — the measured **gateway overhead**, the measured **concurrency knee**, the
**retry-amplification ratio**, the **hang evidence**, and the **stream-chaos behaviour**; the acceptance-gate
results; whether AIMock's `status` fixture can serve a 429 with `Retry-After` (currently UNVERIFIED); whether
AIMock can stub MCP (UNVERIFIED). Call out anything that contradicts §1's numbers — those are the reviewer's
measurements and they may be wrong. STOP and report anything unanticipated.
