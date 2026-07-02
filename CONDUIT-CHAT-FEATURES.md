# Conduit Chat — Feature Spec (LibreChat parity + Conduit glass-box)

**For:** `apps/chat` (the end-user chat — the LibreChat replacement) per `ARCHITECTURE-SPLIT.md`.
**Date:** 2026-07-02 · **Owner:** Sriraj

> **Intent (read this first):** the new chat must be a **full-featured** conversational client — NOT
> a stripped MVP. It should reach **feature parity with LibreChat** for everything chat-related
> (conversations, projects/folders, file uploads, streaming, prompt library, etc.) **plus** Conduit's
> differentiators (the glass-box trace rail, entitlement-scoped grounded answers). "Modern like
> Axiom" is about the *look*; the *capabilities* must not regress from LibreChat.

**Audience — persona-agnostic (important):** this chat is for **any entitled user, not just
relationship managers** — RMs, underwriters, analysts, ops, compliance, execs, and any future role.
What each person can see and do is decided **entirely by their JWT claims (roles/book/segments/
clearance) + the manifest-declared domains** — never by hardcoded, RM- or wealth-specific
assumptions in the UI (the same World-B rule the gateway follows: zero embedded domain knowledge).
No domain/persona names, entity-type literals, or "which client?"-style copy baked into the client;
it's all driven by the effective manifest + the user's entitlements. Any persona in this doc
(`rm_jane`, `uw_sam`, …) is just an illustration.

Priorities: **P0** = parity floor for launch · **P1** = expected soon after · **P2** = later.
Every feature must respect Conduit's rules: **the user is the real principal** (Axiom OIDC — no
admin token), **entitlements gate everything**, and **agent outputs are the only ground truth**.

---

## 1. Conversations & Projects
- **P0 Persistent conversation history** — list in a sidebar, newest first, auto-titled from the
  first turn (the gateway already short-circuits the auto-title call).
- **P0 New conversation** / switch between conversations (each maps to a gateway `conversationId`).
- **P0 Rename, delete, archive** a conversation.
- **P0 Search conversations** (by title + message content).
- **P1 Projects / folders** — group conversations into projects (LibreChat "folders"/"bookmarks").
  Drag-to-organize, collapse/expand, per-project color/label.
- **P1 Pin / favorite** conversations to the top.
- **P1 Export conversation** — Markdown / JSON / copy-all (screenshot optional).
- **P2 Fork / branch** a conversation from any message (explore alternatives).
- **P2 Conversation sharing** — **internal-only** share links gated by entitlements; **no public
  links** (bank data). Default off; admin-configurable.

## 2. Messaging
- **P0 Streaming responses** — token-by-token via the gateway's **byte-exact SSE** (`stream:true`,
  `data: {json}` deltas, terminal `data: [DONE]`). This is the production composer that replaces the
  current MVP non-streaming path.
- **P0 Stop generation** — abort the in-flight stream (AbortController).
- **P0 Regenerate** the last response.
- **P0 Edit a sent message → re-run** from that point.
- **P0 Copy message** + **copy code block** (per-block button).
- **P0 Rich Markdown rendering** — headings, lists, tables, blockquotes, links, and **syntax-
  highlighted code blocks**.
- **P1 Math/LaTeX** rendering (KaTeX) — useful for financial formulas.
- **P1 Message feedback** — 👍/👎 + optional comment → write as a Langfuse score (ties into the eval
  loop).
