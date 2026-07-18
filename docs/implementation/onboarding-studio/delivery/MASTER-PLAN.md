# Conduit Studio Master Delivery Plan

## 1. Product story

Conduit begins with technical agent manifests that platform engineers can understand but business
owners cannot navigate. The Studio turns that catalog into a business landscape, helps a named owner
define a Use Case and its boundaries, then produces a deterministic package that can later be
assured, approved and externally ingested.

The flagship story is:

```text
See the organization
  -> understand Business Lines, capabilities and connected agents
  -> choose a business outcome
  -> establish intent, boundaries, context, authority and consent
  -> reuse or connect agents
  -> review what Conduit understood
  -> generate one exact Conduit Package
  -> prove its schema, references, snapshots and provenance
  -> certify and approve the exact hash
  -> ingest it outside the gateway
  -> serve it through one pinned, immutable gateway catalog
```

The UI is the comprehension layer. The artifact package is the handoff contract. External ingestion
is the mutation authority. The request-path gateway is the read-only execution layer.

## 2. Verified starting point

The repository currently contains:

| Item | Count/status |
|---|---:|
| Business Lines/domain manifests | 4 |
| Context Groups/sub-domain manifests | 7 |
| Connected agent manifests | 18 |
| Capabilities/skills | 18 |
| Business-confirmed Use Cases | 0 |
| Catalog integrity findings | 6 |
| Individual schema tests | 7 passing |
| Catalog cross-reference test | Failing on current data |
| Registry ingestion boundary | Already external through the `registry` Spring profile/container |
| Gateway Spring Boot baseline | 3.5.16 in `gateway/pom.xml` |
| Gateway target for final integration | Spring Boot 4.x |

The six findings are four missing reverse Context Group memberships and two required-context fields
without clarification definitions. M1 first freezes them as a broken legacy regression fixture,
then repairs the working catalog with owner-approved clarification semantics and proves the catalog
contract green. The UI can demonstrate that Conduit found six import defects without shipping a
knowingly broken active baseline.

## 3. Milestone dependency graph

```text
M1 Catalog Truth and Contracts
  G1: frozen inventory + valid schemas + stable SDK/API fixtures
       |
       v
M2 Flagship Studio and Artifact Generation
  G2: UI golden path + deterministic package folder + snapshot proof
       |
       v
M3 Intelligence, Assurance and Governance
  G3: durable evidence + bounded guidance + certification + exact-hash approval
       |
       v
M4 External Ingestion and Read-Only Gateway
  G4: external activation + pinned gateway read + rollback proof
```

No M4 code is required to complete M1–M3. M1 may characterize existing gateway semantics read-only,
but it does not change the gateway runtime.

## 4. Milestone gates

### G1 — Truth is stable

Required evidence:

- canonical broken-import fixture with 4/7/18/18 counts and six expected findings;
- repaired working catalog snapshot with the same counts and zero blocking integrity findings;
- valid Business Line, Use Case, route-target, dossier and bundle schemas;
- deterministic contract/artifact SDK tests;
- generated OpenAPI fixtures used by the frontend;
- no registry or gateway mutation.

### G2 — The product is tangible

Required evidence:

- Overview, Business Lines, Agent Network, project flow and package reveal working;
- Insurance renewal-risk golden project completed without JSON/YAML editing;
- explicit signal explanation and granular consent receipts;
- server-derived intake workflow with blocking readiness checks and gateway-compatibility mappings;
- artifact folder generated atomically outside `registry/`;
- repeated compilation produces identical bytes/hash;
- UI, API, artifact and accessibility snapshots green;
- reproducible Studio JAR/image with targeted cached rebuild and no unrelated service dependency;
- no ingestion or gateway dependency.

### G3 — The package is governable

Required evidence:

- durable PostgreSQL project/dossier state and immutable evidence objects;
- typed model proposals never becoming facts without human acceptance;
- service inspection bounded by consent and read-only policy;
- routing ownership, composition and catalog regression evidence;
- certification verdict over exact input hashes;
- separation-of-duties approval and immutable audit history;
- isolated database/object-store topology with backup/restore and prior-image compatibility proof;
- external ingestion/runtime gates honestly marked `NOT_RUN`.

### G4 — Runtime integration is safe

Required evidence:

- existing external registry-profile ingestion, evolved through shared SDKs, is the only mutation
  authority; a new deployable is introduced only by an explicit ADR;
- shared admission semantics match characterized behavior;
- exact package dry-run, shadow index, activation receipt and rollback work;
- gateway completes Spring Boot 4 target and contains no ingestion endpoints/beans/credentials;
- every request/conversation observes one pinned catalog hash;
- tenant isolation, authorization, coverage, World-B and full regression gates are green.
- immutable image/JAR/package digests, SBOM/profile parity and prior-image rollback evidence.

## 5. Parallelism model

Within a milestone, work may run in parallel only after the listed shared contract gate freezes.

```text
Contract owner
  ├── Backend lane
  ├── Frontend lane
  ├── Compiler/SDK lane
  └── Harness/evidence lane
```

Only the contract owner changes shared schemas or OpenAPI during an active milestone. Other lanes
raise a contract-change request and stop the affected story. Generated clients are refreshed only
after the contract owner publishes a new contract hash.

Never parallelize:

- two edits to the same schema family;
- schema changes and generated-client acceptance;
- compiler canonicalization and golden-hash approval;
- authorization policy and approval-state implementation;
- external ingestion activation and gateway catalog-read semantics;
- gateway Spring Boot migration and unrelated gateway behavior changes;
- a runtime behavior change while the same behavior is serving as the parity oracle.

## 6. Release strategy

| Release | Audience | Enabled behavior |
|---|---|---|
| R1 Catalog Preview | Internal/demo | Read-only current catalog and health findings |
| R2 Package Studio | Design partners | Guided project and local/downloadable package generation |
| R3 Governed Candidate | Internal governance | Evidence, certification and approvals; no activation |
| R4 Sandbox Runtime | Platform team | External ingestion and immutable gateway catalog in sandbox |
| R5 Controlled Production | Authorized tenants | Exact-hash promotion, pinned reads and rollback |

Feature flags separate package generation, model assistance, live inspection, certification,
external ingestion and production activation. Enabling one never implicitly enables the next.

## 7. Program-level acceptance criteria

1. Business users navigate Organization → Business Line → Use Case without needing manifest terms.
2. All discovered agents remain visible even when imported cross-references are defective.
3. A technical skill is never presented as a confirmed Use Case without a named human decision.
4. Every material fact is observed, declared, derived, defaulted or approved with provenance.
5. The model proposes but cannot compile unconfirmed facts, approve, certify or activate.
6. Identical canonical inputs produce identical package bytes and content hash.
7. Every package contains schemas, cross-reference results, signals, provenance and impact proof.
8. Certification and approval bind exact immutable hashes.
9. External ingestion is the only manifest/index mutation authority.
10. The request-path gateway is read-only with respect to catalogs and observes one version per
    request/conversation.
11. Tenant, structural authorization, coverage and DAG-node gates remain fail closed.
12. The flagship demo can add a Use Case to Insurance without changing the Insurance Business Line
    manifest, and can create a new Business Line when shared policy is genuinely new.

## 8. Program definition of done

The full program is done when all four gates are signed, all required harness evidence is retained,
the exact approved package can be activated and rolled back in a production-like environment, the
gateway serves the pinned version without mixed reads, and the UI/audit trail reconstructs every
material decision from business intent to activation receipt.
