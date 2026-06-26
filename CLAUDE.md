# CLAUDE.md — Build Instructions for This Repository

> **Read this entire file before writing any code.** This is the **operating & control
> doc** — it tells you *how* to work. It is **not** the requirements: those live in
> `docs/master-build-plan-consolidated.md`. The build is split into **phases** in `phases/`,
> which you execute in a **loop, pausing for a human test at the end of each phase** (§8).
> When in doubt, follow this file.

---

## 1. What you are building

A custom **enterprise AI gateway** for an enterprise bank. A user types one
plain-English question into a chat UI; the gateway figures out which specialist agents the
question needs across two business domains, calls them in parallel over their native
protocols, enforces that the user is allowed to see the data, merges the results into one
streamed answer, and shows the whole decision live in a side panel.

It is an **orchestration backend** with a thin OpenAI-compatible front door. The chat UI
(LibreChat) is run as-is via config — you do **not** build a UI and you do **not** fork
LibreChat's code.

**The demo target:** the hero prompt (see `docs/agent-catalog.md`) fans out across **HTTP
and MCP** agents in parallel, streams one synthesized answer into a Meridian-branded
LibreChat, shows routing + per-agent latency + the entitlement decision in a glass-box
panel, and still answers when an agent is killed mid-request.

---

## 2. Locked stack — no substitutions

| Concern | Use |
|---|---|
| Gateway runtime | **Java 21+** (25 preferred — see §2.1), Spring Boot **3.5.x**, **virtual threads ON** |
| Mock agents | **Python — FastAPI** (Wealth HTTP; auto-serves OpenAPI) + **Python MCP SDK / FastMCP** (Asset Servicing MCP server). The gateway stays Java; only these stand-ins are Python |
| Routing + state | Redis Stack (RediSearch HNSW vector index + RedisJSON) |
| Embeddings | DJL + `all-MiniLM-L6-v2` (in-JVM, 384-dim) behind an `EmbeddingClient` interface |
| LLM (input extraction + answer synthesis) | **Z.AI GLM** (OpenAI-compatible): base `https://api.z.ai/api/paas/v4`, default model `glm-4.6`; function-calling + structured output + streaming. Behind the `LLMClient` interface (provider-swappable) |
| Resilience | Resilience4j |
| Authorization | Cerbos PDP (sidecar) via the Cerbos Java SDK |
| Telemetry | Micrometer + OpenTelemetry; the **glass-box** panel is the demo's live view. Grafana/Prometheus run **only in the `scale` compose profile** (M14) |
| Chat UI | LibreChat (custom-endpoint config + cosmetic rebrand only) |
| Load test | k6 |
| Orchestration | docker-compose |

Do **not** introduce Python on the request path, LangGraph/LangChain, or an external agent
gateway. Build the protocol wrappers in-JVM behind a `ProtocolAdapter` interface.

### 2.1 Exact versions & prerequisites

Pin these. If a newer stable patch exists, use it; do not change majors.

