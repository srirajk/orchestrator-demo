# Axiom Policy Studio — REST API contract

> Audience: the frontend (Codex) building the Policy Studio screens.
> Backend: `iam-service` (Axiom). Base URL is the IAM service origin (e.g. `http://localhost:8081`).
> Every endpoint here is under `/admin/**`, so it is doubly gated (see **Auth model**).
> Source of truth for shapes: the Java records named in each section — this doc mirrors them field-for-field.

---

## Auth model (read first)

- **Transport**: OIDC Bearer JWT (RS256), `Authorization: Bearer <token>`, minted by Axiom. Verified at every hop.
- **Roles**: the token's `roles` claim → Spring authorities. Studio roles: `policy_drafter` (author), `policy_approver`
  (approve/promote), plus `platform_admin`. These roles sit under `tenant_admin`/`platform_admin` in the Cerbos meta-authz
  hierarchy — a studio user's token typically carries both an admin role and a studio role.
- **Two gates, both enforced**:
  1. **Filter gate** — the whole `/admin/**` surface requires `platform_admin` OR `tenant_admin` OR `domain_admin`.
     A token without one of these is `403` before any handler runs.
  2. **Method gate** — each endpoint additionally requires its specific studio role via `@PreAuthorize` (listed per endpoint).
     Wrong role ⇒ `403`.
- **Tenant scope is derived from the token's `tenant_id` claim (A1/A2), NEVER a request/query/body field.** A body that
  names another tenant, or references another tenant's object, is rejected `403` (`tenant_scope_violation`) or `404`.
  A token with no `tenant_id` claim fails closed `403`.
- **Identity is always the verified `sub`** (drafter/author/approver). No endpoint trusts a caller-supplied identity field.

### Common error envelope

Studio errors return: `{ "error": "<code>", "message": "<human text>" }`.

| Status | `error` code | Meaning |
|---|---|---|
| 401 | (Spring default) | no / invalid token |
| 403 | `tenant_scope_violation` | cross-tenant body, unknown tenant-scoped object, or missing `tenant_id` claim |
| 403 | `unauthorized_promotion` | the signed approval does not authorize this candidate/review |
| 403 | (Spring default) | wrong role (filter or method gate) |
| 409 | `separation_of_duties` | author == approver (C1.2/C1.3), or break-glass self-approval |
| 409 | `stale_baseline` | the reviewed baseline moved (stale review or stale promotion CAS) |
| 422 | `bundle_tamper` | candidate bundle failed content-addressed integrity |
| 422 | `audit_integrity` | examiner join broken (missing/mismatched record) |
| 422 | `unprocessable` | inadmissible artifact / integrity failure (IllegalState) |
| 400 | `bad_request` | malformed request (bad scope, missing field, TTL out of range) |

---

## Endpoint index

| # | Method | Path | Role (method gate) | Service | New? |
|---|---|---|---|---|---|
| 1 | GET | `/admin/tenants` | `platform_admin` | `ActiveTenantDirectory` | **NEW** |
| 2 | GET | `/admin/tenants/{tenantId}` | `platform_admin` | `ActiveTenantDirectory` | **NEW** |
| 3 | POST | `/admin/tenants` | `platform_admin` | `TenantProvisioningService` | pre-existing |
| 4 | DELETE | `/admin/tenants/{tenantId}` | `platform_admin` | `TenantProvisioningService` | pre-existing |
| 5 | GET | `/admin/tenants/directory` | `platform_admin` | `ActiveTenantDirectory` | pre-existing |
| 6 | POST | `/admin/studio/drafts` | `policy_drafter` | `PolicyStudioGenerationService` | **NEW** |
| 7 | POST | `/admin/studio/reviews` | drafter/approver/platform_admin | `ConsequenceDiffService` | **NEW** |
| 8 | GET | `/admin/studio/reviews/{reviewId}` | drafter/approver/platform_admin | (session store) | **NEW** |
| 9 | GET | `/admin/studio/bundles` | drafter/approver/platform_admin | `PolicyBundleRepository` | **NEW** |
| 10 | GET | `/admin/studio/bundles/{bundleId}` | drafter/approver/platform_admin | `PolicyBundleRepository` | **NEW** |
| 11 | GET | `/admin/studio/examiner/{cerbosCallId}` | drafter/approver/platform_admin | `ExaminerChainService` | **NEW** |
| 12 | POST | `/admin/studio/promotions` | `policy_approver` | `ConsequenceApprovalService` + `PolicyPromotionService` | **NEW** |
| 13 | POST | `/admin/studio/rollbacks` | `policy_approver` | `PolicyPromotionService#rollback` | **NEW** |
| 14 | POST | `/admin/studio/break-glass` | `policy_drafter` | `BreakGlassAuthoringService` | **NEW** |
| 15 | POST | `/admin/studio/break-glass/{grantId}/approve` | `policy_approver` | `BreakGlassApprovalService` | **NEW** |
| 16 | GET | `/admin/studio/break-glass` | drafter/approver/platform_admin | (session store) | **NEW** |

