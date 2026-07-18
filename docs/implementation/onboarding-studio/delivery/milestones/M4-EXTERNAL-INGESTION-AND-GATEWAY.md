# Milestone 4 — External Ingestion and Read-Only Gateway

## Milestone objective

Consume the exact package and SDK contracts proven by M1–M3, implement catalog mutation outside the
request-path gateway, migrate/integrate the gateway at Spring Boot 4, and prove atomic activation,
single-version reads and rollback without weakening existing runtime safety.

## Exit gate G4

G4 passes when an exact approved package is dry-run, materialized, shadow-tested and atomically
activated by external ingestion; the read-only gateway serves the pinned catalog version; and the
same package can be rolled back with complete receipts and no mixed-version request.

## Non-negotiable boundary

```text
Studio -> exact approved bundle -> external registry ingestion -> immutable catalog
                                                        |
                                                        v
                                      request-path gateway reads only
```

The request-path gateway has no ingestion controller, registrar/ingestor bean, index writer,
registry mutation credential or Studio workflow state.

## M4-S01 — Extract and characterize shared admission semantics

**Outcome:** External ingestion can reuse the existing Java intelligence without cloning behavior.

**Dependencies:** G3. Sequential root story.

**Scope:**

- characterize current manifest validation, projection, condition, bounded map and static DAG rules;
- extract domain-free behavior into `libs/conduit-admission` using M1 contracts;
- retain runtime execution in gateway;
- prove old/new behavior parity before changing any consumer;
- establish typed admission issue codes.

**Acceptance criteria:**

1. Positive and negative characterization fixtures match before/after results.
2. No Spring, HTTP, Redis, database, model or domain vocabulary enters shared admission.
3. Extraction does not change current gateway route/execution behavior.
4. Condition/projection/map limits remain fail closed.
5. World-B remains CRITICAL 0.

**Harness:** H2 admission architecture/parity plus relevant existing gateway tests and World-B.

## M4-S02 — Evolve the existing external registry-profile ingestion boundary

**Outcome:** The already-external `registry` profile/container consumes the shared SDK/package
contract and remains the only catalog mutation authority.

**Dependencies:** M4-S01. Gateway migration may proceed separately under M4-S06.

**Scope:**

- characterize the current `registry-service` container/profile, bean inventory, credentials and
  Compose health/dependency behavior;
- consume manifest, artifact and admission SDKs;
- implement service-identity authentication and tenant/environment authorization;
- define candidate/receipt/catalog ports;
- isolate write credentials and mutation beans from gateway;
- expose readiness that verifies required stores and policies.

The preferred first implementation retains the existing gateway image plus `registry` profile so the
container boundary and cached base/runtime image remain stable. Extracting a separate module/image is
allowed only after an ADR proves why profile isolation plus architecture/bean tests is insufficient.

**Acceptance criteria:**

1. Registry service starts independently of request-path gateway using the existing external profile.
2. Only authorized service identities can submit/activate/rollback.
3. Studio can hold only client credentials appropriate to approved submission, not gateway runtime.
4. Package hash/signature and expected base catalog are mandatory.
5. Architecture tests prove mutation implementation is absent from gateway dependencies.

**Harness:** H8 service-boundary, authentication and architecture tests.

## M4-S03 — Implement candidate dry-run and introspection

**Outcome:** Candidate receives executable admission evidence without durable catalog mutation.

**Dependencies:** M4-S02.

**Scope:**

- validate exact bundle/hash/schema/references;
- authorize tenant/environment and approval/certification freshness;
- inspect changed HTTP/MCP agent contracts under pinned evidence/consent policy;
- validate generated selection/composition contracts;
- return typed dry-run receipt and observed-contract hashes;
- prohibit index/catalog pointer writes.

**Acceptance criteria:**

1. Invalid package fails before any live call.
2. Dry-run state before/after is byte/logically identical except audit/evidence receipt.
3. Introspection uses bounded targets, methods, timeouts, sizes and credentials.
4. Observed contract mismatch blocks with exact artifact/path evidence.
5. Retry by idempotency key returns the original receipt.

**Harness:** H8 dry-run non-mutation, protocol, security and idempotency tests.

## M4-S04 — Implement versioned materialization and shadow index

**Outcome:** One complete immutable candidate catalog and matching routing index are built off-path.

**Dependencies:** M4-S03.

**Scope:**

- materialize Business Lines, Use Cases, Context Groups, agent records and route targets under one
  catalog hash;
- build a tenant/environment/catalog-version shadow vector index;
- run complete-snapshot readiness and routing regression;
- preserve old versions and source package references;
- prevent partial record/index visibility.

**Acceptance criteria:**

1. Every catalog entry and shadow document carries the same catalog hash.
2. Missing/duplicate/mixed-version member fails readiness.
3. Shadow index cannot receive production reads before activation.
4. Routing gain and existing-catalog regressions are reported per Use Case/capability.
5. Rebuild from the same package/policy produces equivalent immutable catalog contents.

**Harness:** H8 catalog completeness, isolation, regression and failure-injection tests.

## M4-S05 — Implement compare-and-set activation and rollback

**Outcome:** External ingestion atomically changes one tenant/environment active catalog pointer.

**Dependencies:** M4-S04.

**Scope:**

- expected-base compare-and-set activation;
- immutable activation receipt with package/catalog/index/policy hashes;
- concurrent activation conflict handling;
- gateway readiness acknowledgment policy;
- rollback to retained prior catalog;
- idempotent receipt/status lookup;
- audit and metrics.

**Acceptance criteria:**

1. Wrong expected base fails without active-state change.
2. Exactly one of two concurrent competing activations wins.
3. Partial materialization/index cannot be activated.
4. Retry never creates two activation records.
5. Rollback restores the prior exact hash and produces its own receipt.
6. External ingestion remains the only code path with pointer/index mutation credentials.

