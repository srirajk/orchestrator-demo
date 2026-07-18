# Conduit Onboarding Studio — Implementation Execution Model

**Purpose:** Tell Codex what may run in parallel, what is sequential, and what evidence opens each gate.

## 1. Governing rule

Parallelize independent packages only after their shared contracts are frozen. Never parallelize two
workers that are both discovering or changing the same behavioral contract. A merged file is not a
completed dependency; its tests and acceptance evidence must pass first.

The four-milestone package under `delivery/` governs executable story sequencing and acceptance
gates. `UI-FIRST-ARTIFACT-BUILDOUT.md` governs the product sequence: external ingestion and
request-path gateway integration are last. Early phases may read gateway code for characterization
and may create Spring-free shared contracts, but they do not change the running gateway.

## 2. Dependency graph

```text
G0 inventory, schema findings and business vocabulary
        |
        v
G1 Spring-free contracts + read-only importer + compiler fixtures
        |
        v
G2 flagship catalog UI + guided Onboarding Project
        |
        v
G3 deterministic artifact folder + snapshot proof
        |
        v
G4 bounded intelligence + certification + governance
        |
        v
G5 external registry ingestion using the proven package/SDK
        |
        v
G6 immutable catalog activation + read-only gateway integration
```

## 3. Delivery waves

### Wave 0 — Freeze inventory, language and contracts (sequential)

Deliver:

- ADR-001 accepted;
- current catalog imported as a 4 Business Line / 7 Context Group / 18 agent / 18 capability
  snapshot;
- all schema and cross-reference findings recorded, including the current six integrity gaps;
- Business Line, Use Case, route-target, dossier, bundle and error schemas versioned;
- three canonical dossier/manifest fixtures plus overlap and insufficient-evidence fixtures;
- current manifest behavior characterized without changing gateway runtime;
- shared SDK build strategy decided.

Exit gate `G1`: fixture hashes, imported-inventory snapshot and contract tests are green. No UI or
model implementation starts before this gate because otherwise each lane invents its own contract.

### Wave 1 — Artifact core (mostly sequential)

1. Add characterization tests around current schemas and manifest examples.
2. Create Spring-free `conduit-manifest-contracts` and `conduit-artifact-sdk` modules.
3. Implement the read-only repository catalog importer.
4. Implement deterministic dossier-to-folder compilation using shared records.
5. Produce the Insurance renewal-risk golden package and snapshot proof.

Steps 1–3 are sequential and one owner should perform them. Compiler scaffolding may begin after
shared DTOs are frozen. Admission logic that still lives in the gateway is characterized now and
extracted for external ingestion later; the gateway is not switched in this wave.

Exit gate `G2`: the real catalog imports deterministically and the golden dossier produces
byte-identical validated package folders with no registry mutation.

### Wave 2 — Flagship UI and project foundation (parallel after G2)

Safe parallel lanes:

| Lane | Owns | Must not touch |
|---|---|---|
| A — Studio catalog API | importer read models, project/dossier API | gateway execution, UI, prompts |
| B — Web catalog | Overview, Business Lines, Agent Network, catalog health | backend schemas, gateway |
| C — Project/package UX | guided steps, impact preview, artifact explorer | compiler mappings, gateway |
| D — Compiler proof | folder writer, canonicalization, snapshots | UI internals, gateway |

All lanes consume frozen schemas. Contract changes go through one contract owner and trigger all
consumer tests; workers do not casually patch shared DTOs.

Exit gate `G3`: a user can explore the current catalog, create an Onboarding Project, review a
structured business contract and generate the Insurance package into the specified folder. The UI
may use deterministic proposals; model availability is not a gate.

### Wave 3 — Signals, consent and bounded intelligence (partially parallel)

Safe parallel lanes after G3:

- signal explanation and granular consent receipts;
- coverage/authorization onboarding screens and policy proposal;
- routing-neighbor ownership lab and regression dataset UI;
- bounded model guidance, provenance and prompt-injection tests;
- composition visualization and evidence presentation.

Exit gate `G4`: the complete guided journey works with the model available or unavailable, every
material field shows provenance, and signal/consent/package snapshots are stable.

### Wave 4 — Certification and durable governance

Add PostgreSQL persistence, object evidence, certification, authorization, approvals and audit over
the exact package hashes generated in Wave 2. A local/static admission adapter may certify schema,
cross-reference and snapshot gates; executable external-ingestion/gateway gates remain explicitly
`NOT_RUN` until Wave 5.

Required ordering:

1. authorization resource model before approval endpoints;
2. compiled candidate before certification;
3. certification result before any approval;
4. coverage/security hard gates before a resource-scoped pass verdict;
5. no verdict may pretend an external ingestion or runtime test ran when it did not.

Exit gate `G5`: durable projects, evidence, package hashes, certification and approvals are
reconstructable without external ingestion.

### Wave 5 — External ingestion and read-only gateway (sequential safety path)

First build external registry ingestion from the proven package and shared SDK. Then complete the
gateway's Spring Boot 4 target and immutable catalog read integration. No part of the activation
decision chain is parallelized:

```text
freeze dossier -> compile exact hash -> certify exact hash -> approve exact hash
-> external ingest exact hash -> activate immutable catalog -> gateway captures exact hash
-> verify receipt -> rollback rehearsal
```

The external component owns every mutation. The request-path gateway has no ingestion endpoint,
bean or registry write credential. Production enablement remains feature-flagged until rollback
rehearsal succeeds.

Exit gate `G6`: stale-hash rejection, idempotent retry, immutable snapshot capture, catalog-version
pinning, rollback and activation receipt tests pass.

## 4. Work that must never be parallelized

- two workers editing shared manifest/dossier schemas;
- extraction and behavioral modification of the same gateway validator;
- compiler mappings before dossier fields are frozen;
- approval and promotion state machines before authorization policy is frozen;
- certification verdict code before hard-gate taxonomy is frozen;
- generated OpenAPI client changes while backend OpenAPI is changing;
- external ingestion before the generated package contract and golden folder are frozen;
- request-path gateway integration before external ingestion proves immutable catalog output;
- production promotion implementation before exact-hash staging is proven;
- a worker changing `DagPlanExecutor` while another uses its behavior as a certification oracle.

## 5. Critical path

The shortest honest critical path is:

```text
catalog inventory -> contracts -> flagship UI -> guided project
-> deterministic artifact folder -> snapshot proof -> durable governance
-> external ingestion -> immutable catalog -> read-only gateway -> rollback proof
```

The UI is part of the product proof, not decoration after backend work. It must expose contract gaps
honestly; visual polish cannot compensate for missing schema, provenance or snapshot evidence.

## 6. Codex concurrency policy

Use no more than four concurrent implementation lanes. Each lane receives:

- one owned directory set;
- read-only dependencies it may inspect;
- explicit files it must not edit;
- one acceptance command;
- an expected artifact or test report;
- a named integration owner.

Only the integration owner edits shared root build files, shared schemas and generated clients.
Parallel workers stop and report when a contract is missing; they do not invent it locally.

## 7. Work packet template

```text
Objective:
Gate/dependency already satisfied:
Owned files/directories:
Read-only reference files:
Forbidden edits:
Contract version/hash:
Implementation requirements:
Tests to add:
Commands to run:
Required evidence:
Known non-goals:
```

Every packet ends with changed files, tests run, tests not run, behavioral risks and the exact next
gate. “Code compiles” is not acceptance evidence for routing, authorization or concurrency work.
