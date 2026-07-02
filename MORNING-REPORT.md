# Morning Report — Conduit Chat (Java BFF) + Monorepo Split + Stateless Gateway

**Date:** 2026-07-02 · **Owner:** Sriraj · **Branch:** `feat/conduit-chat` (+ `main` for gateway/integration)

> The contract for this report is honesty per item — `[x] done & validated` · `[~] built, not fully validated` · `[ ] todo` · `[defer]` follow-up. Nothing is called done unless it was proven end-to-end.

---

## 1. Executive summary

The crown is **done and demonstrated live**: a real, persistent, entitled, grounded chat on genuine SSO, served by a **production-grade Java BFF** (Spring Boot 3.5, Java 21, virtual threads). Validated in the browser as `rm_jane`:

- Real **Axiom OIDC login** → her token forwarded to the gateway.
- **Grounded answer** for her client (Whitman Family Office — $1,967,000, real positions + allocation).
- **"Access denied for this client relationship."** for an out-of-book client (Okafor).
- **Multi-turn** follow-up ("cash position?") resolved from context.
- **Survives page refresh** (Mongo-backed) **and container recreate** (session-persisted OAuth token).

The gateway remains **World-B**: a manifest-driven interpreter with zero embedded domain knowledge (`world-b-check.sh` CRITICAL 0), stateless (memory is the client's problem).

---

## 2. P0 validation (the proof)

| # | Requirement | Status | Evidence |
|---|---|---|---|
| 1 | Real user login (her token, her entitlements) | `[x]` | rm_jane via Axiom OIDC; BFF forwards her **access token** |
| 2 | Grounded answer for her client | `[x]` | Whitman $1.967M, JPM/MSFT/AAPL/GOOGL/T-Bill, 68/24/8 allocation |
| 3 | Out-of-book denial | `[x]` | Okafor → *"Access denied for this client relationship."* |
| 4 | Streaming (byte-exact SSE) | `[x]` | JDK HttpClient stream → StreamingResponseBody on virtual thread |
| 5 | Survives refresh | `[x]` | hard reload → full history from Mongo |
| 6 | Survives container restart (no re-login) | `[x]` | force-recreate → next message answered without re-login |
| 7 | Multi-turn context | `[x]` | "cash position?" resolved REL-00042 from prior turn |

---

## 3. Checklist status (CONDUIT-CHAT-BUILD-CHECKLIST + this session)

### Java BFF (`apps/chat/bff`) — prod-grade
- `[x]` Spring Boot 3.5, Java 21, **virtual threads**; package-by-feature; `@RestControllerAdvice`; `@ConfigurationProperties`; constructor injection.
- `[x]` **OIDC relying party of Axiom** — Spring Security `oauth2Login`, callback at `/api/auth/callback` matching Axiom's registration. **Axiom (`iam-service`) never modified.**
- `[x]` **Access token persisted in the Mongo-backed session** (`HttpSessionOAuth2AuthorizedClientRepository`) — survives restart/recreate.
- `[x]` Spring Data Mongo; per-user isolation; responses expose `id` (not `_id`).
- `[x]` Conversations CRUD + streaming messages; auto-title.
- `[x]` **Token-budget memory** — jtokkit tokenizer; `CHAT_CONTEXT_MAX_TOKENS` window budget + `CHAT_SUMMARY_TRIGGER_TOKENS` summary trigger (message-count knobs removed).
- `[~]` **Facts-free rolling summary via OpenAI** — wired, key-verified live (200 on `api.openai.com`, gpt-4o-mini, sourced from `.env`, nothing hardcoded), safe no-op fallback. **Not yet observed firing on a genuinely long (>2000-token) thread.**
- `[~]` **Files / MinIO** — endpoint hardened (size limits, 413), real StorageService. **The web app does not call `/api/files` yet** (no attachment UI wiring).
- `[x]` **All envs exposed** — every `conduit.chat.*` in `application.yml` + compose + `.env.example`; no hardcoded config.

### Monorepo split
- `[x]` `@conduit/ui` (Axiom design system: Tailwind preset + tokens + 9 primitives).
- `[x]` `@conduit/gateway-client` (typed, framework-agnostic; SSE parse; trace stream).
- `[x]` `apps/admin` (Axiom console — Policies/Cerbos generator + operator workbench) — **wired into compose as `admin-console` on :5182, validated serving**.
- `[ ]` Apps consuming the packages (both still carry local copies) — **migration follow-up**.
- `[defer]` Fold `apps/chat` into the root workspace (kept separate during concurrent edits).

### Gateway / platform
- `[x]` **LLM key wiring fixed** (`main`) — `CONDUIT_LLM_*_API_KEY` now fall back to the `.env` key; the empty-key 401 that caused permanent "which client?" over-clarify is gone → grounded answers restored.
- `[x]` Stateless gateway + persona endpoint + admin UI landed on `main` (e2e 91/91, smoke 18/0, world-b 0).

---

## 4. Bugs found & fixed this session (all caught by real runtime validation, not "compiles clean")

1. **Node BFF crash** — `.d.ts` imported at runtime (no `.js`) → converted to a real `.ts` module.
2. **Secure cookie on http** — session cookie dropped on `http://localhost` → env-gated `COOKIE_SECURE`.
3. **`_id` vs `id`** — frontend got `undefined` conversation id → Mongo `toJSON` + drop `.lean()`.
4. **JWKS kid mismatch / stale token** — iam regenerates its signing key on restart → gateway rejected old tokens (re-login / gateway re-fetch).
5. **Gateway over-clarify** — root cause was **empty LLM API key** (401), not resolution logic.
6. **In-memory OAuth token lost on restart** — moved to Mongo-backed session; recreate no longer forces re-login.
7. **`apps/admin` Docker build** — missing lockfile after workspace hoist → restored lock + resilient `npm install`.

---

## 5. Honest remaining work / follow-ups

- `[ ]` **Summary firing validation** — drive a >2000-token thread and confirm the facts-free summary generates and stays facts-free (no values/entities/entitlements).
- `[ ]` **Files → UI** — wire the composer attach button to `/api/files` + render attachments.
- `[ ]` **Packages consumed by apps** — migrate `apps/chat/web` + `apps/admin` to import `@conduit/ui` + `@conduit/gateway-client`, delete local duplicates.
- `[defer]` **iam signing-key persistence** — Axiom regenerates its RSA key on boot, invalidating all tokens on restart. Persist the key (Axiom-internal; do **not** couple to Conduit).
- `[defer]` **Numbered clarify UX** — the stateless gateway can't map a numbered reply ("1") to a client (the mapping lives in the assistant message it won't read). Either drop the numbers in the clarify copy or have the client map number→ID before sending.
- `[ ]` **Multi-instance BFF** — session-persisted token is fine single-instance; confirm behavior behind >1 replica.

