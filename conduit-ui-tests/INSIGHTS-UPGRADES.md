# Conduit Insights — Upgrades & Roadmap (living doc — APPEND, never delete)

Durable record of agreed design upgrades for Insights / telemetry / governance. Items here are
**decided** but not necessarily built yet. Do not delete entries; mark status as they land.

---

## UPGRADE 1 — Telemetry privacy + propagation control (do BOTH A and B) · the org-policy showcase
**Status:** ✅ design locked · ⛔ not built yet (demo uses the shortcut below)
**Owner decision:** Sriraj — "we can do both… the lookup manifest is the org-policy showcase."

### The design (layered)
**A — `conversationId` is the only carrier; resolve user+segment via lookup.**
- Propagate **only an opaque `conversationId`** through the trace/baggage → **zero PII in the telemetry
  pipeline** (nothing to header-leak).
- Resolve **`conversationId → userId + segment` at query time** via a lookup/adapter (`ConversationSource`)
  owned by whoever owns conversations — so it also **survives a gateway-only sale**.
- Consequence: cost-by-user becomes a **query-time join** (Langfuse cost by convoId + the lookup). Keeps
  **Langfuse PII-light** — the right trade for a bank.

**B — manifest-declared propagation policy (World-B native).**
- The **agent manifest declares its trust/propagation policy** (trusted-internal vs external).
- The **gateway strips baggage at the boundary** to agents that shouldn't receive it. **Config, not
  gateway code** — onboarding an agent + its propagation rule is a manifest edit.

**Together:** convoId is the safe carrier · user/segment resolved at the edge via lookup · the manifest
polices what crosses each agent boundary.

### Why this is a *product* feature, not just plumbing (the showcase)
The **lookup manifest + per-agent propagation policy = "org policies" you can demo**: an organisation
controls, **declaratively**, what data propagates to which agent/boundary. Data-residency / PII-governance
as manifest config. We already have the manifest infra to show this.

### Current state vs target (honest)
- **Now (demo shortcut):** the gateway tags **Langfuse directly** with `user_id` + `segment` on the root
  chat-turn span (simpler; fine because our stack + admin is cleared for all). See `ChatService.java` Phase 2.
- **Target:** convoId-only carrier + resolve-via-lookup (A) + manifest propagation policy (B).
- **Migration is UI-safe:** only the read layer changes *how* it resolves user/segment — the dashboards
  don't change.

### Guardrails (from the OTel-baggage discussion)
- Baggage is **observability-only, never authz** — authz stays on the verified JWT (JWT is source, baggage
  is carrier).
- Don't header-leak PII to **external** agents — that's exactly what B controls.
- If ever using the **spanmetrics connector**, exclude high-cardinality attrs from Prometheus labels.

---

## UPGRADE 2 — Data-classification gating of shown content
**Status:** ✅ agreed interim · ⛔ gating not built
- **Now:** admin holds the **highest data classification**, so showing trace content (incl. the prompt
  text stored in Redis) is legitimate. Surface the posture honestly: **"Viewing at: highest classification."**
- **Soon:** gate *what content is shown* by the **viewer's** `data_classification`, reusing the existing
  ABAC gates. Redis traces carry the prompt text (PII), so this is a real control, not cosmetic.

---

## UPGRADE 3 — Cold-tier long-term analytics
**Status:** ✅ seam defined · ⛔ not built
- Hot = Mongo (convos) + Langfuse (cost/eval) + Redis (traces) + Prometheus, all **TTL'd** to one
  configurable `CONDUIT_RETENTION_DAYS`.
- Cold = **object storage dump** (parquet) for long-term analytics via a warehouse query path — data moves
  hot→cold **before** it expires. Build only when history beyond the hot window is actually needed.

---

## UPGRADE 4 — Retention alignment (near-term, small)
**Status:** ⚠️ open — needs owner OK
- **Redis decision-trace TTL = 24h; conversations persist longer → replay deep-dive only works for last 24h**
  (the 48 demo-conversation traces expire in ~24h). Config: `conduit.telemetry.trace-ttl-seconds`.
- **Recommended:** align to a shared `CONDUIT_RETENTION_DAYS` (e.g. 30d). One-line config change.