| Thing | Version |
|---|---|
| JDK | **Temurin/OpenJDK 21 or newer.** 21 is fine for the demo (virtual threads are GA). **25 is preferred** for JEP 491, which stops `synchronized` blocks from pinning virtual threads — that mainly bites under load (the M14 scale test), not in normal use. Target whatever the build host has; note the choice in `BUILD_REPORT.md`. No `--enable-preview` — use `CompletableFuture`. On 25 use **Scoped Values** for request context; on 21, `ThreadLocal` is acceptable for the demo |
| Build tool | **Maven 3.9+** (compiler plugin **3.14.1+** only if building with JDK 25); one multi-module build |
| Spring Boot | **3.5.x** (mature; supports Java 21 through 25); Spring Framework 6.2+ |
| MCP client | **Spring AI 1.0.x MCP client starter**; if unavailable, the official **MCP Java SDK** (`io.modelcontextprotocol.sdk`) |
| Resilience4j | **2.x** (`resilience4j-spring-boot3`) |
| OpenAPI parsing | `io.swagger.parser.v3:swagger-parser` (v2) |
| Embeddings | Default **DJL 0.30.x** + `all-MiniLM-L6-v2` (in-JVM, 384-dim, offline, no extra key). DJL pulls a native engine (heavier image) — **if that causes container friction, fall back to an OpenAI-compatible embeddings endpoint** behind the same `EmbeddingClient` interface. Precompute example-prompt vectors at registration so runtime embeds only the user prompt (trivial load for 9 agents) |
| LLM | **Z.AI GLM**, OpenAI-compatible. Base URL `https://api.z.ai/api/paas/v4`, completions path `/chat/completions`; `Authorization: Bearer $ZAI_API_KEY`. Default model **`glm-4.6`** (200K ctx, function-calling + structured output + streaming). Free dev options: `glm-4.7-flash`, `glm-4.5-flash`. Use **Spring AI's OpenAI client with a custom `base-url` + `completions-path`** (or a plain HTTP client) — do **not** use the default OpenAI `/v1` path |
| Redis | **`redis/redis-stack-server:7.4.x`** (RediSearch + RedisJSON; HNSW via `FT.CREATE`) |
| Authorization | **`ghcr.io/cerbos/cerbos:latest`** PDP (ports 3592 HTTP / 3593 gRPC) + `dev.cerbos:cerbos-sdk-java` |
| Telemetry | Micrometer + OTel in-code (feeds the glass-box). `otel/opentelemetry-collector` + `grafana/grafana` + `prom/prometheus` run **only under the `scale` compose profile** for the M14 graph — not in the everyday demo |
| Chat UI | LibreChat via its official docker image + **MongoDB** + **Meilisearch** |
| E2E UI tests | **`@playwright/test`** (Node 20+, Chromium, headless) |
| Load test | **`grafana/k6`** |
| Orchestration | **Docker Compose v2** |
| Mock agents (HTTP) | **Python 3.11+, FastAPI + uvicorn** — FastAPI auto-serves the OpenAPI spec at `/openapi.json` (the registry introspects it directly) |
| Mock agents (MCP) | **Python MCP SDK (`mcp` / FastMCP), SSE transport** |

**Build host prerequisites** (verify before starting; if missing, install or note in the
report): Docker + Compose v2, **JDK 21+** (21 fine; 25 preferred), Maven 3.9+, **Python
3.11+ (with `uv` or `pip`)**, Node 20 + npm, and — critically — **open outbound network** to
Maven Central (`repo1.maven.org`), Docker Hub / GHCR, PyPI, npm, the embedding-model
download, and the **Z.AI API**. If the run environment's egress policy blocks any of these
(some sandboxes allow only PyPI + npm), the build **cannot proceed** — run somewhere with
full egress (see note below).

> **Network allowlist (if running in a restricted/cloud sandbox).** The default "Trusted"
> level usually allows only npm + PyPI. Raise the environment's network level (Custom/Full)
> and allow these outbound domains, or the build stalls at the first Maven/Docker step:
> - **Maven Central:** `repo1.maven.org`, `repo.maven.apache.org`
> - **Docker Hub:** `registry-1.docker.io`, `auth.docker.io`, `index.docker.io`, `production.cloudflare.docker.com`
> - **GHCR** (LibreChat, Cerbos images): `ghcr.io`, `pkg-containers.githubusercontent.com`
> - **Embedding model** (DJL/all-MiniLM): `huggingface.co`, `*.huggingface.co` — or switch to the hosted-embeddings fallback to avoid this
> - **Gateway LLM:** `api.z.ai`
> - **PyPI / npm:** `pypi.org`, `files.pythonhosted.org`, `registry.npmjs.org` (default-allowed under Trusted)
>
> **Caveat — this stack is Docker-heavy.** Even with domains allowlisted, the gateway and
> other services run *inside* their own containers, and container egress (e.g. the gateway
> calling `api.z.ai`) does not automatically traverse the sandbox proxy, and some Node-fetch
> based clients (LibreChat) can break behind it. A multi-container `docker compose` stack is
> far more reliable on a host with **native Docker + open egress** (local machine or a VPS)
> than inside a restricted cloud sandbox.

> **LLM provider note:** where any `/docs` spec says "Claude" or "Anthropic" as the
> *gateway's* runtime LLM, substitute **Z.AI GLM** per this file — the `LLMClient` interface
> is provider-agnostic and **this file is authoritative**. (This is separate from whatever
> model Claude Code itself runs on while building.)

---

## 3. Repository structure — create this

