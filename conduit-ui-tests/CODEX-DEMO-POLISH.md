# Codex Brief — Demo Polish: Trace-panel reliability + Grafana tidy

Two isolated, browser-verified items. Make them **really tight.**

## Ground rules
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (head `dee50cf`).
- **Runtime:** docker project `orchestrator-demo`; chat http://localhost:8099, Grafana http://localhost:3000 (`admin` / `changeme`). Login `rm_jane` / `Meridian@2024`.
- **DO NOT touch:** auth / the Axiom redirect; gateway Java / iam / authz / registry; `backend-*`. Do NOT change the synthesis prompt or any gateway code.
- **Stay inside the repo folder.** `world-b-check.sh` CRITICAL 0. Commit on `feat/conduit-chat`, messages ending **"Approved by Sriraj."**, no AI attribution.

## Item 1 — Trace panel occasionally renders empty (fix)
**Symptom:** sometimes the "Decision trace" panel stays on its placeholder ("Ask a question to see…") even though the gateway **did** emit the full trace for that turn (confirmed independently). Intermittent.

**Anchor:** `apps/chat/web/src/hooks/useTraceStream.ts`. Likely cause: the reconnect loop **pauses ~500ms between turns** after the server closes the stream (see the `setStatus('connecting')` + `setTimeout(…500)` around the natural-end path). If a query is sent **during that gap** — or in a **brand-new conversation before the stream has attached** — the early frames (`request_start`, `intent_classified`, `agents_resolved`) are emitted before the SSE re-attaches and are missed, so the panel looks empty or only shows late frames. Events also reset on `request_start`, so a mid-turn reattach can miss the reset.

**Fix direction:** make the trace stream reliably attached for the active turn — connect **eagerly** on conversation open, shrink/remove the reconnect gap, and/or keep the connection warm so no turn's opening frames are lost. Do NOT lose the existing backoff-on-error behavior.

**Verify (live):** as `rm_jane`, send **5+ queries** — mix of same-conversation follow-ups AND brand-new chats, some in quick succession — and confirm the trace panel **populates for every one**, never empty when a trace was emitted. Screenshot a fresh-chat turn showing the trace filled.

## Item 2 — Grafana dashboards: populate with no manual input + declutter (task #30)
**Symptom:** dashboards require manually typing `relationship` and `conversationId` (they sit empty until you type), and the layout is cluttered / "clumsy."

**Files:** `infra/grafana/provisioning/dashboards/*.json` (8 dashboards; `conversation-trace.json` is the main offender for the required variables).

**Fix direction:**
- Give every templated variable a **sensible default** so dashboards populate on open with **zero manual entry** — set include-all / `"All"` (or a default/current value) and mark them optional so no panel is blank on load.
- **Tidy the layout** — sane panel sizing/grouping, drop empty/noisy panels, so each dashboard reads clean.
- **Do NOT change the underlying queries** — the metrics are already populated; only touch template variables + panel layout.

**Verify (live):** at http://localhost:3000 (`admin`/`changeme`), open the key dashboards (esp. `conversation-trace`, `business-overview`, `gateway-performance`) → they **populate immediately with no manual variable entry**, and the layout reads tidy. Screenshot 2–3. (Grafana auto-reloads provisioned dashboards; `docker restart conduit-grafana` if a reload is needed.)

## Completion contract (don't hand back partial)
Finish BOTH. Leave `npm run build` green (for the web change), world-b 0, everything committed on `feat/conduit-chat`, env healthy (`curl -s -o /dev/null -w '%{http_code}' http://localhost:8099` → 200). Browser-verify each with a screenshot. All work inside the repo folder. If you truly can't finish one, complete the other, revert the incomplete one cleanly so the build passes, and report exactly what's left.