> The end-to-end studio flow: **draft (6)** → **review (7)** → **approve+promote (12)**; **bundles/examiner (9–11)**
> are the audit/history reads; **break-glass (14–16)** is the emergency path. Reviews and break-glass artifacts are held
> in an in-memory session store (single node) so the SPA exchanges ids, not crypto-bound graphs — see "Session store note".

---

## B4 — Provisioning

### 1. `GET /admin/tenants` — list active tenants
- **Role**: `platform_admin`. **Tenant derivation**: n/a (platform-wide view).
- **Security preconditions**: platform-admin only (filter + method); reads the atomic active-tenant directory (a tenant
  appears only after its 4-artifact activation CAS committed).
- **Response 200** (`TenantProvisioningController.TenantList`):
```json
{
  "directoryVersion": 7,
  "tenants": [
    { "tenantId": "acme", "active": true, "policyVersion": "pv-2", "directoryVersion": 7 },
    { "tenantId": "meridian", "active": true, "policyVersion": "pv-1", "directoryVersion": 7 }
  ]
}
```
- **Errors**: 401; 403 (non platform-admin).

### 2. `GET /admin/tenants/{tenantId}` — tenant status
- **Role**: `platform_admin`. **Security preconditions**: platform-admin only. Present ⇒ active; absent ⇒ fail-closed inactive.
- **Response 200** (`TenantStatus`):
```json
{ "tenantId": "meridian", "active": true, "policyVersion": "pv-1", "directoryVersion": 7 }
```
  An unknown tenant returns `{ "tenantId": "ghost", "active": false, "policyVersion": null, "directoryVersion": 7 }`.
- **Errors**: 401; 403.

> (3) `POST /admin/tenants` and (4) `DELETE /admin/tenants/{tenantId}` are pre-existing; both require the
> `Idempotency-Key` header. `POST` body: `{ "tenantId", "name", "slug" }` → `ProvisioningResult`
> (`{ operationId, tenantId, status, policyVersion, directoryVersion, stagedArtifacts[] }`, status ∈
> `PENDING|ACTIVE|FAILED|DEACTIVATED`). (5) `GET /admin/tenants/directory` → `{ version, tenantPolicyVersions{} }`.

---

## C2 — Authoring (draft)

