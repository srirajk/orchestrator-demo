# Model Allocation and Master-Review Policy

## 1. Decision

A normal lower-cost coding model can implement much of this program **only when it receives one
atomic work packet with frozen contracts and exact acceptance commands**. It must not receive an
entire milestone or make irreversible architecture/security decisions.

The master agent owns decomposition, contract truth, sequencing, review and milestone gates.

## 2. Work tiers

### Tier A — Lower-cost implementation model

Suitable when all contracts are frozen and the task is local/mechanical:

- typed records/mappers against approved schemas;
- read-only importer rules with explicit fixtures;
- ordinary REST endpoint implementation;
- React components against generated clients/fixtures;
- deterministic unit/contract test additions;
- Dockerfile/Compose edits against an approved build contract;
- documentation/runbook synchronization.

Required review: module owner plus automated story harness.

### Tier B — Strong implementation model or tightly supervised lower-cost model

Suitable for bounded work with concurrency/state/security implications:

- optimistic project state and idempotency;
- canonical folder/ZIP implementation behind frozen rules;
- PostgreSQL migrations/repositories;
- evidence storage/redaction adapters;
- service inspection/SSRF controls;
- routing/composition evaluation;
- Playwright and failure-injection integration.

Required review: master/integration owner plus domain-specific reviewer.

### Tier C — Master/high-reasoning owner required

Not delegated as an autonomous cheap-model decision:

- schema/ontology/versioning design;
- Business Line-owner semantic approval;
- canonicalization/hash policy changes;
- authorization, consent, certification and approval invariants;
- prompt/tool threat model;
- shared admission parity acceptance;
- activation/rollback/catalog consistency;
- root build/CI/base-image changes;
- Spring Boot 4/Jackson/JMESPath/SSE migration decision.

Tier A/B models may implement subparts after Tier C decisions are frozen, but cannot accept the gate.

## 3. Story allocation

| Story group | Default tier | Master responsibility |
|---|---:|---|
| M1-S01 inventory tooling | A | Validate counts/findings |
| M1-S02 schemas | C | Own semantics/versioning |
| M1-S03 SDK mechanics | B | Freeze canonical contract and review hashes |
| M1-S04 importer | A/B | Review inference boundaries |
| M1-S05 OpenAPI/fixtures | A/B | Freeze public contract |
| M1-S06 catalog repair | C | Obtain owner decisions and approve data change |
| M1-S07 harness | B | Accept planted-mutant evidence |
| M2-S01 API/project state | B | Review aggregate invariants |
| M2-S02/S03 UI shell/catalog | A | Review UX/accessibility |
| M2-S04/S05 project/signals/consent | B/C | Own consent and business semantics |
| M2-S06 compiler | B/C | Own mapping/hash acceptance |
| M2-S07 package proof UI | A/B | Validate no hidden technical authority |
| M2-S08/S09 flagship/a11y | B | Accept golden journey |
| M2-S10 image/Compose | B/C | Own build context/base/cache contract |
| M2-S11 workflow/check engine | B/C | Own stage/check semantics and gateway mapping acceptance |
| M3-S01/S02 persistence/evidence | B | Review tenant, recovery, immutability |
| M3-S03 authorization | C | Own permission model |
| M3-S04 model runtime | B/C | Own tool/prompt authority boundary |
| M3-S05 inspection | B/C | Own SSRF/consent policy |
| M3-S06 assurance | B/C | Own routing/composition gate semantics |
| M3-S07/S08 certification/approval | C | Own verdict/separation of duties |
| M3-S09 scenarios | B | Independent acceptance |
| M3-S10 durable topology | B/C | Own credentials, migration and rollback compatibility |
| M4-S01–S05 ingestion/activation | C | Own parity, mutation and rollback |
| M4-S06 Boot 4 migration | C | Own ADR and byte/runtime parity |
| M4-S07/S08 gateway/E2E | C | Own tenant/single-version release gate |
| M4-S09 supply chain | C | Own immutable image evidence and rollback acceptance |

## 4. Context packet for a lower-cost model

Provide only:

1. one atomic packet from `ATOMIC-WORK-PACKETS.md`;
2. its parent story section;
3. the exact frozen schema/OpenAPI/fixture files it consumes;
4. owned and forbidden paths;
5. baseline and acceptance commands;
6. relevant repository rule file;
7. expected handoff format.

Do not provide the entire 1,000+ line delivery package as the operative prompt. The model may read
references, but success is judged only against its atomic packet.

## 5. Master review loop

```text
Master freezes packet
  -> implementation model runs baseline
  -> implements only owned scope
  -> runs story harness
  -> returns diff/evidence/risks
  -> independent reviewer checks semantics
  -> master accepts or returns exact remediation
  -> integration owner runs joined gate
```

## 6. Definition of “safe for a cheaper model”

A packet is cheap-model-ready when it requires no new product/security decision, owns disjoint files,
has deterministic fixtures, has an exact failing baseline or green starting point, names negative
cases, and can be accepted by one command plus targeted review. If any condition is false, it stays
Tier B/C regardless of apparent coding simplicity.
