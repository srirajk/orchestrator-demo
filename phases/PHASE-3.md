# Phase 3 — Registration & Routing Decisions

**Goal:** All 9 agents are registered (their schemas introspected from the specs), and a
prompt produces the **right routing decision** — visible via a debug endpoint, before any
invocation.

**Milestones:** M3, M4
**Read first:** `docs/agent-registration-schema-a2a-aligned.md` (canonical capture schema),
`docs/agent-registry-demo-spec.md` (flow, introspection, Redis storage), and the resolver
sections of `docs/master-build-plan-consolidated.md` (§5 resolution model).

## Build (scope — simple path only)
- Validate each manifest against the **pinned** `docs/agent-manifest.schema.json` (canonical
  contract — do not regenerate it); write the **9 manifests**.
- **Registry loader:** validate each manifest → **introspect the spec** (FastAPI
  `/openapi.json` for Wealth; MCP `tools/list` for Servicing) to derive input/output schema →
  store as RedisJSON → embed example prompts into the HNSW vector index. Bootstrap at startup
  through the same path used by `POST /admin/agents`. Add `GET`/`DELETE /admin/agents`.
- **Resolver Stage A+B:** embed the prompt → vector search → **confidence floor** → filter by
  `domain` + `is_mutating==false` → fan-out decision.
- A **debug endpoint** `GET /debug/resolve?prompt=...` returning the selected capabilities
  with scores and the fallback flag (so routing is inspectable without invoking agents).

## Do NOT build
Input synthesis, agent invocation, answer synthesis. Routing decision only.

## Automated acceptance (you run these)
- 9 agents load + index at boot; an invalid manifest is rejected.
- An **unseen paraphrase** of a registered prompt finds the right agent.
- The hero prompt resolves to the correct ~7-agent subset and **excludes `nav`**.
- A nonsense prompt falls below the confidence floor (fallback).

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. `curl '/debug/resolve?prompt=<hero prompt>'` → confirm ~7 agents selected across both
   domains, with scores, and **`nav` is not among them**.
2. Try a **reworded** prompt → confirm it still routes to the right agents.
3. Try an off-topic prompt (e.g. "what's the weather") → confirm it falls back, not
   misroutes.

**PASS =** routing decisions look correct for prompts the human types.
Write steps + status to `BUILD_REPORT.md`, post the `PHASE 3 COMPLETE` banner, and **halt**
until "proceed to Phase 4".
