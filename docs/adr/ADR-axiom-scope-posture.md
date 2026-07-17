# ADR — Axiom Cerbos scope-posture: base=ceiling, tenant=grants, deny-all totality

**Status:** Accepted for implementation (Story B1 — de-risking prerequisite)
**Date:** 2026-07-16
**Scope of change:** Cerbos config + docs + a proof harness + a totality lint. **Does NOT touch
the gateway request path** — no Java, no existing policy is re-scoped. This ADR pins the posture
and the empirical facts that later multi-tenant work will build on.

---

## Context

Axiom (multi-tenant IAM) needs a Cerbos posture where a **base** policy defines the maximum
permission set (a ceiling) and each **tenant** policy can only *narrow* it — never exceed it, and
never silently inherit it. Cerbos expresses this with **scoped policies** and the
`SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS` scope-permission mode. Two contracts were
asserted up front and had to be **empirically proven** against the pinned runtime before any tenant
policy is authored — a wrong assumption here is fail-open at the authorization boundary.

### Runtime proven against (contract #1)

| | |
|---|---|
| Target (pinned) | `ghcr.io/cerbos/cerbos:0.53.0` |
| **Actually run** | `ghcr.io/cerbos/cerbos:latest`, which **is 0.53.0** — build commit `78d494cab93cf3e3723da18d5390bf9222593560`, build timestamp `2026-05-05T11:24:22Z`, Go 1.26.2 |
| Resolved digest | `sha256:c3fe64202e3793b7e9c41c5cbfcf390a51fb28ebce0a7d70754155e70db839c4` (RepoDigest of the local `:latest` tag) |
| Validation/test command | `cerbos compile --test-output=junit <policies-dir>` — **confirmed**: there is no standalone `cerbos test`; `compile` runs the `*_test.yaml` suites and `--test-output=junit` is a real flag on 0.53.0 |
| Config keys | `engine.lenientScopeSearch` and `audit.decisionLogsEnabled` — **confirmed valid** on 0.53.0 (server boots clean with both set) |

> **Offline note (honest):** the sandbox network blocks image pulls, so the `:0.53.0` *tag* could
> not be pulled to compare its manifest digest. The `:latest` image already present locally
> **reports version 0.53.0** (same binary, verified via `--version`), so the empirical proof was run
> against the exact pinned version — not a different cached one. Open item: re-pin the `:0.53.0`
> tag's own digest when network access is available (the *binary* is already confirmed 0.53.0).
> The unrelated `backend-*` project's `cerbos:0.40.0` image was left untouched.

---

## Decision

1. **Base scope (`scope: ""`) = the ceiling.** It enumerates every ALLOW any tenant may ever
   receive. It uses `scopePermissions: SCOPE_PERMISSIONS_OVERRIDE_PARENT` — **not** parental
   consent (it has no parent; see finding F0).
2. **Tenant scopes carry `SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS`.** A tenant ALLOW
   is effective only if the base ceiling also allows the same tuple. A tenant can therefore only
   *narrow*, never exceed, the ceiling (finding F3).
3. **Strict scope search — `engine.lenientScopeSearch: false`, set explicitly.** A request for a
   tenant scope that has **no policy** hard-DENYs instead of walking up to the base ceiling
   (finding F1). This is the difference between "unregistered tenant = locked out" and
   "unregistered tenant = inherits everything". Pinned explicitly because the failure mode of the
   opposite setting is fail-open.
4. **A tenant policy is a TOTAL RESTRICTION LAYER, authored from the deny-all bootstrap template.**
   Because a silent tenant tuple inherits the parent ALLOW (finding F2), every activated tenant
   policy must carry an **explicit `EFFECT_DENY`** for every base-ceiling tuple it does not intend
   to grant. An empty/stub tenant policy is **fail-open** and is forbidden.
5. **A static totality lint** (`scripts/cerbos-tenant-totality-lint.py`) rejects any tenant policy
   that leaves a base-allowed `(resource, action, role)` tuple unmatched — catching the
   fall-through hole before it ships.
6. **`audit.decisionLogsEnabled: true`** so every verdict is on the audit trail.

---

## Empirical findings (the deliverable)

All decisions below are the **actual** verdicts captured from `CheckResources` against
cerbos 0.53.0, reproducible with `bash infra/cerbos/proof/run-proof.sh` and asserted in
`infra/cerbos/proof/policies/scope_posture_test.yaml` (11 test cases, all pass via
`cerbos compile --test-output=junit`). Fixture ceiling: role `user` may `view`+`edit` a `widget`;
`delete` is off-ceiling.

