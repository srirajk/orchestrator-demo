# Phase 4 — The Real End-to-End Answer  ★ core demo

**Goal:** The hero prompt returns **one grounded, merged answer** fanned across HTTP **and**
MCP agents in parallel. This is the milestone phase — the thing the whole demo exists to show.

**Milestones:** M5, M6, M7
**Read first:** `docs/input-synthesis-deep-spec.md` (Extract→Resolve→Bind),
`docs/execution-orchestration-layer.md` (Plan + executor + harness),
`docs/master-build-plan-consolidated.md` §6 (answer synthesis & grounding).
Hard rules in play: **b** (zero fabricated IDs), **c** (agent output untrusted + only ground
truth), **d** (partial-result tolerant).

## Build (scope — simple path only)
- **Input synthesis** — Extract (LLM pulls human references only) → Resolve (deterministic
  lookup to IDs) → Bind. **Build and test this in isolation against fixtures first.**
- **`ProtocolAdapter`** interface with `describe()` + `invoke()`; `HttpAdapter` (OpenAPI-
  driven) + `McpAdapter` (MCP client).
- **Plan executor (flat)** + **Resilience4j harness** per the execution spec: per-agent
  breaker, two-tier timeouts, bulkhead, retry (read-only); **partial-result joins** (a failed
  node never cancels siblings).
- **Answer synthesis** via Z.AI GLM, streaming: agent outputs delimited as **DATA only**, no
  outside knowledge or invented numbers; **post-synthesis numeric grounding check** (every
  number in the answer appears in some agent output); **partial-result honesty** (state any
  missing data). Wire into `/v1/chat/completions` streaming.

## Do NOT build
Entitlements, glass-box, clarification, rebrand. Just get the grounded answer flowing.

## Automated acceptance (you run these)
- Input-synthesis fixtures pass with **zero fabricated identifiers**; a missing reference
  triggers clarification, not a guess.
- The hero prompt fans across HTTP **and** MCP in parallel and returns a merged answer.
- Killing one agent (`_fail=true`) still returns an answer from the survivors.
- The numeric grounding check passes; facts are attributed to their source agent.

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. Open LibreChat, type the **hero prompt** (from `docs/agent-catalog.md`).
2. Confirm **one merged answer streams in**, covering holdings, performance, risk,
   settlements, corporate actions, and cash.
3. Pick a number in the answer and confirm it **matches the canned data** of the agent that
   owns it (grounding works).
4. (Optional) Re-ask with the settlement agent set to `_fail=true` → answer still returns and
   **says settlement data is unavailable**.

**PASS =** the real demo answer appears, grounded and merged across both protocols.
Write steps + status to `BUILD_REPORT.md`, post the `PHASE 4 COMPLETE` banner, and **halt**
until "proceed to Phase 5".
