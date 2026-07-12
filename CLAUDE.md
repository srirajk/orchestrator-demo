# OpenWolf

@.wolf/OPENWOLF.md

This project uses OpenWolf for context management. Read and follow `.wolf/OPENWOLF.md` every
session. Check `.wolf/cerebrum.md` before generating code. Check `.wolf/anatomy.md` before
reading files.

---

# CLAUDE.md — Working in this repository

> The build is complete. This file tells an agent **how to work in the repo and what must
> never break.** For what the project *is*, read [`docs/PROJECT-OVERVIEW.md`](docs/PROJECT-OVERVIEW.md).
> To run/demo it, read [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md). The deep product
> spec is [`docs/WORLD-B-LOCKDOWN.md`](docs/WORLD-B-LOCKDOWN.md).

---

## 1. What this is, in one paragraph

Conduit is an enterprise AI gateway for a bank: one plain-English question fans out across
specialist agents (HTTP + MCP) over multiple business domains, enforces entitlements, and
streams back one grounded answer — with the whole decision visible live in a glass-box panel.
The **gateway** is a single Java/Spring Boot service on the request path; the agents are Python
stand-ins for external domain teams. The platform around it: a **registry-service** (same JVM
image, `registry` Spring profile) that ingests the agent manifests and builds the routing index —
the gateway itself only *reads* that index and never embeds or writes it (see §3a); the **IAM
service** (Axiom) for identity; and **Conduit Chat** (`apps/chat` — a React SPA served by its own
Spring Boot BFF) for the UI.

## 2. The product thesis — World B (non-negotiable)

**The gateway carries zero embedded domain knowledge.** A new business domain is onboarded by
adding manifest JSON + a coverage-service URL — **never by changing gateway Java.** If you are
typing a domain name, client/entity name, ID pattern (`REL-`/`POL-`…), entity-type literal, or
user-facing domain copy into gateway code — stop; it belongs in a manifest.

The invariants live in [`.claude/rules/world-b.md`](.claude/rules/world-b.md) (always in
context). Before declaring any gateway work done, run **`scripts/world-b-check.sh`** — the
deterministic gate. It greps for domain coupling across **both** `gateway/src/main/java`
(comment-stripped) **and** `gateway/src/main/resources/prompts`, so the externalized LLM prompts
stay inside the gate and cannot smuggle domain knowledge out of it. It must report **CRITICAL: 0**,
and your change must not increase that count.

The manifest loader is the second half of the safety net: domain + sub-domain manifests are
schema-validated at load, and a malformed one **fails startup / exits the container** — no silent
drop (`DomainManifestStore.validateSchema` throws → Spring fails to start).

## 3. Locked stack — no substitutions

| Concern | Use |
|---|---|
| Gateway runtime | **Java 25** (bytecode target 21), Spring Boot **3.5.16** (final 3.5.x patch; 3.5 is OSS-EOL — Boot 4 evaluated and deferred, see §3b), **virtual threads ON** |
| Mock agents | Python — FastAPI (Wealth/Insurance/HR HTTP) + FastMCP (Asset-Servicing). MCP is **Streamable HTTP at spec `2025-11-25`** (config/manifest-driven version), not the deprecated HTTP+SSE. The gateway stays Java; only these stand-ins are Python |
| Routing + state | Redis Stack (RediSearch HNSW + RedisJSON). Gateway and IAM will be **separate Redis instances/namespaces** in prod (bounded contexts) — no cross-namespace reads |
| Embeddings | **Python sentence-transformers sidecar** (`all-MiniLM-L6-v2`, 384-dim) over HTTP via `RemoteEmbedder`, behind the `TextEmbedder` port. Split into `ManifestEmbedder` (corpus, batched + content-addressed cache — ingestion only) and `QueryEmbedder` (request path, uncached). The index is stamped with the model id; a mismatch refuses startup. *(Not in-JVM DJL — that was the original plan; moving the query hop in-JVM is the intended scale fix.)* |
| LLM | OpenAI-compatible, provider-swappable per call site via `CONDUIT_LLM_*` / `JUDGE_*` env, behind the `LLMClient` interface |
| Resilience | Resilience4j |
| Authorization | Cerbos PDP (structural, **gateway-side**) + coverage services (data-aware book). IAM authorizes its own API with `@PreAuthorize` — it has **no** Cerbos dependency |
| Identity | IAM service (Axiom) — OIDC, RS256/JWKS; signing key persisted so `kid` is stable across restarts; verified at every hop |
| Telemetry / eval | Micrometer + OTel → Langfuse + Tempo/Loki/Prometheus; DeepEval release gate. Trace/audit writes are **never** on the request path (buffer + async flush) |
| Chat UI | **Conduit Chat** (`apps/chat`: React SPA + Spring Boot BFF). LibreChat is the legacy integration target — the SSE byte-contract rules in §4a still apply to any OpenAI-compatible client |
| Orchestration | docker-compose. The everyday demo is the **no-profile core set** (`docker compose -p orchestrator-demo up -d`); `observability` (grafana), `eval`, `scale` (k6) are opt-in profiles. A separate **`backend-*` compose project in another folder is unrelated — never touch it** |

