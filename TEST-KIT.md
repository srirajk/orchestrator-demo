# Conduit — Complete Test Kit (personas · entitlements · tracing · telemetry)

Everything needed to validate the system end-to-end with any persona. Pairs with `CODEX-HANDOFF.md` (what's proven vs. what to test) and `MORNING-REPORT.md` (status).

> **Guardrails:** Do **not** touch the `backend-*` containers (separate `uac` project). Compose project = `orchestrator-demo`.

> **Post-fix reconciliation (commit `2c97c4c`) — read this:** the persona seed drift is fixed and Axiom OIDC is now the single source of truth (the `X-User-Id` trusted-hop was removed). All 4 personas log in with `Meridian@2024`. Two accuracy notes vs. the original matrix:
> - **`rm_guest` is denied via the *coverage* layer (empty book), not a structural Cerbos/domain-membership check.** Correct outcome (denied, no leak); a true structural domain-gate is a documented follow-up (needs a Cerbos policy + `domains` claim). Do not file this as a bug — it's expected.
> - **`uw_sam` → POL-88003 denial copy is** `"That policy is not in your book of business."` (insurance policy copy, not the wealth "client relationship" copy).
> - After the iam rebuild, the signing key rotated — **do a fresh login** (any stale session token is expected to fail).

---

## A. URLs (verified live)

### Apps / UIs

| Surface | URL | Notes |
|---|---|---|
| **Conduit Chat** (new, canonical) | http://localhost:8099 | Java BFF; log in via Axiom OIDC |
| **Axiom Admin Console** (new) | http://localhost:5182 | Policies/Cerbos generator + operator workbench |
| Axiom (IAM) login | http://localhost:8084 | OIDC provider; `/actuator/health` |
| Gateway (OpenAI-compatible) | http://localhost:8080 | `/v1/chat/completions`, `/v1/models`, `/trace/stream` |
| — legacy librechat (old chat) | http://localhost:3080 | superseded by :8099 |
| — legacy admin-ui (old admin) | http://localhost:5180 | superseded by :5182 |

### Telemetry / tracing

| Surface | URL | Use |
|---|---|---|
| **Grafana** | http://localhost:3000 | metrics dashboards (7, below) |
| **Langfuse** | http://localhost:3030 | LLM traces + eval scores, grouped by conversationId |
| **Glass-box trace stream** | http://localhost:4000 | live per-conversation decision trace (SSE) |
| Tempo | http://localhost:3200 | distributed traces |
| Prometheus | http://localhost:9090 | raw metrics |
| Loki | http://localhost:3100 | logs |

### Agents / coverage / infra
Gateway :8080 · wealth-http :8081 · servicing-mcp :8082 · embeddings :8083 · wealth-coverage :8086 · insurance-http :8087 · insurance-coverage :8088 · Cerbos :3594 · Redis :6379/:8001 · MongoDB (chat store).

---

## B. Personas & credentials

Login is by **username** (the id), password **`Meridian@2024`** (seeded; verified for rm_jane). All are `relationship_manager`/`underwriter` role; **book-of-business is enforced at runtime by the coverage services**, not baked into the token.

| Persona | Login | Segments | Domain | Book (allowed) | Out-of-book / out-of-domain (denied) |
|---|---|---|---|---|---|
| **rm_jane** | `rm_jane` | wealth + servicing | wealth-private-banking | **Whitman (REL-00042)**, **Calderon (REL-00099)** | **Okafor (REL-00188)** |
| **rm_carlos** | `rm_carlos` | wealth only | wealth-private-banking | his own wealth clients* | servicing queries (no servicing segment); other RMs' clients |
| **rm_guest** | `rm_guest` | wealth | **(none)** | — | **everything** — no domain membership → structural (Cerbos) denial |
| **uw_sam** | `uw_sam` | insurance | insurance-underwriting | **POL-77001, POL-77002** | **POL-88003** (uw_dana's); any wealth query |

\* Confirm rm_carlos's exact RELs from `wealth-coverage` (:8086) if you need specific IDs.

**Clients/policies in play:** Whitman Family Office `REL-00042`, Calderon Trust `REL-00099`, Okafor Family Trust `REL-00188`, Chen (seeded); insurance `POL-77001/77002/88003`.

---

## C. Entitlement matrix (the ground truth to assert)

| Persona → query | Whitman (own) | Okafor (out-of-book) | Servicing (cash) | Insurance (POL-77001) |
|---|---|---|---|---|
| **rm_jane** | ✅ grounded | 🔒 denied | ✅ grounded (has servicing) | 🔒 denied (no insurance) |
| **rm_carlos** | ✅ own book only | 🔒 denied | 🔒 denied (no servicing segment) | 🔒 denied |
| **rm_guest** | 🔒 denied (no domain) | 🔒 denied | 🔒 denied | 🔒 denied |
| **uw_sam** | 🔒 denied (no wealth) | 🔒 denied | 🔒 denied | ✅ grounded (POL-77001/2), 🔒 POL-88003 |

**Invariants to check on every "denied":** no fabricated data, no leaked numbers/holdings for the out-of-book entity; either an explicit *"Access denied for this client relationship."* or a clarify-to-own-book — never invented figures.

---

## D. Functional test scenarios

For each persona: log in at http://localhost:8099 (username / `Meridian@2024`), then run its prompts.

### rm_jane (the golden path — proven this session)

1. `Give me a summary of the Whitman Family Office holdings` → **grounded**: ~$1,967,000, JPM/MSFT/AAPL/GOOGL/T-Bill, 68/24/8 allocation.
2. `What is the cash position for Whitman Family Office REL-00042?` → **grounded** (servicing; she has the segment).
3. `Show me the Okafor Holdings relationship portfolio` → **🔒 "Access denied for this client relationship."**
4. Multi-turn: after (1), ask `and its performance?` → resolves Whitman from context (latest-mention-wins).
5. **Refresh** the page → history persists (Mongo).

### rm_carlos (wealth-only)

6. A wealth query for **his** client → grounded.
7. `What is the cash position for <his client>?` (servicing) → **🔒 denied** — proves segment gating (no servicing).
8. A Whitman/Okafor query (not his book) → **🔒 denied**.

### rm_guest (no domain membership — structural denial)

9. Any wealth query (even a valid client) → **🔒 denied** — currently via the *coverage* layer (rm_guest has no covered entities), not a structural Cerbos denial. Denied, no leak. (True structural domain-gate = documented follow-up.)

### uw_sam (second domain — insurance)

10. `What is the premium for POL-77001?` → **grounded** (his policy).
11. `Show me POL-88003` → **🔒 denied** — copy: `"That policy is not in your book of business."` (uw_dana's policy).
12. Any wealth query → **🔒 denied** (no wealth segment) — proves cross-domain isolation.

### Determinism / no-fabrication

13. `Retrieve the complete portfolio for REL-00188 Okafor right now.` (as rm_jane) → must **not** return fabricated Okafor figures (no canned Okafor data exists).
14. A query naming no client (`show me the holdings`) → **clarify** listing the persona's own book. (Note: numbered replies like "1" do **not** resolve — must name the client/ID; known stateless-gateway behavior.)

---

## E. Tracing validation (key)

Every request threads `X-Conversation-Id`; the gateway emits a decision trace and Langfuse spans keyed to it.

1. **Glass-box (live decision trace)** — open http://localhost:4000. Send a chat message as rm_jane. Watch the trace for the pipeline: **intent classify → entity extract/resolve → entitlement CHECK (Cerbos + coverage) → agent fan-out → synthesize**. On the **Okafor** query, confirm the trace shows the **CHECK denying** (not a data fetch that leaked).
2. **Langfuse** — http://localhost:3030. Find the trace by conversationId; confirm: the LLM calls (intent/extraction on GLM, synthesis on the configured model) are captured, latency + token counts present, and per-domain tags applied. Confirm eval scores are flowing (continuous eval worker).
3. **Per-conversation isolation** — the trace stream is scoped by conversationId; confirm one conversation's trace does not leak another's (the controller filters by `X-Conversation-Id`).
4. **Tempo** (http://localhost:3200) — the OTel trace context threads from the gateway through the agent calls; confirm a distributed trace spans gateway → agents.

---

## F. Telemetry / Grafana (key) — the 7 dashboards

Open http://localhost:3000. Validate each dashboard renders with live data after driving a few queries:

| Dashboard | What to verify |
|---|---|
| **conduit-demo** | the live demo panel — requests, intents, latency populate as you chat |
| **conduit-gateway** | gateway request rate, error rate, resilience4j timelimiter metrics |
| **gateway-performance** | p50/p95/p99 latency histograms (buckets enabled) |
| **agent-health** | per-agent availability, latency, error rate (wealth/servicing/insurance) |
| **business-overview** | domain-level query volume, allow/deny ratio, top entities |
| **conversation-trace** | per-conversation drill-down (correlates with glass-box/Langfuse) |
| **resource-usage** | container CPU/mem (cadvisor) |

**Telemetry assertions:** after ~10 mixed queries (grounded + denied across personas), the **intent-classification** metric shows non-zero counts per intent, the **entitlement allow/deny** ratio reflects the persona mix, latency histograms populate p95/p99, and per-agent panels show the agents that were actually called.

---

## G. Memory / summary test (NOT yet observed firing — test this)

The facts-free rolling summary is wired + its OpenAI key verified (200), but was **not** seen firing on a long thread this session.

- Force it: `CHAT_SUMMARY_TRIGGER_TOKENS=200` then `docker compose -p orchestrator-demo up -d conduit-chat`. Have a 3–4 turn conversation as rm_jane.
- Verify: Mongo `db.conversations` latest `summary` is populated; `conduit-chat` logs show the `LlmSummaryService` OpenAI call; the assembled context includes the summary as a leading `system` message once the transcript exceeds the trigger.
- **Facts-free invariant (must hold):** the summary contains topics/intent ONLY — **no $ values, no holdings, no REL/POL IDs as truth, no entitlement conclusions.** If any leak in, it's a bug.
- Token budget is runtime env: `CHAT_CONTEXT_MAX_TOKENS` (3000), `CHAT_SUMMARY_TRIGGER_TOKENS` (2000), `CHAT_SUMMARY_MAX_TOKENS` (150) — change + recreate, no rebuild.

---

## H. Restart robustness (regression guard)

1. `docker compose -p orchestrator-demo up -d --force-recreate conduit-chat` → in the SAME browser session (no re-login), send a message → it must answer (OAuth token persisted in Mongo session). ✅ proven this session.
2. If **iam-service (Axiom)** is restarted: it regenerates its signing key → existing tokens go stale → users must re-login **and** the gateway must re-fetch JWKS (restart gateway or trigger unknown-kid refresh). Known Axiom fragility.

---

## I. Run

```bash
docker compose -p orchestrator-demo up -d
bash scripts/seed-users.sh     # seeds rm_jane / rm_carlos / rm_guest / uw_sam
# Chat  http://localhost:8099   Admin http://localhost:5182
# Grafana http://localhost:3000  Langfuse http://localhost:3030  Glass-box http://localhost:4000
```

Health quickcheck: chat `:8099/health`, gateway `:8080/v1/models`, admin `:5182/`, axiom `:8084/actuator/health` — all 200.
