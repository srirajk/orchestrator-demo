# Phase 6 — Demo Polish: Clarify, Resilience, Rebrand

**Goal:** The demo feels real and bank-ready — it asks when unsure, survives an agent dying
mid-request, and shows Meridian branding.

**Milestones:** M10, M11, M12
**Read first:** `docs/master-build-plan-consolidated.md` §5 (uncertainty/clarification
model) and §4 (partial-result execution), `CLAUDE.md` §6 rule **f** (no LibreChat fork).

## Build (scope — simple path only)
- **One scoped clarification path:** when two capabilities tie or an entity is ambiguous, ask
  a precise question with **entitlement-filtered** options, then proceed on the answer.
- **Resilience beat:** wire the agent-kill into the demo flow (fault knob → degraded-but-
  graceful answer that names the missing piece). This is the executor's partial-result join
  made visible.
- **Meridian rebrand:** LibreChat logo, theme, title; hide the model selector so it reads as one
  bespoke assistant. **Config + cosmetic only — do not fork LibreChat code.**

## Do NOT build
Eval, load test (those are Phase 7).

## Automated acceptance (you run these)
- An ambiguous prompt yields a scoped clarifying question; answering it proceeds correctly.
- Killing an MCP agent mid-request yields a degraded answer that states the missing data.
- The UI shows Meridian branding and no model selector.

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. Type an **ambiguous** prompt → confirm a scoped clarifying question appears; pick an
   option; confirm it proceeds.
2. Start the hero prompt, then **kill the settlement agent** (fault knob) → confirm the
   answer still returns and **says settlement data is unavailable**.
3. Confirm the UI **shows Meridian branding** (branding, single assistant, no model picker).

**PASS =** the demo feels polished, asks smart questions, and degrades gracefully.
Write steps + status to `BUILD_REPORT.md`, post the `PHASE 6 COMPLETE` banner, and **halt**
until "proceed to Phase 7".
