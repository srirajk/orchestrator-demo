# Conduit Onboarding Studio — Reference Package Structure

## 1. Proposed repository layout

```text
pom.xml                                      new Maven aggregator
libs/
  conduit-admission/
    pom.xml
    src/main/java/ai/conduit/admission/
      manifest/                              domain-free manifest records
      schema/                                schema validation contracts
      projection/                            JMESPath projection validation
      composition/                           condition/map/static DAG admission
      result/                                typed issues and summaries
    src/test/java/ai/conduit/admission/
      characterization/
      fixtures/

services/
  onboarding-studio/
    pom.xml
    src/main/java/ai/conduit/studio/
      StudioApplication.java
      config/
      api/
        casework/
        evidence/
        interview/
        compilation/
        certification/
        approval/
        promotion/
        error/
      application/
        command/
        query/
        port/in/
        port/out/
        service/
      domain/
        casework/
        dossier/
        evidence/
        proposal/
        bundle/
        certification/
        approval/
        promotion/
        job/
      compiler/
        mapping/
        canonical/
        provenance/
      certification/
        gate/
        runner/
        verdict/
      modelruntime/
        GuidanceModel.java
        operation/
        output/
        prompt/
        policy/
      infrastructure/
        persistence/jpa/
        objectstore/
        gatewayregistry/
        openai/
        oidc/
        policy/
        outbox/
        telemetry/
    src/main/resources/
      db/migration/
      prompts/
      application.yml
    src/test/java/ai/conduit/studio/
      unit/
      integration/
      contract/
      security/
      architecture/
    src/test/resources/fixtures/

apps/
  onboarding-studio/
    web/
      package.json
      src/
        app/
        api/generated/
        auth/
        components/
        features/
          cases/
          evidence/
          interview/
          capability-review/
          routing-lab/
          composition/
          certification/
          approvals/
          promotion/
        test/

contracts/
  onboarding-studio/
    dossier.schema.json
    evidence.schema.json
    candidate-bundle.schema.json
    certification.schema.json
    studio-api.openapi.yaml
    gateway-onboarding-api.openapi.yaml
    events/

gateway/src/main/java/ai/conduit/gateway/registry/onboarding/
  api/                                       registry-profile controllers
  application/                              dry-run, snapshot, activate use cases
  dto/
  security/

scripts/eval-worker/                        existing Python worker, optional adapter only
```

The aggregator must not change gateway runtime versions: retain Spring Boot 3.5.16, Java 25 runtime
and bytecode target 21. It only coordinates modules and dependency versions.

## 2. Boundary rules

### `conduit-admission`

- no Spring annotations;
- no HTTP, database, Redis, model or environment access;
- no business-domain vocabulary;
- deterministic inputs and typed outputs;
- owns static manifest/projection/condition/map admission semantics;
- does not execute remote agents or mutate a registry.

### Studio domain

- imports no Spring, JPA, HTTP or OpenAI implementation packages;
- owns invariants and state transitions;
- treats model results as proposals;
- approvals always reference immutable hashes.

### Studio application

- depends on domain and ports;
- coordinates transactions and authorization decisions;
- never embeds gateway execution logic;
- only promotion use cases can reach the registry mutation port.

### Infrastructure

- implements ports and contains vendor/framework code;
- OpenAI adapter has no dependency on approval or promotion adapters;
- gateway registry client uses generated/versioned DTOs;
- persistence records and domain objects are mapped explicitly.

### Compiler

- Java, deterministic, no network/model/database calls;
- consumes one immutable confirmed dossier plus compiler-policy version;
- outputs canonical bytes and provenance;
- uses shared manifest records from `conduit-admission`.

### Certification

- can read candidate/evidence/catalog snapshots and call non-mutating probes;
- cannot call activation or update approval records;
- hard-gate failures cannot be overridden by a model score.

### Web

- uses the generated OpenAPI client;
- contains no role or promotion truth beyond presentation;
- never receives registry credentials;
- feature packages do not import one another’s internal state.

## 3. Extraction map from current gateway

| Current code | Destination/relationship |
|---|---|
| `registry.model.AgentManifest` | move-compatible records in `conduit-admission/manifest` |
| `registry.service.ManifestValidator` | static schema portion in `conduit-admission/schema` |
| `registry.service.SelectContractValidator` | `conduit-admission/projection` and `composition` |
| `orchestration.executor.InputContractValidator` | shared static input contract utility |
| `orchestration.executor.Blackboard` | remains runtime; fixtures define parity oracle |
| `orchestration.executor.DagPlanExecutor` | remains gateway runtime; shadow execution is final oracle |
| `registry.introspection.AgentIntrospector` | remains gateway; exposed through dry-run API |
| `registry.service.AgentRegistry` | remains gateway mutation authority |
| `registry.index.VectorIndexWriter` | remains gateway; unreachable from dry-run |

Do not move `DagPlanExecutor` into Studio. Static admission is shared; actual execution remains the
authoritative gateway proof.

## 4. Build dependency direction

```text
conduit-admission <- gateway
conduit-admission <- onboarding-studio
studio-api-contract -> generated web client
gateway-onboarding-contract -> generated Studio client
scripts/eval-worker <- certification job adapter (optional process boundary)
```

No dependency points from gateway to Studio. No dependency points from shared admission to either
application.

## 5. Architecture enforcement

Add ArchUnit tests that fail when:

- domain imports infrastructure/framework packages;
- model runtime imports approval/promotion infrastructure;
- certification imports activation adapters;
- compiler imports network/model/persistence packages;
- gateway imports Studio packages;
- shared admission imports Spring or domain-specific gateway packages.

Add contract tests for generated OpenAPI clients and fixture parity tests for both Java consumers.
