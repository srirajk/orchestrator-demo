# Conduit Studio Story Map

## 1. Story status model

```text
PROPOSED -> READY -> IN_PROGRESS -> IN_REVIEW -> VERIFIED -> DONE
                       |               |
                       +-> BLOCKED <---+
```

`READY` means dependencies, contracts, fixtures, owned files and acceptance commands are known.
`DONE` means acceptance evidence has passed review; merged code without evidence is `IN_REVIEW`.

## 2. Complete child-story catalog

### Milestone 1 — Catalog Truth and Contracts

| ID | Story | Depends on | Parallel group | Primary output |
|---|---|---|---|---|
| M1-S01 | Freeze current catalog inventory and defects | None | Sequential root | Canonical 4/7/18/18 snapshot + six findings |
| M1-S02 | Define business catalog and package schemas | M1-S01 | Contract owner only | Versioned schemas and examples |
| M1-S03 | Build manifest-contract and artifact SDK skeletons | M1-S02 | A | Spring-free Java modules |
| M1-S04 | Build read-only importer and health analyzer | M1-S02 | B | Typed imported catalog/read model |
| M1-S05 | Freeze Studio read API and generated UI fixtures | M1-S02 | C | OpenAPI + stable real-data fixtures |
| M1-S06 | Repair current catalog integrity with approved semantics | M1-S01,S04 | Contract owner | Green working catalog + broken regression fixture |
| M1-S07 | Establish M1 harness and golden hashes | M1-S03–S06 | Integration | Reproducible G1 evidence |

### Milestone 2 — Flagship Studio and Artifact Generation

| ID | Story | Depends on | Parallel group | Primary output |
|---|---|---|---|---|
| M2-S01 | Build Studio API shell and project aggregate | G1 | A | Spring Boot 4 API with fixture/filesystem adapters |
| M2-S02 | Build web shell, tokens and navigation | G1 | B | React shell and accessible navigation |
| M2-S03 | Build Overview, Business Lines and Agent Network | M2-S01,S02 | B | Real catalog intelligence UI |
| M2-S04 | Build guided Onboarding Project workspace | M2-S01,S02,S11 | C | Outcome, ownership and agent selection flow |
| M2-S05 | Build signals, consent and boundary lab | M2-S04 | C | Confirmed signals and consent receipts |
| M2-S06 | Build deterministic compiler and atomic artifact folder | G1 | A | Content-addressed package folder |
| M2-S07 | Build impact preview, package explorer and proof view | M2-S02,S06 | B | Business/artifact/provenance reveal |
| M2-S08 | Implement Insurance renewal-risk flagship journey | M2-S03–S07 | Integration | Complete no-ingestion golden path |
| M2-S09 | Complete accessibility, responsive and visual QA | M2-S08 | Integration | G2 visual/a11y evidence |
| M2-S10 | Package and run the targeted Studio image | M2-S08 | Build/integration | Reproducible cached image + Compose service |
| M2-S11 | Build intake workflow and blocking readiness checks | M2-S01,S02 | Integration | Server-derived stages + gateway-compatibility check rail |

### Milestone 3 — Intelligence, Assurance and Governance

| ID | Story | Depends on | Parallel group | Primary output |
|---|---|---|---|---|
| M3-S01 | Add PostgreSQL project/dossier/outbox persistence | G2 | A | Durable versioned workflow |
| M3-S02 | Add immutable evidence/object storage | G2 | A | Content-addressed evidence |
| M3-S03 | Add OIDC, tenant/Business Line authorization | G2 | B | Verified identity and scoped permissions |
| M3-S04 | Add bounded guidance and proposal runtime | G2 | C | Typed, non-authoritative assistance |
| M3-S05 | Add consent-bound service inspection and probes | M3-S02,S03 | C | Observed contract evidence |
| M3-S06 | Add routing ownership and composition assurance | M3-S02,S04 | D | Neighbor and capability-plan proof |
| M3-S07 | Add certification runner and verdict composition | M3-S01,S02,S05,S06 | Integration | Exact-hash certification |
| M3-S08 | Add approvals, separation of duties and audit | M3-S03,S07 | B | Governed candidate lifecycle |
| M3-S09 | Prove knowledge, resource and composition scenarios | M3-S01–S08 | Integration | Reproducible G3 evidence |
| M3-S10 | Add durable container topology and recovery proof | M3-S01,S02 | Build/integration | Isolated DB/object storage + rollback evidence |

