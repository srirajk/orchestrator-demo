# Morning notes — OIDC SSO is fixed ✅

Short version: **"Login with Meridian SSO" now works.** I found two real backend bugs,
fixed them in Axiom (the iam-service), and proved the *entire* login flow end-to-end —
including LibreChat's own callback creating the session and provisioning the user.

I could not type your password into the browser myself (hard safety rule — I never enter
credentials into a UI). So I validated by driving the **exact same HTTP flow the browser
performs**, with curl, all the way through LibreChat's callback. It succeeds. When you click
the button and type the password, it will complete the same way.

---

## What was actually broken (two bugs, both in Axiom)

The login never worked before — not a rename regression. Axiom's own DEBUG logs gave the
smoking gun on the token call:

```
POST /oauth/token
Authentication failed with provider ClientSecretAuthenticationProvider
since Client authentication failed: authentication_method
```

1. **Client-auth method mismatch.** The `meridian-librechat` OAuth client was registered for
   `CLIENT_SECRET_BASIC` only. LibreChat's `openid-client` sends the secret in the request
   **body** (`client_secret_post`). Spring rejected it → `invalid_client` → the generic
   LibreChat error *"server responded with an error in the response body."*

2. **id_token / userinfo had only `sub`.** Our JWT customizer enriched the **access token**
   only, so the **id_token** (and the userinfo endpoint, which maps from it) carried no
   `email`/`name`. LibreChat needs an email to provision the user, so even a successful token
   exchange would have failed at user creation.

## The fix (3 files, `iam-service`)

- `config/SecurityConfig.java` — added `CLIENT_SECRET_POST` to the LibreChat client
  (keeps `CLIENT_SECRET_BASIC` too, so either method works).
- `auth/JwtClaimsCustomizer.java` — now also enriches the **ID_TOKEN** (not just the access
  token).
- `auth/OidcClaimEnricher.java` — new `enrichIdToken()` returns `email`, `email_verified`,
  `preferred_username`, `name` (from the principal + its `display_name` attribute), all
  inside a read-only transaction (same pattern that fixed the earlier lazy-loading bug).

Nothing hardcoded; no domain knowledge added; `world-b-check` unaffected (iam-service is not
the gateway).

## Proof it works (what I ran)

- `client_secret_post` token exchange → **HTTP 200** (was 401 `invalid_client`).
- id_token + `/oauth/userinfo` now return:
  `email=rm.jane@meridian.local, name=Jane Kowalski, preferred_username=rm_jane`.
- Full flow through LibreChat's callback → redirect to `http://localhost:3080/`,
  **`refreshToken` issued**, and Mongo now has:
  `{username: rm_jane, email: rm.jane@meridian.local, provider: openid}`.
- LibreChat log: **`[openidStrategy] login success openidId: rm_jane`**.

---

## How to test it in the browser (60 seconds)

1. Make sure the stack is up: `docker compose ps` (gateway, iam-service, librechat, mongodb).
2. Open **http://localhost:3080**.
3. Click **"Login with Meridian SSO"**.
4. You're sent to the **Axiom** login page → enter `rm_jane` / `Meridian@2024`.
5. You land back in LibreChat, logged in as Jane Kowalski. Ask the hero prompt to confirm
   chat still works.

> Note: this demo path uses **plain local login + `X-User-Id`** for the *chat* requests; SSO
> is the *sign-in* layer. Both work. If you prefer the simplest demo, the email/password
> login (`rm_jane` / `Meridian@2024`) also still works.

### If Chrome ever shows a TLS / "Invalid character" error on `host.docker.internal:8084`
That's Chrome's HTTPS-First auto-upgrading the issuer URL (it did *not* happen in your
earlier run — you reached the Axiom login screen fine). If it ever does:
- Easiest: in Chrome, visit `chrome://settings/security` and turn **"Always use secure
  connections"** off, **or** clear HSTS for the host at `chrome://net-internals/#hsts`
  (Delete domain: `host.docker.internal`).
- The proper production fix is to serve Axiom over HTTPS (TLS-terminating proxy) and set the
  issuer to `https://…`. Not needed for the local demo.

---

## Also done this session
- **Conduit rename** fully validated: **89/89 Playwright e2e green** (52 min) after the rename.
- The product is **Conduit**; **Meridian** = the demo bank; **Axiom** = the identity service
  (`iam-service`). The "Meridian SSO" button label is intentional — employees sign in with
  their *bank* (Meridian) identity.

## Open / optional
- Commit is on `main` (SSO fix). Everything builds; iam-service image rebuilt with the fix.
- If you want zero browser warnings for SSO over HTTPS later, that's the TLS-proxy task above
  — purely cosmetic for the demo.
