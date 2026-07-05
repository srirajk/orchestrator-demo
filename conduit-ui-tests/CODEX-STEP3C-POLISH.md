# Codex Brief — STEP 3c: Insights boards polish (layout + timeframe + nav + wording)

The boards load and RBAC works, but they don't match the mockup yet. Fix four things in
`apps/insights/web` (mainly `App.tsx` + `index.css`). Make it look like the approved mockup.

## Ground rules
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Runtime
  docker `orchestrator-demo`, Insights at **http://localhost:5175**. Logins: `insights_admin`,
  `rm_jane` (both `Meridian@2024`).
- **`apps/insights/web` only.** Do NOT touch the gateway/iam/`backend-*`. world-b 0. Commit on
  `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution. Rebuild `conduit-insights`.
- **Design source:** the approved Conduit dashboards mockup — reproduce its layout faithfully.
- **Wording:** apply `conduit-ui-tests/INSIGHTS-WORDING.md` to every string (see item 4).

## 1. Layout — make it the full-width dashboard (currently it's a narrow left column)
Right now everything is crammed in a ~320px left column with the right ~2/3 of the screen empty. Match
the mockup: a **full-height left rail** (board nav) + a **wide main area with a responsive 12-col panel
grid** that **fills the viewport width**. **Center** the SSO landing + no-access cards (they're
left-aligned now). No horizontal page scroll; wide tables scroll inside their own container.

## 2. Timeframe selector (top bar)
Add a **timeframe dropdown** in the top bar — **24h / 7d / 30d, default 24h**. On change, pass
**`?range=24h|7d|30d`** to `GET /v1/insights/boards/{id}?range=...` and re-fetch the active board.
*(The API accepts `range` — added on the backend; if a board ignores it, that's fine, just send it.)*

## 3. Board nav — ONE clean switcher with canonical titles (drop the numbers)
There are currently **two** switchers — a named nav AND a redundant `1 2 3 4 5 6 7` row. **Remove the
numbers.** Keep one named rail using the **canonical titles**:
Executive Overview · Governance & Trust · AI Pipeline · Agent Fleet · Gateway Deep-Dive ·
Live Decision Trace · Quality & Economics. (Map each API board id to its title by content.)

## 4. Wording — finish what Step 3b started
The no-access **body** still says "workspace." Fix it per the guide:
> **You don't have access to Insights** · *You're signed in as {name}, but Conduit Insights is available
> only to administrators. Contact your Conduit admin if you need access.*
Remove **every** user-facing "workspace"/"console." (CSS class names are fine; UI copy is not.)

## Verify (browser)
- The dashboard **fills the screen** and matches the mockup (rail + wide grid); cards centered; no page-
  level horizontal scroll; light + dark both clean.
- **Timeframe** switches 24h/7d/30d and the numbers change.
- **One** nav, canonical titles, **no `1–7` row**.
- No-access page: **no "workspace"** anywhere; correct body copy.

## Completion contract
`npm run build` green, world-b 0, committed, `conduit-insights` rebuilt + healthy. Screenshots: a board
at full width + the timeframe selector + the corrected no-access page. Only `apps/insights/web` touched.