### Milestone 4 — External Ingestion and Read-Only Gateway

| ID | Story | Depends on | Parallel group | Primary output |
|---|---|---|---|---|
| M4-S01 | Extract and characterize shared admission semantics | G3 | Sequential root | Parity-tested admission SDK |
| M4-S02 | Evolve the existing external registry-profile ingestion boundary | M4-S01 | A | SDK-backed external mutation component |
| M4-S03 | Implement candidate dry-run and introspection | M4-S02 | A | Non-mutating admission receipt |
| M4-S04 | Implement versioned materialization and shadow index | M4-S03 | A | Immutable candidate catalog |
| M4-S05 | Implement compare-and-set activation and rollback | M4-S04 | A | Activation/rollback receipts |
| M4-S06 | Resolve compatibility and complete approved Spring Boot 4 migration | G3,M4-S01 | B | ADR-backed behavior-equivalent Boot 4 gateway |
| M4-S07 | Add immutable catalog read and conversation pinning | M4-S05,S06 | Integration | Read-only single-version gateway |
| M4-S08 | Prove sandbox activation, tenant isolation and rollback | M4-S07 | Integration | Reproducible G4 evidence |
| M4-S09 | Produce release image and supply-chain evidence | M4-S08 | Build/integration | Digests, SBOM, profile parity and image rollback |

## 3. Parallel execution lanes

### M1

After M1-S02 freezes schemas, M1-S03 and M1-S04 may run in parallel. M1-S05 consumes the importer
read model. M1-S06 repairs the working catalog only after findings are frozen. M1-S07 integrates all
M1 lanes.

### M2

M2-S01, M2-S02 and M2-S06 may start in parallel after G1. M2-S11 joins API and shell to freeze the
workflow/check contract before the project UX. Catalog UI depends on API plus shell; package reveal
depends on compiler plus shell. M2-S08 is the first product integration point; M2-S10 owns shared
build/Compose files separately.

### M3

Persistence/evidence, authorization, model guidance and routing/composition may proceed in separate
owned directories. Certification cannot start acceptance until their pinned contracts exist.
Approvals cannot start until authorization and certification contracts are frozen.

### M4

Admission extraction and external ingestion are sequential. Gateway Spring Boot migration may run
in parallel with M4-S02–S05 only after M4-S01 freezes shared contracts. M4-S07 joins the lanes.

## 4. Critical path

```text
M1-S01 -> M1-S02 -> M1-S03/M1-S04 -> M1-S05/M1-S06 -> M1-S07
-> M2-S01/M2-S02/M2-S06 -> M2-S03/M2-S07/M2-S11 -> M2-S04 -> M2-S05 -> M2-S08 -> M2-S09/M2-S10
-> M3-S01/M3-S02/M3-S03/M3-S04 -> M3-S05/M3-S06 -> M3-S07 -> M3-S08 -> M3-S09/M3-S10
-> M4-S01 -> M4-S02 -> M4-S03 -> M4-S04 -> M4-S05
   + M4-S06 -> M4-S07 -> M4-S08 -> M4-S09
```

## 5. Contract-change protocol

1. Story owner records the missing/incorrect contract and affected consumers.
2. Contract owner proposes the smallest versioned change and migration effect.
3. Impacted story owners acknowledge the new hash.
4. Contract tests and generated clients update in one owned integration change.
5. Dependent snapshots are reviewed; they are never blindly regenerated.
6. Stories resume only after the contract gate is green.

## 6. Story readiness checklist

A child story is `READY` only if it has:

- a single measurable outcome;
- explicit dependencies and contract versions;
- owned and forbidden file sets;
- inputs/fixtures with hashes;
- API/schema behavior when applicable;
- acceptance criteria and negative cases;
- exact narrow and integration commands;
- expected evidence files;
- rollback/migration expectations;
- a named integration reviewer.
