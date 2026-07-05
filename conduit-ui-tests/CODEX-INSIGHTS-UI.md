# Codex Brief — Conduit Insights UI (native React boards)

Build the native **Insights** section in the Conduit UI — the 7 executive dashboards, admin-gated,
consuming the Insights API. **Make it match the approved mockup pixel-for-pixel.**

## Inputs (read both first)
- **Design (pixel spec):** the approved mockup — `conduit-ui-tests/` context + the published artifact of
  Conduit's 7-board dashboard suite (ink-navy `#0B1220` ground, lifted panels `#131C2E`, **Conduit gold
  `#F0C45A`** as the only accent, mono figures with `tabular-nums`, semantic green `#3DD68C` / red
  `#F0655A` / info `#5B9BF0` kept separate from the accent). Match its layout, tokens, and panel styles.
- **Full spec:** `conduit-ui-tests/INSIGHTS-SPEC.md`.
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Runtime
  docker project `orchestrator-demo`, chat at http://localhost:8099.

## The API contract (the backend, built separately, serves this)
- Gateway: `GET /v1/insights/boards/{boardId}` (1–7) → `{ panels: [ { id, title, type, unit, status,
  value?, series?, rows? } ] }` where `type ∈ {stat, area, line, donut, bars, table, waterfall}` and
  `status ∈ {"ok","unavailable"}`.
- **You consume it via the BFF:** add a proxy `GET /api/insights/boards/{id}` in `apps/chat/bff` →
  gateway `/v1/insights/*`, forwarding the session's bearer token (same pattern as the chat proxy).

## Scope (frontend only)
1. **BFF proxy** — `/api/insights/*` → gateway `/v1/insights/*`, session-authed. (`apps/chat/bff`.)
2. **Insights section** in `apps/chat/web` — a new left-nav entry + route rendering the 7 boards with a
   board switcher (like the mockup's rail). Each board = a responsive 12-col grid of panels.
3. **Panel renderers** matching the mockup: `stat` tiles (big mono number + delta), `area`/`line` (SVG,
   area fill + faint grid + emphasized endpoint), `donut`, `bars` (labelled tracks), `table` (overflow-x
   auto), `waterfall` (the Live Trace gantt). Reuse the mockup's exact styling.
4. **Graceful states** — `status:"unavailable"` panels render a quiet empty state (not an error); loading
   skeletons; theme-aware (light + dark).
5. **Admin gating** — the Insights nav item + route are shown **only for the admin role**; hide entirely
   from a `chat_user`. The API returns 403 for non-admins — handle it cleanly (no crash).

## Constraints
- Frontend only: `apps/chat/web` + the BFF proxy (`apps/chat/bff`). Do NOT touch gateway/iam/authz/
  registry (the API + RBAC are the backend task) or `backend-*`. Stay inside the repo folder.
- `world-b-check.sh` CRITICAL 0; commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution.
- **Browser-verify.** Rebuild `conduit-chat` after.

## Definition of done
- Log in as **admin** → the Insights section shows all 7 boards, rendering real data from the API, matching
  the mockup; `unavailable` panels degrade gracefully.
- Log in as **`chat_user`** → **no Insights nav**, and the route/API is not accessible (403 handled).
- `npm run build` green, world-b 0, committed, env healthy at :8099. Screenshots of 3–4 boards.
- No silent skips — if a board can't be finished, finish the rest and report exactly what's left, build passing.
