# Conduit — User Testing Guide (walk through it one by one)

A plain, click-by-click walkthrough. Do them in order, top to bottom. Each step says **what to do** and **what you should see** (✅ = pass). No technical setup needed beyond the stack being up.

**Everyone's password:** `Meridian@2024`

**The URLs you'll use:**

| What | Open in browser |
|---|---|
| **Conduit Chat** | http://localhost:8099 |
| **Axiom Admin** | http://localhost:5182 |
| **Glass-box (decision trace)** | http://localhost:4000 |
| **Grafana (dashboards)** | http://localhost:3000 |

> Quick "is it alive?" check (optional, terminal): `bash scripts/smoke-ui.sh` → should say **🟢 TIER-1 GREEN (16/0)**.

---

## Scenario 1 — Jane, a wealth relationship manager (the golden path)

**1.1 — Log in**
- Go to **http://localhost:8099**.
- You get redirected to the **Axiom** sign-in page.
- Username `rm_jane`, password `Meridian@2024`, **Sign in**.
- ✅ You land in the Conduit chat. Bottom-left shows **rm_jane / rm.jane@meridian.local**.

**1.2 — Ask about a client she covers (allowed → real answer)**
- In the message box type: `Give me a summary of the Whitman Family Office holdings`
- ✅ You get a **real, grounded answer**: total value **~$1,967,000**, positions (JPMorgan, Microsoft, Apple, Google, T-Bill), allocation **68% equities / 24% fixed income / 8% cash**. Nicely formatted with bullets.

**1.3 — Ask about a client she does NOT cover (blocked)**
- Type: `Show me the Okafor Holdings relationship portfolio`
- ✅ You get: **"Access denied for this client relationship."** — and **no** Okafor numbers leak. (This is the entitlement engine working.)

**1.4 — Follow-up in the same chat (memory works)**
- Type: `and what's its cash position?`
- ✅ It understands you still mean Whitman (from context) and answers about Whitman's cash — you did **not** have to name the client again.

**1.5 — Refresh survives**
- Reload the browser tab (Cmd/Ctrl-R).
- ✅ The whole conversation is still there (it's saved server-side, not just in the tab).

**1.6 — Start a new chat**
- Click **+ New Chat** (top-left).
- ✅ Clean new conversation; the old one stays in the sidebar. Ask Whitman again → grounded again.

---

## Scenario 2 — Carlos, a different wealth RM (different book)

**2.1 — Log in as Carlos**
- Open a fresh tab (or log out): **http://localhost:8099** → sign in `rm_carlos` / `Meridian@2024`.

**2.2 — His own client (allowed)**
- Type: `Show me the Sterling Capital Partners holdings`
- ✅ Grounded answer — total **~$1,107,000**, positions (NVIDIA, etc.). Sterling is *his* book.

**2.3 — Jane's client, not his (blocked)**
- Type: `Give me the Whitman Family Office holdings`
- ✅ **"Access denied for this client relationship."** — proves each RM only sees *their* clients, no leaks between them.

---

## Scenario 3 — Guest RM (no assigned clients at all)

**3.1 — Log in as guest**
- **http://localhost:8099** → `rm_guest` / `Meridian@2024`.

**3.2 — Any client query**
- Type: `Give me the Whitman Family Office holdings`
- ✅ **Denied, no data.** rm_guest has no assigned book, so nothing is reachable. (Good test that "logged in" ≠ "can see everything.")

---

## Scenario 4 — Sam, an insurance underwriter (a *different* business line)

**4.1 — Log in as Sam**
- **http://localhost:8099** → `uw_sam` / `Meridian@2024`.

**4.2 — His own policy (allowed)**
- Type: `What is the premium for POL-77001?`
- ✅ Grounded: **premium is 48500 USD.**

**4.3 — A policy that isn't his (blocked, with the *right* wording)**
- Type: `Show me POL-88003`
- ✅ **"That policy is not in your book of business."** — note it says **policy**, not "client" (insurance speaks insurance).

**4.4 — A wealth question (wrong business line)**
- Type: `Give me the Whitman Family Office holdings`
- ✅ Denied — Sam is insurance, not wealth. Proves the same engine cleanly separates business lines.

---

## Scenario 5 — The Axiom Admin console

**5.1 — Open it**
- Go to **http://localhost:5182**, sign in (admin credentials).
- ✅ The **Axiom Admin Console** loads (navy/gold), no CORS/login error.

**5.2 — Look around**
- ✅ You can see the **Policies / Cerbos policy** area and the **operator workbench** — this is where entitlement policies live (the thing that decided "allowed/denied" above).

---

## Scenario 6 — The "glass box" (see *how* it decided)

**6.1 — Open the trace view**
- Open **http://localhost:4000** in a second tab, next to the chat.

**6.2 — Watch a denial get decided**
- Back in the chat (as `rm_jane`), send: `Show me the Okafor relationship holdings`
- ✅ In the glass-box you see the pipeline: **intent → find the entity → entitlement CHECK → *denied*** — with a **`check_denied`** event, the entity `REL-00188`, reason `not-in-book`, and **0 agents called** (it stopped *before* fetching any data). That's the proof it didn't just hide data — it never fetched it.

---

## Scenario 7 — Telemetry (Grafana dashboards)

**7.1 — Open Grafana**
- **http://localhost:3000** → dashboards.

**7.2 — Check the live numbers**
- After running the scenarios above, open the **Conduit** dashboards.
- ✅ You should see live data: request counts, intents classified, **allow vs. deny** ratio, latency, and per-agent health. All **7 dashboards** are present.

---

## Scenario 8 (optional) — Memory / summarization

Long conversations get a **facts-free summary** so the assistant keeps context without re-sending everything.
- As `rm_jane`, have a longer back-and-forth (several turns).
- ✅ It stays coherent across turns. (Under the hood a rolling summary kicks in past a token threshold — it captures *topics*, never dollar values or client IDs.)

---

## What "all green" looks like
- rm_jane: Whitman ✅ answered, Okafor ✅ denied, follow-up ✅ remembered, refresh ✅ survived.
- rm_carlos: Sterling ✅ answered, Whitman ✅ denied.
- rm_guest: everything ✅ denied.
- uw_sam: POL-77001 ✅ answered, POL-88003 ✅ denied ("policy"), wealth ✅ denied.
- Admin ✅ loads, Glass-box ✅ shows the deny decision, Grafana ✅ has live metrics.

If any step doesn't match, note the persona + the exact prompt + what you saw — that's everything needed to chase it down.
