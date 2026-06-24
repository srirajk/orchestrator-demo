# Phase 10 — Role-Based Authorization (Spring Security) + Declarative Org Seed

> **What this phase adds.** Phases 8 / 8.5 gave you *authentication* (verified RS256 JWT) and
> *relationship entitlement* (the book check on data). They do **not** gate endpoints by role —
> today the admin plane (`/admin/agents`) is **wide open**: any caller reaching the gateway can
> register or delete an agent. This phase closes that with **role-based endpoint authorization**
> via Spring Security, adds **domain-scoped admins**, and preloads the org (teams + people) from a
> **declarative seed file**. After this, *who you are* decides *what endpoints you can touch*, and
> the demo comes up fully populated and resettable.

**Milestones:** M10-1 (Spring Security resource-server + role matrix), M10-2 (domain-scoped admin
check + declarative org seed).
**Read first:** the Phase 8 identity spec, `ROLE-MODEL-AND-SECURITY.md` (full detail), the audit
finding that the admin plane is unauthorized.

## Current state (verified in code)
Phase 8.5 fixes are **done and confirmed**: the JWT principal crosses the async boundary
(flip test passes), the OTel `trace_id` is the single correlation id, the LibreChat header is the
conversation id, skipped agents are emitted, anonymous has an empty book. **21/21 unit + 25/25 E2E
pass.** The async fix was the *explicit-pass* approach (principal captured on the servlet thread
and passed in) — this matters for the SecurityContext note below.

---

## The three roles

| Role | Scope | Can do |
|------|-------|--------|
| `platform_admin` | **Global** | Everything, every domain — register/manage agents anywhere, chat, all traces. |
| `domain_admin` | **Their domain(s)** | Register/manage agents **in their own domain only**, chat, traces for their domain. |
| `relationship_manager` | **Their domain(s)** | Chat (call agents) + read traces; data still bounded by relationship entitlement. **Cannot** register agents. |

**JWT claims** (issued by user-mgmt): `roles`, `domains` (member of), `admin_domains` (administers;
only for `domain_admin` — `platform_admin` is global by role).

---

## Authorization matrix

| Endpoint | Coarse (role — Spring Security URL rule) | Fine (resource-level check) |
|----------|------------------------------------------|------------------------------|
| `POST/PUT/DELETE /admin/agents/**` | `platform_admin` OR `domain_admin` | `domain_admin`: agent's `domain` ∈ `admin_domains`; `platform_admin`: any |
| `GET /admin/agents/**` | `platform_admin` OR `domain_admin` | `domain_admin` sees own domains; `platform_admin` all |
| `GET /debug/resolve` | `platform_admin` OR `domain_admin` | — (internal diagnostic) |
| `POST /v1/chat/completions` | **authenticated** (any role) | relationship entitlement (existing) |
| `GET /trace/stream`, `/trace/health` | **authenticated** (any role) | — (SSE caveat below) |
| `GET /v1/models`, `/actuator/**` | `permitAll` | — |

> Chat (agent callouts) and trace viewing sit at the **same** member level; agent *registration* is
> the admin action. `platform_admin` clears every row by the global role.

---

## Build (scope)

### M10-1 — Spring Security + role matrix
- Add `spring-boot-starter-oauth2-resource-server`. Configure JWKS uri (user-mgmt), iss/aud
  validation. This **replaces** the hand-rolled `JwtAuthFilter` + `JwksClient` (the audit's
  JWKS-refresh race goes away). **Keep the JWT rejection tests** (forge wrong-key/expired/wrong-aud
  → 401) — they still apply to the resource-server.
- `JwtAuthenticationConverter` maps the `roles` claim → `ROLE_` authorities.
- `SecurityFilterChain` implements the coarse matrix above (`hasAnyRole(...)` / `authenticated()` /
  `permitAll()`).

### M10-2 — Domain-scoped admin + declarative seed
- **Fine check (resource-level):** an `AgentAuthorization` bean reads `admin_domains` from the JWT
  and verifies the agent's domain is in scope; `platform_admin` bypasses. Called in
  `AgentRegistryController` (or via `@PreAuthorize`). **This is mandatory** — the URL rule alone
  can't see the agent's domain, so without it a `domain_admin` of one domain could register into
  another.
