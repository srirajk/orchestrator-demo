# Codex UI Test Plan — Grafana · Langfuse · Chat UI · Insights UI

**Goal:** visually verify the four UIs on the freshly-rebuilt stack (Playwright/Chrome), screenshot each key
view, and report a structured pass/fail back. All backend/gateway logic was already tested at the API level;
this pass confirms the **UI layer renders correctly after a full `down -v` rebuild** and that the six recent
gateway fixes actually **show up correctly in the Chat UI** (not just the API).

---

## 0. Environment (running now — do not rebuild)

Docker compose project **`orchestrator-demo`**. Services already up + seeded.

| UI | URL | Notes |
|---|---|---|
| Chat UI | http://localhost:8099 | OIDC login via Axiom |
| Insights UI | http://localhost:5175 | admin-gated |
| Grafana | http://localhost:3000 | 8 provisioned dashboards |
| Langfuse | http://localhost:3030 | traces / cost / eval |
| Gateway (ref) | http://localhost:8080 | `/v1/insights/*`, `/actuator/health` |
| IAM (ref) | http://localhost:8084 | `/auth/token`, `/login` |

**Logins** — shared password **`Meridian@2024`**:
- `insights_admin` (admin — Insights), `rm_jane` (wealth+servicing), `rm_carlos` (wealth), `rm_guest` (wealth,
  **empty book**), `uw_sam` (insurance).
- **Grafana + Langfuse creds:** discover from the repo — check `orchestrator-chat/docker-compose.yml`
  (`GF_SECURITY_ADMIN_*`, `GF_AUTH_ANONYMOUS_*`, `LANGFUSE_INIT_*`) and `.env`. Do NOT guess; read the config.

**Coverage facts for scenario checks:**
- rm_jane covers **Whitman (REL-00042)** + **Calderon Trust (REL-00099)**; NOT Okafor (REL-00188).
- uw_sam covers **Aurora Mfg Property (POL-77002)** + **Continental Freight Liability (POL-77001)**; NOT Zenith.

**What changed recently (so you know what the Chat UI should show):** six gateway fixes — context-aware
routing, conversation focus, anaphora recency, coverage/synthesis polish, natural (composed) clarifications,
and clarify-copy (no numbered lists). The Chat UI scenarios below exercise each.

**First: generate traffic** so dashboards have data — the seed made 6 conversations; also run the Chat UI
scenarios in §3 before judging Grafana/Langfuse (§1/§2), since those populate traces/metrics.

---

## 1. Grafana (http://localhost:3000) — all 8 dashboards

For **each** dashboard (list them from the dashboards menu): open it, set time range to **Last 30 minutes**,
screenshot, and check:
- Panels **render** — no "No data" on panels that should have data, no red/error panels, no "Datasource not
  found".
- **Datasources** healthy: Prometheus, Loki, Tempo (Configuration → Data sources → each "Save & test" = green).
- Named dashboards to verify specifically (titles may vary — match by content):
  - **Memory / Compaction** — "Summary-Attached Ratio" guardrail panel has a line (not flat-zero while turns ran).
  - **Conversation Trace** — Loki logs render for a real conversation id (the `convId` variable). Note: gateway
    logs conversation ids under the Loki label **`gateway`** (not `conduit-gateway`).
  - **Intent** panels — intent classification counts present.
  - **Latency** — p50/p95/p99 histogram panels render (buckets enabled).
  - **Live Demo / resilience4j** — timelimiter metrics bound.
- **Report per dashboard:** name → PASS / PASS-with-empty-panels(list them) / FAIL(reason) + screenshot path.

## 2. Langfuse (http://localhost:3030)

Log in (creds from config). Check:
- **Traces** — the seeded + Chat-UI conversations appear as traces (chat-turn root spans). Open one → spans for
  intent → routing → agents → synthesis are present.
- **Cost** — non-zero USD (config-driven pricing from `registry/model-prices.json`). Screenshot the cost view.
- **Scores / Eval** — continuous-eval scores (grounding / safety / relevance / partial_honesty) may still be
  populating; note whether any are present.
- **Report:** traces present Y/N · cost non-zero Y/N ($ value) · eval scores present Y/N + screenshots.

## 3. Chat UI (http://localhost:8099) — render the fixes, not just the API

Log in as **rm_jane** (OIDC → Axiom login form → back to chat). In ONE conversation, run this sequence and
confirm **each renders correctly** — message bubbles appear (user + assistant), the **Decision-trace panel**
populates (Question Received → Intent → Resolved agents → Answer Ready), and streaming completes:

1. `Give me the Whitman Family Office holdings` → answers; trace shows **FETCH_DATA** + wealth agents.
2. `and what's their performance?` → **answers with data** (must NOT say "no info").
3. `Now pull up Calderon Trust's holdings` → **answers Calderon** (must NOT wrongly clarify).
4. `settlement status for Whitman` → Whitman settlement.
5. `and their financial goals?` → **Whitman's** goals (recency — must NOT return Calderon's).
6. `Show me Okafor holdings` → **"not in your coverage"** (clean deny, no data leak).
7. `can you summarize the account for me?` → a **natural clarify** — must have **NO numbered list** and **no
   "reply with the number"**; then answer `Calderon Trust` → resolves to the Calderon summary.
8. **Logout** → returns to login and the Axiom session is terminated (re-login required).

Then a short **cross-persona** check (new logins):
- **uw_sam**: `give me the Aurora Mfg Property policy details` → answers (insurance); `and their claim status?`
  → follow-up answers. Confirms insurance renders + focus works in another domain.
- **rm_guest**: `show me my clients' holdings` → **"you have no client relationships in your coverage"** (empty
  book renders cleanly).

**Watch for:** blank center pane while a bubble should render, decision-trace panel stuck/empty, streaming
that never completes, console errors (open DevTools console + network; report any 4xx/5xx or JS errors).
**Report per step:** PASS/FAIL + one-line of what rendered + screenshot; plus console/network error list.

## 4. Insights UI (http://localhost:5175) — RBAC + boards

**RBAC gate first:**
- `insights_admin` → the ops-plane **loads**.
- `rm_jane` (non-admin) → **blocked / 403** (no ops-plane).
- no auth → **login / 401**.

**Then, as insights_admin, each view** (nav groups Operate / Evaluate / Audit — ids ov/tr/ag/ec/aq/us/iv):
open each, screenshot, confirm it renders with **real data** (not "N/A"/"collecting" everywhere — the seed +
§3 traffic should populate it):
- Overview, Cost/Economics (**real $** — config-driven), Agents, Answer-quality/Eval, Users, and the
  **Conversation Trace / Investigate** view — paste a real conversation id and confirm the decision trace
  renders (intent → agents → gates → synthesis).
- **Design check:** dark mission-control theme intact, colors/typography consistent, no broken layout.
- **Report per view:** PASS / PARTIAL(empty sections) / FAIL + screenshot.

---

## Report format (return this to me)

For each of the four UIs: a **pass/fail table** (item → PASS/PARTIAL/FAIL → note), the **screenshot paths**,
and an **Issues Found** list (severity: blocker / major / minor, with repro). End with a one-paragraph
**overall verdict**: are the four UIs demo-ready after the rebuild, and what (if anything) needs a fix.
Do NOT change any code — this is a verification pass; just report.
