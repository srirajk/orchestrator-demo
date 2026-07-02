# ADR — Split the admin console from the end-user chat

**Status:** Proposed · **Date:** 2026-07-02 · **Owner:** Sriraj
**Related:** `CODEX-WORKLIST.md` (P0 persona/impersonation — this ADR *retires* it)

---

## Context

The current app (`admin-ui/`, project `conduit-integrated-enterprise-ai-ui`) is **one Vite app** that
bundles two very different surfaces under a single shell, single login, and single JWT:

- **Axiom Admin Console** — IAM governance: Users, Teams, Roles, Policies (Cerbos), Audit Log.
  Audience: **platform/security admins** (`platform_admin`).
- **Conduit Workbench** — chat + live glass-box trace rail. Intended as the **end-user chat**
  (the LibreChat replacement, for **any entitled user — not just RMs**: RMs, underwriters, analysts,
  ops, compliance, execs, and any future role; what each sees is driven by their entitlements + the
  manifests, never hardcoded).

Bundling them was a reasonable **speed** move for an MVP, but it's the wrong **end-state** for an
end-user surface. Evidence it's already causing problems: the Workbench sends the *admin's* token to
the gateway, so entitlements reflect the admin, not the user — the Context Ledger literally shows
`PRINCIPAL: admin`. That is the P0 persona bug, and it is a **symptom of the merge.**

## Decision

**Split into two apps that share a design system and gateway client.**

1. **Axiom Admin Console** (`apps/admin`) — admins only.
   Pages: Dashboard, Users, Teams, Roles, Policies, Audit. Auth: admin login → JWT with
   `platform_admin`; Bearer to **`/api`** (IAM). *Optional:* keep an **operator Workbench** here
   **with impersonation** purely so admins can test the gateway as a chosen persona.
2. **Conduit Chat** (`apps/chat`) — **any entitled end-user** (RMs, underwriters, analysts, ops,
   compliance, …). **The LibreChat replacement.**
   The chat + glass-box trace rail, where the user logs in **as themselves** (OIDC/SSO via Axiom).
   Auth: the *user's* JWT (carrying their `book`/`segments`/`clearance`/`roles`); Bearer to
   **`/gateway-api`**.
3. **Shared packages** (monorepo):
   - `packages/ui` — Tailwind config + `index.css` tokens + the `ui/` component kit (Button, Dialog,
     Badge, Input, Toast, Skeleton, EmptyState, Panel, StatusPill).
   - `packages/gateway-client` — the gateway API client + workbench types/hooks
     (`useTraceStream`, `useWorkbenchChat`, `selectors`, `traceEvents`), reused by the chat app and
     the optional admin operator view.

## Why (rationale)

1. **Audience & least privilege.** The admin console creates users, edits roles, and writes Cerbos
   policy — strictly `platform_admin` work. An RM must never be one nav-click from IAM
   administration. Different audiences → different apps.
2. **It dissolves P0 by construction.** In `apps/chat`, the logged-in user *is* the principal
   (rm_jane logs in as herself), so the gateway receives rm_jane's claims directly →
   entitlement/denial works with **no impersonation endpoint at all**. Impersonation is then only
   needed for the *optional* admin operator-test view.
3. **Security surface & deployment.** Admin and end-user chat get different auth scopes and separate
   deployables; a bug or compromise in one doesn't expose the other. One bundle = larger blast
   radius.
4. **Product clarity.** Axiom (identity governance) and Conduit (the AI gateway) are distinct
   products with distinct value props; one shell blurs both brands.

## Auth model per app (the crux)

| | Axiom Admin (`apps/admin`) | Conduit Chat (`apps/chat`) |
|---|---|---|
| Who logs in | platform/security admin | any entitled end-user (e.g. rm_jane, uw_sam) |
| Login | Axiom username/password | **Axiom OIDC / SSO** |
| Token claims | `platform_admin` roles | user's `book`/`segments`/`clearance`/`roles` |
| Calls | `/api` (IAM) | `/gateway-api` (gateway) |
| Entitlements | n/a (admin) | **correct by construction** (real principal) |
| Impersonation | only for optional operator Workbench | **none needed** |

## How this retires P0

P0 asked for an Axiom `/auth/impersonate` token-exchange so the *admin* app could run chat as a
persona. With the split, the **end-user chat authenticates as the real user**, so the denial story
(rm_jane → Whitman ok, Okafor denied) is inherent — no impersonation, no `X-Act-As-User`. Keep the
`/auth/impersonate` contract **only** if you keep an admin operator Workbench for testing.

## Migration path (reuse what's built — low waste)

1. Stand up a monorepo (pnpm/npm workspaces or Turborepo). Move `admin-ui/` → `apps/admin`.
2. Extract `packages/ui` (Tailwind config, `index.css`, `components/ui/*`) and
   `packages/gateway-client` (`features/workbench/api.ts`, `types.ts`, `hooks/*`, `utils/*`).
3. Create `apps/chat`: the existing `features/workbench/*` (ChatPanel, TraceRail, ContextLedger,
   CoveragePanel, HealthPanel, WorkbenchPage) becomes the chat app's main screen, consuming
   `packages/gateway-client`. Wire its login to **Axiom OIDC/SSO** so the user token is the real
   principal.
4. In `apps/admin`, either drop the Workbench route or keep it as an **operator** view behind an
   impersonation picker (`/auth/impersonate`).
5. Fix the shared code once, both apps benefit — and fold in the `CODEX-WORKLIST.md` items
   (NEW-1 trace scoping, NEW-3 reset, ErrorBoundary/NEW-12, tests/NEW-13, a11y) into the shared
   packages so neither app regresses.

## Trade-offs / when the merge would have been fine

- **Cost:** two build/deploy pipelines + monorepo tooling; a little more setup.
- **The merge is acceptable** *only* if the Workbench were purely an **admin operator tool** (never
  an end-user surface). Given the goal is to replace LibreChat for **all entitled users**, it isn't —
  so split.

## Open decisions for Sriraj

1. Keep an **operator Workbench** inside the admin console (needs impersonation), or move all chat to
   `apps/chat` only?
2. Monorepo tool: **Turborepo** vs plain **pnpm workspaces**.
3. Does `apps/chat` reuse the full glass-box trace rail for end-users, or a simplified end-user view
   (trace rail as an admin/debug-only affordance)?
