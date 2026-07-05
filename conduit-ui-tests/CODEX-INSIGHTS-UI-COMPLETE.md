# Codex Brief — Conduit Insights: the Operations Plane

Build Insights as **ONE operations plane — mission control for the gateway.** Everyone (exec, ops, risk,
finance) uses the **same plane**; depth is **layered (glance → scan → drill)**, never split into role-views.
It is **live-state-first** (what's happening / what needs attention *now*), not a retrospective report.
`apps/insights/web` only · repo `orchestrator-chat` / `feat/conduit-chat` · Insights at :5175 · logins
`insights_admin` + demo bankers + `rm_jane` (all `Meridian@2024`) · world-b 0 · commit "Approved by Sriraj."
· read `INSIGHTS-WORDING.md` (it's **Insights**, never "workspace").

## The core idea — one plane, layered depth (this is how it serves everyone)
The same plane reads at three depths; nobody is fenced into a role view:
- **Glance (5s):** a **live-state strip** at the top of every view — overall health (● green / ◐ amber /
  ● red), the 3–4 numbers that matter, and **"what needs attention."** An exec, risk officer, and SRE all
  get their answer here without drilling.
- **Scan (30s):** the operational panels for that question.
- **Drill:** click any number → the items behind it → down to the **decision replay.**

## The IA — question-led, ~4–5 views (NOT 7 system-named boards)
Nav is the operational questions, not our architecture. Map the backend's 7 boards + `/cost` into these:
1. **Overview — "Is it working?"** mission-control home: live health strip, what-needs-attention, volume /
   latency / outcome mix. (from Executive + Traffic/Intent + a live decision feed)
2. **Trust & Governance — "Is it safe?"** authz decisions, denials, coverage gaps, entitlement gates. (Governance)
3. **Agents & Pipeline — "How's it running?"** agent fleet health, fan-out, intent mix, **latency by stage**,
   reliability/breakers. (Agent Fleet + AI Pipeline + Gateway Deep-Dive)
4. **Quality & Economics — "Is it good, and what's it costing?"** eval scores (grounding/safety/relevance) +
   **cost by user / segment / model** + unit economics. (Quality + `/cost`)
5. **Investigate — "What happened here?"** the `conversationId` deep-dive — the incident tool (below).

## Live-state-first (mission control, not a rearview mirror)
- Every view **opens with current health + "what needs attention,"** not a historical summary.
- **"What needs attention" is EARNED** — a breaker open, denials spiking, a quality dip, an agent degraded.
  **Not** a templated restatement of a metric ("fan-out was 1.9s" is not a signal). If nothing's wrong, say
  **"All systems nominal"** honestly — that's a valid, confident state.
- Encode state in **form** (color / pill / severity stripe), not just numbers — it must read at a glance.

## The decision replay = a NARRATIVE (the crown jewel — do not ship an event dump)
Investigate → paste a `conversationId` → `GET /v1/insights/conversations/{id}/trace` → render the trace as a
**human story**, one readable line per step, with data + timing, failures/denials highlighted:
> **rm_carlos** asked *"Give me a risk summary for my top client."*
> → classified **FETCH_DATA** (120ms) → resolved *"my top client"* → **entitlement ✓** (wealth) →
> called **2 agents** (holdings, risk) → synthesized · **grounded 0.97 · safety 1.0** (2.1s total)
This is the glass box. Design it as **intelligence, not a log.** A raw `TraceEvent` table fails the brief.

## Low-data resilience (CRITICAL — the demo is thin: $0.06, 48 convos, same-day)
- Charts must look **intentional at small N** — no lonely single dots, no sad empty lines. Sparse/1-point →
  show the **number + a "collecting…" state**, not a broken chart. Null deltas → hide the delta, don't show "—".
- **Lead the plane's value with the live decision flow + the investigation** (impressive at any volume) — the
  cost/volume totals are supporting, not the hero (a $0.06 total must never be the biggest thing on screen).

## Keep (locked earlier)
- **Deep-link routing:** view + range + conversationId live in the URL (`/insights/agents?range=7d`,
  `/insights/investigate/{conversationId}`) — shareable, bookmarkable, restores on reload.
- **Freshness badges:** ● **Live** (ops/cost, near-instant) · ◐ **Near real-time · updated Xm ago** (eval,
  ~5min async runner) + a one-line footnote on quality panels.
- **Timeframe** selector 24h/7d/30d → `?range=` (default 24h).
- **"Viewing at: highest data classification"** note (honest; gating comes later).
- Full-width layout; fix the no-access **body** ("…available only to administrators…", kill "workspace").

## Backend (live, verified)
`GET /v1/insights/boards/{1-7}?range=` · `GET /v1/insights/cost?range=` (`byModel/byUser/bySegment` +
`unitEconomics`) · `GET /v1/insights/conversations/{id}/trace`.

## Design
Full-width **mission-control** aesthetic: ink-navy `#0B1220` ground, panel `#131C2E`, **gold `#F0C45A`**
accent, **semantic green/amber/red for state** (separate from the accent). Strong hierarchy — the health
strip + "what needs attention" are the largest thing; supporting panels quieter. Light + dark. No page-level
horizontal scroll (wide tables scroll in their own container). `font-variant-numeric: tabular-nums` on all figures.

## The "By user" pillar (per-principal audit — reconciled with no-list / adapter / gateway-native)
A dedicated **By user** view — "click any banker → their whole story":
- **Always (gateway-native, from Langfuse user tags):** cost by user, tokens, **per-user continuous-eval
  scores** (grounding/safety/relevance/partial-honesty), **entitlement decisions by domain** (allow/deny).
- **Richer (via the `ConversationSource` adapter — our Mongo now, a customer's store later):** their
  **conversations** (→ click → the decision replay) and **memory compactions** (which sessions compacted).
- **User list** is fine (bounded, from IAM / Langfuse user tags) — unlike a conversation list. Degrade
  gracefully: if no conversation-store adapter, show the aggregate + eval + entitlement half only.

## Operational depth (progressive disclosure — carry the Grafana/Langfuse richness, don't ship summary-only)
The glance stays calm, but **scan/drill must carry real depth** — it must be able to replace staring at Grafana:
- **Agents & Pipeline:** JVM / virtual-threads / **bulkhead** saturation, **circuit-breaker** state per agent,
  **latency histogram** + true p50/p95/p99, **agent-selection** distribution, **intent mix**, throughput/RPS.
- **Quality:** the **eval-outliers** list (low-scoring answers → click → replay) — *the money panel*;
  `partial_honesty`; quality + cost **trend over time**; token trends (prompt/completion, by day).
- **Trust:** authz-decision trend, per-agent denials, coverage-gap (unmet-demand) detail.
- **Investigate:** the **span-timeline waterfall** beside the narrative (gantt from the trace).
- **Overview:** **error taxonomy** (timeout / LLM-error / no-coverage) + **adoption by role**.
- **Escape-hatch, don't duplicate:** every dense panel carries an **"↗ Grafana / ↗ Langfuse"** deep-link for
  raw exploration. The plane is the narrative + key signals; the power tools stay one click away.

## Eval on the plane = CONTINUOUS only (build-time certification stays in CI)
The operations plane shows the **continuous** eval — the live operational signal. Build-time golden-dataset
certification is a **CI/release gate**, NOT an operational view.
- **Answer Quality (the eval view)** — from `eval/langfuse_continuous.py` (the scheduled `continuous_loop`),
  **already live in Langfuse**: grounding · safety · relevance · partial_honesty, scored by an **independent**
  judge. Show: verdict → gauges → **distribution** → **low-scoring outliers → click → replay** →
  grounding-by-model. **Backend-ready — just fetch the Langfuse scores.** "The model doesn't grade itself."
- **Build-time golden certification** (`worker.py`, DeepEval gate, Cerbos golden dataset) — **NOT a plane
  view.** It gates deployment; it isn't live ops. Optionally a small **"✓ Build certified · CI"** badge as a
  trust nod (in the Answer Quality header). No golden-run fetch, no Cerbos-in-Langfuse dependency needed.

## Every dashboard is a STORYLINE (verdict → evidence → detail — ordered, never a flat grid)
Opening any view reads top-to-bottom like a story (inverted pyramid):
1. **Verdict first** — the health strip / "what needs attention" answers the view's question in one line.
2. **Evidence** — the 2–3 panels that back the verdict.
3. **Detail / drill** — the dense panels for whoever needs to act.
Per view: **Overview** = health → flow/volume → outcomes & failures → live feed · **Trust** = safe? →
decisions → where stopped (gate) → who (segment) → 0 fabricated IDs · **Agents** = what's degraded → fleet →
where time goes → the machinery · **Quality & Economics** = is it good → the outliers → what it costs → trends ·
**By user** = who → cost → quality → entitlements → their conversations. **Panel ORDER carries the narrative.**

## Incident & failure design (the bad-day version — where an ops plane earns its keep)
Use metrics we ALREADY emit (verified against the Grafana dashboards):
- **Error taxonomy** (`conduit_request_outcome_total`): ok · clarified · **forbidden** (authz) · **not_found**
  (no resolve) · **error** (agent/LLM). Plus agent-level: `conduit.agent.denials`, agent error rate, breaker
  `failure_rate` / `slow_calls` / `not_permitted` (breaker-rejected), `resolver.fallback`.
- **Critical / red state** — breaker open, failure-rate breach, or bulkhead saturation
  (`conduit_bulkhead_queued`>0) → the health strip goes **red** and names the incident. "All nominal" isn't the only state.
- **Failure replay** — Investigate must render a FAILURE too: *why* grounding was 0.58 (which agent returned
  weak data), a denied request (which gate + why), an agent timeout (which hop).
- **Compute "needs attention"** from real signals (thresholds / SLO burn): breaker open · failure-rate up ·
  queued>0 · low `resolver.route.confidence` · `resolver.fallback` spike · `conduit.authz.revocations` · error-outcome spike.
- **Surface emitted-but-unused metrics:** `conduit.adoption` (by role), `resolver.route.confidence`,
  `resolver.fallback`, `conduit.authz.revocations`, breaker `slow_calls` / `not_permitted`.

## Verify (browser)
The 4–5 question-led views; a **live-state strip** with earned "what needs attention"; **narrative** replay
for `demo-rm_carlos-wealth-book` (a readable story, not a table); cost views show real by-user/segment/model;
deep-links restore state; freshness badges present; **charts look intentional at low data**; no "workspace";
`rm_jane` → no-access. `npm run build` green, world-b 0, committed, `conduit-insights` healthy.
