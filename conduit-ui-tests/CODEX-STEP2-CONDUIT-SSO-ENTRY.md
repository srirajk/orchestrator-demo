# Codex Brief — STEP 2: Conduit Chat "Sign in with SSO" entry page

Put a **Conduit-branded "Sign in with SSO" landing** in front of the chat app's login. Today the app
**silently redirects** unauthenticated users straight to Axiom; Step 2 shows a branded page first with a
**"Sign in with SSO"** button that kicks off the *same* redirect. This is the front door; the Axiom
login page (Step 1, already polished) is where credentials are entered.

## The flow (target)
> Open Conduit → **Conduit "Sign in with SSO" page** (branded, one button) → click → **Axiom login**
> (Step 1) → back into the chat on success.

## The EXACT files (frontend only — `apps/chat/web`)
- **`apps/chat/web/src/components/AuthGate.tsx`** — currently, when unauthenticated, an effect calls
  `redirectToLogin()` (line ~25) and auto-bounces to Axiom. **Change:** when there's no session, render a
  branded **Sign-in landing** instead of auto-redirecting.
- **`apps/chat/web/src/api/client.ts`** — `redirectToLogin()` (line ~5) does
  `window.location.assign('/api/auth/login?returnTo=…')` (the OIDC kickoff). **Keep this function as the
  kickoff** — the landing's button calls it. (Also decide: the 401 handler at ~line 27 should route to the
  landing, not straight to Axiom, so the experience is consistent — you always see the Conduit page first.)
- **New component:** a `LoginLanding` (or similar) — the branded page itself.

## Build
1. **Branded landing** — a clean, centered Conduit card: logo/wordmark ("Conduit — Enterprise AI
   Gateway"), a short line, and a primary **"Sign in with SSO"** button. Conduit design language
   (ink-navy `#0B1220` ground, panel `#131C2E`, gold `#F0C45A` accent) — it must feel like the product.
   **CRITICAL: NO username/password fields on this page.** It is *only* branding + the "Sign in with SSO"
   button. The user enters credentials on the **Axiom** login (Step 1); **Conduit never handles the
   password.** Conduit UI = the SSO button; Axiom = the actual login.
2. **Wire the button** → call the existing `redirectToLogin()` (→ `/api/auth/login` → Axiom). Preserve
   `returnTo` so the user lands back where they were.
3. **Unauthenticated = show the landing** (no more silent auto-redirect); after a 401, route to the landing
   too (consistent). Authenticated users never see it.

## Constraints (critical — do NOT touch auth logic)
- **Frontend only: `apps/chat/web`.** DO NOT change the **BFF OIDC flow**, `apps/chat/bff` SecurityConfig,
  the `/api/auth/login` endpoint, the OIDC clients, or any auth *decision*. You are only changing **when/
  how** the existing redirect is triggered (a branded button instead of an automatic bounce).
- Do NOT touch the Axiom login page (Step 1 owns it), iam, gateway, or `backend-*`.
- `world-b-check.sh` CRITICAL 0. Commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution.
  Rebuild `conduit-chat` after.

## Verify (browser)
- Logged out / first visit → the **branded "Sign in with SSO" page** (not an instant Axiom bounce).
- Click "Sign in with SSO" → the **Step-1 Axiom login** page → sign in `rm_jane` / `Meridian@2024` → land
  back in the chat where you started (`returnTo` preserved).
- Log out → returns to the branded Sign-in landing.
- An already-authenticated reload → straight into the chat (no landing flash).

## Completion contract
`npm run build` green, world-b 0, committed, `conduit-chat` rebuilt + healthy at :8099. Screenshots: the
branded Sign-in landing + the full round-trip working. Only `apps/chat/web` touched. If anything requires a
BFF/OIDC/auth-decision change, STOP and report — that's out of scope.
