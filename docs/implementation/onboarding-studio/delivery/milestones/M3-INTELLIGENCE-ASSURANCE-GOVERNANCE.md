# Milestone 3 — Intelligence, Assurance and Governance

## Milestone objective

Turn a compelling generated package into a durable, evidence-backed governed candidate. Add bounded
intelligence and service inspection without giving models authority. Certify and approve exact
hashes while honestly leaving external ingestion/runtime gates unexecuted.

## Exit gate G3

G3 passes when projects/evidence survive restart, bounded guidance and inspection respect consent,
knowledge/resource/composition candidates receive reproducible certification, approvals enforce
separation of duties, and audit reconstructs every material fact/hash. Nothing is activated.

## M3-S01 — Add PostgreSQL project, dossier and outbox persistence

**Outcome:** Project state and workflow are durable, versioned and recoverable.

**Dependencies:** G2. May run parallel with M3-S02–S04.

**Scope:**

- Flyway-managed organization, Business Line, Use Case, project, dossier, proposal, consent, job,
  artifact, certification, approval and audit tables;
- normalized identity/relationship columns plus immutable JSONB snapshots;
- optimistic versions, idempotency records and transactional outbox;
- repository ports replacing M2 fixture/in-memory adapters without API behavior change;
- tenant predicates and optional RLS defense in depth.

**Acceptance criteria:**

1. M2 golden project survives restart with the same dossier/package hashes.
2. Concurrent stale writes fail without partial events/outbox records.
3. Duplicate idempotent commands return the original result.
4. Migrations work from empty database and the immediately prior schema.
5. Tenant-scoped queries cannot omit tenant predicates.
6. Database backup/restore rehearsal preserves hashes and audit order.

**Harness:** H3 Testcontainers integration, migration, concurrency and tenant tests.

## M3-S02 — Add immutable evidence and object storage

**Outcome:** Documents, service snapshots, probes and bundles have content-addressed durable evidence.

**Dependencies:** G2. May run parallel with M3-S01/S03/S04.

**Scope:**

- S3-compatible object store port with MinIO adapter;
- raw versus redacted object separation;
- evidence metadata, classification, provenance and retention;
- hash verification on write/read;
- upload type/size limits and archive/document isolation policy;
- immutable package storage using the M2 artifact SDK.

**Acceptance criteria:**

1. Hash mismatch, duplicate conflicting metadata and path/key confusion fail closed.
2. Authorized readers receive only permitted redacted/original representation.
3. Object deletion/retention actions are audited and cannot invalidate an approved bundle silently.
4. Credentials and secret-like fields are rejected/redacted before durable evidence.
5. Object-store outage does not create a database record claiming evidence exists.

**Harness:** H3/H6 object-store integration, security and recovery tests.

## M3-S03 — Add OIDC and tenant/Business Line authorization

**Outcome:** Verified identity and scoped roles control every Studio resource/action.

**Dependencies:** G2. May run parallel with M3-S01/S02/S04.

**Scope:**

- Axiom OIDC Authorization Code/BFF session;
- tenant, Business Line and project scope enforcement;
- submitter, owner, technical owner, security reviewer, platform reviewer, release manager and auditor
  roles;
- server-side authorization for reads, mutations, evidence and later approvals;
- secure cookie/CSRF/session expiry behavior;
- non-disclosure responses for unrelated tenant resources.

**Acceptance criteria:**

1. Browser never stores a reusable bearer token in local storage.
2. Cross-tenant/project enumeration and direct-object-reference tests fail.
3. Hidden UI actions remain denied at API level.
4. Session expiry preserves safe unsaved state and requires reauthentication.
5. `platform_admin` does not automatically satisfy business/security approval roles.
6. Authorization decisions are auditable without logging token/secret content.

**Harness:** H3/H7 security matrix and cross-tenant adversarial tests.

## M3-S04 — Add bounded guidance and proposal runtime

**Outcome:** Model assistance asks better questions and proposes typed facts without owning truth.

**Dependencies:** G2. May run parallel with M3-S01–S03.

**Scope:**

- Java `GuidanceModel` port and bounded provider adapter;
- authorized context builder with classification/redaction/token limits;
- typed question, proposal, explanation and evidence-summary outputs;
- prompt/version records, tracing and cost controls;
- deterministic fallback question backlog;
- tools limited to read-only dossier/evidence queries and controlled job requests.

**Acceptance criteria:**

1. Invalid/untrusted output never changes project state.
2. Human acceptance is required to convert a proposal into a declared fact.
3. Model has no approval, certification verdict, activation, policy mutation or secret tool.
4. Prompt-injected evidence cannot widen tools or consent.
5. Provider outage/timeout leaves deterministic project/generation behavior usable.
6. Sensitive data and prompts are excluded from telemetry by policy.

**Harness:** H6 typed output, injection, outage, privacy, cost and deterministic fallback evals.

## M3-S05 — Add consent-bound service inspection and probes

**Outcome:** Existing HTTP/OpenAPI and MCP services can be observed safely before runtime ingestion.

**Dependencies:** M3-S02 and M3-S03.

**Scope:**

- protocol contract fetch/parse behind Studio inspection ports;
- allowlist, DNS/IP/redirect and egress controls;
- opaque credential references, never raw credentials;
- separate inspect and named read-only probe consent;
- request/response size/time limits, redaction and evidence capture;
- observed-versus-declared contract comparison;
- no registry/index mutation.

**Acceptance criteria:**

1. Inspection without matching unexpired consent is denied.
2. Private/loopback/link-local/metadata targets and redirect escapes are blocked by policy except
   explicit isolated test fixtures.
3. Only approved safe methods/tools/operations run.
4. Probe evidence is hashed, redacted and tied to target/credential reference/version/time.
5. Contract disagreement creates a blocking gap rather than silent compiler coercion.
6. Timeout/outage yields actionable `UNABLE_TO_ASSESS` evidence.