---

## 6. Container rationalization (#38) — PLAN, deferred to a dedicated pass

**Note:** `docker ps` shows 41 containers, but **11 are a separate project of yours** (`backend`, from `~/projects/uac/backend`, `uac-network`) — untouched. **Our stack is ~30 `conduit-*` containers.**

Conservative rationalization plan (needs e2e re-point, hence deferred):
- **Retire `conduit-librechat`** — superseded by `conduit-chat`. Move to a `legacy` profile (e2e login/branding specs point at it — update them first).
- **Retire `conduit-admin-ui`** — superseded by `admin-console` (`apps/admin`). Same: `legacy` profile + re-point `admin-ui.spec.ts`.
- **Keep `conduit-mongodb`** (chat store), Redis, Cerbos, iam, gateway, agents, coverage — all load-bearing.
- **`conduit-glassbox`** — keep until the workbench trace rail fully replaces it.
- **Profile the observability stack** (grafana/tempo/loki/prometheus/promtail/otel/cadvisor) under `observability` so the everyday `core` profile is lean.
- **Postgres consolidation** — langfuse-db vs postgres: verify before merging (data-loss risk).

Net: `core` profile could drop from ~30 → ~18 with the two legacy UIs + observability profiled out. **Do this deliberately with an e2e run, not at the tail of a long session.**

---

## 7. How to run / demo

```bash
# from the repo (chat worktree), .env must carry ZAI_API_KEY + CONDUIT_LLM_* (OpenAI synthesizer key)
docker compose -p orchestrator-demo up -d
# Chat:   http://localhost:8099   (login rm_jane / Meridian@2024 via Axiom)
# Admin:  http://localhost:5182   (Axiom console)
# Demo:   "summary of the Whitman Family Office holdings" → grounded
#         "Okafor Holdings relationship portfolio"        → Access denied
```

Change memory window any time (no rebuild): set `CHAT_CONTEXT_MAX_TOKENS` / `CHAT_SUMMARY_TRIGGER_TOKENS` → `docker compose up -d conduit-chat`.

---

## 8. Commits (this session)

- `main` — `148c02c` stateless integration; `4f1dc30` gateway LLM key fix.
- `feat/conduit-chat` — chat build + runtime fixes; `b68257c` Java BFF (token memory, OpenAI summary, MinIO, restart-safe token, all envs); `28a93d3` split (packages + apps/admin); `dcf5e89` admin-console wired.

All commits credited to Sriraj; no AI attribution. `iam-service` (Axiom) untouched throughout the chat work.
