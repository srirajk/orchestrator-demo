# Critical Review of the Delivery Plan

## Verdict

The product architecture and milestone order are strong. The first draft was **not yet safe to hand
unchanged to a cheaper coding model** because several “stories” were still epics and repository build
facts were underspecified. This review records the defects and the hardening required before execution.

After the corrections listed here and the atomic work-packet/model-allocation documents, cheaper
models may implement bounded low/medium-risk packets. They must not independently own shared contract,
security, canonical-hash, activation or gateway-migration decisions.

## Findings and dispositions

| Finding | Risk | Disposition |
|---|---|---|
| Current catalog test is red | Agent may normalize or ignore a real failure | Freeze broken fixture, repair working catalog in M1-S06, require green G1 |
| Existing registry ingestion already runs externally | Plan could create an unnecessary server | M4 evolves the existing `registry` profile; new deployable requires ADR |
| Gateway Boot 4 conflicts with locked repository decision | Blind version bump can break Jackson/JMESPath/SSE | Add compatibility/ADR gate and assign migration to master/high-reasoning lane |
| Stories such as persistence, certification and Boot migration are epic-sized | Cheap model may scatter incomplete scaffolding | Split into atomic work packets with one output/owned root/acceptance command |
| File ownership was implicit | Parallel agents can collide in POM/OpenAPI/Compose | Add ownership matrix and one integration owner for shared files |
| Build/container delivery was absent | “Working code” may be unreproducible or rebuild whole stack | Add build/JAR/ZIP/Docker/Compose/supply-chain specification |
| Future commands were milestone-level only | Agent could claim success with narrow tests | Require `studio-check --story` plus story evidence manifest |
| Snapshot approval policy lacked independent owner mapping | Generated code could approve generated golden files | Separate contract, business fixture and integration approval roles |
| UI showed active known defects | Product could ship a knowingly red catalog | Show repaired current health plus historical defects caught by Conduit |
| Runtime/storage topology lacked rollback compatibility | Image rollback could fail after migrations | Add forward migration, compatibility and backup/restore gates |

## Scorecard after required corrections

| Area | Rating | Remaining condition |
|---|---:|---|
| Product narrative | 9/10 | Validate with one non-platform user in M2 |
| Domain hierarchy | 9/10 | Owner confirmation of Context Group clarification copy |
| Architecture boundaries | 9/10 | ADR if ingestion leaves existing registry profile |
| Story coverage | 9/10 | Execute one atomic packet at a time |
| Cheap-model executability | 8/10 | Master assigns frozen packets and reviews evidence |
| Harness/evidence | 9/10 | Implement planted-mutant proof in M1 |
| Build/deployment maturity | 9/10 | Prove reproducibility/cache/image rollback, not only document it |
| Gateway migration safety | 7/10 | Jackson 3/JMESPath/SSE compatibility proof remains a real risk |

## Decisions that cannot be delegated to a cheap model

1. Business Line/Use Case schema meaning and versioning.
2. Owner-approved clarification/coverage/security semantics.
3. Canonical serialization and content hash changes.
4. Authorization, consent, certification and approval invariants.
5. Prompt/tool safety policy.
6. Admission parity acceptance.
7. External activation/rollback semantics.
8. Spring Boot 4 ADR, Jackson migration and SSE parity.
9. Changes to root Maven aggregation, Docker base images, Compose shared topology or CI release gates.

Cheaper models may implement code behind those frozen decisions and produce evidence. The master
agent/integration owner remains accountable for contract correctness and gate acceptance.

## Pre-execution blockers

Execution must not begin until:

- atomic packet M1-S01-T01 is issued with owned paths and baseline command;
- a contract/integration owner is named;
- the broken/current catalog fixture policy is accepted;
- clarification semantics have an identified Business Line owner;
- root Maven aggregation strategy and Studio image strategy are accepted;
- evidence output stays ignored and no secret is captured;
- the Boot 4 target is recorded as M4 planned work subject to the compatibility gate, not a current
  repository fact.

## Final critic position

The plan is excellent enough to execute **under orchestration**, not as a 1,690-line document handed
wholesale to one cheap model. The correct operating model is: master freezes one atomic packet,
cheaper model implements it, harness produces evidence, independent review accepts it, and only then
the next dependent packet becomes ready.

