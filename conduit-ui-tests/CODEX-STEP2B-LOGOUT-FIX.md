# Codex Brief — STEP 2b: Fix logout (real single-sign-out + land on Conduit SSO page)

Step 2's logout is **local-only** — it clears the Conduit/BFF session but leaves the **Axiom** session
alive, so after logout, clicking "Sign in with SSO" walks straight back into the app with **no Axiom
password prompt** (verified in the browser). Fix it so logout **terminates the Axiom session** (re-auth
required) **and** lands on the Conduit SSO landing.

## Root cause
`apps/chat/web/src/components/Sidebar.tsx` `handleLogout()` does `fetch('/api/auth/logout')` + a separate
`window.location.assign(landing)`. A background **fetch** does not carry out Axiom's **front-channel
`end_session`** (which requires an actual browser **navigation** so the Axiom session cookie is
terminated). So the BFF session clears but Axiom's does not.

## The fix — three precise changes
1. **Frontend — `apps/chat/web/src/components/Sidebar.tsx`:** replace the `fetch(...) + assign(landing)`
   in `handleLogout` with a single **navigation**:
   ```ts
   window.location.assign('/api/auth/logout')
   ```
   The BFF `/api/auth/logout` (unchanged) invalidates the session and 302-redirects to Axiom's
   `end_session_endpoint`; because the browser **navigates**, the Axiom session is actually terminated.
2. **BFF post-logout target — `apps/chat/bff`:** set the post-logout redirect
   (`conduit.chat.auth.post-logout-redirect-uri`, and the matching compose env
   `CHAT_POST_LOGOUT_REDIRECT_URI` in `docker-compose.yml`) to **`http://localhost:8099/`** (the SPA root)
   instead of `.../api/auth/login`. After `end_session`, the browser lands on the SPA → `AuthGate` sees
   it's unauthenticated → shows the **Conduit SSO landing**. (`/api/auth/login` would auto-kick a new
   login and dump the user on the raw Axiom page — we don't want that.)
3. **iam registered client — `iam-service/src/main/java/com/openwolf/iam/config/SecurityConfig.java`:**
   the `conduitChatClient` must have **`http://localhost:8099/`** registered as a `postLogoutRedirectUri`
   (OIDC validates the post_logout_redirect_uri against the client). **Add it** (additive — keep the
   existing `.../api/auth/login` too so nothing else breaks). Also update the `@Value` default for
   `conduit-chat.post-logout-redirect-uri` accordingly.

## Constraints
- Only the three files above (+ the compose env line). **Additive** auth config — do NOT change auth
  *decisions*, other clients, the login flow, or `backend-*`. Rebuild `conduit-chat` **and** `iam-service`.
- world-b CRITICAL 0. Commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution.

## Verify (browser — this exact sequence is the whole test)
1. `http://localhost:8099` → Conduit SSO landing → "Sign in with SSO" → Axiom login → `rm_jane` /
   `Meridian@2024` → into chat.
2. **Logout** → lands on the **Conduit SSO landing**.
3. **Click "Sign in with SSO" again → Axiom MUST ask for the password again** (not walk straight in).
   That is the pass condition. Also confirm nothing else regressed (fresh login still works, returnTo intact).

## Completion contract
`conduit-chat` + `iam-service` rebuilt + healthy, world-b 0, committed. Screenshot proof of step 3 (the
Axiom password prompt appearing after logout). If any change forces a broader auth-decision edit, STOP and
report.