```
/
├── CLAUDE.md                 (this file)
├── README.md                 (write: human overview + how to run)
├── docker-compose.yml        (write: all services)
├── docs/                     (specs — already provided, read as referenced)
├── phases/                   (PHASE-1.md … PHASE-7.md — your loop unit; read one at a time)
├── BUILD_REPORT.md           (you create + maintain: per-phase status & the human test steps)
├── gateway/                  (Spring Boot: ingress, resolver, synthesis, harness, adapters, telemetry)
├── mock-agents/              (Python: FastAPI Wealth HTTP service [auto-OpenAPI] + FastMCP Asset-Servicing MCP server)
├── registry/                 (manifests/*.json + the enforceable JSON schema + loader)
├── glassbox/                 (standalone single-page HTML/JS trace dashboard)
├── librechat/                (librechat.yaml + rebrand assets + compose override)
└── loadtest/                 (k6 scripts)
```

---

## 4. The specs in /docs (read the relevant one before its milestone)

| File | Covers |
|---|---|
| `master-build-plan-consolidated.md` | the whole plan + the three researched gap-fills (synthesis, eval, guardrails) |
| `agent-catalog.md` | **the 9 agents to build** — ids, protocols, I/O, example prompts, canned data, fault knobs, seed entities |
| `agent-registration-schema-a2a-aligned.md` | the registry schema **rationale** (A2A-Agent-Card-aligned). The enforceable contract is pinned at `docs/agent-manifest.schema.json` |
| `agent-manifest.schema.json` | **canonical, pinned** JSON Schema (draft 2020-12) — validate every manifest against this *before* introspection/storage. Do **not** regenerate it |
| `agent-registry-demo-spec.md` | registration flow, introspection, Redis storage, the live-registration beat |
| `input-synthesis-deep-spec.md` | Extract→Resolve→Bind (how prompts become agent inputs safely) |
| `execution-orchestration-layer.md` | the Plan model + executor + harness composition |
| `harness-and-telemetry-deep-spec.md` | the per-call harness pipeline + OTel spans + glass-box events |
| `authorization-abac-cerbos-deep-spec.md` | Cerbos ABAC, prune-before-fan-out, identity seam |
| `technical-architecture-clear-boundaries.md` | who owns what (gateway vs LibreChat vs Redis) |
| `platform-vision-and-maturity-path.md` | context only (the read→write vision) |

> `docs/agent-manifest.schema.json` is the **pinned, canonical** manifest contract (already
> generated and validated). Validate manifests against it as-is — do not regenerate or alter it.

---

## 5. The phases — your loop unit

The build is broken into **7 phases**, each ending in a human test gate. Execute them in
order per the loop protocol (§8); the files are in `phases/`.

| Phase | What the human tests at the gate | Milestones |
|---|---|---|
| **1** | Type in LibreChat → a streamed reply appears (the pipe works) | M0–M1 |
| **2** | The 9 mock agents respond with canned data + fault knobs | M2 |
| **3** | A prompt shows the right routing decision (hero → ~7 agents, not `nav`) | M3–M4 |
| **4** | The hero prompt returns one grounded answer across HTTP + MCP (the core demo) | M5–M7 |
| **5** | Glass-box shows the live decision; an out-of-book relationship is denied | M8–M9 |
| **6** | Asks when unsure; survives an agent kill; shows Meridian branding | M10–M12 |
| **7** | A routing-accuracy number; a flat-p99 / virtual-thread scale graph | M13–M14 |
| **8** | One signed identity (user-mgmt OIDC, RS256/JWKS) verified at **every hop**; entitlements from a **domain/member** model — security + authz end-to-end | M15–M16 |
| **9** | AI observability & eval (Phoenix online + DeepEval offline) + registry ingestion + pre-demo hardening — *operate-and-improve; after the core is solid* | M17–M18 |

## 5b. Milestone detail (reference — the phases aggregate these)

Build **one vertical slice end to end first, then widen and deepen.** Each milestone has an
acceptance test; do not move on until it passes. For every milestone: **build the simple
path, leave the seam noted in the spec, and do NOT build the scale version.**

**M0 — Scaffold.** docker-compose with Redis Stack, an empty gateway, an empty mock-agents
service, and LibreChat (+ its Mongo/Meilisearch). *Accept:* `docker compose up` → all
containers healthy.

**M1 — Thin slice (do this before any logic).** Gateway exposes `POST /v1/chat/completions`
(SSE) and `GET /v1/models`; route every prompt to one hardcoded agent; stream a correct
OpenAI SSE response; LibreChat configured to point at the gateway. *Accept:* typing in
LibreChat streams an answer. **This closes SSE-format risk first — see §6 rule (a).**

