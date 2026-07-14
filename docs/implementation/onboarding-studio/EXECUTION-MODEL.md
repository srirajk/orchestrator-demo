# Conduit Onboarding Studio — Implementation Execution Model

**Purpose:** Tell Codex what may run in parallel, what is sequential, and what evidence opens each gate.

## 1. Governing rule

Parallelize independent packages only after their shared contracts are frozen. Never parallelize two
workers that are both discovering or changing the same behavioral contract. A merged file is not a
completed dependency; its tests and acceptance evidence must pass first.

## 2. Dependency graph

```text
G0 decisions and fixtures
        |
        v
G1 characterization tests -> shared contracts/admission extraction
        |                         |
        +------------+------------+
                     v
        Studio domain + persistence + API contracts
          |          |           |
          v          v           v
       React UI   model adapter   compiler
          |          |           |
          +----------+-----------+
                     v
          registry dry-run + certification
                     |
                     v
          authorization + approval gates
                     |
                     v
           staging promotion rehearsal
                     |
                     v
              production enablement
```

## 3. Delivery waves

### Wave 0 — Freeze decisions and fixtures (sequential)

Deliver:

- ADR-001 accepted;
- three canonical dossier/manifest fixtures plus overlap and insufficient-evidence fixtures;
- current gateway behavior characterized;
- dossier, bundle, certification and error schemas versioned;
- module build strategy decided.

Exit gate `G1`: fixture hashes and contract tests are green. No UI/model/promotion implementation
starts before this gate because otherwise each lane invents its own contract.

### Wave 1 — Semantic core (mostly sequential)

1. Add characterization tests around existing Java behavior.
2. Extract `conduit-admission` without changing behavior.
3. Switch gateway to shared module.
4. Run the full relevant gateway battery and World-B check.
5. Implement deterministic dossier-to-bundle compiler using the shared models.

Steps 1–3 are sequential and one owner should perform them. Compiler scaffolding may begin after
shared DTOs are frozen, but it cannot be accepted before shared admission extraction passes.

Exit gate `G2`: old and extracted semantics match on fixtures, condition/map negative cases and
concurrency tests.

### Wave 2 — Control-plane foundation (parallel after G2)

Safe parallel lanes:

| Lane | Owns | Must not touch |
|---|---|---|
| A — Studio core | case/dossier domain, PostgreSQL, outbox, API | gateway execution, UI, prompts |
| B — Gateway bridge | snapshot, dry-run, shadow-route APIs | Studio DB, model runtime, UI |
| C — Web shell | auth shell, case list/detail, generated client | backend schemas, gateway |
| D — Guidance | `GuidanceModel`, prompts, typed proposals, evals | workflow transitions, approval, promotion |

All lanes consume frozen schemas. Contract changes go through one contract owner and trigger all
consumer tests; workers do not casually patch shared DTOs.

Exit gate `G3`: a user can create a case, review a structured dossier, compile and dry-run one
knowledge agent without registry mutation. The UI may use a fake model; model availability is not a
gate for deterministic flow.

### Wave 3 — Certification and resource security (partially parallel)

Safe parallel lanes after G3:

- certification runner and evidence bundle;
- coverage/authorization onboarding screens and policy proposal;
- routing-neighbor lab and regression dataset UI;
- model guidance evaluation and prompt-injection tests.

Required ordering:

1. authorization resource model before approval endpoints;
2. catalog snapshot before routing regression;
3. compiled candidate before certification;
4. certification result before any approval;
5. coverage/security hard gates before a resource-scoped pass verdict.

Exit gate `G4`: knowledge and resource-scoped fixtures pass; deny, outage and cross-tenant tests fail
closed; no approval can reference stale hashes.

### Wave 4 — Composable archetype (sequential core, parallel UI/evidence)

The composition implementation itself is sequential:

1. infer a typed proposal;
2. human confirms business intent;
3. compiler emits dependency/select/condition/map fields;
4. shared admission validates static contracts;
5. gateway shadow execution validates actual layer behavior;
6. certification compares outputs and traces against reference evidence.

While steps 3–5 are being implemented, UI composition visualization and evidence presentation can
run in parallel against frozen fixtures. They cannot declare completion until real trace contracts
exist.

Exit gate `G5`: composable fixture proves true/false condition paths, condition error, map cap,
partial item failure, layered fan-in and deterministic parallel-vs-serial equivalence.

### Wave 5 — Approval and promotion (sequential safety path)

No part of the safety decision chain is parallelized:

```text
freeze dossier -> compile exact hash -> certify exact hash -> approve exact hash
-> stage exact hash -> verify receipt -> promote exact hash -> verify receipt
```

UI work and audit views may be parallel, but each state transition is implemented and tested in
order. Production enablement remains feature-flagged until rollback rehearsal succeeds.

Exit gate `G6`: separation of duties, stale-hash rejection, idempotent retry, rollback and immutable
activation receipt tests pass.

## 4. Work that must never be parallelized

- two workers editing shared manifest/dossier schemas;
- extraction and behavioral modification of the same gateway validator;
- compiler mappings before dossier fields are frozen;
- approval and promotion state machines before authorization policy is frozen;
- certification verdict code before hard-gate taxonomy is frozen;
- generated OpenAPI client changes while backend OpenAPI is changing;
- production promotion implementation before exact-hash staging is proven;
- a worker changing `DagPlanExecutor` while another uses its behavior as a certification oracle.

## 5. Critical path

The shortest honest critical path is:

```text
fixtures -> characterization -> shared admission -> deterministic compiler
-> non-mutating gateway dry run -> certification -> authorization/approval
-> staging exact-hash activation -> production promotion
```

The UI and model-guidance experience are important but are not allowed to hide or redefine this
path. A beautiful interview cannot compensate for missing executable-semantic parity.

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

