# Codex Brief — Insights App: Auth Shell (STEP 3, do this FIRST)

Build the **auth foundation** for the dedicated Conduit Insights app — branded entry → Axiom SSO →
return on success → clean access-denied for non-admins. **No dashboards yet.** Prove auth end-to-end,
then step 4 (boards) goes inside this working shell.

## Prereq (owner-provided — already done)
The `conduit-insights` **OIDC client** (authorization-code + **PKCE**, public; redirect
`http://localhost:5175/callback`) is **already registered in Axiom** (owner did it, commit `3efc047`).
You do NOT need any gateway CORS change — see the same-origin proxy below. Confirm the client id
`conduit-insights` resolves at the Axiom authorize endpoint; if not, stop and report — don't invent auth.

## Ground rules
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
- **ONE new app folder only: `apps/insights/web`** (a Vite/React SPA). **Create NO other new folders.**
- **Same-origin, no CORS, no Java BFF.** Serve the built SPA with a small **nginx** (its own container)
  that **proxies same-origin**: `/v1/insights/*` → the gateway, and the OIDC **token endpoint** → iam.
  From the browser everything is one origin — so **no gateway CORS change**, no separate BFF. The only
  cross-origin step is the full-page **authorize redirect** to Axiom (navigations don't need CORS).
- **DO NOT touch:** the Chat app, gateway/iam **auth-decision** logic, `backend-*`. World-b CRITICAL 0.
  Commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution.
- **Design:** reuse the approved mockup's tokens — ink-navy `#0B1220`, panel `#131C2E`, **Conduit gold
  `#F0C45A`** accent, mono figures. The Insights app must feel like the same product.

## Build (auth shell only)
1. Scaffold `apps/insights/web` (Vite + React + TS) + its **nginx** container (serves the SPA + the
   same-origin proxy above) + wire the `conduit-insights` **compose service** (port 5175).
2. **Branded landing — "Sign in with SSO"** — a Conduit Insights sign-in screen: logo, short line, and a
   single **"Sign in with SSO"** button. **NO username/password fields** — credentials are entered on the
   **Axiom** login (the polished Step-1 page); Conduit never handles the password. The button starts the
   **OIDC authorization-code + PKCE** redirect to **Axiom** (client `conduit-insights`).
3. **Callback** — handle the `/callback` route: exchange the code (PKCE) for tokens, store them
   (in-memory + refresh handling), and route into the app.
4. **Authenticated shell** — a minimal empty shell (top bar with Conduit branding + the signed-in user +
   sign-out; an empty content area where boards will go). This is the surface step 4 fills.
5. **Access-denied page** — after login, check the role/claim: if the user is **not** an Insights admin
   (i.e. lacks the `conduit_admin`/`insights:read` entitlement — the gateway returns **403** on
   `/v1/insights/*`), show a clean branded **"You don't have access to Insights"** page with sign-out /
   back. NEVER hang, loop, or show a raw error.
6. **Session robustness** — token expiry / refresh failure → back to the branded landing cleanly (mirror
   the Chat fix: fail to login, not a hang).

## Verify (browser, end-to-end — this is the whole point)
- **Not logged in** → the branded landing; "Sign in" redirects to Axiom.
- **`insights_admin` / `Meridian@2024`** → after Axiom SSO, lands in the empty authenticated shell.
- **`rm_jane` / `Meridian@2024`** (a chat_user) → after Axiom SSO, sees the branded **access-denied**
  page (because `/v1/insights/*` → 403), cleanly — not a crash.
- Sign-out returns to the landing.

## Completion contract
Finish the whole shell. `npm run build` green, world-b 0, committed, the `conduit-insights` container
builds + healthy. Screenshots: landing, admin-in-shell, chat_user-access-denied. Only `apps/insights/web`
touched (+ its compose service if you wire it). No other new folders. If blocked on auth config, stop and
report exactly what's missing — do not fake it.