**Harness:** H6 SSRF, redirect, credential, timeout, malformed-spec and read-only probe tests.

## M3-S06 — Add routing ownership and composition assurance

**Outcome:** Business boundaries and multi-capability plans receive inspectable evidence.

**Dependencies:** M3-S02 and M3-S04.

**Scope:**

- catalog-neighbor retrieval from immutable fixture/catalog snapshots;
- ownership exercises for positive, boundary, unsupported and ambiguous questions;
- before/after route-target comparison without production activation;
- typed capability plan for goal/dependency/optional roles;
- projection, condition and bounded-map static checks;
- visualization of business flow and exact generated semantics;
- held-out/adversarial dataset separation.

**Acceptance criteria:**

1. Every approved positive selects the candidate in the pinned test policy.
2. Every boundary/unsupported signal rejects or selects the approved neighbor/abstention.
3. Existing affected capabilities are named; no single score hides poaching.
4. Composition catches missing producers, schema mismatch, cycles and unbounded map.
5. Human intent confirmation precedes expression generation.
6. Model-generated examples cannot populate the independent held-out set.

**Harness:** H6/H7 routing, composition, regression and provenance suites.

## M3-S07 — Add certification runner and verdict composition

**Outcome:** One immutable certification record evaluates an exact candidate and evidence set.

**Dependencies:** M3-S01, M3-S02, M3-S05 and M3-S06.

**Required gate classes:**

- schema/cross-reference;
- provenance/consent;
- service/observed contract;
- routing ownership/regression;
- coverage/authorization design;
- composition/static dataflow;
- functional/golden;
- security/model safety;
- external ingestion/runtime, marked `NOT_RUN` in M3.

**Acceptance criteria:**

1. Run pins dossier, bundle, catalog, dataset, policy, prompt/model and service-evidence hashes.
2. Hard gate failure cannot be averaged away by measured quality.
3. Missing required evidence yields `UNABLE_TO_ASSESS`, not pass.
4. Unsupported platform primitive yields `UNSUPPORTED_REQUIREMENT` with a gap record.
5. Input change invalidates only dependent results but makes the overall old run stale.
6. External ingestion/runtime gates cannot report pass before M4 evidence exists.

**Harness:** H7 verdict truth-table, staleness, determinism and planted-failure tests.

## M3-S08 — Add approvals, separation of duties and audit

**Outcome:** Authorized humans decide on exact hashes with reconstructable history.

**Dependencies:** M3-S03 and M3-S07.

**Scope:**

- role/field-specific approval requirements;
- approve, reject, request change and conditional approval records;
- exact dossier/bundle/certification/environment hashes;
- material-change invalidation;
- submitter/editor/approver/release separation;
- append-only audit and authorized audit UI;
- no activation tool/action in M3.

**Acceptance criteria:**

1. Submitter cannot self-satisfy required independent approval.
2. Stale or mismatched hash approval is rejected.
3. Rejection/change request returns project to an explicit responsible step without mutating bundle.
4. Audit reconstructs actor, authority, before/after hash, reason and timestamp.
5. Model/assistant cannot render or invoke the authoritative approval control.
6. Approved candidate remains unactivated and clearly labeled.

**Harness:** H7 authorization matrix, stale hash, concurrent approval and audit reconstruction tests.

## M3-S09 — Prove knowledge, resource and composition scenarios

**Outcome:** G3 is supported by multiple archetypes, failures and tenant boundaries.

**Dependencies:** M3-S01 through M3-S08.

**Required scenarios:**

1. HR policy enterprise knowledge.
2. Insurance policy/renewal resource-scoped behavior.
3. Wealth concentration review composition.
4. Trade-penalty bounded map.
5. Neighbor overlap rejected until ownership resolved.
6. Insufficient evidence produces honest blocked verdict.
7. Malicious document fails to influence authority/tools.
8. Cross-tenant reads/approvals are non-disclosing and denied.
9. Model and object-store outages preserve safe state.

**Acceptance criteria:**

1. Each scenario has pinned fixtures, expected verdict and evidence manifest.
2. Knowledge/resource/composition happy paths reach approved-candidate state only.
3. All negative scenarios fail at the intended gate with no partial approval.
4. Restart/retry preserves idempotent state and hashes.
5. M3 flagship journey can run without an external ingestion or gateway service.

**Harness:** H10 M3 plus H3/H6/H7 required suites.

## M3-S10 — Add durable container topology and recovery proof

**Outcome:** Studio persistence/evidence dependencies are isolated, reproducible and compatible with
image rollback.

**Dependencies:** M3-S01 and M3-S02. Shared Compose/init files are integration-owner-only.

**Scope:** Add a separate Studio database/schema/principal, Studio-only MinIO buckets/policies,
idempotent initialization, Flyway readiness, named volumes, health dependencies and backup/restore
runbook without giving Studio access to gateway Redis/index keys.

**Acceptance criteria:**

1. Studio database/object credentials cannot access unrelated IAM/gateway data.
2. Empty and prior-schema startup/migrations pass; schema mismatch fails closed.
3. MinIO initialization is idempotent and scope-limited.
4. Backup/restore preserves dossier, package and evidence hashes.
5. Prior-image/forward-schema compatibility is proven or rollback is blocked with an explicit plan.
6. Dependency outage produces correct readiness without corrupting durable state.

**Harness:** H11 Compose, migration, isolation, backup/restore and image rollback proof.

## Milestone definition of done

All ten stories are `DONE`; G3 runs from one command; durable state, evidence, certification,
approval and audit are reproducible; model/inspection authority remains bounded; and every UI surface
states that the candidate is approved but not ingested or active.
