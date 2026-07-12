# Conduit UI — full end-to-end browser test plan (for Codex)

> **You (Codex) can drive the user's logged-in Chrome; the user cannot — that's why this is handed
> to you.** Execute every scenario below **in the browser**, capture a screenshot as evidence for
> each, and give a **section-by-section PASS/FAIL** plus an overall "is the UI production-presentable"
> verdict and the top blocking issues (if any).
>
> **Backend smoke already passed** (API level, {{run date}}): grounded HR answer, Okafor coverage
> denial, chat/admin/insights SPAs all 200, admin login OK, audit objects being written. Your job is
> the **interactive UI** layer that headless checks can't see: rendering, the SSO flow, the live
> glass-box panel, buttons, and logout.
>
> **Constraints:** READ-ONLY. Do not delete data, change settings, submit destructive actions, or
> touch any code. If a browser dialog/alert blocks you, note it and continue. If login or a page
> fails after 2–3 attempts, STOP and report — do not loop.

## The stack (docker compose project `orchestrator-demo`, all healthy)

| Surface | URL | Login |
|---|---|---|
| **Conduit Chat** (primary UI) | http://localhost:8099 | `rm_jane` / `Meridian@2024` (Axiom SSO/OIDC) |
| **Admin UI** | http://localhost:5180 | `admin` / `Meridian@2024` |
| **Conduit Insights** | http://localhost:5175 | `admin` / `Meridian@2024` (OIDC) |
| Gateway API (reference) | http://localhost:8080 | Bearer JWT from IAM |
| IAM (Axiom) | http://localhost:8084 | — |

## Personas & entitlements (ground truth for the assertions)

- **rm_jane** (relationship manager, role `chat_user`): her book of business is **Whitman Family
  Office (REL-00042)** and **Calderon Trust (REL-00099)**. She is **NOT** entitled to **Okafor
  Capital (REL-00188)**. HR policy questions are open to all staff.
- **admin** (`platform_admin`): full admin-UI and insights access.

---

## SECTION A — Conduit Chat (http://localhost:8099), the primary UI

Log in as **rm_jane / Meridian@2024** through the Axiom SSO screen. Confirm you land on the chat SPA
(not stuck on the SSO/login page).

### A1 — Grounded answer + live glass box
- Send: **"What is our parental leave policy?"**
- PASS if: the **user bubble AND the assistant bubble render in the centre pane** (must NOT stay on
  the "Start a conversation" empty state); the answer **streams in**; and the right-hand **"Decision
  trace" / glass-box panel** shows live steps — segment/classification/entitlement gates, agent(s)
  running, and a terminal "Answer Ready".
- Expected answer content: maternity leave ~20 weeks paid, paternity ~10 weeks paid, adoption leave.

### A2 — Coverage DENIAL (the entitlement story)
- Send: **"Give me Okafor Capital's holdings"** (REL-00188 — NOT in rm_jane's book).
- PASS if: the answer is a **polite denial** ("that client is not in your coverage" / access denied),
  **no Okafor holdings/figures leak** into the response, and the glass box shows a **coverage-denied**
  step.

### A3 — Coverage ALLOW (grounded data)
- Send: **"Give me a summary of the Whitman Family Office holdings"** (REL-00042 — IS in her book).
- PASS if: a **grounded answer with real figures** renders, and the glass box shows agent(s) running
  and gates passing.

### A4 — Multi-turn context
- In the same conversation, send a follow-up: **"what about its year-to-date performance?"**
- PASS if: the answer stays on the Whitman context (no re-clarification asking which client), i.e.
  conversation context is retained.

### A5 — Clarification path (optional but valuable)
- Start a NEW conversation and send a deliberately vague: **"show me the holdings"** (no client named).
- PASS if: the assistant asks a **clarifying question** (e.g. "which client?") rather than guessing or
  fabricating — and, if the UI offers them, shows the in-book client options (Whitman / Calderon).

### A6 — Conversation history re-render
- Pick an earlier conversation from the **left sidebar** and open it.
- PASS if: its **message history renders** (both sides of the exchange) — NOT the empty
  "Start a conversation" state.

### A7 — Logout
- Use the app's logout control.
- PASS if: it actually logs out and returns to the login/SSO screen (and you cannot get back into the
  chat without logging in again).

---

## SECTION B — Admin UI (http://localhost:5180)

Log in as **admin / Meridian@2024**.

- **B1 Login + dashboard:** login succeeds; the dashboard / stat cards render with data.
- **B2 Users:** the Users page loads and lists users (rm_jane, admin, etc.).
- **B3 Roles:** the Roles page loads and lists roles.
- **B4 Teams:** the Teams page loads.
- **B5 Policies:** the Policies page loads (Cerbos authorization policies).
- **B6 Workbench:** the persona/impersonation Workbench loads with a persona selector.
- For each: click INTO the page, confirm it renders (no blank page / spinner-forever / console error),
  screenshot it. Do **not** create/edit/delete anything.

---

## SECTION C — Conduit Insights (http://localhost:5175)

Log in as **admin / Meridian@2024** (OIDC).

- **C1 Login:** the OIDC login completes and lands on the Insights app.
- **C2 Boards render:** the dashboards/boards render with data — e.g. an **overview** board and a
  **cost / cost-quality** board (charts, tiles, numbers — not empty frames).
- Click through the available boards; screenshot each. Note any board that fails to load or shows an
  error/empty state.

---

## Cross-cutting checks (note these as you go)

- **Browser console:** flag any red errors (uncaught exceptions, failed network calls, 4xx/5xx on
  XHRs) on each surface.
- **Auth integrity:** confirm rm_jane never sees admin surfaces, and that logout truly ends the session.
- **No data leak:** in A2 specifically, confirm Okafor figures/tickers never appear anywhere in the
  denied response.
- **SSE/streaming:** in A1/A3, confirm the answer streams token-by-token (not a single late dump) and
  the stream ends cleanly (no hang, no truncation).

## Out of scope (do not test here)

- The WORM **audit** trail is backend-only (objects in MinIO `conduit-audit`); it is **not** a UI
  surface — skip it.
- Load/performance, and anything requiring code changes.

## Deliverable

A section-by-section **PASS/FAIL** table (A1–A7, B1–B6, C1–C2), each with a one-line result and a
screenshot reference, the **console-error** findings, and a final verdict: **is the Conduit UI
production-presentable, and what are the top issues (if any) blocking "done"?**
