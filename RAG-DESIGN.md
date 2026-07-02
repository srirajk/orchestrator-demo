# RAG + Projects — Design (PARKED, revisit later)

Status: **parked** — captured for a later build, not started. Decided in discussion 2026-07-02.

The goal: LibreChat-style **Projects** (folders of docs) + **RAG** (chat grounded on your uploaded docs), but built on the stack we already run and consistent with the manifest-driven, stateless-gateway architecture — **not** a LibreChat clone.

---

## Decision: metadata "LLM wiki" retrieval, NOT a vector store (for now)

We explicitly rejected embeddings/Redis-vector RAG for v1. For a **project-scoped wiki** with a modest number of docs, you don't need similarity search — you need a **catalog + let the LLM pick the doc**, exactly like the gateway already routes over agent manifests. LLM reasons over a table-of-contents, selects, we fetch.

- **No vector DB, no embedding pipeline for v1.**
- **Embeddings are the seam:** only add them as a *pre-filter* if a single project ever holds thousands of docs (ToC won't fit in context). Build the simple path, leave the seam.

## Storage & scoping

**MinIO object keys namespaced by identity:**
```
{userId}/_personal/{fileId}        ← no project (user-level, loose)
{userId}/{projectId}/{fileId}      ← inside a project (the "folder")
```

**Doc manifest — lives in Mongo next to `Project` (no new store):**
```
{ fileId, userId, projectId, title, summary, topics[], storageKey, mime, size, createdAt }
```

- On **upload**: store bytes in MinIO; generate a one-line **`summary`** ("what this doc is about") via the existing OpenAI summarizer → that's the wiki entry.

## Retrieval flow (catalog → LLM selects → fetch)

1. Turn arrives in a project (or personal scope).
2. Build a compact **table of contents** from the manifest (title + summary per doc), filtered to the authed `userId` + `projectId`.
3. Hand the LLM the ToC + the question → it returns the relevant `fileId(s)`.
4. **Fetch** those from MinIO → inject the text as **delimited context** into `messages[]`.
5. Token budgeting handled by the existing `CHAT_CONTEXT_MAX_TOKENS` machinery.

Retrieval stays **client-owned** (BFF does it); the gateway remains stateless + domain-agnostic. RAG is just another form of client context, like the rolling summary.

## The two contracts that make it correct

1. **Isolation = the security boundary.** MinIO key *and* manifest query both filtered by the **server-side authenticated `userId`** (never client-supplied scope). That single filter prevents cross-tenant leakage. Non-negotiable.
2. **Provenance & trust separation.** Entitled bank data (from agents behind a coverage CHECK) and a user's uploaded PDF are **different trust classes**. In the synthesis prompt they must be delimited and labelled — *"from your uploaded document"* vs *"from the bank's systems"* — so the answer never blurs "your doc claims $X" with "the ledger says $X".
3. **Lifecycle/cascade.** Delete file → purge manifest entry + MinIO object. Delete project → cascade. Keep MinIO ↔ manifest ↔ `Project`/`Message` consistent (no orphan docs answering questions).

## Projects (folders)

Extend the existing BFF `Project` model: conversations + files belong to a project; a conversation with no project uses the `_personal` scope. RAG retrieval is confined to the active project's manifest.

## Also parked (same discussion) — Agent-provenance chip [high wow / low cost]

Surface **which agents answered** under each chat reply (chip → expand to the full fan-out + which passed entitlement). The data already exists in the glass-box trace (`/trace/stream`, per `X-Conversation-Id`) and `@conduit/gateway-client`; this is a UI feature over existing telemetry, no new backend. Build order suggestion: **(1) agent-provenance chip → (2) file-upload UI (backend already done) → (3) this project-RAG.**

---

**Revisit:** when greenlit, this doc is the spec — lock the ToC/selection prompt, the Mongo manifest schema, and the isolation/provenance/cascade contracts, then implement in the BFF.
