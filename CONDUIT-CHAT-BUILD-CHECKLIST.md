# Conduit Chat (`apps/chat`) — Build Checklist

**Branch:** `feat/conduit-chat` (worktree `/Users/srirajkadimisetty/projects/orchestrator-chat`)
**Goal:** a *real*, persistent, LibreChat-like chat — Mongo-backed, real Axiom OIDC login, streaming,
history that survives refresh — not an ephemeral demo. Built fresh on the Axiom design system (not a fork).
**Contract:** every item below carries a status — `[ ] todo` · `[~] scaffolded` · `[x] done`. Nothing
is silently skipped. "Done" for P0 = it works end-to-end and persists.

Legend: **P0** = required for a working persistent chat · **P1** = expected · **P2** = later.

---

## A. Scaffolding & structure
- [ ] **P0** `apps/chat/web` — Vite + React + TS + Tailwind, importing the Axiom design tokens/ui kit.
- [x] **P0** `apps/chat/bff` — BFF (Java, Spring Boot, virtual threads).
- [x] **P0** MongoDB connection in the BFF (reuse existing `mongodb` service — user directed Mongo).
- [x] **P0** MinIO client in the BFF for files (reuse existing `minio` service).
- [x] **P0** Dockerfile(s) + one `apps/chat` service wired into the **single** canonical compose.
- [x] **P0** Dev proxy: web → BFF; BFF → gateway (`/v1/chat/completions`, `/trace/stream`) + IAM.

## B. Auth — real user login (retires P0/persona by construction)
- [ ] **P0** Axiom OIDC login (redirect → callback → session cookie).
- [ ] **P0** Session middleware; every BFF route requires the logged-in user.
- [ ] **P0** Forward the **user's** access token as `Bearer` to the gateway (entitlements as the real user).
- [ ] **P0** Logout.
- [ ] **P1** 401/expiry → re-login; refresh handling.

## C. Data model (MongoDB, per-user isolation)
**Backend persistence built in the Java BFF; remaining work is the web conversation-management UI.**

- [x] **P0** `Conversation` {id, userId, title, projectId?, summary, createdAt, updatedAt}.
- [x] **P0** `Message` {id, conversationId, userId, role, content, files?, createdAt}.
- [~] **P1** `Project` {id, userId, name, color}.
- [~] **P1** `Attachment` {id, userId, conversationId, name, mime, size, storageKey}.
- [x] **P0** Every query scoped to `userId` (isolation).

## D. Conversations — persistence (THE core)
**Backend persistence built in the Java BFF; remaining work is the web conversation-management UI.**

- [x] **P0** List conversations (per user, newest first; load from Mongo).
- [x] **P0** New conversation.
- [x] **P0** Open/switch → load messages from Mongo.
- [x] **P0** **Survives refresh + across devices** (server-side, not localStorage).
- [x] **P0** Auto-title from first turn.
- [~] **P1** Rename / delete / archive.
- [~] **P1** Search conversations.

## E. Messaging — the chat
- [x] **P0** Send: BFF persists user msg → assembles context → calls gateway `stream:true` → **streams** to client → persists assistant msg.
- [~] **P0** Streaming UI (token-by-token; consume byte-exact SSE, done on `data: [DONE]`).
- [~] **P0** Markdown + syntax-highlighted code + copy.
- [~] **P1** Stop generation (AbortController).
- [~] **P1** Regenerate / edit-and-rerun.
- [~] **P1** Message feedback → Langfuse score.

## F. Memory / compaction (client-owned, per ADR-STATELESS-GATEWAY.md)
- [ ] **P0** BFF assembles context = last `recentMessages` (config, default 8) from Mongo.
- [ ] **P1** Facts-free rolling summary once transcript > `summaryAfterMessages` (12), cap `summaryMaxTokens` (~150).
- [ ] **P0** Config-driven (no magic numbers): `chat.context.{recentMessages,summaryAfterMessages,summaryMaxTokens}`.
- [ ] **P0** Summary is **facts-free** — never values/entities-as-truth/entitlements.

## G. Projects / folders
- [ ] **P1** Create/rename/delete projects; assign conversations; sidebar grouping.

## H. Files / attachments
- [ ] **P1** Upload (click/drag/paste) → MinIO; attach to message; display (image inline, doc chip).
- [ ] **P2** File management view; entitlement/DLP hooks (stub).

## I. Composer & input
- [ ] **P0** Multiline; **Enter = send, Shift+Enter = newline**; auto-grow.
- [ ] **P1** Attach button; prompt library.
- [ ] **P2** Slash commands; @-mention agent/domain; voice.

## J. Glass-box (Conduit differentiator)
- [ ] **P1** Trace rail (reuse `packages/gateway-client` SSE/types + shared `packages/ui`), conversationId-scoped; collapsible.
- [ ] **P1** Explicit "Access denied" UX on entitlement denial.

## K. UX / resilience
- [ ] **P0** `ErrorBoundary` (no white-screen).
- [ ] **P0** Empty / loading / streaming / error states (neutral no-data, never red-for-empty).
- [ ] **P1** Light/dark theme; responsive; a11y (aria, focus, keyboard).

## L. One env / docker
- [ ] **P0** `apps/chat` service(s) in the **single** canonical `docker-compose` (no second env).
- [ ] **P0** Reuse existing `mongodb` + `minio` (do NOT add stores).
- [ ] **P1** Retire `librechat` from default profile once chat is up (its Mongo store stays, now serving chat).

## M. Validation (the anti-half-job gate)
- [ ] **P0** Builds: web `tsc`+`vite build`, BFF `tsc`.
- [ ] **P0** Smoke path: login → new conversation → send → streamed grounded answer → **refresh → history persists** → out-of-book client → **Access denied**.
- [ ] **P0** Does NOT break existing services; world-b CRITICAL 0 unaffected.
- [ ] **P0** MORNING-REPORT: exact done/scaffolded/todo status of EVERY item above.

---

### Honest target for this session (1–2h)
Land **all P0** to a working state (persistent Mongo chat + OIDC + streaming + history + denial + one env),
**scaffold P1** (projects, files, trace rail, rename/search), and **document P2** precisely. Every box gets
a truthful status in the morning report — that's the contract.
