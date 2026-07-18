# Conduit Onboarding Studio — Solution Architecture

**Status:** Proposed production architecture  
**Product:** Separate Conduit control-plane application  
**Guidance runtime:** Bounded OpenAI inference with structured outputs behind a Java port  
**System of record:** Studio workflow database and immutable evidence, not agent conversation state

---

## 1. Architecture decision

Build Conduit Onboarding Studio as a separate product surface and deployable control plane. Do not
place its workflow, UI, guidance runtime or persistence inside the request-path gateway. Do not place
its UI inside the Axiom identity administration console.

Use bounded OpenAI inference for guided questions, explanations, evidence analysis and typed
proposals. Use deterministic Java application services for project state, dossier facts, manifest
compilation, composition admission, certification, approvals and promotion.

```text
Browser
  |
  v
Onboarding Studio Web/BFF  <---- OIDC ----> Axiom
  |
  +--> Studio API --------------------------+
  |       |                                 |
  |       +--> Workflow/PostgreSQL          |
  |       +--> Evidence/Object Store        |
  |       +--> Audit/Outbox                  |
  |                                         |
  +--> Guidance model adapter                |
          |                                  |
          +--> authorized context builder    |
          +--> typed proposal validator -----+--> External registry-ingestion profile
          +--> proposal store                |      - catalog reads
                                             |      - introspection dry run
Deterministic compiler/certifier ------------+      - candidate validation
                                                    - approved activation
```

The Studio creates a candidate package. An external registry-ingestion component, assembled from
shared Java admission logic, remains the only authority that can validate, store and index an
activated agent manifest. The request-path gateway only reads immutable activated catalogs.

This diagram is the target architecture, not the first build sequence. The UI-first milestone stops
after deterministic package generation and snapshot proof. External ingestion is implemented next
to last; read-only gateway integration is last.

---

## 2. Why a separate Studio

- Onboarding is long-running and human-in-the-loop; gateway requests are short-lived and stateless.
- Studio holds documents, evidence, proposals, approvals and promotion history; the gateway must not.
- Studio users and permissions differ from chat users and Axiom identity administrators.
- Onboarding model/tool failures must not affect serving traffic.
- Studio can evolve model providers independently while retaining Java composition semantics.
- Production activation retains a narrow, auditable external registry-ingestion API boundary.

---

## 3. Deployable components

### 3.1 `onboarding-studio-web`

React/TypeScript application for Business Lines, Use Cases, Onboarding Projects, evidence, contract review, certification,
approvals and promotion. Served behind the Studio BFF; no direct registry credentials in the browser.

### 3.2 `onboarding-studio-api`

Java/Spring Boot service that acts as BFF and application API. Responsibilities:

- Axiom OIDC login/session integration;
- authorization enforcement;
- project/dossier/evidence commands and reads;
- job submission and progress streaming;
- approval and promotion commands;
- artifact download;
- guidance inference coordination.

The API never trusts the browser or model runtime to assert a role, approval or state transition.

### 3.3 `guidance-model-adapter`

Java adapter behind the application-owned `GuidanceModel` port. Responsibilities:

- guided interviews;
- document/evidence summaries;
- capability-boundary analysis;
- catalog-neighbor explanations;
- composition proposals;
- plain-language gap explanations.

The adapter returns typed proposals to application services. It cannot write confirmed facts,
approve, compile, certify, change gate thresholds or activate. It has no registry mutation client.

### 3.4 `manifest-compiler`

Deterministic Java library/service. It consumes a confirmed dossier and versioned compiler policy
and produces canonical candidate artifacts. It performs no model calls and has no registry write
credential.

### 3.5 `certification-runner`

Deterministic job runner that coordinates schema, introspection, dataflow, routing, coverage,
authorization, golden, regression and security gates. Model-judged evals may contribute measured
quality evidence but cannot override hard gates.

### 3.6 Shared admission engine and external registry-ingestion extensions

Extract domain-free manifest models and static composition admission from the gateway into
`libs/conduit-admission`. External registry ingestion and the read-only gateway use this artifact.
Characterization tests must prove parity before either switches to it.

Add registry-profile-only APIs for:

- catalog snapshot and effective manifest reads;
- candidate dry-run validation/introspection with no mutation;
- shadow-index routing evaluation;
- approved activation of an exact certified bundle hash;
- deactivation/rollback using existing registry authority.

The request-path gateway receives no Studio or ingestion endpoints, ingestion beans, or registry
write credentials.

---

## 4. Technology decisions

| Concern | Decision |
|---|---|
| Studio guidance runtime | Java `GuidanceModel` port with bounded structured inference |
| Studio API | Spring Boot MVC with typed request/response records |
| UI | React + TypeScript, feature-module structure |
| Workflow system of record | PostgreSQL |
| Documents/evidence/bundles | S3-compatible object store with immutable content hashes |
| Async work | PostgreSQL outbox/job table with leased workers; no external broker in v1 |
| Authentication | Axiom OIDC Authorization Code flow; secure BFF session cookie |
| Authorization | Studio policy enforcement plus domain scope; Axiom supplies identity/roles |
| Registry mutation | External Java registry-ingestion component only |
| Model observability | Studio OTel spans, audit records and usage metrics with sensitive data disabled |
| Platform observability | Existing OTel/Prometheus/Langfuse conventions where applicable |

PostgreSQL is selected over Redis as the production system of record because projects contain durable
relationships, approvals, versions, jobs and promotion history. Redis may be used only for caches,
locks or ephemeral coordination.

---

## 5. Source-of-truth hierarchy

1. Raw evidence snapshot.
2. Versioned typed dossier facts with provenance.
3. Named human decisions and approvals.
4. Deterministically compiled candidate bundle.
5. Pinned certification run.
6. Approved promotion record.
7. Registry activation receipt.