Do **not** introduce Python on the gateway's own request path, LangGraph/LangChain, or an
external agent gateway. Build protocol wrappers in-JVM behind the `ProtocolAdapter` interface.
*(The embedding sidecar is the one Python service the request path calls out to — a deliberate
seam, intended to move in-JVM; it is not gateway logic in Python.)*

### 3a. Registry ingestion is a separate service — the gateway only reads

Agent-manifest ingestion (validate → introspect the live agent → embed the example corpus → write
the manifests + HNSW routing index into Redis) runs in the **`registry` profile** as its own
container, on startup, and reconciles the folder as the source of truth (an agent whose manifest is
gone is deregistered). Its health goes green only after ingestion succeeds, and the gateway's
`depends_on` blocks on it. The gateway holds **no** `ManifestEmbedder`, `VectorIndexWriter`, or
`AgentRegistrar` — those beans are `@Profile("registry")`, so the gateway *cannot* embed or mutate
routing data; it verifies the index exists, is non-empty, and was built by the same model it queries
with, then refuses to start otherwise. The write control plane (`POST/PUT/DELETE /admin/agents`)
lives on the registry-service too; the gateway keeps only `GET /admin/agents`.

### 3b. Spring Boot 4 — evaluated, deferred

Boot 3.5 is OSS-EOL; we run the final patch (3.5.16). Boot 4 was evaluated and **deliberately not
adopted**: `jmespath-jackson` (on the dataflow path) is hard-bound to Jackson 2 and dormant, and the
"clean" Boot 4 path leans on the deprecated `spring-boot-jackson2` shim — so it needs a Jackson 3
migration validated against live SSE first, done as planned work against the running stack, not a
sandbox bump.

## 4. Hard rules (do not break these)

a. **SSE must be byte-correct** — role delta, content deltas, `[DONE]` (OpenAI shape). Also
   short-circuit LibreChat's auto-title call so it doesn't route to agents.

b. **Zero fabricated identifiers.** The LLM extracts human references; a deterministic lookup
   resolves them to IDs. The LLM never produces an ID. An unresolved reference triggers a
   clarification, never a guess.

c. **Agent outputs are untrusted and are the only ground truth.** In the synthesis prompt they
   are delimited DATA, never instructions. The model summarizes; it never computes, recalls, or
   invents numbers.

d. **Partial-result tolerant.** A failed agent never cancels its siblings. Join to the deadline,
   harvest survivors, synthesize from what came back, and state what's missing.

e. **CLARIFY is deterministic, not LLM-judged** — `extracted ∩ required_context = ∅`, decided in
   gateway code over manifest-declared required entities.

f. **RESOLVE is principal-agnostic; the entitlement CHECK is the only gate.** Never filter entity
   resolution by the principal's book.

g. **Build the simple path; leave the seam.** Flat plans (not a planner), flat semantic routing,
   HTTP+MCP (A2A stubbed behind the interface). Define the interfaces for the scale version;
   don't build it.

