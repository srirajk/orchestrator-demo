# Axiom Policy Studio — REST API contract

> Audience: the frontend (Codex) building the Policy Studio screens.
> Backend: `iam-service` (Axiom). Base URL is the IAM service origin (e.g. `http://localhost:8081`).
> Every endpoint here is under `/admin/**`, so it is doubly gated (see **Auth model**).
> Source of truth for shapes: the Java records named in each section — this doc mirrors them field-for-field.

---

## Auth model (read first)

- **Transport**: OIDC Bearer JWT (RS256), `Authorization: Bearer <token>`, minted by Axiom. Verified at every hop.
- **Roles**: the token's `roles` claim → Spring authorities. Studio roles: `policy_author` (author), `policy_approver`
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
| 6 | GET | `/admin/studio/vocabulary/{resourceKind}` | author/approver/platform_admin | `StudioGroundingProvider` | **NEW** |
| 7 | POST | `/admin/studio/drafts` | `policy_author` | `PolicyStudioGenerationService` | **NEW** |
| 8 | POST | `/admin/studio/reviews/assembled` | author/approver/platform_admin | `StudioGroundingProvider` + `ConsequenceDiffService` | **NEW** |
| 9 | POST | `/admin/studio/bundles/candidates` | author/approver/platform_admin | `BundleCanonicalizer` | **NEW** |
| 10 | POST | `/admin/studio/reviews` | author/approver/platform_admin | `ConsequenceDiffService` | **NEW** |
| 8 | GET | `/admin/studio/reviews/{reviewId}` | drafter/approver/platform_admin | (session store) | **NEW** |
| 9 | GET | `/admin/studio/bundles` | drafter/approver/platform_admin | `PolicyBundleRepository` | **NEW** |
| 10 | GET | `/admin/studio/bundles/{bundleId}` | drafter/approver/platform_admin | `PolicyBundleRepository` | **NEW** |
| 11 | GET | `/admin/studio/examiner/{cerbosCallId}` | drafter/approver/platform_admin | `ExaminerChainService` | **NEW** |
| 12 | POST | `/admin/studio/promotions` | `policy_approver` | `ConsequenceApprovalService` + `PolicyPromotionService` | **NEW** |
| 13 | POST | `/admin/studio/rollbacks` | `policy_approver` | `PolicyPromotionService#rollback` | **NEW** |
| 14 | POST | `/admin/studio/break-glass` | `policy_author` | `BreakGlassAuthoringService` | **NEW** |
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

### 6. `GET /admin/studio/vocabulary/{resourceKind}` — trusted manifest grounding
- **Role**: `policy_author` | `policy_approver` | `platform_admin`.
- **Security preconditions**: tenant comes from the token. The response is assembled from `registry/domains/*.json`
  (`entity_types`, `domain_context`) plus `registry/manifests/*.json`, and the typed immutable base ceiling. The base
  ceiling is not reconstructed from rendered bundle YAML.
- **Response 200** (`StudioGroundingSnapshot`): `{ tenantId, vocabulary, baseCeiling, matrix, current, manifestRefs }`.

