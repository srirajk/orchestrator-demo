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

Meridian is an enterprise AI gateway for a bank: one plain-English question fans out across
specialist agents (HTTP + MCP) over multiple business domains, enforces entitlements, and
streams back one grounded answer — with the whole decision visible live in a glass-box panel.
The gateway is a **single Java/Spring Boot service**; the agents are Python stand-ins for
external domain teams.

## 2. The product thesis — World B (non-negotiable)

**The gateway carries zero embedded domain knowledge.** A new business domain is onboarded by
adding manifest JSON + a coverage-service URL — **never by changing gateway Java.** If you are
typing a domain name, client/entity name, ID pattern (`REL-`/`POL-`…), entity-type literal, or
user-facing domain copy into gateway code — stop; it belongs in a manifest.

The invariants live in [`.claude/rules/world-b.md`](.claude/rules/world-b.md) (always in
context). Before declaring any gateway work done, run **`scripts/world-b-check.sh`** — it greps
`gateway/src/main/java` for domain coupling and must report **CRITICAL: 0**. Your change must
not increase that count.

## 3. Locked stack — no substitutions

| Concern | Use |
|---|---|
| Gateway runtime | Java 21+ (25 preferred), Spring Boot 3.5.x, **virtual threads ON** |
| Mock agents | Python — FastAPI (Wealth/Insurance HTTP) + FastMCP (Asset-Servicing MCP). The gateway stays Java; only these stand-ins are Python |
| Routing + state | Redis Stack (RediSearch HNSW + RedisJSON) |
| Embeddings | DJL + all-MiniLM-L6-v2 (in-JVM, 384-dim) behind `EmbeddingClient` |
| LLM | OpenAI-compatible, provider-swappable per call site via `MERIDIAN_LLM_*` / `JUDGE_*` env, behind the `LLMClient` interface |
| Resilience | Resilience4j |
| Authorization | Cerbos PDP (structural) + coverage services (data-aware book) |
| Identity | IAM service — OIDC, RS256/JWKS; verified at every hop |
| Telemetry / eval | Micrometer + OTel → Langfuse + Tempo/Loki/Prometheus; DeepEval release gate |
| Chat UI | LibreChat (custom-endpoint config + cosmetic rebrand **only** — no fork) |
| Orchestration | docker-compose (`core` profile = the everyday demo) |

Do **not** introduce Python on the request path, LangGraph/LangChain, or an external agent
gateway. Build protocol wrappers in-JVM behind the `ProtocolAdapter` interface.

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
- Secrets/credentials from env, never committed. `ZAI_API_KEY` (if GLM used) and the OpenAI key
  are read from env; see `.env.example`.

## 6. How to extend — add a business domain (the World B workflow)

1. Write the domain manifest (`registry/domains/<domain>.json`) — entity types, coverage-service
   URLs, user-facing copy.
2. Write each agent manifest (`registry/manifests/<id>.json`), validated against the pinned
   `registry/agent-manifest.schema.json`.
3. Stand up the agent service(s) and a coverage service (book-of-business).
4. If the structural authz needs a new segment→domain mapping, add it to the **Cerbos policy**
   (`infra/cerbos/policies/`) — config, not gateway code.
5. Run `scripts/world-b-check.sh` (must stay CRITICAL 0) and `scripts/verify.sh`.

No gateway Java changes. That's the whole point.

## 7. Verify & run

- **Verify everything:** `scripts/verify.sh` (build → `docker compose up` → smoke → e2e → eval;
  includes `world-b-check.sh` as a hard gate).
- **Run the stack:** `docker compose up -d` (core), then `bash scripts/seed-users.sh`. Continuous
  eval: `docker compose --profile eval up -d eval-worker`.
- **Tests:** JUnit + Testcontainers (gateway), pytest (agents), Playwright (`e2e/`), k6 (`loadtest/`).
- Full URLs, logins, and the demo script are in [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md).

## 8. Working agreement

- Debate the approach before writing code on anything non-trivial; don't jump straight to an edit.
- Commit per logical change; keep the repo runnable. Branch before committing on `main`.
- When spawning subagents to write gateway code, inject the World B harness
  (`docs/WORLD-B-LOCKDOWN.md` §9) and require them to report the `world-b-check.sh` before/after
  CRITICAL count.