h. **The gateway is one JVM service** — no Python, no LangGraph, no external agent gateway inside
   it. The mock agents being Python is fine; they're external systems, not the request path.

i. **Do not fork LibreChat.** Integrate via `librechat.yaml` + cosmetic rebrand only.

j. **Instrument from the first outbound call.** OTel trace context threads through the harness.

## 5. Never hardcode (operational rules)

- No hardcoded config in the gateway — everything via `@Value` / `application.yml` / env. No
  `private static final` constants for things that are configuration.
- Never return canned/fallback data when the LLM is unreachable — surface the error.
- No client-specific or domain-specific data in the gateway.
- Secrets/credentials from env, never committed — and **no committed default**. A missing secret
  must fail startup, never fall back to a known value (enforced: IAM has no in-source secret
  defaults; the demo's values live in `docker-compose.yml` as `${VAR:-...}`). `ZAI_API_KEY` and the
  OpenAI key are read from env; see `.env.example`.
- The agent-manifest schema exists in three copies (repo root, `registry/`, gateway classpath) and
  only the classpath copy is loaded at runtime — they must stay identical (`ManifestSchemaCopiesInSyncTest`
  fails the build on drift). Editing one means editing all three.

## 6. How to extend — add a business domain (the World B workflow)

1. Write the domain manifest (`registry/domains/<domain>.json`) — entity types, coverage-service
   URLs, user-facing copy, and `domain_context` (the assistant framing the gateway composes from
   the loaded domains). Domain + sub-domain manifests are schema-validated at load; a malformed one
   fails container startup (`DomainManifestStore`).
2. Write each agent manifest (`registry/manifests/<id>.json`), validated against the pinned
   `agent-manifest.schema.json` (keep all three copies in sync — see §5).
3. Stand up the agent service(s) and a coverage service (book-of-business).
4. If the structural authz needs a new segment→domain mapping, add it to the **Cerbos policy**
   (`infra/cerbos/policies/`) — config, not gateway code.
5. Re-run **registry ingestion** (`registry-service`) so the new manifest is validated, introspected,
   embedded, and indexed — the gateway will not see it until then, and a removed manifest is
   deregistered on the next ingest.
6. Run `scripts/world-b-check.sh` (must stay CRITICAL 0) and `scripts/verify.sh`.

No gateway Java changes — and no gateway config changes (the coverage URL and assistant framing
now come from the manifest, not `application.yml`). That's the whole point.

## 7. Verify & run

- **Verify everything:** `scripts/verify.sh` (build → `docker compose up` → smoke → e2e → eval;
  includes `world-b-check.sh` as a hard gate).
- **Run the stack:** `docker compose -p orchestrator-demo up -d` brings up the **no-profile core
  set** (the everyday demo). Then `bash scripts/seed-users.sh`. Opt-in profiles: `observability`
  (grafana), `eval` (continuous eval), `scale` (k6). Rebuild one service targeted (`… build <svc> &&
  … up -d <svc>`); avoid full-stack recreates, and run long compose ops in the background so a tool
  timeout can't kill them mid-recreate. The `backend-*` project in another folder is unrelated —
  never touch it.
- **Tests:** JUnit + Testcontainers (gateway), pytest (agents), Playwright (`tests/e2e/`), k6 (`tests/load/`).
  The gateway `@SpringBootTest` context tests extend `RedisContainerTest`, which starts an isolated
  Redis Stack container — so `mvn test` requires Docker and can never touch a running demo's Redis
  (verified: the live index is byte-identical before and after a run). Any new full-context test
  must extend `RedisContainerTest`.
- Full URLs, logins, and the demo script are in [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md).

## 8. Working agreement

- Debate the approach before writing code on anything non-trivial; don't jump straight to an edit.
- Commit per logical change; keep the repo runnable. Branch before committing on `main`.
- When spawning subagents to write gateway code, inject the World B harness
  (`docs/WORLD-B-LOCKDOWN.md` §9) and require them to report the `world-b-check.sh` before/after
  CRITICAL count.