- **Declarative org seed:** user-mgmt loads `seed/org.yaml` idempotently at startup (domains,
  people, roles, memberships, admin grants). Books are **derived** from membership, not stored.
  Mount read-only so editing + restart resets the demo. Add `domain_admin`/`platform_admin` roles,
  an `admin_domains` set per user, `POST/DELETE /domains/{id}/admins` endpoints, and emit
  `roles`/`domains`/`admin_domains` in the JWT.

### Sample set (seed)
**Teams:** `wealth-private-banking` (REL-00042, REL-00099), `intl-wealth` (REL-00188, REL-00200),
`servicing-ops` (REL-00300), `platform`.
**People:** `admin`/Avery Stone (platform_admin); `da_wpb`/Dana Whitfield (domain_admin,
wealth-private-banking); `rm_jane`, `rm_chen` (RMs, wealth-private-banking); `da_intl`/Kemi Adebayo
(domain_admin, intl-wealth); `rm_okafor` (RM, intl-wealth); `da_svc`/Sam Ortiz (domain_admin,
servicing-ops); `rm_diaz` (RM, servicing-ops).

---

## Notes & caveats

- **SecurityContext and the async boundary — now a non-issue, here's why.** `SecurityContextHolder`
  is a `ThreadLocal` and won't cross into the chat pipeline's async thread. But it doesn't need to:
  the **coarse role checks run in Spring Security's filter chain on the servlet thread** (before
  the controller/`runAsync`), the **admin endpoints are synchronous** (so the fine domain-scope
  check has the SecurityContext), and the only async endpoint (chat) just needs `authenticated` —
  enforced on the servlet thread — plus the principal, which is already passed explicitly. So **do
  not** add an authorization check *inside* the async pipeline expecting `SecurityContextHolder` to
  be populated; use the passed principal if you need identity there.
- **Glass-box `/trace/stream` SSE** can't send a token (browser `EventSource` has no headers). To
  enforce `authenticated` you'd pass the token as a query param + a small filter, or keep it on the
  internal network. For the demo, keep it internal; note as a seam.

## Out of scope (deliberate)
- **Embedding the glass-box in LibreChat** — that's a frontend fork; use a split-screen wrapper page
  you own for the demo. Post-sale decision, not this phase.
- **Cerbos-as-enforcer (F7) and per-hop agent verification (F8/M16)** — separate workstreams; not
  part of role authorization.

---

## Automated acceptance (the role matrix — REQUIRED)
- `relationship_manager` (rm_jane) → `POST /admin/agents` → **403**.
- `domain_admin` (da_wpb) → register agent `domain=wealth-private-banking` → **201**; register
  agent `domain=intl-wealth` → **403** (the fine check).
- `platform_admin` (admin) → register agent in **any** domain → **201**.
- **No token** → `/admin/agents` → **401**.
- `relationship_manager` → `POST /v1/chat/completions` → **200** (then relationship entitlement);
  → `GET /trace/health` → **200**.
- Seed: a clean `docker compose up` loads the sample org from `seed/org.yaml`; re-running is
  idempotent; rm_jane's derived book = her domains' relationships.

## ■ HUMAN TEST GATE → STOP
1. Log in as **rm_jane** → try to register an agent (via API) → **denied (403)**; use chat → works.
2. Log in as **da_wpb** (domain admin) → register an agent in **wealth-private-banking** → **works**;
   try **intl-wealth** → **denied**.
3. Log in as **admin** (platform) → register in any domain → **works**.
4. Wipe Redis + restart → the org reloads from `seed/org.yaml` to the same state.

**PASS =** endpoints are gated by role, domain admins are scoped to their own domain, registration
is admin-only (RMs can't), and the org seeds declaratively and resets cleanly. Write status to
`BUILD_REPORT.md`, post `PHASE 10 COMPLETE`, halt.

## Order
Phase 8 (identity) → Phase 8.5 (correctness — **done**) → **Phase 10 (this — roles/seed)** → Phase 9
(persistence/observability). Phase 10 depends only on the 8.5 propagation fix (done), not on
Phase 9 — it can run now.
