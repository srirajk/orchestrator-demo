# Phase 8 — Identity, Domains & End-to-End Authorization

> **Enterprise-grade identity, security all the way through.** One identity authority
> (user-mgmt) issues signed (**RS256**) tokens; the gateway **and every agent** verify them;
> and entitlements **derive from a domain → member → membership model** (Cerbos), so the
> rm_jane/Okafor denial is a *consequence of org structure*, not seed data. Most "AI gateway"
> demos enforce auth at the front door and trust everything internally — this one verifies at
> every hop and authorizes at every decision. **That is the differentiator.**
>
> **Security looks identical whether it's real or fake** on the happy path. The only proof is
> tests that make the negative cases fail. Treat the test matrix below as part of "done," not
> an afterthought — the current repo has ~one gateway test, so these must actually be written.

**Structure — two milestones so a hard part can't sink the whole phase:**
- **M15 = Core (wins the demo, demoable on its own):** RS256/JWKS issuance, domain/member
  model, **gateway** verifies, Cerbos authz from membership, LibreChat forwards the token.
- **M16 = Completion (security at *every* hop):** push JWT verification out to the **agents**,
  and stand up the **OIDC login + redirect** flow. The MCP-path JWT verification is the
  least-trodden ground in the stack — isolate it here so M15 can pass even if M16 is fiddly.

**Read first:** `docs/authorization-abac-cerbos-deep-spec.md`,
`docs/technical-architecture-clear-boundaries.md`, the existing `user-mgmt/` service, and
`CLAUDE.md` §6 rule **f** (no LibreChat fork).

---

## M15 — Core: one verified identity + domain-driven authz

### Identity authority (`user-mgmt`)
- **Domain / member / membership model** (the heart — authz must follow this, not seed lists):
  CRUD for **domains**, **members**, and **membership** (add member→domain, role in domain).
  Seed: `rm_jane` ∈ *Wealth* (owns REL-00042, REL-00099); `rm_okafor` ∈ a *different* domain
  (owns REL-00188); an `admin`.
- **RS256 + JWKS (never HS256).** Sign with a private key; expose `/.well-known/jwks.json` so
  verifiers hold only the **public** key. Claims: `sub` (principal, e.g. `rm_jane`), `email`,
  `roles`, domain memberships. Short-lived access token + refresh.

### Gateway — verify at the front door
- An **interceptor** verifies every request's JWT: **signature via JWKS, `exp`, `iss`, `aud`**
  → 401 on any failure. Extract claims → **Cerbos principal** + memberships.
- **Verify, don't trust.** A forwarded token is trusted only *after* verification.

### Authorization from the org model (Cerbos)
- Policies decide on **domain membership + relationship ownership**, not seed lists. `rm_jane`
  sees REL-00042 (member of owning domain); REL-00188 denied (not a member). `is_mutating==
  false` still enforced.

### LibreChat wiring (config only — no fork)
- Forward identity + conversation id over the internal hop:
  ```yaml
  headers:
    Authorization:     'Bearer {{LIBRECHAT_OPENID_TOKEN}}'   # or a user-mgmt-issued token
    X-Conversation-Id: '{{LIBRECHAT_BODY_CONVERSATIONID}}'
  ```
  (For M15 the gateway may trust this internal hop; the **redirect login** is M16.)

### M15 automated tests (REQUIRED — these are the proof, not the gate)
Mint tokens in-test with a throwaway keypair so you can forge bad ones:
- **Reject** (gateway → 401): wrong-key signature · expired · wrong `aud` · wrong `iss` ·
  tampered payload (valid header, mutated claim) · missing token. *(These prove real
  verification vs decode-and-trust — the single most important tests in the build.)*
- **Accept:** a valid token → 200 and correct principal extracted.
- **Authz, by membership not seed:** `rm_jane`→REL-00188 **denied**; then **add `rm_jane` to
  Okafor's domain** and the *same* request flips to **allowed** (proves entitlements follow the
  model). `is_mutating==true` request → denied.
- Layers: JUnit + Testcontainers (Cerbos, Redis); pytest for user-mgmt issuance + JWKS.

### ■ M15 HUMAN TEST GATE → STOP
Log in as `rm_jane` (token over the internal hop) → hero prompt returns. Ask about Okafor
(REL-00188) → **denied, reason = not a member of the owning domain**. As admin, create a
domain + member + membership → the new member's access reflects it. Show one **rejection** live
(e.g. a tampered token → 401). Write status to `BUILD_REPORT.md`, post `PHASE 8 (M15) COMPLETE`,
halt for "proceed to M16".

---

## M16 — Completion: security at every hop + OIDC redirect

### Agents verify too (HTTP/FastAPI + MCP)
- Each agent **verifies the JWT itself** (signature via JWKS, `exp`/`aud`) before serving — no
  implicit trust gateway→agent. Propagate the token: `Authorization: Bearer …` on HTTP;
  header **and** `_meta` on MCP (transport headers may not reach the tool handler — the fiddly
  bit; budget for it).

### OIDC login + redirect
- LibreChat delegates login via the **authorization-code flow**. **Build-vs-configure — decide
  explicitly:** a hand-rolled OIDC provider is what a bank security review probes hardest —
  **prefer configuring a proven base** (or front with **Keycloak**, with user-mgmt owning the
  domains/members/roles that map into it). If implemented directly, do it to spec: discovery
  doc, JWKS, PKCE, `state`/`nonce`, redirect-URI validation.

### M16 automated tests (REQUIRED)
- **Per-hop reject:** call a FastAPI agent and the MCP server directly with **no / invalid /
  expired** token → **401 at the agent** (proves the trace doesn't rely on the gateway alone).
  Valid token → serves.
- **OIDC flow:** automated check of the redirect → callback → token issuance (or, if
  configured on Keycloak, a smoke test that login yields a verifiable RS256 token with the
  expected claims).

### ■ M16 HUMAN TEST GATE → STOP
Log in through the **OIDC redirect** (redirect → login → back). Hero prompt as `rm_jane` →
returns; confirm via logs/trace the token was **verified at the gateway AND at an agent**.
Hit an agent directly with a bad token → **401**. Write status, post `PHASE 8 COMPLETE`, halt
for "proceed to Phase 9".

---

## Enterprise-grade bar (acceptance backbone)
RS256 + JWKS; verify **signature + `exp` + `iss` + `aud`** at the **gateway AND each agent**;
authz from **domains/members**; short-lived tokens; **key rotation** documented. A
forwarded-but-unverified token is **worse** than a header — never land there.

## Production seam (document, don't build for the demo)
Full standalone OIDC provider hardening, refresh-token rotation, key rotation automation, and
federation to the bank's existing IdP. State it in `BUILD_REPORT.md` as the production path so
the architecture answer is complete without spending demo runway on it.

## Do NOT build
- HS256 for cross-service trust (must be RS256 + JWKS).
- A hand-rolled OIDC provider when a proven base will do (prefer configure).
- Any path that trusts a forwarded JWT **without verifying** it.
- A LibreChat code fork (config only).