### 7. `POST /admin/studio/drafts` — NL intent → validated canonical policy
- **Role**: `policy_author`.
- **Security preconditions**: author = verified `sub`; **author scope = `TenantScope.of(<tenant_id claim>)`** (never a body
  field), so a drafter can only target their own tenant subtree. The model only *proposes*; a deterministic gate decides;
  only the IR-derived **canonical** YAML is ever returned (never the model's raw text). A rejected proposal is a normal
  `200` with violations, not an error.
- **Request** (`StudioAuthoringController.DraftPayload`) — `resourceKind` selects the trusted manifest grounding; if
  `vocabulary`/`baseCeiling` are omitted, the server supplies them:
```json
{
  "intent": "allow relationship managers to invoke tenant agents",
  "resourceKind": "agent",
  "subscopesEnabled": false,
  "vocabulary": null,
  "baseCeiling": null
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

### 8. `POST /admin/studio/reviews/assembled` — compute the decision delta from server-grounded snapshots
- **Role**: `policy_author` | `policy_approver` | `platform_admin`.
- **Security preconditions**: tenant, current base snapshot, base ceiling, vocabulary, sampled fixture matrix and fixture hash
  are assembled server-side from the trusted manifest provider. The request supplies only `resourceKind` and accepted
  canonical YAML. Truth is still real Cerbos; optional `displayProse` is LLM display text outside the hash.
- **Request**: `{ "resourceKind": "agent", "canonicalYaml": "apiVersion: api.cerbos.dev/v1\n..." }`.
- **Response 200**: same `ConsequenceReview` shape as below.

### 9. `POST /admin/studio/bundles/candidates` — materialize a promotion candidate bundle
- **Role**: `policy_author` | `policy_approver` | `platform_admin`.
- **Security preconditions**: tenant is the token claim. The candidate bundle is content-addressed server-side from canonical
  YAML, trusted manifest refs, and the reviewed fixture set hash.
- **Request**: `{ "resourceKind": "agent", "canonicalYaml": "...", "fixtureSetHash": "..." }`.
- **Response 200**: `PolicyBundle`.

### 10. `POST /admin/studio/reviews` — compute the decision delta (business consequences)
- **Role**: `policy_author` | `policy_approver` | `platform_admin`.
- **Security preconditions**: tenant = claim; the **verified author (`sub`) is recorded server-side with the review** so the
  later promotion can enforce author≠approver against a trusted identity. Truth is computed by
  **`ProductionPdpDecisionSource` using pinned Cerbos 0.53.0** against both immutable snapshots. If pinned Cerbos or the base
  bundle is unavailable, review creation fails closed; there is no Java-evaluator fallback. **No LLM touches the truth.**
  The review is cached (keyed by `consequenceReviewHash`, tenant-tagged) for re-fetch + promotion.
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
- **Role**: `policy_author`.
- **Security preconditions**: requester = verified `sub`. **`issuedAt` is stamped from the SERVER clock** (any caller value
  is ignored — there is no such field); **TTL is clamped to `(0, 60]` minutes** against the server clock, and `expiresAt =
  server now() + ttlMinutes`. The grant `scope` root must equal the claim tenant ⇒ else `403`. The same two deterministic
  gates every studio policy runs (C6 bounds + the C2 policy gate) decide admissibility.
- **Request** (`StudioBreakGlassController.RequestPayload`) — the emergency trust roots are resolved server-side;
  callers must not supply `vocabulary`, `baseCeiling`, or `allowlist`:
```json
{
  "scope": "meridian", "resourceKind": "agent", "action": "break", "role": "desk",
  "ttlMinutes": 30, "justification": "incident 42"
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

## Grounding model (for the frontend)

`GET /admin/studio/vocabulary/{resourceKind}` returns trusted vocabulary, base ceiling, current base snapshot, sampled matrix,
and manifest refs from the per-tenant `StudioGroundingProvider`. `POST /admin/studio/reviews/assembled` uses the same provider
to assemble review inputs server-side, and `POST /admin/studio/bundles/candidates` materializes the candidate bundle from the
accepted canonical YAML plus the reviewed fixture hash. The older raw `POST /reviews` shape remains for deterministic harnesses.

---

## Service-layer hardening status and intentional limitation

The adversarial findings below are closed in both the HTTP boundary and the underlying services unless explicitly described
as an intentional limitation. Identity comes from the verified JWT; policy truth, candidate bundles, clocks, and trust roots
come from the server; tenant and two-person gates are rechecked before activation.

1. **~~`PolicyPromotionService.promote()` does not re-run `GeneratedPolicyValidator`~~ — CLOSED (H2).** `promote()` now
   re-runs `GeneratedPolicyValidator` **unconditionally** on the candidate's tenant-child policies (scope-containment,
   no-wildcard, base-ceiling totality), **recomputes the `consequenceReviewHash` server-side** from the review's truth fields
   (a tampered hash is rejected), and the `cerbos compile` probe now **HARD-FAILS** promotion when Cerbos is unavailable
   (never version-stamp-only). The validator's vocabulary/ceiling/author-scope come from a trusted
   `PromotionValidationContextProvider` keyed off the bundle — never the request. **Honest note:** the promotion candidate is
   a `PolicyBundle` (rendered YAML + test metadata) and does **not** carry a typed vocabulary/ceiling, and the base ceiling
   cannot be reconstructed from the bundle's YAML (its derived-role modules aren't the single `resourcePolicy` the parser
   accepts) — so this needed a **provider**, not derivation from the snapshot. The production provider
   (`ManifestPromotionValidationContextProvider`) **fails closed** until a per-tenant manifest→vocabulary/ceiling source is
   wired (the same grounding gap `StudioAuthoringController` documents); the deterministic gate injects a fixture provider to
   prove the re-validation rejects wildcard / sibling-scope / omitted-tuple candidates.
2. **~~Break-glass time integrity~~ — CLOSED (H1).** The controller stamps `issuedAt` from the server clock and clamps TTL to
   `(0,60]`; `BreakGlassValidator` independently checks the server-clock ceiling; and the compiled Cerbos policy carries both
   `now() >= timestamp(issuedAt)` and `now() < timestamp(expiresAt)` bounds. Approval also rejects an already-expired pending
   grant immediately before issuance.
3. **~~Break-glass approver-role mismatch~~ — CLOSED.** The HTTP method gate, admin UI, and
   `BreakGlassApprovalService` all require the single product role `policy_approver`. This role is a fixed service invariant,
   not a runtime setting that could drift away from the API contract. Author≠approver remains mandatory.
4. **~~A null/absent approver id must fail closed everywhere~~ — CLOSED (H3).** `ConsequenceApprovalService.approve` now
   enforces the approver gate at the **service** from verified identity: a null/blank approver **fails closed**, an approver
   role is required (`platform_admin` alone is not an auto-approver), the approver must be in the review's tenant, and
   **author≠approver** is enforced from the author recorded server-side at review-compute time (`ReviewAuthorRegistry`), never
   a caller-supplied id. This is defence-in-depth behind the controller gate.
5. **~~C4/C5 active-pointer vs JPA transaction~~ — CLOSED (H4).** The `ActiveTenantDirectory` promotion CAS now registers a
   **transaction synchronization**: if the surrounding `@Transactional promote()` rolls back after the in-memory advance, the
   pointer is **compensated back** to the prior snapshot (the durable `save` rolls back with the txn), so the live pointer
   never diverges from the durable store until a restart `reload()`.
6. **Intentional demo limitation — single-node handoff cache.** Pending consequence reviews (including their exact candidate
   bundles) and pending break-glass artifacts are held in the tenant-scoped in-memory `StudioSessionStore`. They survive a
   browser identity handoff on the same IAM process, but not an IAM restart and not a request routed to another IAM node.
   A multi-node or restart-durable deployment must replace this cache with a tenant-partitioned durable store while preserving
   the same immutable review id, verified author, exact candidate, completion state, and separation-of-duties checks.