**M2 — Mock agents (both protocols), in Python for speed.** Build the 9 agents from
`agent-catalog.md`: Wealth (4) as a **FastAPI** service — FastAPI auto-generates the OpenAPI
spec at `/openapi.json`, which the registry introspects directly in M3; Asset Servicing (5)
as a **Python MCP server** (`mcp` / FastMCP, SSE) exposing 5 tools. Each returns canned JSON
and supports a fault knob — HTTP via `?_delay_ms=` / `?_fail=true`, MCP via equivalent tool
args or env. *Accept:* each reachable; FastAPI serves `/openapi.json`; MCP serves
`tools/list`; fault knobs work. Add Python-side tests (pytest + FastAPI `TestClient`, and an
MCP client smoke test) asserting schema-valid canned data and working fault knobs.

**M3 — Registry.** Validate manifests against the pinned `docs/agent-manifest.schema.json`. Write the
9 manifests. Build the loader: validate → **introspect the spec** (OpenAPI / MCP
`tools/list`) to derive input/output schemas → store as RedisJSON → embed example prompts
into the HNSW index. Add `POST/GET/DELETE /admin/agents`. *Accept:* 9 load at boot; an
unseen paraphrase finds the right agent via vector search; an invalid manifest is rejected.

**M4 — Resolver.** Stage A (embed prompt → vector search → **confidence floor**) → Stage B
(filter by `domain` + `is_mutating==false`) → fan-out decision. *Accept:* the hero prompt
resolves to the correct ~7-agent subset across both protocols and **does not** select
`nav`.

**M5 — Input synthesis.** Build Extract→Resolve→Bind per `input-synthesis-deep-spec.md`.
**Build and test this in isolation against a fixture set before wiring it into the
fan-out.** *Accept:* correct per-agent inputs; **zero fabricated identifiers, ever** (hard
bar); a missing reference triggers a clarification instead of a guess.

**M6 — Wrappers + executor + harness.** `ProtocolAdapter` interface with `describe()` +
`invoke()`; `HttpAdapter` (OpenAPI-driven) + `McpAdapter` (MCP client); the Plan executor
(flat) + Resilience4j harness per `execution-orchestration-layer.md`. *Accept:* hero fans
across HTTP **and** MCP in parallel; killing an agent (`_fail=true`) still returns an answer
from the survivors.

**M7 — Answer synthesis + grounding.** Synthesize the merged answer with Claude, streaming.
**Agent outputs are the only ground truth** — the synthesis prompt presents them as
delimited DATA and forbids outside knowledge or invented numbers. Add a post-synthesis
**numeric grounding check** (every number in the answer must appear in some agent output)
and **partial-result honesty** (state missing data, never omit silently). *Accept:* answer
is grounded and attributed; a removed agent's data is acknowledged as missing.

**M8 — Entitlements (Cerbos).** PDP sidecar; `relationship` + `agent` ABAC policies; seed
principal attributes (RM `rm_jane` with a book) in Redis; **prune-before-fan-out** via
`PlanResources`; entity-level check after resolution. Identity comes from the LibreChat-
forwarded `user_id` (stubbed seam). *Accept:* as `rm_jane`, the Whitman relationship is
allowed; the **Okafor** relationship (out of book) is pruned and shown denied.

**M9 — Telemetry + glass-box + guardrail.** OTel spans (one root per request, child per
stage/agent); Micrometer metrics; the standalone glass-box page subscribing to a
`/trace/stream` SSE; Grafana. Guardrail: **separate data from instructions** in the
synthesis prompt (agent outputs are untrusted), and PII-aware trace logging. *Accept:* the
cross-protocol fan-out + routing + authz decision is visible live; agent output containing
an injected instruction does not alter behavior.

**M10 — Uncertainty beat.** One scoped clarification path: when two capabilities tie or an
entity is ambiguous, ask a precise question with **entitlement-filtered** options. *Accept:*
an ambiguous prompt yields a smart, scoped clarifying question, then proceeds.

**M11 — Resilience beat.** Wire the agent-kill into the demo path (fault knob → partial
join). *Accept:* killing an MCP agent mid-question still returns a degraded answer.

**M12 — UI rebrand.** Meridian branding (logo, theme, title); hide the model selector
so it reads as one bespoke assistant. **Config + cosmetic only — no code fork.** *Accept:*
hero works end-to-end from the Meridian-branded UI.

