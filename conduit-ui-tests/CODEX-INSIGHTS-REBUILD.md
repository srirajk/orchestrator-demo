# Codex Brief — Insights UI REBUILD (port the mockup file exactly)

**Stop incrementing on the existing UI.** Commit `e27658a` wired the data correctly but kept the old
boards-1-7 light-theme shell — it looks nothing like the approved design. This pass is a **visual rebuild**
of `apps/insights/web` to match the mockup **exactly**. **Keep the working API calls; throw away the old shell.**

## THE SOURCE OF TRUTH — port this file, do not interpret
**`/Users/srirajkadimisetty/projects/orchestrator-demo/conduit-ui-tests/insights-ops-plane-mockup.html`**
(540 lines, self-contained HTML+CSS+JS). **Open it in a browser and read it.** Port its **theme, layout,
CSS, nav, and every view into `apps/insights/web` (`App.tsx` + `index.css`) verbatim.** Lift the CSS
wholesale — the dark tokens, panels, gauges, bars, rails, badges are all defined there. The running app at
`:5175` and this file must look like **the same product** when you're done.

## KEEP from `e27658a` (the data works — reuse it)
`GET /v1/insights/boards/{id}?range=` · `GET /v1/insights/cost?range=` · `GET /v1/insights/conversations/{id}/trace`
· the Langfuse continuous scores · the 24h/7d/30d control · the SSO/auth shell. **Only the presentation changes.**

## DELETE / FIX — the current build is wrong on every one of these
- ❌ **Light theme** → the mockup's **dark ink-navy mission-control** theme (`--bg:#0B1220`, gold `#F0C45A`, semantic green/amber/red).
- ❌ **Old nav** (Overview/Traffic/Governance/Agents/Reliability/Trace/Cost) → the mockup's grouped nav:
  **Operate** (Overview · Trust & Governance · Agents & Pipeline · Economics) · **Evaluate** (Answer quality) ·
  **Audit & investigate** (By user · Decision replay).
- ❌ **The `1 2 3 4 5 6 7` number row** → **GONE.** One named nav only. (We've said this three times.)
- ❌ **Flat grid** → the **storyline**: every view opens with the health strip / verdict / "needs attention" banner, then evidence panels, then depth.
- ❌ **Missing views** → build them all, exactly as in the mockup:
  - **Overview** — health strip, KPI cards, request-volume area, outcome donut, **outcomes & failure taxonomy**, live decision feed.
  - **Trust & Governance** — denials by gate / by segment, 0 fabricated IDs.
  - **Agents & Pipeline** — fleet, latency-by-stage, **runtime & resilience** (VT/bulkhead/breakers), **latency histogram**, agent selection, intent mix.
  - **Economics** — cost verdict → by segment → trend → by model → by user.
  - **Answer quality** — gauges (grounding/safety/relevance/honesty), **distribution**, **outliers → click → replay**, grounding-by-model, "✓ Build certified · CI" badge.
  - **By user** — user picker → cost / continuous eval / entitlement decisions / their conversations / compactions.
  - **Investigate** — the **narrative decision replay** (a human story, not a table) + the **span-timeline waterfall**.

## Wire REAL data into the mockup's panels — never the mockup's fake numbers
The mockup's numbers are placeholders. Map live data into every panel:
Overview KPIs/feed ← boards · Economics ← `/cost` · Answer-quality gauges/outliers ← Langfuse scores ·
Investigate ← `/conversations/{id}/trace`. Where a panel has **no live endpoint yet** (deep runtime metrics,
by-user compactions), render a clean **"collecting…" / empty state** — **do NOT display the mockup's fabricated values.**

## Verify — side by side
Open the mockup HTML and `:5175` together: **same dark theme, same grouped nav, same views, same storyline,
no 1-7 row.** `npm run build` green, world-b 0, `conduit-insights` rebuilt + healthy, commit "Approved by Sriraj."
`apps/insights/web` only. If a panel can't be filled from a real endpoint, show the empty state and note it — don't fake it.