| # | Probe | Setup | **Actual verdict** | Meaning |
|---|---|---|---|---|
| **F0** | Root under parental-consent | base `scope:""` set to `REQUIRE_PARENTAL_CONSENT` | view/edit **DENY** | A root that requires parental consent denies everything (no parent can consent). ⇒ base must use `OVERRIDE_PARENT`. |
| **F1** | `absentTenantScopeDeniesEverything` | request scope `ghost` (no policy), strict search | view/edit **DENY** | Missing tenant scope = **hard deny**. ✅ desired posture. |
| **F1′** | Same, **lenient** search (contrast) | `lenientScopeSearch: true` | view/edit **ALLOW** | Under lenient search the absent tenant **inherits the base ceiling** — fail-open. Proves `false` is load-bearing. |
| **F2** | `emptyChildFallsThroughToParent` | tenant `acme` grants only `view`, silent on `edit` | edit **ALLOW** (inherited) | A tenant tuple the policy is **silent** on inherits the parent ALLOW. **Contract #2 CONFIRMED: an empty tenant policy does NOT deny — it is fail-open.** |
| **F3** | `tenantOverrideCannotExceedBaseCeiling` | tenant `exceed` grants `delete`; base withholds it | delete **DENY** | A tenant cannot out-grant its parent. Ceiling holds. ✅ |
| **F4a** | `denyAllTemplateDeniesUntilGranted` (before) | tenant `boot` = deny-all template, no grants | view/edit **DENY** | Explicit-DENY template denies everything even though base allows. ✅ |
| **F4b** | `denyAllTemplateDeniesUntilGranted` (after) | tenant `boot2` = template + `view` grant | view **ALLOW**, edit **DENY** | Denies until a grant is explicitly added. ✅ |

### The load-bearing conclusion

**Contract #2 is CONFIRMED.** Under `REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS`, parental consent only
constrains tuples the tenant *explicitly ALLOWs* (they must also be allowed by the parent). It does
**not** require the tenant to re-state everything — a tuple the tenant is silent on **falls through
to the parent's ALLOW**. Therefore:

- An "empty tenant policy denies everything" assumption is **FALSE and fail-open**.
- The corollary phrasing "an ALLOW with a false condition = implicit child deny" is **also
  fail-open** — a non-matching rule is *silence*, and silence inherits the parent ALLOW. The
  deny-all template therefore uses an **explicit `EFFECT_DENY`** for withheld tuples, not a
  false-conditioned ALLOW. (This is a correction the empirical result forced onto the original
  template sketch.) `EFFECT_DENY` beats `EFFECT_ALLOW` in Cerbos and a child DENY overrides the
  parent ceiling, so an explicitly-withheld tuple is truly denied.

---

## The deny-all bootstrap template

`infra/cerbos/templates/tenant-deny-all.yaml`. Model:

- `scopePermissions: SCOPE_PERMISSIONS_REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS`.
- A **GRANTED bucket** (`EFFECT_ALLOW`) — empty for a fresh tenant.
- A **WITHHELD bucket** (`EFFECT_DENY`) — every base-ceiling `(action, role)` tuple.
- To grant a tuple: **move** it from WITHHELD to GRANTED (never delete — deletion reopens the
  fall-through hole).

## The totality lint

`scripts/cerbos-tenant-totality-lint.py [POLICIES_DIR …]` (default `infra/cerbos/policies`).

- Computes the ceiling per resource = union of `EFFECT_ALLOW` tuples across every strict ancestor
  scope (root + any intermediate scopes).
- For every tenant (non-empty scope) policy, asserts each ceiling tuple is **explicitly covered**
  (an ALLOW or a DENY rule whose actions include the action, or `*`, and whose roles include the
  role, or `*`).
- An uncovered tuple ⇒ **exit 1** with the exact `(resource@scope, action, role, file)` to fix.
- Warns if a tenant policy is missing `REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS`.

Demonstrated teeth: against the proof fixtures it flags exactly the two intentionally-holey tenants
(`acme` and `exceed`, both silent on `edit`); against the current production `infra/cerbos/policies`
(which has **no** tenant scopes yet) it passes clean.

---

## Consequences

**Benefits.** The multi-tenant authorization posture is now proven, not assumed. The single most
dangerous failure mode (an empty/stub tenant policy silently inheriting the base ceiling) is (a)
documented with a live repro, (b) prevented by an explicit-DENY template, and (c) caught in CI by a
static lint. The config pin (`lenientScopeSearch: false`) closes the unregistered-tenant fail-open.

**Costs / caveats.**
- Tenant policies are verbose by design (every ceiling tuple explicitly stated). That verbosity is
  the safety property; the lint enforces it.
- Conditional grants: for a grant gated on a CEL condition, "allow when C" needs a paired
  "deny when !C" (because a false condition is silence → fall-through). Unconditional role-based
  grants use the simple two-bucket form. The lint checks *coverage*, not the condition logic.
- The existing production policies are **not** re-scoped by this ADR — they remain single-scope
  (base) and behave identically. Re-homing them under base+tenant scopes is later, request-path
  work, gated by this posture.

## Open items / deferrals

1. Re-pin the `:0.53.0` **tag** digest once network allows an image pull (binary already confirmed
   0.53.0 via the local `:latest`).
2. Wire the totality lint into `scripts/verify.sh` / CI when tenant scopes are first introduced
   (today it passes trivially — zero tenant scopes).
3. ~~Re-home the live Conduit resource policies (`agent`, `relationship`, `iam-resource`, …) under
   base+tenant scopes~~ — **DELIVERED by Story B2** (2026-07-17). All 6 policies moved to the
   base/tenant scoped model with a shared `business_derived_roles` tenant-equality backstop, the
   `default` tenant total children, and BYTE-IDENTICAL single-tenant decisions (parity gate:
   `scripts/cerbos-parity-run.sh`, 800 cells PRE=POST). NB: the root scope leaves `scopePermissions`
   UNSET (Cerbos default = OVERRIDE_PARENT) — setting it explicitly on >1 root policy false-conflicts
   on 0.53 (see B2 notes). Turned out to be **NOT** request-path-touching: the gateway sends no
   `tenant_id`/`scope`, so it was policy-only.