### 6. `POST /admin/studio/drafts` — NL intent → validated canonical policy
- **Role**: `policy_drafter`.
- **Security preconditions**: author = verified `sub`; **author scope = `TenantScope.of(<tenant_id claim>)`** (never a body
  field), so a drafter can only target their own tenant subtree. The model only *proposes*; a deterministic gate decides;
  only the IR-derived **canonical** YAML is ever returned (never the model's raw text). A rejected proposal is a normal
  `200` with violations, not an error.
- **Request** (`StudioAuthoringController.DraftPayload`) — `vocabulary`/`baseCeiling` are the closed manifest grounding
  (see **Grounding gap** below):
```json
{
  "intent": "allow the desk to read agent decisions",
  "subscopesEnabled": false,
  "vocabulary": {
    "resourceKind": "agent",
    "actions": ["read"], "classifications": [], "attributes": [],
    "roles": ["desk"], "approvedImports": []
  },
  "baseCeiling": {
    "resourceKind": "agent",
    "tuples": [ { "action": "read", "role": "desk" } ],
    "carriesTenantEqualityBackstop": true,
    "reservedIdentities": []
  }
}
```
- **Response 200** (`DraftResponse`):
```json
{
  "draftId": "draft-8f0c…",
  "tenantId": "meridian",
  "authorId": "drafter-dan",
  "accepted": true,
  "canonicalYaml": "apiVersion: api.cerbos.dev/v1\n…",
  "validation": { "ok": true, "violations": [], "stage": "ACCEPTED" }
}
```
  On rejection: `accepted:false`, `canonicalYaml:null`, `validation.ok:false`, `validation.violations:[…]`,
  `validation.stage ∈ PARSE|VALIDATE|COMPILE|COMPILE_SKIPPED`.
- **Errors**: 401; 403 (not a drafter / no tenant claim); 400 (`bad_request` — missing intent/vocabulary/baseCeiling,
  or vocabulary.resourceKind ≠ baseCeiling.resourceKind).

---

## C4 — Consequence review

### 7. `POST /admin/studio/reviews` — compute the decision delta (business consequences)
- **Role**: `policy_drafter` | `policy_approver` | `platform_admin`.
- **Security preconditions**: tenant = claim; the **verified author (`sub`) is recorded server-side with the review** so the
  later promotion can enforce author≠approver against a trusted identity. Truth is computed from **real PDP decisions**
  (`LocalPdpDecisionSource` — reproducible, in-process; the real ephemeral-Cerbos source is swappable). **No LLM touches the
  truth.** The review is cached (keyed by `consequenceReviewHash`, tenant-tagged) for re-fetch + promotion.
- **Request** (`StudioReviewController.ReviewPayload`) — the two immutable snapshots + sampled matrix + vocabulary (see gap):
```json
{
  "current":   { "bundleId": "bundle-aaaa", "policy": null, "ceiling": null, "canonicalContent": "…" },
  "candidate": { "bundleId": "bundle-bbbb", "policy": { "…PolicyIR…" }, "ceiling": null, "canonicalContent": "…" },
  "matrix":    { "cells": [ { "principalRoles": ["desk"], "principalTenant": "meridian",
                             "principalAttrs": {}, "resourceTenant": "meridian", "resourceAttrs": {},
                             "action": "read", "label": "desk-reads-agent" } ],
                 "fixtureSetHash": "…" },
  "vocabulary": { "resourceKind": "agent", "actions": ["read"], "classifications": [],
                  "attributes": [], "roles": ["desk"], "approvedImports": [] }
}
```
- **Response 200** (`ConsequenceReview`) — `reviewId` = `consequenceReviewHash`:
```json
{
  "tenantId": "meridian",
  "resourceKind": "agent",
  "currentBundleId": "bundle-aaaa",
  "candidateBundleId": "bundle-bbbb",
  "fixtureSetHash": "…",
  "deltas": [
    { "cell": { "principalRoles": ["desk"], "principalTenant": "meridian", "…": "…" },
      "from": "DENY", "to": "ALLOW", "direction": "WIDENED", "overPermission": true,
      "businessConsequence": "Principals holding role(s) [desk] GAIN 'read' access to agent — currently denied, would be allowed." }
  ],
  "overPermissionAlarm": true,
  "principalsGainingAccess": 1,
  "canonicalDelta": "…",
  "consequenceReviewHash": "crh-…",
  "disclosure": { "sampledNotFormal": true, "sampledCellCount": 1, "statement": "This consequence diff is computed over a sampled fixture matrix…" },
  "provenance": { "sourceId": "local-pdp", "currentBatch": { "…" }, "candidateBatch": { "…" } },
  "generatedAt": "2026-07-17T10:00:00Z",
  "displayProse": null
}
```
  `deltas[].direction ∈ WIDENED|NARROWED`; `from`/`to ∈ ALLOW|DENY`. `overPermissionAlarm` is `true` iff any delta widens
  (DENY→ALLOW). `disclosure` is the first-class "sampled, not formal" statement — render it. `displayProse` is optional
  LLM-phrased text OUTSIDE the hash (may be null).
- **Errors**: 401; 403 (role / tenant); 400 (`bad_request` — missing current/candidate/matrix/vocabulary).

### 8. `GET /admin/studio/reviews/{reviewId}` — re-fetch a cached review
- **Role**: drafter/approver/platform_admin. **Tenant**: claim. **Security preconditions**: the lookup is tenant-scoped —
  another tenant's `reviewId` resolves as `404`.
- **Response 200**: the same `ConsequenceReview` shape. **404** if unknown / not this tenant's.

---

## C5 — Lifecycle (bundles, examiner, promote, rollback)

### 9. `GET /admin/studio/bundles` — this tenant's immutable bundle history
- **Role**: drafter/approver/platform_admin. **Tenant**: claim (list is filtered to it). Newest first.
- **Response 200** (`StudioLifecycleController.BundleView[]`):
```json
[ { "bundleId": "b_1a2b…", "tenantId": "meridian", "gitCommit": "abc123", "fixtureSetHash": "…",
    "testCount": 42, "testOracle": "independent-c3", "pdpSourceId": "cerbos-0.53.0",
    "createdAt": "2026-07-01T00:00:00Z" } ]
```

### 10. `GET /admin/studio/bundles/{bundleId}` — one bundle
- **Role**: drafter/approver/platform_admin. **Security preconditions**: tenant-scoped — another tenant's bundle ⇒ `404`.
- **Response 200**: one `BundleView`. **404** if absent / cross-tenant.

### 11. `GET /admin/studio/examiner/{cerbosCallId}` — zero-LLM audit chain
- **Role**: drafter/approver/platform_admin. **Security preconditions**: requires a tenant-scoped principal; the chain is an
  audit reconstruction joined entirely from immutable records (application audit → Cerbos decision → immutable bundle → Git
  commit → signed approval + tests). No LLM.
- **Response 200** (`ExaminerChain`):
```json
{
  "transactionId": "txn-1", "cerbosCallId": "call-1", "activePolicyVersion": "b_1a2b…",
  "decision": "EFFECT_ALLOW", "resourceKind": "agent", "action": "read",
  "bundleId": "b_1a2b…", "gitCommit": "abc123",
  "testMetadata": { "fixtureSetHash": "…", "testCount": 42, "oracle": "independent-c3", "pdpSourceId": "cerbos-0.53.0" },
  "approverId": "approver-bob", "consequenceReviewHash": "crh-…",
  "approvalSignatureValid": true, "complete": true
}
```
- **Errors**: 401; 403; **422** `audit_integrity` (any missing/mismatched/tampered join — never a partial chain).

### 12. `POST /admin/studio/promotions` — approve **and** promote
- **Role**: `policy_approver`.
- **Security preconditions** (all identity VERIFIED, never a body field):
  - approver = verified `sub`; a null/absent principal fails closed (never skips the SoD check);
  - the review is looked up by `reviewId` in **this tenant's** store — a miss (wrong tenant / unknown / not computed here)
    fails closed `403`; the review, its tenant, and its **author** all come from that trusted record;
  - **author≠approver** (C1.2/C1.3): the approver may not be the verified author who computed the review — enforced
    *regardless of role*, so a `platform_admin` is never an auto-approver of its own draft ⇒ `409`;
  - `candidate.tenantId` must equal the claim tenant ⇒ else `403`;
  - server-side, `ConsequenceApprovalService.approve` recomputes the `consequenceReviewHash` from the review's truth fields
    and refuses a **tampered** review, and blocks a **stale** review (active bundle drifted).
- **Request** (`StudioPromotionController.PromotePayload`):
```json
{
  "reviewId": "crh-…",
  "candidate": { "bundleId": "b_bbbb", "tenantId": "meridian", "files": [ { "path": "…", "yaml": "…" } ],
                 "manifestRefs": ["…"], "testMetadata": { "fixtureSetHash": "…", "testCount": 42,
                 "oracle": "independent-c3", "pdpSourceId": "cerbos-0.53.0" }, "canonicalContent": "…" },
  "idempotencyKey": "promote-2026-07-17-001"
}
```
- **Response 200** (`PromotionReceipt`):
```json
{ "promotionId": "promo-1", "tenantId": "meridian", "fromBundleId": "b_aaaa", "toBundleId": "b_bbbb",
  "directoryVersion": 42, "kind": "PROMOTION", "idempotentReplay": false }
```
  A lost-response retry with the same `idempotencyKey` returns the same receipt with `idempotentReplay: true` (no second CAS).
- **Errors**: 401; 403 (role / tenant / unknown review / `unauthorized_promotion`); 409 (`separation_of_duties`,
  `stale_baseline`); 422 (`bundle_tamper`); 400 (`bad_request`).

### 13. `POST /admin/studio/rollbacks` — roll back to a previously certified bundle
- **Role**: `policy_approver`. Same payload + rules as (12); `candidate` is the prior bundle to restore (must still exist in
  the immutable store). A rollback is a NEW authorized promotion (never a delete). Response `PromotionReceipt` with
  `kind: "ROLLBACK"`.

---

## C6 — Break-glass (two-person emergency grant)

### 14. `POST /admin/studio/break-glass` — author (request) a time-bounded grant
- **Role**: `policy_drafter`.
- **Security preconditions**: requester = verified `sub`. **`issuedAt` is stamped from the SERVER clock** (any caller value
  is ignored — there is no such field); **TTL is clamped to `(0, 60]` minutes** against the server clock, and `expiresAt =
  server now() + ttlMinutes`. The grant `scope` root must equal the claim tenant ⇒ else `403`. The same two deterministic
  gates every studio policy runs (C6 bounds + the C2 policy gate) decide admissibility.
- **Request** (`StudioBreakGlassController.RequestPayload`) — `vocabulary`/`baseCeiling`/`allowlist` are grounding (see gap):
```json
{
  "scope": "meridian", "resourceKind": "agent", "action": "break", "role": "desk",
  "ttlMinutes": 30, "justification": "incident 42",
  "vocabulary": { "resourceKind": "agent", "actions": ["break"], "classifications": [], "attributes": [], "roles": ["desk"], "approvedImports": [] },
  "baseCeiling": { "resourceKind": "agent", "tuples": [ { "action": "break", "role": "desk" } ], "carriesTenantEqualityBackstop": true, "reservedIdentities": [] },
  "allowlist": { "resources": ["agent"], "actions": ["break"] }
}
```
- **Response 200** (`StudioBreakGlassController.GrantView`):
```json
{ "grantId": "bg-…", "tenantId": "meridian", "requestedBy": "drafter-dan", "admissible": true,
  "issued": false, "expiresAt": "2026-07-17T10:30:00Z", "boundsViolations": [], "c2Violations": [] }
```
  `admissible` is `true` only when BOTH gates accept; otherwise the violation lists explain why.
- **Errors**: 401; 403 (role / tenant / cross-tenant scope); 400 (`bad_request` — TTL out of `(0,60]`, missing fields).

### 15. `POST /admin/studio/break-glass/{grantId}/approve` — the two-person issue step
- **Role**: `policy_approver`. Optional header `X-Correlation-Id` (else server-generated).
- **Security preconditions**: approver = verified `sub`; the grant is fetched **tenant-scoped** (unknown/other-tenant ⇒ `400`).
  `BreakGlassApprovalService` enforces **requester≠approver** and the configured approver role server-side (see TODO on the
  configured role name). On success the issuance is audited to the tenant's A6 partition.
- **Response 200**: the `GrantView` with `issued: true`.
- **Errors**: 401; 403 (role); 409 (`separation_of_duties` — self-approval); 422 (`unprocessable` — inadmissible artifact);
  400 (`bad_request` — unknown grant).

### 16. `GET /admin/studio/break-glass` — active (issued, unexpired) grants for this tenant
- **Role**: drafter/approver/platform_admin. **Tenant**: claim. **Response 200**: `GrantView[]` (issued + `expiresAt` in the
  future), newest first.

---

## Session store note (for the frontend)

Reviews (7/8) and break-glass artifacts (14–16) are held in a **single-node, in-memory** session store, tenant-tagged, so the
SPA can re-fetch by id and echo ids into later steps instead of reconstructing crypto-bound graphs. It is **not** a system of
record (the bundle/promotion/approval repositories + the active directory are). Practical implication: **compute a review on
the same IAM node you promote against** within a session. A restart-durable / multi-node store is a documented follow-up.

## Grounding gap (for the frontend)

`drafts` (6), `reviews` (7), and `break-glass` (14) currently take the closed manifest **`vocabulary`**, the immutable
**`baseCeiling`**, the **`allowlist`**, and (for reviews) the two **`BundleSnapshot`s + `matrix`** in the request body. There
is **no per-tenant provider bean** that derives these from the effective manifest yet (they are test fixtures today). The
intended follow-up is a `GET /admin/studio/vocabulary/{resourceKind}` (+ a snapshot/matrix assembler keyed by two draft ids)
so the SPA does not hand-build them. Until then, treat these as World-B config the caller supplies — they are **not** domain
code and carry no domain literals in Java.

---

## Known service-layer hardening TODOs (do NOT expose in the UI as "done")

An adversarial audit found the underlying services bind some enforcement to caller-supplied fields or skip checks. The
**controllers** in this contract already bind identity to the verified JWT and the server clock, and enforce the role/tenant/
SoD gates. The following are **service-layer** gaps that a later hardening pass must close — the frontend and any security copy
must **not** claim these are enforced:

1. **`PolicyPromotionService.promote()` does not re-run `GeneratedPolicyValidator` on the candidate**, and **does not
   hard-fail when the real Cerbos PDP is unavailable** (it can promote a candidate that was only deterministically gated).
   *Controllers cannot cheaply add this — the validator needs the candidate's vocabulary/ceiling/scope, which promotion does
   not carry.* The consequence-review **hash IS re-verified server-side** by `ConsequenceApprovalService.approve` (tamper +
   staleness), so that part is covered; the validator-rerun + Cerbos-down hard-fail are not.
2. **Break-glass TTL is only checked as a relative span in `BreakGlassValidator`** — a future-dated `issuedAt` would defeat it.
   *Mitigated at the controller* (server-stamped `issuedAt`, `(0,60]` clamp against the server clock), but the **CEL condition
   should also carry a `now() >= issuedAt` lower bound** and the service should clamp against the server clock independently.
3. **`BreakGlassApprovalService` uses a configured approver role** (`iam.break-glass.approver-role`, default
   `studio_policy_approver`) distinct from the studio `policy_approver` role the method gate checks. A break-glass approver
   must currently hold **both**. Reconcile the role names (or make the method gate read the configured role).
4. **A null/absent approver id must fail closed everywhere.** The controllers do (verified `sub`, else `403`); confirm the
   services never treat a null approver as a skip.
5. **C4/C5 active-pointer vs JPA transaction**: the active-directory CAS (`ActiveTenantDirectory`) is an in-memory
   `AtomicReference` published inside `@Transactional promote()`. If the surrounding JPA transaction rolls back **after** the
   CAS published, the in-memory pointer is not rolled back with it. Make the pointer swap participate in (or follow) the
   transaction commit.