**Harness:** H8 concurrency, failure injection, idempotency, receipt and rollback suites.

## M4-S06 — Resolve compatibility and complete the approved gateway Spring Boot 4 migration

**Outcome:** Gateway reaches Spring Boot 4 with behavior equivalence before catalog-read changes.

**Dependencies:** G3 and M4-S01 shared contracts. May run parallel with M4-S02–S05 in disjoint files,
but must not implement ingestion.

**Baseline:** `gateway/pom.xml` currently declares Spring Boot 3.5.16 and Java 21 bytecode. Repository
policy records that Boot 4 was previously deferred because `jmespath-jackson` is Jackson-2-bound and
the Jackson 3/SSE path was not proven.

**Scope:**

- first write/approve an ADR with live characterization of JMESPath, Jackson serialization and SSE
  byte contracts, including the chosen replacement/shim/no-go decision;
- migrate parent/dependencies/plugins/configuration/tests to Spring Boot 4.x only after that gate;
- resolve Security/MVC/Redis/observability/Testcontainers compatibility;
- replace Boot-3-specific integrations only where required;
- keep public OpenAI-compatible API and routing/execution semantics stable;
- adopt Spring-free shared contract/admission records where parity is proven;
- do not combine unrelated feature changes.

**Acceptance criteria:**

1. ADR and compatibility spike prove the selected JMESPath/Jackson path before the parent version
   changes.
2. Gateway unit and integration `mvn verify` pass without unexpected skips.
3. Existing API smoke, byte-exact SSE, authorization, coverage, orchestration and resilience behavior
   match.
4. World-B is CRITICAL 0.
5. Dependency/security scan has no unresolved release blocker.
6. No ingestion endpoint/bean/index-writer authority is introduced.
7. Migration rollback procedure is documented and rehearsed before catalog integration.

**Harness:** H9 full gateway/IAM/relevant compose regression and dependency report.

## M4-S07 — Add immutable catalog read and conversation pinning

**Outcome:** Every gateway request uses exactly one activated tenant catalog version.

**Dependencies:** M4-S05 and M4-S06.

**Scope:**

- read-only immutable catalog snapshot client/store;
- active version resolution by verified tenant/environment;
- capture once per request and thread through classification, entity extraction, routing,
  authorization, DAG planning/execution and telemetry;
- conversation catalog pin from BFF with drain/expiry behavior;
- readiness/acknowledgment and cache invalidation;
- no “current” reread within a request.

**Acceptance criteria:**

1. A forced activation during an in-flight request cannot mix old/new records or index.
2. Conversation continues on pinned version during allowed drain window.
3. Retired/expired version fails closed with resumable catalog-changed response.
4. Tenant A cannot select/read tenant B catalog/index.
5. Structural authorization, coverage and DAG-node checks use the same captured snapshot.
6. Gateway has read credentials only and cannot invoke ingestion mutation APIs.

**Harness:** H9 concurrency, pin/drain, tenant, authorization/coverage and readiness tests.

## M4-S08 — Prove sandbox activation, tenant isolation and rollback

**Outcome:** The complete flagship story reaches runtime and safely returns to baseline.

**Dependencies:** M4-S07.

**Journey:**

1. Import baseline and generate Insurance renewal-risk package through Studio.
2. Certify and approve the exact hash.
3. External ingestion dry-runs, introspects, materializes and shadow-tests it.
4. Activate with expected base hash.
5. Gateway/BFF pin and serve the new Use Case.
6. Show trace containing Business Line, Use Case, route target, goal capability and catalog hash.
7. Prove unrelated and second-tenant behavior unchanged.
8. Roll back and show the prior behavior/hash restored.

**Acceptance criteria:**

1. One package hash appears end to end in dossier, package, certification, approval, ingestion,
   activation, gateway trace and rollback evidence.
2. Before activation the new route abstains/does not exist; after activation it routes as approved;
   after rollback baseline behavior returns.
3. Concurrent/in-flight requests never show mixed versions.
4. Cross-tenant, denial, coverage outage and DAG-added producer tests fail closed.
5. Full relevant `scripts/verify.sh`, World-B, Studio, ingestion and Playwright/eval gates pass.
6. Runbook succeeds from a clean production-like sandbox with retained evidence manifest.

**Harness:** H10 M4 plus all H8/H9 release gates.

## M4-S09 — Produce release image and supply-chain evidence

**Outcome:** Exact runtime images/profiles are identifiable, scannable, reproducible and rollback-ready.

**Dependencies:** M4-S08. Image/base/Compose files are integration-owner-only.

**Scope:** Record JAR/UI/package/image hashes, OCI digests, base identities, SBOMs, dependency trees,
vulnerability results, registry/gateway profile bean inventories and prior-image rollback evidence.
Reuse the current shared gateway image/profile pattern unless an approved ADR chose extraction.

**Acceptance criteria:**

1. Evidence names immutable image/package identities, never `latest` alone.
2. Registry profile contains mutation beans/credentials; gateway profile demonstrably does not.
3. Runtime images contain no source, build cache, secrets, registry contents or tenant package.
4. Release severity policy is green or every exception has owner/expiry.
5. Previous runtime image plus previous catalog restores service from the runbook.
6. Targeted rebuild reuses unchanged base/dependency layers without forced pull.

**Harness:** H11 release image, supply-chain, profile parity and rollback gate.

## Milestone definition of done

All nine stories are `DONE`; G4 passes from one documented command/runbook; external ingestion alone
holds mutation authority; gateway is Spring Boot 4 and read-only for catalogs; activation and rollback
receipts are immutable; and the full system proves one pinned catalog hash without tenant or safety
regression.