**M13 — Eval set.** 30–50 banker prompts → expected capability set (+ expected entity), run
as a routing-accuracy test in CI; a faithfulness spot-check on synthesis. *Accept:* a
routing-accuracy number is produced and printed.

**M14 — Scale proof (LAST).** k6: many concurrent streams; chart p99 + virtual-thread vs
OS-thread counts. *Accept:* hits the concurrency target with flat p99.

---

## 6. Hard rules (do not break these)

a. **SSE must be byte-correct** (role delta, content deltas, `[DONE]`). Test against the
OpenAI shape in M1 — if it's wrong, LibreChat shows a blank reply. Also **short-circuit
LibreChat's auto-title call** (it sends a separate "name this conversation" request that
must not route to agents).

b. **Zero fabricated identifiers.** The LLM extracts human references; a deterministic
lookup resolves them to IDs. The LLM never produces a `relationship_id`. An unresolved
reference triggers a clarification, never a guess.

c. **Agent outputs are untrusted and are the only ground truth.** In the synthesis prompt
they are delimited DATA, never instructions. The model summarizes; it never computes,
recalls, or invents numbers.

d. **Partial-result tolerant.** A failed agent never cancels its siblings. Join to the
overall deadline, harvest survivors, synthesize from what came back.

e. **Build the simple path; leave the seam.** Flat plans (not a planner), flat semantic
routing (not hierarchical), HTTP+MCP (A2A stubbed behind the interface), stubbed auth
identity. Define the interfaces that allow the scale version later; do **not** build the
scale version.

f. **Do not fork LibreChat's code.** Integrate via `librechat.yaml` and cosmetic rebrand
only. Run the glass-box as a separate page beside it.

g. **The gateway is one JVM (Java/Spring Boot) service** — no Python, no LangGraph, no
external agent gateway *inside the gateway*. The **mock agents are Python/FastAPI**, and
that is fine: they stand in for external domain-team agents and are not part of the
gateway's request-processing path.

h. **Instrument from M4 onward, not at the end.** The OTel trace context must thread through
the harness from the first outbound call.

---

## 7. Secrets & environment

- `ZAI_API_KEY` — the **gateway's runtime LLM key** (Z.AI GLM), used for input extraction
  and answer synthesis (read from env; never hardcode). This is **distinct** from whatever
  key Claude Code uses for its own operation while building. The gateway authenticates to
  Z.AI as `Authorization: Bearer $ZAI_API_KEY`.
- Redis Stack, Cerbos PDP, LibreChat (+ Mongo + Meilisearch), Grafana, OTel collector — all
  via docker-compose.
- DJL will download the MiniLM model on first run; allow for that.
- LibreChat custom-endpoint config: `baseURL` → the gateway's `/v1`; set `dropParams` for
  params the gateway doesn't accept; `streamRate` ~35.

---

## 8. Phase loop protocol (how you pace the work)

You do **not** run straight through. You work in a **loop, one phase at a time, pausing for
a human test at the end of each phase.**

For each phase (`phases/PHASE-N.md`):
1. Read the phase file and the specs it references in `docs/`.
2. Build **only that phase's scope** — the simple path; leave the seams it names; don't build
   ahead into later phases.
3. Run the phase's **automated acceptance** until green.
4. Reach the **■ HUMAN TEST GATE → STOP.** Append to `BUILD_REPORT.md`: what you built, the
   automated results, and the **exact human-test steps copied from the phase file**. Put a
   banner at the top of the report: `PHASE N COMPLETE — run the test steps below, then reply
   "proceed to Phase N+1".` Then **HALT.**
5. **Do not start the next phase** until the human replies to proceed. If they report a
   problem, fix it within the current phase and re-present the gate.

- **Within a phase:** work autonomously — don't stop to ask; make reasonable assumptions and
  log them in `BUILD_REPORT.md`. On a failing check, attempt up to 3 fixes; if still stuck,
  mark the phase **BLOCKED** with your diagnosis and surface it at the gate.
- **Between phases:** always stop. Never run two phases without a human OK in between.
- **Commit** at least once per phase (`Phase N: <summary>`); keep the repo runnable throughout.

## 9. Testing & verification (must be automated so you can self-verify)

Three layers, all runnable headless:

