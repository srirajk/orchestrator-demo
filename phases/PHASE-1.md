# Phase 1 — Skeleton & First Streamed Reply

**Goal:** The whole pipe exists — typing in LibreChat streams a reply back from the gateway
(even if the answer is hardcoded). Nothing intelligent yet; just proof the wiring is sound.

**Milestones:** M0, M1
**Read first:** `docs/technical-architecture-clear-boundaries.md` (the boundary),
`CLAUDE.md` §4 (stack) and §6 (hard rules — especially rule **a**, SSE correctness).

## Build (scope — simple path only)
- `docker-compose.yml` with the **core** profile: `redis-stack`, `gateway` (empty Spring Boot
  app), a placeholder `mock-agents` container, `librechat` (+ `mongodb`, Meilisearch disabled).
- Gateway: `POST /v1/chat/completions` (SSE) and `GET /v1/models` (returns one model,
  `meridian-assistant`). For now, **route every prompt to a single hardcoded reply**
  streamed as correct OpenAI SSE (role delta → content deltas → `[DONE]`).
- **Short-circuit LibreChat's auto-title call** so it doesn't break.
- `librechat.yaml` custom endpoint pointing `baseURL` at the gateway's `/v1`; `dropParams`
  set; `streamRate` ~35. No rebrand yet.

## Do NOT build
Routing, real agents, registry, synthesis, auth — none of it. Just the pipe.

## Automated acceptance (you run these)
- `docker compose up -d` → all core containers report healthy.
- `curl /v1/models` returns the one model.
- A streaming `curl` to `/v1/chat/completions` returns well-formed SSE ending in `[DONE]`.

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. Open LibreChat (e.g. `http://localhost:3080`).
2. Type any message (e.g. "hello").
3. **Confirm a streamed reply appears in the chat.**

**PASS =** a reply streams into the LibreChat UI. (Content can be a placeholder.)
On reaching this gate, write the steps + status to `BUILD_REPORT.md`, post the
`PHASE 1 COMPLETE` banner, and **halt** until the human replies "proceed to Phase 2".
