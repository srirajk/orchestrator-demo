# Milestone 1 — Catalog Truth and Contracts

## Milestone objective

Turn the current filesystem registry into a deterministic, honest business read model and freeze the
contracts/SDKs every later lane will consume. No UI claim, package or runtime integration may be
built on invented counts or inferred Use Cases.

## Exit gate G1

G1 passes when the broken legacy fixture preserves the original 4/7/18/18 inventory and six stable
findings, while the repaired working catalog has the same inventory with zero blocking consistency
findings. All new schemas/examples are valid, SDK/API fixtures are deterministic, and no runtime
state is mutated.

## M1-S01 — Freeze current catalog inventory and defects

**Outcome:** One canonical baseline records what exists and what is inconsistent.

**Dependencies:** None. This is the sequential root story.

**Scope:**

- enumerate domain, sub-domain and agent manifests;
- derive capabilities from skills without calling them confirmed Use Cases;
- validate individual schemas and full cross-references;
- assign stable finding codes and affected paths;
- produce sanitized canonical inventory JSON plus a human-readable report.

**Required finding codes:**

```text
CATALOG_REQUIRED_CONTEXT_CLARIFICATION_MISSING
CATALOG_AGENT_REVERSE_MEMBERSHIP_MISSING
```

**Primary output paths:**

```text
contracts/onboarding-studio/fixtures/catalog-legacy-broken/
  inventory.json
  findings.json
  source-hashes.json
  README.md
```

**Acceptance criteria:**

1. Counts are 4/7/18/18 and confirmed Use Cases are zero.
2. Four reverse-membership and two clarification findings are present with exact affected IDs.
3. Agents are discovered independently of reverse membership, so none disappear from inventory.
4. Every source file has a SHA-256 entry.
5. Re-running without source change produces byte-identical output.
6. No source manifest is edited by this story.

**Harness:** H1 plus the current schema test. The expected catalog-level failure must be converted
into structured findings in the importer fixture, not suppressed from repository tests.

**Done evidence:** inventory hash, findings hash, test result and source hash manifest.

## M1-S02 — Define business catalog and package schemas

**Outcome:** Versioned machine contracts express the full hierarchy and generated package.

**Dependencies:** M1-S01.

**Owned contracts:**

```text
contracts/onboarding-studio/business-line.schema.json
contracts/onboarding-studio/use-case.schema.json
contracts/onboarding-studio/route-target.schema.json
contracts/onboarding-studio/capability.schema.json
contracts/onboarding-studio/agent-summary.schema.json
contracts/onboarding-studio/onboarding-project.schema.json
contracts/onboarding-studio/confirmed-dossier.schema.json
contracts/onboarding-studio/consent-receipt.schema.json
contracts/onboarding-studio/bundle.schema.json
contracts/onboarding-studio/catalog-health-finding.schema.json
contracts/onboarding-studio/check-definition.schema.json
contracts/onboarding-studio/check-result.schema.json
contracts/onboarding-studio/workflow-readiness.schema.json
```

**Contract rules:**

- Organization/tenant owns Business Lines.
- Business Line IDs map compatibly to current domain IDs.
- Use Case is a new business outcome contract and cannot be inferred as confirmed.
- Capability may be reused by multiple Use Cases.
- Route target connects a Use Case/capability to a goal agent skill.
- Context Group is runtime structure, not the primary business hierarchy.
- material fields carry provenance or reference dossier provenance;
- schemas use explicit version, closed objects where practical and stable enums;
- references are checked beyond JSON Schema by a catalog contract validator.
- workflow state is derived from versioned check results; no client/model-authored pass field exists.

**Acceptance criteria:**

1. Every schema passes Draft 2020-12 meta-validation.
2. Positive examples validate and a negative fixture exists for every required field/reference rule.
3. Use Case schema represents positive, boundary, unsupported and ambiguity signal references.
4. Consent schema separates analysis, model, inspection, probe, retention and generation purposes.
5. Bundle schema describes the folder entries, hashes, compiler policy and source catalog hash.
6. Existing manifests can be represented without semantic loss or pretending inferred facts are
   confirmed.
7. Versioning and forward/unsupported-version behavior are documented.

**Harness:** H1 meta-schema, example and negative-case suite.

**Done evidence:** schema hashes, example hashes and validation report.

## M1-S03 — Build manifest-contract and artifact SDK skeletons

**Outcome:** Spring-free Java libraries own shared records and deterministic artifact mechanics.

**Dependencies:** M1-S02. May run parallel with M1-S04 and M1-S05.

**Modules:**

```text
libs/conduit-manifest-contracts/
libs/conduit-artifact-sdk/
```

**Required APIs:**

```text
CatalogContracts.parseAndValidate(bytes, schemaVersion)
CanonicalJson.write(value)
ContentHash.sha256(bytes)
BundleWriter.stage(project, dossier, entries)
BundleVerifier.verify(path, expectedHash)
BundleReader.open(path)
```

**Constraints:**

- Java 21 bytecode;
- no Spring, database, Redis, HTTP, model or gateway packages;
- canonical JSON rules are explicit and tested;
- path traversal and symlink escape are rejected;
- the writer stages then atomically publishes only verified bundles;
- timestamps come from frozen input, never implicit wall-clock values inside canonical artifacts.

**Acceptance criteria:**

