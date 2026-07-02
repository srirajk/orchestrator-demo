# Handoff → Codex (validation & test)

Read `MORNING-REPORT.md` for the full picture. This note is the **validation-focused** version: what's already proven, what you should actually test, the gotchas, and the hard "do not touch" list.

---

## Guardrails (do NOT touch)

- **`backend` (uac project)** — `~/projects/uac/backend`, `uac-network`, containers `backend-*`. **A completely separate project. Do not stop, restart, or modify it.**
- **`iam-service` (Axiom)** — the identity provider. Conduit is only an OIDC *relying party*. **Do not modify Axiom to suit Conduit.**
- Compose project name is `orchestrator-demo`. Branch under test: **`feat/conduit-chat`**.

## Environment note

- `.env` (gitignored) must be present in the worktree so compose loads keys: `ZAI_API_KEY` (gateway GLM) and `CONDUIT_LLM_SYNTHESIZER_*` (points at **OpenAI**, `gpt-4o-mini`, the 164-char key — this is what the chat summary reuses).
- Token-budget knobs are **runtime env** (no rebuild): `CHAT_CONTEXT_MAX_TOKENS` (3000), `CHAT_SUMMARY_TRIGGER_TOKENS` (2000), `CHAT_SUMMARY_MAX_TOKENS` (150) → change + `docker compose up -d conduit-chat`.

---

## Already PROVEN live (browser, as `rm_jane` / `Meridian@2024`)
You can re-verify, but these passed end-to-end this session:

1. Real Axiom OIDC login → her access token forwarded to the gateway.
2. **Grounded** answer for her client: "summary of the Whitman Family Office holdings" → $1,967,000, real positions, 68/24/8 allocation.
3. **Denial**: "Okafor Holdings relationship portfolio" → *"Access denied for this client relationship."*
4. **Multi-turn**: follow-up "cash position?" resolved REL-00042 from context.
5. **Survives page refresh** (Mongo) **and container recreate** (OAuth token now persisted in the Mongo session — a `docker compose up -d --force-recreate conduit-chat` no longer forces re-login).

## What to ACTUALLY TEST (not yet validated this session)

### 1. Facts-free summary FIRING (the important one — "we did summary testing")

- **State:** the summary LLM is wired token-driven and the **key was verified live** (200 on `api.openai.com`, `gpt-4o-mini`). But I did **not** observe it actually generate on a long thread — the trigger is `CHAT_SUMMARY_TRIGGER_TOKENS=2000` and my test threads were short.
- **Test:** drive a conversation past ~2000 tokens (either send a long turn or several turns), then:
  - Confirm a summary is generated (check Mongo `conversations.summary` for the latest conv; check `conduit-chat` logs for the `LlmSummaryService` call).
  - Confirm the assembled context includes the summary as a leading `system` message once the transcript exceeds the trigger.
  - **Critically, confirm it's FACTS-FREE** — the summary must contain topics/intent only, **never** values ($ amounts, holdings), entities-as-truth (REL IDs as facts), or entitlement conclusions. This is a hard invariant (`ADR-STATELESS-GATEWAY.md`). If any of those leak in, that's a bug.
- To make it fire fast for testing: set `CHAT_SUMMARY_TRIGGER_TOKENS=200` + recreate, then a couple of turns.

### 2. Files / MinIO

- Endpoint (`POST /api/files`) is real and hardened (size limit, 413), but **the web UI does not call it yet** — no attach button. Test the endpoint directly (multipart) if you want; UI wiring is a known todo.

### 3. Restart robustness (regression guard)

- Recreate `conduit-chat` and confirm an existing logged-in session still answers WITHOUT re-login (the token-persistence fix).
- Note: if **`iam-service` (Axiom)** restarts, it regenerates its signing key → existing tokens go stale → users must re-login and the **gateway must re-fetch JWKS** (restart gateway or it refreshes on unknown-kid). This is a known Axiom fragility (see follow-ups).

### 4. admin-console

- `apps/admin` runs as `admin-console` on **:5182** (Axiom console: Policies/Cerbos generator + operator workbench). Verify it loads + the nginx `/gateway-api` and `/api` proxies work.

---

## Known open items (expected to be incomplete — not regressions)

- Summary firing on long thread (test above).
- Files → chat UI wiring.
- `apps/chat/web` + `apps/admin` still carry local UI/gateway copies; migration to consume `@conduit/ui` + `@conduit/gateway-client` is a follow-up (packages are built + published in-workspace).
- Numbered clarify replies ("1") don't work with the stateless gateway — must name the client/ID (by design; UX follow-up).
- Container rationalization (retire legacy librechat/admin-ui, profile observability) is planned but NOT executed — needs e2e re-point. **Excludes `backend`.**

## Run / demo
```bash
docker compose -p orchestrator-demo up -d
# Chat  http://localhost:8099   (rm_jane / Meridian@2024)
# Admin http://localhost:5182
```
