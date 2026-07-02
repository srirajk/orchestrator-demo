# ADR — The gateway is stateless; memory is the client's problem

**Status:** Accepted · **Date:** 2026-07-02 · **Owner:** Sriraj
**Supersedes:** the "gateway memory-compaction" line of thinking (entity-session layers, gateway-side
summaries). **Relates to:** `ARCHITECTURE-SPLIT.md`, `CONDUIT-CHAT-FEATURES.md`, `CODEX-WORKLIST.md`.

---

## Context

The gateway was always meant to be a **stateless interpreter**: receive a prompt (+ the context the
caller sends) and *solve it* — route → resolve entities → enforce entitlements → fetch fresh data →
synthesize → return. That is also the contract it **exposes**: an **OpenAI-compatible
`/v1/chat/completions`**, which is stateless by definition — the client sends `messages[]` every
turn and the server holds nothing.

**We drifted.** The gateway grew a `ConversationSession` in Redis (resolved entities,
`lastAgentResults`, `authorizationCache`, `domainWorkflowState`, `deferredClarifications`). That is
the gateway holding *conversational memory*, which contradicts both the design intent and the API it
advertises.

**The drift caused bugs.** The multi-turn client-pivot bug (Codex QA #2/#3) existed *only because*
the session carried a prior entity (`REL-00042`) that overrode the entity named this turn (Okafor).
A stateless gateway reading the current `messages[]` cannot have that bug — there is no carried
state to override anything. The precedence and "reset doesn't clear the rail" bugs are the same
family. **The session store didn't just violate the principle — it manufactured a bug class.**

## Decision

**Make the gateway stateless for conversation. Memory + compaction are 100% the client's problem.**

1. **Retire `ConversationSession`-as-memory.** The gateway derives everything it needs per request
   from **`messages[]` + the JWT** — nothing persists between turns.
2. **`conversationId` is a trace/observability tag only** (Langfuse session grouping), never a
   memory key.
3. **Entity resolution = "latest explicit mention wins" over the sent conversation.** Extract the
   *human reference* the user is asking about — prefer the latest user message; if it names none,
   use the most-recently-referenced entity from earlier *user* turns. **Never** read an ID from a
   prior *assistant* message (World-B: the LLM extracts human references; deterministic lookup
   resolves them). This cleanly handles *both* the pivot (latest names Okafor → Okafor) and the
   follow-up ("and its cash?" → most recent = Whitman).
4. **The client owns all memory:** transcript, optional facts-free topical summary, and the decision
   of *what context to send each turn* (recent window + its summary). Exactly like calling OpenAI.

## Why this is right (consequences)

- **Kills the bug class at the root** — pivot, carry-forward precedence, and reset-doesn't-clear all
  disappear; there is no server state to desync.
- **Matches the advertised API** — a stateless OpenAI-compatible endpoint that is actually stateless.
- **Scales trivially** — no Redis session affinity, no session lifecycle/expiry/migration.
- **Retires the whole "gateway memory compaction" question** — it was solving a problem that
  shouldn't exist in the gateway.
- **Preserves all four invariants:** grounded (data re-fetched every turn), entitled (re-checked
  every turn), bounded (the *client* controls the context window), auditable (trace by
  `conversationId`).

## The gateway as a pure function (per request)

```
answer  =  gateway( messages[] , JWT )
             │           │         └── principal + claims (book/segments/clearance/roles) → entitlements
             │           └── conversation context (client-owned): recent turns + optional summary
             └── classify intent → extract+resolve entity (latest-mention-wins) → CHECK entitlement
                 → fetch fresh data → synthesize grounded answer → stream (SSE) / JSON
```
No reads or writes to any conversation store. `conversationId` header → attach to the trace only.

## What the client (Conduit Chat) owns

- Full transcript (its conversation store).
- Optional **facts-free** rolling topical summary (continuity/intent only — never values, entities-
  as-truth, or entitlement conclusions; see `CONDUIT-CHAT-FEATURES.md`).
- History compaction / what-to-send: a recent window (+ its summary) per turn.

## Migration (unwind the session — honest inventory)

| Currently in `ConversationSession` | New home |
|---|---|
| `resolvedEntities` (carry-forward) | **Removed** — re-resolve from `messages[]` (latest-mention-wins) each turn |
| `carriedCoverageId` logic in `ChatService` | **Removed** — resolve from conversation; no override path |
| `lastAgentResults` (for "explain that") | **Client** re-sends the relevant prior turn(s); gateway grounds on what's sent + fresh fetch |
| `authorizationCache` | **Removed** — already re-checks every turn; drop the cross-turn cache (stale-auth risk) |
| `domainWorkflowState` | **None needed** — Conduit uses flat plans, not long-running tasks. If ever needed → an explicit **task resource**, not a chat session |
| `deferredClarifications` | Encode in the conversation (the clarify question + the user's answer are just turns in `messages[]`) |
| `handleSummarization` intercept | **Removed** — it was a LibreChat coupling; the client owns summaries |
| `conversationId` | **Kept — trace/Langfuse grouping only** |

**On the interim pivot fix (already merged):** the "extract from latest message + session fallback"
fix was a correct *stopgap* inside the session model. Under this ADR it evolves into
conversation-aware **latest-mention-wins** resolution and the session fallback goes away. Not wasted
— it's the stepping stone; the stateless refactor makes the whole session (and its bug family)
vanish.

## Risks / things to get right

- **"Explain that / why did you say that"** needs the prior *answer* — so the **client must send a
  sufficient recent window** (this is a client contract, define the default, e.g. last N turns +
  summary). This is normal OpenAI-style usage.
- **Re-resolving entities each turn** is fine — it's a deterministic lookup, cheap, cacheable
  *within* a request. (An optional within-request memo is OK; a cross-turn store is not.)
- **Define the context contract**: how many recent turns the client sends, and whether it also sends
  its topical summary as a system/context message (the gateway treats it as context for
  classification only — and because extraction is scoped to the latest user message, a summary in
  context won't pollute entity extraction).

## Decisions (resolved 2026-07-02)

1. **Context window contract → CONFIG, not hardcoded** (no magic numbers in code). The client sends
   a recent window each turn, plus a facts-free rolling summary once the thread gets long — all
   driven by config with sensible defaults:
   - `chat.context.recentMessages` — how many recent messages to send each turn (**default 8**).
   - `chat.context.summaryAfterMessages` — start sending the rolling summary once the transcript
     exceeds this (**default 12**).
   - `chat.context.summaryMaxTokens` — cap on the facts-free summary (**default ~150**).
   The **gateway uses whatever the client sends** — it must NOT hard-truncate. The former
   intent-classifier/answer-synthesis fixed history windows have been removed.
2. **Cutover → phased** *(plain meaning: don't rip the old memory out in one go).* **Phase 1** —
   switch the gateway to the new way (derive the entity from the sent `messages[]`) while the old
   session code sits **idle**: still present, but not read for any decision; confirm everything still
   works. **Phase 2** — once proven, **delete** the now-unused session code. Safer than big-bang.
3. **Within-request entity memo → skip** *(plain meaning: don't cache the name→ID lookup inside a
   single request).* The lookup happens once per turn anyway, so caching adds complexity for no
   gain. Add it later only if profiling shows it's actually slow.

---

## Change set for Codex — do exactly this (in order)

### A. Gateway — Phase 1: derive state from the request; stop reading the session for decisions
1. **Entity resolution = latest-mention-wins over the sent `messages[]`.** In the
   intent/extraction path: extract the human entity reference from the **latest user message**; if
   it names none, scan back through the **user** turns in the sent window and take the
   most-recently-referenced entity; **never** read an ID from an assistant message. (This replaces
   today's "latest message only + session fallback".)
2. **`ChatService` coverage pipeline:** remove the `carriedCoverageId` branch and all
   `session.resolvedEntity(...)` reads. Resolve the entity from the conversation (step 1) → run the
   entitlement CHECK (unchanged — already every turn) → fetch. A newly-named client re-resolves +
   re-authorizes; a follow-up naming no client uses the most-recent conversation entity.
3. **Stop using the session for any decision.** `conversationId` → **trace/Langfuse tag only**. (You
   may leave `sessionStore.save` as a temporary no-op; do not read it.)
4. **Verify Phase 1:** pivot (Whitman → Okafor, same conversation) denies **3/3**; "and its cash?"
   stays on Whitman; `scripts/smoke.sh` + full e2e green; `world-b-check.sh` CRITICAL 0.

### B. Gateway — Phase 2: delete the session (only after A is verified)
5. Remove `ConversationSession`, `ConversationSessionStore`, all reads/writes, and the fields
   `authorizationCache`, `domainWorkflowState`, `deferredClarifications`, `lastAgentResults`.
6. Remove `isSummarizationRequest` + `handleSummarization` (LibreChat coupling — the client owns
   summaries now).
7. Keep `conversationId` solely for Langfuse trace grouping. Re-run e2e + world-b.

### C. Client (`apps/chat`) — the context contract
8. Own the full transcript locally (the chat's conversation store).
9. On each turn POST `messages` = **[optional rolling summary as a leading context message] + last
   8 messages**.
10. Maintain a **facts-free rolling topical summary** (topics/intent only — **no values, no
    entity-as-truth, no entitlement conclusions**); update it when the transcript exceeds ~12
    messages; cap ~150 tokens.
11. "Explain that / why" needs no special handling — the last-8 window carries the prior answer.

### D. Do NOT
- Do **not** add any cross-turn store (gateway or a new one).
- Do **not** put values / entities-as-truth / entitlement conclusions in the client summary.
- Do **not** use `conversationId` as a memory key — trace tag only.
- Do **not** trust any entitlement across turns — CHECK every turn (already the case; keep it).