1. Architecture tests prove forbidden dependencies are absent.
2. Same input is byte-identical across two clean runs.
3. Unicode, number, optional field and stable-array ordering cases are pinned.
4. Verification detects modified, missing and extra files.
5. Invalid bundles leave no discoverable final directory.
6. SDK APIs use typed errors with stable codes and paths.

**Harness:** H2 SDK unit, architecture and byte-golden tests.

**Done evidence:** Maven test report, dependency report and canonical fixture hashes.

## M1-S04 — Build read-only importer and health analyzer

**Outcome:** Typed business read models are derived from the current registry without mutation.

**Dependencies:** M1-S02. May run parallel with M1-S03 and M1-S05.

**Scope:**

- parse current domains as Business Lines;
- parse sub-domains as Context Groups;
- parse agent manifests and skills as connected agents/capabilities;
- produce provisional route targets with `NEEDS_BUSINESS_CONFIRMATION`;
- build both forward and reverse relationships;
- report duplicates, missing references, membership mismatches and clarification gaps;
- attach source hashes and import timestamp outside canonical business content.

**Acceptance criteria:**

1. Import matches M1-S01 inventory and finding hashes.
2. No inferred Use Case is emitted as `CONFIRMED`.
3. All 18 agents remain searchable despite membership gaps.
4. Unknown schema versions fail with actionable errors.
5. Import is read-only and has no Redis/database/network dependency.
6. Duplicate IDs and directory/manifest parent disagreement fail deterministically.

**Harness:** H1 importer contract tests against current and adversarial fixture trees.

**Done evidence:** read-model snapshot, health report and mutation-absence test.

## M1-S05 — Freeze Studio read API and generated UI fixtures

**Outcome:** Frontend and backend share one OpenAPI/read-model contract.

**Dependencies:** M1-S02 and M1-S04. May run parallel with the approved catalog repair in M1-S06.

**Required endpoints:**

```text
GET /studio/overview
GET /studio/business-lines
GET /studio/business-lines/{id}
GET /studio/use-cases
GET /studio/agents
GET /studio/agents/{id}
GET /studio/catalog-health
```

**Required behavior:**

- pagination/filter types are defined even if the fixture is small;
- responses carry source catalog hash, schema version, integrity status and import time;
- unknown IDs return stable RFC 9457-style problem details;
- UI fixtures are generated from M1-S01 data and validate against OpenAPI;
- the historical six findings remain visible in the broken/import-history fixture while the current
  clean fixture reports no blocking inconsistency.

**Acceptance criteria:**

1. OpenAPI validates and operation IDs are stable/unique.
2. Generated TypeScript types compile without handwritten duplicates.
3. Every fixture validates against the API schema.
4. Filters cannot expose unrelated tenant/Business Line data in later adapters.
5. No mutation, ingestion or activation endpoint exists.

**Harness:** H1 OpenAPI/client/fixture contract tests.

**Done evidence:** OpenAPI hash, generated-client hash and fixture-validation report.

## M1-S06 — Repair current catalog integrity with approved semantics

**Outcome:** Working repository manifests pass the complete bidirectional catalog contract while the
original defects remain testable as an immutable regression fixture.

**Dependencies:** M1-S01 and M1-S04.

**Scope:**

- add the four missing agent IDs to their declared Context Group membership lists;
- add clarification definitions for required `relationship_id` context in cash management and
  custody operations using Business Line-owner-approved questions/options/priority;
- extend repository cross-reference tests to verify agent → Context Group → agent bidirectionality;
- keep the original broken inputs only in `catalog-legacy-broken` test fixtures;
- regenerate the working catalog snapshot as `catalog-current-clean`.

**Acceptance criteria:**

1. Current registry schema/cross-reference tests pass without expected-failure exceptions.
2. Working inventory remains 4/7/18/18 and has zero blocking findings.
3. Broken fixture still produces exactly the six original stable findings.
4. Clarification wording/options have recorded Business Line-owner approval and are not invented by
   an implementation agent.
5. No gateway Java or runtime ingestion behavior changes.

**Harness:** H1 current-clean and legacy-broken fixtures plus planted reverse-membership mutation.

**Done evidence:** owner decision reference, manifest diffs, green current test and broken-fixture
finding hash.

## M1-S07 — Establish M1 harness and golden hashes

**Outcome:** One command reproduces G1 and stores a hashable evidence manifest.

**Dependencies:** M1-S03, M1-S04, M1-S05 and M1-S06.

**Scope:**

- implement `scripts/studio-check.sh --milestone M1` and `--story <work-packet-id>`;
- run schema/meta-schema, importer, SDK, OpenAPI and fixture suites;
- collect tool versions, commands, counts and hashes;
- fail on unapproved snapshot changes or unexpected skips;
- prove the repaired working catalog is green and the frozen broken fixture reports exactly six
  findings.

**Acceptance criteria:**

1. Clean runs produce equivalent evidence content excluding declared run metadata.
2. A planted schema, hash and membership mutation each causes a hard failure.
3. Evidence contains no credential/environment secret value.
4. The command can run without starting gateway, Redis, PostgreSQL or Docker.
5. G1 result clearly distinguishes repaired current state, frozen historical findings and work not
   yet applicable.

**Harness:** H1/H2 contract, importer, SDK and planted-mutant integration gate.

**Done evidence:** complete M1 evidence directory plus planted-mutant proof.

## Milestone definition of done

All seven stories are `DONE`; G1 reproduces from one command; contracts are frozen with hashes; later
lanes can build independently against generated fixtures; and no application/runtime behavior has
changed.
