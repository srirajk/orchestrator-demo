# Codex Brief — STEP 1: Axiom SSO Login Page (polish the IdP login)

Make the **Axiom SSO login page** — the page *every* Conduit app (Chat, Insights) lands on when it
redirects for authentication — a polished, professional, branded, enterprise-grade sign-in with clean
error handling. It already exists; **refine it, don't rebuild.** Do this first; Chat + Insights inherit it.

## Repo / branch
`/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Runtime docker project
`orchestrator-demo`. Rebuild iam to test: `docker compose -p orchestrator-demo build iam-service && docker compose -p orchestrator-demo up -d iam-service`.

## The files (all in `iam-service`)
- `iam-service/src/main/resources/templates/login.html` — the login form (Thymeleaf).
- `iam-service/src/main/resources/static/css/axiom-login.css` — its styling.
- `iam-service/src/main/java/com/openwolf/iam/controller/LoginController.java` — serves `/login` (and error/param passing).
- `iam-service/src/main/java/com/openwolf/iam/config/SecurityConfig.java` — `formLogin(...loginPage("/login"))`. **Leave the security wiring / auth decision alone — only the page + CSS + (if needed) the error-message passing.**

## Build (cosmetic + template only)
1. **Polish the login UI** — a clean, trustworthy, enterprise identity sign-in: Axiom wordmark/logo, a
   focused username + password form, a clear primary "Sign in" button, subtle "secured by Axiom" framing.
   It's an *identity product* page — professional and calm, not flashy. (You may align the palette with
   the Conduit design language — ink-navy + a restrained accent — since it fronts the Conduit apps.)
2. **Clean error handling** — a wrong username/password shows a **clear inline error** ("Incorrect
   username or password"), never a stack trace, blank page, or generic 500. Preserve the redirect-back
   (`?continue`/SAML/OIDC return) so a failed attempt re-renders the login with the flow intact.
3. **Responsive + accessible** — labels, focus states, keyboard submit, mobile layout.

## Constraints
- **`iam-service` only** — the login template + CSS + (if strictly needed) `LoginController` error passing.
- **DO NOT change** the SecurityConfig auth decisions/filter-chain behavior, the OIDC clients, the seed,
  claims, or the `/login` wiring. This is a **page**, not an auth change.
- Commit on `feat/conduit-chat`, "Approved by Sriraj.", no AI attribution. Run `scripts/world-b-check.sh`
  (CRITICAL stays 0).

## Verify (browser, real OIDC flow)
- Hit an app's OIDC entry (or go to `http://localhost:8084/login`) → the **branded Axiom login renders**.
- Sign in `insights_admin` / `Meridian@2024` → authenticates and returns to the flow.
- Wrong password → a **clean inline error**, form re-rendered, flow preserved (no 500 / no blank page).
- Renders well on desktop + mobile widths.

## Completion contract
iam builds + healthy, login page polished + error handling clean, world-b 0, committed. Screenshots:
the login page + the error state. Only `iam-service` touched. If anything forces an auth-decision change,
STOP and report — that's out of scope for this step.