- **P1 Grounding / citations** — render the agents/sources behind an answer (Conduit's "agent
  outputs are ground truth"); click a citation → see the source data. This is a Conduit upgrade over
  LibreChat.
- **P2 Artifacts / rich previews** — render returned tables/charts/HTML in a side "artifact" panel
  (LibreChat Artifacts equivalent) — e.g. a holdings table rendered as a real table.

## 3. Files & Attachments  *(explicitly required — full support)*
- **P0 File upload** — click, **drag-and-drop**, and **paste** (clipboard image/file).
- **P0 Image upload + vision** — attach images to a turn; render inline.
- **P0 Document upload for analysis** — PDF / DOCX / CSV / XLSX / TXT; used for grounded Q&A over the
  file (RAG or pass-through per gateway capability).
- **P1 Per-conversation attachment tray** — see/remove files attached to the conversation; re-use
  across turns.
- **P1 File management** — a "my files" view (list, delete, re-attach).
- **P2 Image generation** display (if a gateway image tool exists).
- **Enterprise controls (bank) — MUST design in, not bolt on:**
  - **Entitlements on uploads** — who may upload, size/type allowlist, per-domain rules.
  - **DLP + AV scan** on upload; block/redact sensitive content per policy.
  - **Retention & residency** — configurable retention; encrypt at rest; region pinning.
  - **Audit** — every upload/download is an audit event (actor, file, conversation).

## 4. Composer & Input
- **P0 Multi-line composer** — **Enter to send, Shift+Enter for newline** (fix the current
  no-Enter-submit gap), auto-grow, char/upload affordances.
- **P0 Attach button** (files, per §3) + drag-drop target.
- **P1 Prompt library / templates** — saved & shared prompt snippets, insert into composer
  (LibreChat "Prompts"). Personal + org-shared.
- **P1 Slash commands** — `/` menu for quick actions (new chat, switch agent, insert template).
- **P1 @-mention agents/domains** — target a specific agent/domain (wealth, servicing, insurance);
  the gateway routes accordingly.
- **P2 Voice input** (speech-to-text) and **read-aloud** (text-to-speech) of responses.

## 5. Model / Agent selection
- **P0 Assistant identity** — a clear "Conduit Assistant" header; the gateway model is
  `conduit-assistant`.
- **P1 Agent/domain scope selector** — optionally scope a conversation to a domain, or let routing
  decide. Reflect which agents answered (ties to citations §2).
- **P2 Presets** — saved conversation configs (scope, template). Model params like temperature are
  **hidden by default** for enterprise (gateway-controlled), exposed only if product wants it.

## 6. Conduit glass-box (differentiator — keep it)
- **P0 Live trace rail** — the existing glass-box (intent → agents → entitlement allow/deny →
  synthesis → complete), driven by `/trace/stream` **scoped to the current conversation** (fix
  NEW-1: strict `conversationId` filtering, no null-whitelist).
- **P1 Collapsible** — end-users may want it as an optional "why did I get this answer?" panel;
  power/ops users keep it open. Decide default per persona.
- **P1 Explicit denial UX** — when entitlements deny (e.g. out-of-book client), show the clear
  "Access denied" state + the trace reason (the product's trust story).

## 7. UX, accessibility, resilience
- **P0 Empty / loading / error / streaming states** on every surface — neutral empties (never red
  for no-data), skeletons, real error states, a visible streaming indicator.
- **P0 ErrorBoundary** — a thrown render must show a fallback, not white-screen the app
  (see `CODEX-WORKLIST.md` NEW-12).
- **P0 Keyboard-first** — shortcuts (new chat, focus composer, stop, search), visible focus rings,
  aria on icon-only buttons, focus-trapped dialogs.
- **P1 Theme** — light/dark, Axiom brand tokens (shared `packages/ui`).
- **P1 Responsive** — usable on tablet/laptop; sidebar collapses.
- **P2 i18n** scaffolding.

## 8. Identity, entitlements, audit  *(non-negotiable — from the ADR)*
- **P0 Axiom OIDC/SSO login — the user logs in AS THEMSELVES** (rm_jane), and the **user's JWT**
  (with `book`/`segments`/`clearance`/`roles`) is the Bearer to `/gateway-api`. This is what makes
  the entitlement/denial story correct **by construction** — and it **retires the P0 persona/
  impersonation problem** entirely (no admin token, no `/auth/impersonate` for the end-user path).
- **P0 401/expiry handling** — refresh or route to login on expiry; no silent stale sessions.
- **P1 Per-user history isolation** — a user only ever sees their own conversations/files.
- **P1 Usage/quota** surfacing if the gateway meters.

## 9. Gateway integration contract (reuse `packages/gateway-client`)
- **Streaming:** `POST /v1/chat/completions` `stream:true` → SSE (byte-exact); consume via
  fetch-stream (so `Authorization` header works), key done on `data: [DONE]`.
- **Non-streaming fallback:** `stream:false` → single `chat.completion` JSON.
- **Multi-turn:** send `X-Conversation-Id`; it must round-trip into trace frames (NEW-1).
- **Trace:** `GET /trace/stream` (SSE) scoped to the conversation.
- **Registry:** `/admin/domains`, `/admin/agents` for scope/citation display; `/trace/health`.
- Reuse the hardened hooks (`useTraceStream`, `useWorkbenchChat`) + fixes from `CODEX-WORKLIST.md`.

---

## The "do NOT strip" floor (parity with LibreChat)
Conversation history + projects/folders + search · **streaming** + stop + regenerate + edit-and-rerun
· **file upload (drag/drop/paste, images + documents)** · Markdown/code rendering · prompt library ·
Enter/Shift-Enter composer · copy/export · light/dark. If any of these is missing, it is **not** at
parity.

## Intentionally SKIP (LibreChat features not wanted here)
- Public/external share links (bank data) — internal-only if any.
- Bring-your-own API keys / arbitrary model endpoints — the gateway is the only backend.
- Plugin marketplace / arbitrary third-party tools — tools come from Conduit's agent registry.
- Multi-tenant model config UI — gateway/admin-owned, not end-user.

## Open decisions for Sriraj
1. **RAG on uploads:** does the gateway do document RAG, or does the chat pass files through to a
   gateway capability? (Drives §3 depth.)
2. **Glass-box for end-users:** default-on, collapsible, or admin/debug-only for RMs?
3. **Projects model:** simple folders, or first-class "projects" with shared prompts/files per
   project (Claude/ChatGPT-Projects style)?
4. **Voice/TTS** in scope for v1 or later?