**a. Unit + integration (JUnit 5 + Spring Boot Test + Testcontainers).** Spin Redis and
Cerbos via Testcontainers. Cover:
- resolver routes the hero prompt to the correct ~7-agent subset and **excludes `nav`**;
- input synthesis fixtures — correct per-agent inputs and **zero fabricated identifiers**;
- harness — breaker trips on repeated failure; a failed node yields a partial result, not a
  thrown request;
- registry — introspection derives input/output schema from OpenAPI and MCP `tools/list`; an
  invalid manifest is rejected;
- Cerbos — `rm_jane` is allowed the Whitman relationship and **denied** the Okafor one.

**b. API smoke (RestAssured / curl).** `/v1/models` returns the one model; a streaming
`/v1/chat/completions` call yields well-formed OpenAI SSE chunks ending in `[DONE]`;
`/admin/agents` register+list works; `/trace/stream` emits events.

**c. End-to-end UI (Playwright, Chromium, headless) — drive the real Meridian-branded LibreChat.**
Put these in an `e2e/` Playwright project with `npm run e2e`. Tests:
1. **Hero:** type the hero prompt → assert a streamed answer renders and contains expected
   grounded facts (e.g. an allocation %, a settlement reference).
2. **Glass-box:** assert the panel shows ≥7 agents across **both** protocols with latencies,
   and that `nav` is not among them.
3. **Resilience:** set the settlement agent's fault knob → re-ask → assert the answer still
   returns, **states the missing piece**, and the glass-box shows the failed node.
4. **Entitlement:** as `rm_jane`, ask about the **Okafor** relationship → assert it is
   **not** answered (denied/filtered) and shown denied in the glass-box.
5. **Clarification:** type an ambiguous prompt → assert a scoped clarifying question appears,
   select an option, assert it proceeds.

**d. Eval (M13).** A script runs the 30–50 golden prompts through the resolver, prints a
routing-accuracy number, and fails under a set threshold. The golden prompt set may be
seeded from the user's existing prompt framework; agents themselves can also be exercised
directly (FastAPI `/openapi.json` calls and MCP `tools/call`) using that framework.

Provide a single entry point — `scripts/verify.sh` (or a `make verify`) — that: builds all
modules → `docker compose up -d` → waits for healthy → runs (a) → (b) → (c) → (d) → writes
results into `BUILD_REPORT.md`.

## 10. Running the stack & reporting back

- Use **docker-compose profiles** to keep the everyday demo lean:
    - **`core`** (default): `redis-stack`, `gateway`, `mock-agents` (HTTP + MCP), `cerbos`,
      `glassbox`, `librechat` (+ `mongodb`). Disable LibreChat's **Meilisearch** (set search
      off) to drop a container — the demo doesn't need conversation search.
    - **`scale`** (M14 only): adds `otel-collector`, `grafana`, `prometheus`, `k6`.
    - `docker compose up -d` brings up `core`; `docker compose --profile scale up -d` adds the
      rest. Each service has a **healthcheck**; the gateway depends on `redis-stack` and
      `cerbos` being healthy.
- Provide **`scripts/wait-for-healthy.sh`** so tests never run before services are up.
- Provide **`.env.example`** listing required env (`ZAI_API_KEY`, ports, etc.); load it
  via compose `env_file`. Never hardcode secrets.
- Write **`README.md`**: set `ZAI_API_KEY`, run `./scripts/verify.sh`, then open
  LibreChat (e.g. `localhost:3080`), the glass-box, and Grafana — with the exact URLs/ports.
- **Hand-back deliverable:** a `BUILD_REPORT.md` where every milestone is DONE (or explicitly
  BLOCKED with a reason and the human action needed), a running compose stack, and passing
  Playwright E2E results. If anything is blocked only on a missing secret or host
  prerequisite, say so clearly at the top of the report so we can unblock it immediately.

---

## 11. Definition of done

The Meridian-branded LibreChat takes the hero prompt (typed fresh, proving real routing), the
gateway routes it to the right agents **across HTTP and MCP**, synthesizes each input with
**zero fabricated IDs**, fans out in parallel through the harness, enforces that the user
may see the relationship, streams **one grounded, attributed answer**, shows it all live in
the glass-box, and **still answers when an agent is killed**. The eval set prints a
routing-accuracy number. The k6 graph (last) shows flat p99 under load.

Build M0 and M1 first, verify the slice runs, then proceed in order.