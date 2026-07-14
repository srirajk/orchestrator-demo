# Conduit Onboarding Studio — Identity, Authorization, Approval and Promotion Specification

**Status:** Proposed  
**Identity provider:** Axiom OIDC  
**Policy principle:** The agent advises; humans and deterministic policy authorize.

---

## 1. Authentication

Studio uses Axiom OIDC Authorization Code flow through its BFF. Browser receives a secure,
HttpOnly, SameSite session cookie; it does not store a reusable bearer token in local storage.

Studio validates issuer, audience, signature, expiry and nonce/state. Session contains a server-side
reference to the authenticated principal and token lifecycle. Registry calls use Studio service
identity plus explicit user/case/promotion context, not the browser token alone.

Suggested audience: `conduit-onboarding-studio`.

---

## 2. Roles

| Role | Purpose |
|---|---|
| `onboarding_submitter` | Create and work on cases in assigned tenant/domains |
| `domain_editor` | Edit domain business declarations and datasets |
| `domain_owner` | Approve capability ownership, authority and business semantics |
| `security_reviewer` | Approve classification, audience, coverage and sensitive-data controls |
| `platform_reviewer` | Approve manifest/platform compatibility and supported primitives |
| `release_manager` | Promote an approved exact bundle between environments |
| `auditor` | Read evidence, decisions, approvals and receipts without mutation |
| `platform_admin` | Configure Studio policies/roles; not an automatic business/security approver |

Roles are necessary but not sufficient. Domain scope and case/environment policy are checked on every
operation.

---

## 3. Resource model

Authorization resources:

- `onboarding_case`
- `dossier_version`
- `evidence_item`
- `candidate_bundle`
- `certification_run`
- `approval_request`
- `promotion`
- `studio_policy`

Attributes include tenant, domain, sub-domain, case state, classification, environment, owner IDs,
artifact hashes and requested action.

---

## 4. Permission matrix

| Action | Submitter | Domain owner | Security | Platform reviewer | Release manager | Auditor |
|---|---:|---:|---:|---:|---:|---:|
| Create case | scoped | scoped | no | yes | no | no |
| Add ordinary evidence | scoped | scoped | scoped | scoped | no | no |
| View sensitive evidence | policy | policy | scoped | policy | no | scoped read |
| Edit unconfirmed dossier | scoped | scoped | scoped fields | platform fields | no | no |
| Confirm business contract | no | yes | no | no | no | no |
| Approve classification/coverage | no | no | yes | no | no | no |
| Approve platform compatibility | no | no | no | yes | no | no |
| Run compilation/certification | scoped | scoped | scoped | yes | no | no |
| Submit approval request | scoped | scoped | no | yes | no | no |
| Promote sandbox/staging | no | no | no | policy | yes | no |
| Promote production | no | no | no | no | yes | no |
| Read audit | own/scoped | scoped | scoped | scoped | scoped | yes |

“Scoped” always means same tenant and authorized domain plus current state policy.

---

## 5. Field-level ownership

Business approval:

- capability outcome/boundary;
- source-of-record authority;
- entity/business semantics;
- freshness/completeness;
- expected failure/partial behavior;
- condition business meaning.

Security approval:

- audience;
- classification;
- coverage contract;
- produced-entity filtering;
- data handling/redaction;
- credential policy.

Platform approval:

- supported protocol/adapter;
- semantic type and composition compatibility;
- condition/map technical safety;
- schema/compiler/platform-gap decisions;
- admission policy exceptions.

Release approval:

- exact environment transition and bundle hash;
- certification/approval freshness;
- rollback and release window.

---

## 6. Separation of duties

- Submitter cannot be the sole domain, security, platform or production release approver.
- `platform_admin` does not automatically satisfy domain/security approval.
- Production requires a release manager different from the last material dossier editor.
- Resource-scoped capabilities require domain + security + platform approval.
- Composable capabilities require domain + platform approval and security approval whenever they
  consume/produce resource-scoped or classified data.
- Conditional readiness requires the owner of each limitation plus platform approval.
- Emergency override, if ever supported, requires two-person approval, expiry and retrospective audit;
  it is out of v1.

---

## 7. Approval object

```text
approvalId
caseId, tenantId, domainId
approvalType
approverId, approverRoles
dossierHash, bundleHash, certificationHash
targetEnvironment
decision: APPROVE | REJECT | REQUEST_CHANGES | APPROVE_WITH_LIMITATION
reason
limitations[]
policyVersion
createdAt, expiresAt
signature/audit reference
```

An approval is valid only for the exact hashes and environment. Any material change invalidates it.

---

## 8. Promotion state machine

