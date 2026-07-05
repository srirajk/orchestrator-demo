# Codex Brief — Insights App: The 7 Boards (STEP 4, AFTER the auth shell)

Build the 7 dashboards **inside the already-working authenticated Insights shell** (step 3). Match the
approved mockup pixel-for-pixel, fed by the live Insights API. Do NOT start until the auth shell is built
and verified (admin lands in the shell, chat_user gets access-denied).

## Ground rules
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
- **Only `apps/insights/web`** — the app already exists (step 3). **Create NO new folders.**
- **DO NOT touch:** the gateway (the API + RBAC are DONE and verified), iam, `backend-*`, the Chat app.
  World-b CRITICAL 0. Commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution.
- **Design source (pixel spec):** the approved Conduit dashboard mockup — its tokens, panel styles,
  layout, and copy. Reproduce it faithfully.

## Wording (MANDATORY — read `conduit-ui-tests/INSIGHTS-WORDING.md` first)
Apply the canonical terminology to **every** string. Key points:
- It's **Conduit Insights** / **Insights** — **never** "console" or "workspace."
- **Correct the access-denied page** you built in Step 3a: heading → `You don't have access to Insights`;
  body → `You're signed in as {name}, but Conduit Insights is available only to administrators. Contact
  your Conduit admin if you need access.` **Remove "workspace."**
- Use the **7 canonical board titles** from the guide (Executive Overview · Governance & Trust · AI
  Pipeline · Agent Fleet · Gateway Deep-Dive · Live Decision Trace · Quality & Economics) — label each
  API board by its content; flag any that don't map cleanly.

## The API (live + verified — build against it)
`GET /v1/insights/boards/{boardId}` (1–7), called with the signed-in bearer token, returns:
```
{ "panels": [ { "id","title","type","unit","status", "value"?, "series"?, "rows"? } ] }
```
- `type ∈ {stat, area, line, donut, bars, table, waterfall}`
- `status ∈ {"ok","unavailable"}`  ·  `series` = array of points, `rows` = array of records.
Boards (confirmed live): 1 Executive · 2 Traffic/Intent · 3 Governance · 4 Agent Perf · 5 Reliability ·
6 Live Trace · 7 Cost/Quality. (Board 7 cost may read 0 until Langfuse has priced traffic — render it
truthfully, don't fake numbers.)

## Build
1. **Board switcher** — the left rail from the mockup (7 boards), inside the authenticated shell.
2. **Panel renderers** matching the mockup exactly: `stat` (big mono number + delta), `area`/`line`
   (SVG, area fill + faint grid + emphasized endpoint), `donut`, `bars` (labelled tracks), `table`
   (overflow-x auto, tabular-nums), `waterfall` (the Live-Trace gantt). Drive each from the panel `type`.
3. **Data loading** — fetch `/v1/insights/boards/{id}` per board (via the SPA's bearer token); loading
   skeletons; `status:"unavailable"` panels render a quiet empty state, **not** an error.
4. **Theme-aware** (light + dark), responsive grid, `aria` on interactive controls.
5. **Refresh** — a manual refresh + a sensible auto-refresh interval (e.g., 30s), pausing when hidden.

## Verify (browser)
- As `insights_admin` → all 7 boards render with **real data**, matching the mockup; switching boards is
  instant; `unavailable` panels degrade gracefully; light/dark both clean.
- As `rm_jane` → still blocked at the access-denied shell (unchanged from step 3).

## Completion contract
All 7 boards done. `npm run build` green, world-b 0, committed, `conduit-insights` rebuilt + healthy.
Screenshots of 4+ boards (incl. Governance and Live Trace). Only `apps/insights/web` touched, no new
folders. No silent skips — if a board can't be finished, finish the rest, report exactly what's left,
build passing.