Conversation history, model confidence and generated prose are never above any item in this list.

---

## 6. Core aggregate model

### Onboarding Project

Owns scope, archetype, current dossier version, workflow state and domain/tenant boundary.

### Dossier version

Immutable snapshot of observed, declared, derived and approved facts. Editing creates a successor
version and invalidates dependent compilation/certification as defined by policy.

### Evidence item

Content-addressed immutable object plus metadata, redaction policy and access classification.

### Proposal

Typed model/deterministic recommendation against dossier paths. Accepting it creates a declared
fact attributed to the human; it does not automatically approve it.

### Candidate bundle

Canonical manifests, eval assets, policy proposal, provenance sidecar and hashes.

### Certification run

Pinned execution of gate versions against dossier, bundle, catalog, dataset and service snapshots.

### Approval

Named decision over an exact dossier/bundle/certification hash and environment.

### Promotion

State transition of the exact approved artifact through sandbox, staging and production registry
environments.

---

## 7. Main data flow

1. User authenticates through Axiom.
2. API creates a project scoped to tenant/Business Line and intended environment.
3. User uploads requirements and supplies a non-production URL/credential reference.
4. Studio inspection adapters capture bounded protocol evidence without catalog mutation.
5. Guidance adapter receives only authorized evidence summaries and unresolved dossier paths.
6. Adapter produces typed questions/proposals; UI records human answers.
7. Catalog analysis produces ownership exercises and regression neighborhood.
8. Authorized users confirm the human-readable dossier.
9. Compiler produces canonical artifacts.
10. Certification runner executes all required gates.
11. Required reviewers approve the exact hashes.
12. Release manager promotes the exact bundle through environment policy.
13. Registry returns an activation receipt and new catalog snapshot.
14. Studio records evidence and schedules drift checks.

---

## 8. API domains

```text
/studio/session/*             authentication/session
/studio/projects/*            workflow and dossier
/studio/evidence/*            uploads, snapshots, redacted reads
/studio/interviews/*          agent runs, questions and proposals
/studio/catalog/*             neighbors and ownership exercises
/studio/compilations/*        candidate bundle jobs/results
/studio/certifications/*      gate runs/results/verdicts
/studio/approvals/*           approval commands/history
/studio/promotions/*          environment promotion/receipts
/studio/audit/*               authorized audit reads
```

All mutation commands require an idempotency key and expected aggregate version. Optimistic locking
prevents lost approvals or stale promotion.

---

## 9. Async job contract

Long-running inspection, agent analysis, compilation, certification and promotion operations use a
durable job model:

```text
jobId, caseId, type, state, requestedBy
inputHashes, policyVersion
attempt, leaseOwner, heartbeatAt
progressCode, progressPercent
resultReference, errorCode, remediation
createdAt, startedAt, completedAt
```

Jobs are idempotent over their pinned input hashes. Retrying does not create new evidence or promote
twice. Worker death returns the lease to the queue after timeout.

---

## 10. Environment model

### Draft

No registry mutation. Evidence, dossier, compiler and static certification only.

### Sandbox

Ephemeral/shadow catalog and test credentials. Live probes and routing evaluation permitted within
project policy. No production user traffic.

### Staging

Production-like catalog snapshot, policy and infrastructure. Full regression and approval rehearsal.

### Production

Exact certified bundle only. Registry activation uses release-manager authority and returns an
immutable receipt. No model has production mutation credentials or an activation tool.

---

## 11. Failure boundaries

- OpenAI outage: interview/proposals pause; deterministic workflow, evidence and review remain usable.
- Agent run timeout: job fails/retries; project state remains unchanged.
- Registry dry-run outage: certification becomes `UNABLE_TO_ASSESS`; no fallback schema is invented.
- Compiler failure: typed field/path error; no partial candidate promoted.
- Certification failure: actionable gate result; prior production activation unchanged.
- Approval rejection: project returns to the specified decision state with reason; artifact remains immutable.
- Promotion interruption: idempotent registry receipt check determines actual state before retry.
- Catalog changes: affected routing/certification hashes become stale before approval/promotion.

---

## 12. Repository proposal

```text
apps/onboarding-studio/web/
services/onboarding-studio/src/main/java/ai/conduit/studio/
  api/
  application/
  domain/
  infrastructure/
  modelruntime/
  compiler/
  certification/
  promotion/
services/onboarding-studio/src/test/java/ai/conduit/studio/

libs/conduit-manifest-contracts/src/main/java/ai/conduit/contracts/
libs/conduit-artifact-sdk/src/main/java/ai/conduit/artifacts/
libs/conduit-admission/src/main/java/ai/conduit/admission/

gateway/src/main/java/ai/conduit/gateway/registry/              # existing external registry profile
  api/             dry-run/snapshot/activation endpoints in registry profile only
  service/         shared-validator orchestration and mutation beans
```

The shared contract, artifact and admission modules may not depend on Spring, network, persistence
or model code. Studio may not duplicate runtime composition rules. The existing external registry
profile adopts shared admission in the penultimate phase; the read-only gateway adopts immutable
snapshot contracts in the final phase. A new ingestion deployable requires an ADR.

---

## 13. Architecture acceptance criteria

- Studio can be stopped without affecting chat/gateway serving.
- Request-path gateway starts with no Studio endpoints/beans.
- Model adapter credentials cannot call production registry mutation.
- Studio API enforces every state transition and approval.
- Same confirmed dossier/compiler version produces the same bundle hash.
- Production registry accepts only a current certified/approved hash.
- Cross-tenant/domain evidence access is denied.
- Every promotion has an activation receipt and audit chain.
- Model request history is disposable without losing dossier truth.