```text
DRAFT
 -> COMPILED
 -> CERTIFIED
 -> APPROVAL_PENDING
 -> APPROVED
 -> SANDBOX_PROMOTING -> SANDBOX_ACTIVE
 -> STAGING_PROMOTING -> STAGING_ACTIVE
 -> PRODUCTION_PROMOTING -> PRODUCTION_ACTIVE
```

Failure/side states:

- `CHANGES_REQUESTED`
- `CERTIFICATION_STALE`
- `APPROVAL_EXPIRED`
- `PROMOTION_FAILED`
- `ROLLED_BACK`
- `DEACTIVATED`

Only deterministic application commands transition state.

---

## 9. Environment requirements

### Sandbox

- Valid compiled bundle.
- Technical-owner consent for live non-production probes.
- No production customer traffic/data.
- Shadow/ephemeral index permitted.

### Staging

- Passing hard gates.
- Domain and platform approval.
- Security approval when resource-scoped/classified.
- Full focused regression and global sentinels.
- Production-like identity/coverage test personas.

### Production

- `READY` or policy-allowed `CONDITIONALLY_READY` verdict.
- Current certification against target catalog/policy/schema.
- All required approvals on exact hashes.
- Staging activation evidence.
- Release-manager action and rollback target.
- Registry service confirms idempotent exact-hash activation.

---

## 10. Promotion protocol

1. Studio locks a promotion request with expected case/bundle/catalog versions.
2. Revalidate certification and approvals.
3. Create immutable promotion intent and idempotency key.
4. Call target registry using Studio release service identity.
5. Registry independently verifies artifact hash, allowed environment, signature/context and current
   catalog precondition.
6. Registry validates/introspects as required, stores/indexes atomically and returns receipt.
7. Studio records receipt and target catalog snapshot.
8. If response is lost, Studio queries receipt by idempotency key before retry.

The registry does not accept “latest draft” or agent-generated manifests. It accepts the exact
approved bundle artifact.

---

## 11. Rollback and deactivation

- Every activation records previous artifact/catalog state.
- Rollback is a new authorized promotion to a previously certified artifact, not database reversal.
- Deactivation requires release authority and impact statement.
- Production incident automation may recommend rollback but cannot execute it through the agent.
- Rollback/deactivation receipts are immutable and visible to auditors.

---

## 12. Staleness and invalidation

Invalidate certification/approvals based on dependency:

| Change | Invalidates |
|---|---|
| Dossier material fact | Compilation, certification, approvals |
| Service wire schema | Contract/composition gates and dependent approvals |
| Catalog/routing index | Routing/regression gates and platform approval |
| Cerbos/coverage policy | Authorization/coverage gates and security approval |
| Compiler/schema version | Bundle, certification, approvals |
| Model/prompt version | Agent-runtime/eval evidence according to policy, not deterministic facts |
| Target environment | Environment-specific approval/promotion |

Studio must show staleness before the user enters an approval screen.

---

## 13. Evidence access

- Evidence inherits tenant/domain/classification and may add narrower case roles.
- Model workers receive redacted excerpts through short-lived scoped tokens.
- Raw credentials are never evidence.
- Dataset exports apply row/field redaction policy.
- Auditors see evidence required for audit but may be denied raw sensitive payloads; hashes and
  validation receipts preserve proof.

---

## 14. Audit requirements

Record:

- authentication/session events;
- case/evidence creation;
- fact/proposal/answer changes;
- inspection and agent tool consent;
- compilation/certification inputs and outputs;
- approvals/rejections/change requests;
- promotion/rollback/deactivation intents and receipts;
- access denials and policy decisions;
- administrator policy changes.

Audit events contain correlation, actor, resource, action, before/after hashes, policy version and
timestamp. Sensitive values are referenced, not copied.

---

## 15. Threat scenarios

- Submitter attempts to approve own resource-scoped agent.
- Domain owner attempts cross-domain evidence access.
- Model fabricates an approval in prose.
- Browser calls registry directly.
- Stale certified bundle promoted after catalog change.
- Promotion response lost and retried.
- Compromised worker attempts registry mutation.
- Platform admin bypasses domain/security owner.
- Malicious evidence attempts credential exfiltration.
- Approval replayed for a different environment/hash.

Each scenario requires deterministic deny plus audit evidence.

---

## 16. Acceptance criteria

- All APIs deny by default and enforce tenant/domain/resource/action.
- UI hiding is never the authorization control.
- Submitter cannot self-certify or self-promote.
- Agent prose/tool state cannot create governance approval.
- Production registry rejects stale/unapproved/mismatched artifacts.
- Promotion retry is idempotent.
- Every active artifact has a complete approval/certification/receipt chain.
- Rollback is authorized, reproducible and audited.
