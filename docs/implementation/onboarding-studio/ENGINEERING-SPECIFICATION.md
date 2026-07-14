# Conduit Onboarding Studio — Engineering Specification

**Status:** Proposed  
**Depends on:** `PRODUCT-REQUIREMENTS.md`, current registry schemas, gateway registry profile  
**Primary constraint:** Reuse the registry control plane; do not add onboarding logic to the gateway
request path.

This document is the cross-cutting contract. Focused production specifications are authoritative for
their areas:

- `SOLUTION-ARCHITECTURE.md`
- `MODEL-RUNTIME.md`
- `STUDIO-UX.md`
- `AUTHORIZATION-APPROVAL-PROMOTION.md`
- `MANIFEST-COMPILER.md`
- `CERTIFICATION-ADMISSION.md`

---

## 1. System boundary

The onboarding agent is a durable control-plane workflow, not a chat-only bot and not a runtime
planner.

```text
Onboarding Web UI
      |
      v
Onboarding API / Workflow  -- LLM --> interview + proposals
      |       |       |
      |       |       +--> Evidence store / approvals
      |       +----------> Catalog-neighbor + certification runner
      +------------------> Deterministic dossier compiler
                               |
                               v
                     Candidate manifest/eval bundle
                               |
                         platform approval
                               |
                               v
                     existing gateway registry ingest
```

### Recommended v1 placement

Implement a separate Java/Spring Boot Studio control plane plus a separate React Studio UI. Extract
the gateway's manifest, JMESPath projection, condition and bounded-map admission semantics into a
shared Java module used by both Studio and gateway. Keep live execution probes and registry mutation
behind narrow gateway registry-profile APIs. This preserves one executable definition of
composition without putting long-running Studio workflow state in the request-path gateway.

Do not add the UI to the Axiom identity admin console. Axiom owns identity governance; Studio owns
Conduit capability admission.

Recommended repository layout:

```text
services/onboarding-studio/src/main/java/ai/conduit/studio/
  api/
  application/
  domain/
  infrastructure/
  modelruntime/
  compiler/
  certification/
  promotion/

apps/onboarding-studio/web/
  src/features/cases/
  src/features/evidence/
  src/features/review/
  src/features/certification/

gateway/src/main/java/ai/conduit/gateway/registry/onboarding/
  api/
  service/
```

Shared admission code is domain-free and has no Spring, network, persistence or model dependency.
Studio and gateway both depend on it. Live gateway capabilities remain behind versioned APIs. Within
Studio, persistence, model calls, document extraction and artifact publication remain behind ports.

---

## 2. Trust model

The implementation maintains five classes of information:

| Class | Meaning | May the model change it? |
|---|---|---|
| `OBSERVED` | Raw service/document/probe evidence | No |
| `DECLARED` | A human's business or governance assertion | Only by creating a proposal requiring confirmation |
| `DERIVED` | Platform analysis such as neighbors or candidate mappings | Recomputable; never silently promoted |
| `DEFAULTED` | Schema/compiler default | Yes, deterministically from versioned config |
| `APPROVED` | A declared/derived decision signed by an authorized reviewer | No; later changes create a new version |

The LLM never writes an approved fact. It produces typed proposals referencing evidence IDs. A
human decision or deterministic validator changes workflow state.

---

## 3. Canonical source model

### 3.1 `OnboardingCase`

```text
id                    UUID
tenantId              string
scope                 EXISTING_SUBDOMAIN | NEW_SUBDOMAIN | NEW_DOMAIN
archetype             KNOWLEDGE | RESOURCE_SCOPED | COMPOSABLE
status                state enum
dossierVersion        integer
schemaVersion         string
catalogSnapshotId     string
createdBy/createdAt
updatedBy/updatedAt
```

### 3.2 `CapabilityDossier`

The dossier is versioned structured data. Minimum sections:

```text
identity
  proposedAgentId, name, version, provider
  businessOwner, technicalOwner, securityOwner

service
  protocol, specUrl/serverUrl, operationId/tool
  credentialReference, environment
  observedInputSchema, observedOutputSchema, resolvedConnection
  errorSchemas, probeEvidenceIds

businessContract
  outcome, authorityKind, sourceOfRecord
  inScopeQuestions, outOfScopeQuestions
  freshness, completeness, partialResultSemantics
  vocabulary[], synonyms[]

domainContract
  domainId, subDomainId, audience, resourceScoped
  entityTypes[], requiredContext[]
  coverageContract
  clarification/denial intent (plain meaning, not generated copy only)

routingContract
  positiveExamples[]
  negativeExamples[]
  ownershipDecisions[]
  neighborAssessments[]

dataflowContract
  consumes[]
  produces[]
  figures[]
  conditionProposal
  mapProposal

governance
  accessMode, dataClassification, sla, rateLimit
  approvals[]

evidence
  requirementsDocuments[]
  submittedDataset
  adversarialDataset
  heldOutDatasetReferences
```

Every material scalar or collection item is represented as a `Fact<T>`:

```json
{
  "value": "confidential",
  "provenance": "DECLARED",
  "sourceIds": ["answer-42"],
  "confidence": null,
  "approval": {"requiredRole": "security_owner", "status": "APPROVED"}
}
```

Confidence is diagnostic only and can never replace required approval.

### 3.3 Evidence objects

Evidence is immutable and content-addressed:

```text
EvidenceItem
  id, caseId, kind, sha256, mediaType
  capturedAt, capturedBy
  sourceUri (redacted where necessary)
  storageReference
  metadata
```

Kinds include `REQUIREMENTS_DOCUMENT`, `OPENAPI_SNAPSHOT`, `MCP_TOOLS_LIST`, `LIVE_REQUEST`,
`LIVE_RESPONSE`, `HUMAN_ANSWER`, `GOLDEN_DATASET`, `MODEL_PROPOSAL`, `VALIDATION_REPORT` and
`APPROVAL_EVENT`.

---

## 4. Workflow state machine

```text
DRAFT
  -> INSPECTING
  -> NEEDS_EVIDENCE
  -> INTERVIEWING
  -> NEEDS_DECISIONS
  -> READY_FOR_CONFIRMATION
  -> CONFIRMED
  -> COMPILING
  -> CERTIFYING
  -> READY | CONDITIONALLY_READY | NOT_READY
  -> SUBMITTED_FOR_APPROVAL
  -> APPROVED
  -> ACTIVATED
```

Terminal side states:

- `UNABLE_TO_ASSESS`
- `UNSUPPORTED_REQUIREMENT`
- `CANCELLED`
- `SUPERSEDED`

Rules:

- States advance only through application-service commands.
- Model output never directly advances state.
- Any changed approved fact increments `dossierVersion`, invalidates later approvals and marks prior
  certification stale.
- A changed catalog snapshot invalidates routing certification, not the entire dossier.
- A changed service schema invalidates contract and composition certification.
- Every transition emits an immutable audit event.

---

## 5. API contract

All endpoints require authenticated users. Tenant/domain authorization is enforced before case data
is read. Illustrative v1 API:

```text
POST   /admin/onboarding/cases
GET    /admin/onboarding/cases
GET    /admin/onboarding/cases/{caseId}
POST   /admin/onboarding/cases/{caseId}/documents
POST   /admin/onboarding/cases/{caseId}/service/inspect
POST   /admin/onboarding/cases/{caseId}/service/probe
GET    /admin/onboarding/cases/{caseId}/questions
POST   /admin/onboarding/cases/{caseId}/answers
GET    /admin/onboarding/cases/{caseId}/neighbors
POST   /admin/onboarding/cases/{caseId}/ownership-decisions
POST   /admin/onboarding/cases/{caseId}/confirm
POST   /admin/onboarding/cases/{caseId}/compile
POST   /admin/onboarding/cases/{caseId}/certifications
GET    /admin/onboarding/cases/{caseId}/certifications/{runId}
GET    /admin/onboarding/cases/{caseId}/bundle
POST   /admin/onboarding/cases/{caseId}/submit
```

Long-running inspection and certification return `202 Accepted` with a job ID. Job reads expose
state, bounded progress and evidence references. Retry uses the same idempotency key.

### Dry-run registry endpoint

Add a registry-profile-only dry-run application service that calls existing derivation and contract
validators without `storeAndIndex`:

```text
POST /admin/registry/dry-run
  -> schema validation
  -> live introspection
  -> domain/sub-domain referential validation
  -> semantic select/condition/map validation against candidate catalog
  -> no Redis manifest write
  -> no vector index write
```

Do not implement dry run by calling the existing mutating registration endpoint and rolling back.

---

## 6. Interview engine

### 6.1 Inputs

- Current dossier and unresolved required fields.
- Evidence summaries, not raw secrets.
- Catalog neighbor summaries.
- Versioned question policy.
- Supported-archetype rules.

### 6.2 Outputs

The model returns schema-constrained proposals only:

```json
{
  "questions": [
    {
      "id": "ownership.failure_reason",
      "plainQuestion": "Should your service explain why a trade failed, or only estimate its penalty?",
      "whyAsked": "The existing settlement-status capability already explains failure reasons.",
      "answerType": "SINGLE_CHOICE",
      "choices": ["Explain failures", "Estimate penalties", "Both", "Unsure"],
      "factPaths": ["businessContract.outcome", "routingContract.ownershipDecisions"],
      "evidenceIds": ["neighbor-17"]
    }
  ],
  "proposals": []
}
```

### 6.3 Question policy

- Ask only questions tied to unresolved or conflicting dossier paths.
- Prefer concrete contrastive examples over platform terminology.
- Always offer `Unsure / needs another owner` for governance questions.
- Never ask users to write JSON, regex, JMESPath or routing jargon.
- Explain why an answer matters and which gate consumes it.
- Do not repeat a confirmed answer unless new contradictory evidence exists.
- Cap questions per interaction; persist progress.

### 6.4 Deterministic post-validation

Reject model output containing unknown fact paths, unknown evidence IDs, unsupported answer types,
unbounded free-text requests for secrets, or questions unrelated to an unresolved field.

---

## 7. Service inspection and probes

Reuse `AgentIntrospector` and `McpToolIntrospector` through a non-mutating adapter. Store raw specs
as evidence and normalized schemas as observed dossier facts.

V1 probes are read-only and explicitly approved. Requirements:

- allowlisted URL schemes and destinations;
- SSRF protection and DNS/IP revalidation;
- credential references resolved server-side;
- response size and time limits;
- no redirect to an unapproved host;
- complete request/response evidence with configured redaction;
- no production endpoint by default;
- no mutating operation probes in v1.

Introspection failure produces a gap; it never causes the model to fabricate a schema.

---

## 8. Catalog neighbor and ownership analysis

“Neighbor” is a platform implementation concept. Users see ownership choices.

### 8.1 Candidate retrieval

Retrieve a bounded candidate neighborhood using:

- embeddings over candidate capability description, examples and business outcome;
- shared domain/sub-domain;
- overlapping tags and entity requirements;
- overlapping semantic `produces.type`/`consumes.from` values;
- lexical overlap in submitted real questions.

Do not use one similarity threshold as an ownership decision. Retrieval only produces candidates for
analysis.

### 8.2 Contrastive case generation

For each high-risk neighbor, assemble cases from:

- submitted real questions;
- both manifests' existing examples;
- held-out catalog cases where available;
- model-proposed boundary cases labeled as generated.

The domain owner labels each case `CANDIDATE`, `NEIGHBOR`, `BOTH_SEQUENCE`, `NEITHER` or
`NEEDS_OWNER`. `NEEDS_OWNER` blocks admission until resolved or explicitly excluded with approval.

### 8.3 Regression scope

Run focused regression over the semantic neighborhood plus global abstention sentinels. Periodically
run a full-fleet gate asynchronously. This avoids requiring an O(N²) synchronous test for every
draft while preventing local self-certification.

---

## 9. Deterministic manifest compiler

### 9.1 Compiler contract

```text
compile(confirmedDossier, schemaVersion, compilerVersion)
  -> CandidateBundle | CompilationErrors
```

The compiler:

- performs no model calls;
- sorts keys and sets canonically;
- uses explicit mapping functions per artifact/version;
- refuses unresolved required facts;
- refuses unsupported archetype features;
- attaches provenance in the bundle metadata, not fields unsupported by current manifest schemas;
- produces byte-identical output for identical inputs;
- never writes to the registry.

### 9.2 Artifact mappings

**Agent manifest** comes from confirmed identity, service, business, routing, dataflow and governance
sections. Wire schemas remain introspected/derived; optional output-schema fallback is used only when
the existing registry contract permits it.

**Sub-domain manifest** comes from confirmed entity, required-context, resource-scope,
clarification, denial and membership decisions.

**Domain manifest** comes from domain identity, context, coverage and governed-memory decisions.
Existing domains are referenced, not regenerated, unless the case scope explicitly changes them.

**Eval assets** preserve provenance classes. Generated adversarial rows must never be serialized as
team-provided golden rows.

**Policy proposal** is a separate artifact. The compiler does not merge it into Cerbos policy and
cannot approve it.

### 9.3 Human-readable projection

Generate a stable capability contract view from the dossier. The UI and approval record use this
projection so reviewers do not need to read JSON.

---

## 10. Composition advisor

V1 composition proposals are constrained:

### Entity leaf

Propose when a required wire input corresponds to a confirmed sub-domain entity key.

### Producer dependency

Propose when exactly one catalog capability declares a semantically compatible output and a sample
projection can satisfy the candidate's introspected input schema. Multiple producers produce an
ownership question, never an automatic edge.

### Projection

Generate a JMESPath candidate from schema fields, then validate it using the existing
`SelectContractValidator`. A validated expression remains a proposal until the owner confirms the
business mapping.

### Condition

Propose only when confirmed requirements describe applicability and the referenced fields exist in
the merged input schema. Validation must prove Boolean evaluation on synthesized and supplied cases.

### Map

Propose only when confirmed requirements describe per-item behavior, the target resolves to an
array, and per-item input validates. Require explicit item and concurrency caps clamped by platform
ceilings.

The advisor never introduces arbitrary code, loops, branches or agents not in the catalog.

---

## 11. Certification architecture

### 11.1 Gate result

```text
GateResult
  gateId
  category
  blocking
  status: PASS | FAIL | WARN | NOT_RUN | UNABLE_TO_ASSESS
  summary
  evidenceIds[]
  remediation
  ownerRole
  metrics{}
```

### 11.2 Certification run

A run pins:

- dossier version and hash;
- candidate bundle hash;
- catalog snapshot/hash;
- manifest schema versions;
- compiler and question-policy versions;
- dataset hashes;
- model IDs for model-assisted evaluators;
- deterministic gate configuration.

### 11.3 Gate implementations

Reuse or wrap:

- `ManifestValidator` and domain-manifest validation;
- `AgentIntrospector` / `McpToolIntrospector`;
- `SelectContractValidator`;
- `DagResolver` for graph simulation;
- `scripts/world-b-check.sh` for repository changes, not per-case semantics;
- the goal-pick measurement machinery for routing tests;
- coverage and Cerbos golden evaluators;
- existing agent verification/eval patterns.

The onboarding runner must not depend on `scripts/verify.sh` as proof of full certification because
that script currently does not run every claimed E2E/eval gate. Invoke and record each required gate
explicitly.

### 11.4 Verdict composition

Security, coverage, schema, contract, required approvals and held-out-evidence gates are
non-compensable. Any blocking `FAIL`, `NOT_RUN` or `UNABLE_TO_ASSESS` prevents `READY`.

Other quality thresholds are versioned configuration. `CONDITIONALLY_READY` requires an explicit
limitation policy and approver; it cannot waive a non-compensable gate.

---

## 12. Persistence and artifacts

Define ports:

```text
OnboardingCaseRepository
EvidenceStore
ArtifactStore
ApprovalStore
JobRepository
CatalogSnapshotProvider
ModelProposalClient
```

PostgreSQL is the v1 system of record for cases, dossier versions, jobs, approvals and promotion
history. Evidence and candidate bundles use durable object storage with content hashes. Redis may be
used only for cache, lease or ephemeral coordination; do not store raw documents, credentials or
authoritative workflow state in Redis.

Bundles are immutable. A correction creates a new dossier/bundle version. Never overwrite evidence
used by an earlier verdict.

---

## 13. Authorization

Suggested roles:

- `onboarding_submitter`
- `domain_owner`
- `security_reviewer`
- `platform_reviewer`
- `platform_admin`

Domain-scoped users may view and change only cases in authorized domains. New-domain cases require a
platform reviewer from creation. Approval uses separation of duties: the submitter cannot provide
the only platform/security approval.

Service credentials are opaque references resolved by a secret provider. They never appear in model
prompts, evidence payloads, logs, exports or manifests.

---

## 14. LLM safety and reproducibility

- Use schema-constrained output for questions and proposals.
- Treat documents, OpenAPI descriptions and agent responses as untrusted data, never instructions.
- Include a fixed instruction hierarchy.
- Redact configured sensitive fields before model calls.
- Record prompt-template version, model and output hash.
- Bound documents, tokens, retries and spend per case.
- A model outage pauses proposal work but does not invalidate captured evidence or deterministic
  compilation/certification.
- No LLM output can approve, activate, lower thresholds or change a gate result.

---

## 15. Observability

Emit metrics and structured events for:

- cases by state/archetype/domain;
- time in each state;
- inspection/probe success and latency;
- questions asked, answered, deferred and reopened;
- proposal acceptance/correction rates;
- compilation failures by field/gate;
- certification gate outcomes;
- routing regression and neighbor conflicts;
- model token/cost/latency by workflow stage;
- dossier, bundle and catalog hashes;
- approval and activation events.

Do not expose business documents, sample customer data, secrets or raw prompts in general-purpose
metrics/logs.

---

## 16. Testing strategy

### Unit

- State transitions and invalidation rules.
- Fact provenance and approval enforcement.
- Question-output validation.
- Canonical deterministic compilation.
- Dossier-to-manifest mappings.
- Verdict composition.
- Neighbor-label recording.

### Contract

- HTTP/OpenAPI fixture inspection.
- MCP fixture inspection.
- Broken/unreachable/malicious spec fixtures.
- Live response schema drift.
- Registry dry-run no-mutation proof.

### Golden onboarding fixtures

1. Enterprise knowledge fixture.
2. Resource-scoped fixture with coverage.
3. Composable DAG fixture.
4. Deliberately overlapping neighbor fixture.
5. Poorly documented service that ends `UNABLE_TO_ASSESS`.
6. Unsupported write agent that ends `UNSUPPORTED_REQUIREMENT`.

### Security

- SSRF and redirect escape.
- Prompt injection in documents/spec descriptions.
- Cross-tenant case/evidence access.
- Secret leakage in prompts/logs/bundles.
- Approval self-dealing.
- Attempted activation with stale certification.

### End-to-end

For each supported archetype, start from URL + requirements + examples and finish with a correct
candidate bundle and expected verdict without editing JSON/YAML.

---

## 17. Compatibility and migration

- Pin the dossier schema, compiler and manifest schemas in every bundle.
- Provide a dossier migration function between supported versions.
- Recompile and diff artifacts after schema migration; never mutate old bundles.
- Existing hand-authored manifests remain valid and can be imported into a dossier with all unknown
  provenance labeled `IMPORTED_UNVERIFIED` until reviewed.
- Activation must verify that certification used the current required catalog/schema policy.

---

## 18. Resolved v1 product and architecture decisions

1. PostgreSQL is authoritative for workflow state; object storage is authoritative for immutable
   evidence and bundles. Redis is non-authoritative cache/coordination only.
2. Studio publishes a content-addressed downloadable bundle and promotes its exact hash through the
   gateway registry API. Git PR integration and a registry draft store are deferred.
3. V1 accepts text, Markdown, JSON, OpenAPI JSON/YAML and PDF. DOCX and arbitrary archive ingestion
   are deferred until isolated extraction and adversarial-file tests exist.
4. Hard routing policy requires every approved ownership example to select the candidate, every
   approved confuser/negative to reject it, and zero material regressions in the pinned existing
   catalog set. Score margins remain versioned policy because calibration is catalog/model specific.
5. Before production, each capability needs at least 30 owner-approved positive examples, 20
   owner-approved neighboring/confuser examples and 10 unsupported/out-of-scope examples. At least
   20% of each category is held out and controlled by the domain reviewer, not generated by Studio.
6. New-domain policy artifacts are proposals in v1; they require platform and security approval and
   are never installed automatically.
7. `CONDITIONALLY_READY` is allowed in draft/sandbox/staging only. Production requires `READY`.
8. OpenAI access uses an enterprise-approved project and configured retention policy. Credentials,
   raw secrets and unredacted regulated/customer data are excluded from model input. A provider
   policy can disable guidance without disabling deterministic onboarding.
9. A2A inspection is deferred. V1 supports the repository's implemented HTTP/OpenAPI and MCP paths;
   A2A requests produce `UNSUPPORTED_REQUIREMENT`.
10. Platform operations owns drift scheduling and alerting; the domain owner owns business remediation;
    security owns authorization/coverage remediation.
11. V1 asynchronous work uses a PostgreSQL outbox/job table with leased workers and `SKIP LOCKED`-style
    claiming. No external broker is required for v1.

Thresholds and minimums are versioned admission policy. Changing them requires policy review,
re-baselining and a new certification run; implementation code must not hardcode alternatives.

---

## 19. Standards baseline and design crosswalk

The onboarding product is Conduit-specific, but its discovery, evidence and governance contracts
must align with established standards rather than inventing incompatible equivalents.

### 19.1 OpenAPI

The [OpenAPI Specification](https://spec.openapis.org/oas/) is authoritative for HTTP operation
discovery. An `operationId` identifies an operation and is expected to be unique within the API;
request parameters/bodies and response schemas are observed evidence, not proof of business meaning.

Conduit requirement:

- Snapshot the exact OpenAPI document and version used for certification.
- Resolve and validate the selected operation deterministically.
- Derive wire schemas while preserving unsupported schema constructs as explicit gaps.
- Require separate owner confirmation for authority, freshness, completeness and business semantics.

### 19.2 Model Context Protocol

The official [MCP tools specification](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
defines tool `inputSchema`, optional `outputSchema` and structured results. It also states that tool
annotations are untrusted unless they come from trusted servers.

Conduit requirement:

- Capture `tools/list` as evidence and pin the negotiated protocol version.
- Validate structured results against `outputSchema` when supplied.
- Treat names, descriptions and annotations as untrusted proposals, not governance facts.
- Mark missing output schemas as reduced evidence and require live response/golden validation.

### 19.3 Agent2Agent Protocol

The [A2A specification](https://a2a-protocol.org/latest/specification) defines Agent Cards containing
identity, capabilities, skills, endpoints and authentication requirements. It supports discovery but
does not replace Conduit's business ownership, coverage, authorization or admission decisions.

Conduit requirement:

- Preserve Agent Card provenance and validate declared security requirements when A2A support lands.
- Never infer Conduit authorization from advertised A2A skills.
- Keep v1 A2A submissions `UNSUPPORTED_REQUIREMENT` until a real introspector/adapter and conformance
  suite exist.

### 19.4 NIST AI RMF

The [NIST AI Risk Management Framework](https://www.nist.gov/itl/ai-risk-management-framework) and
[AI RMF Core](https://airc.nist.gov/airmf-resources/airmf/5-sec-core/) organize risk work around
Govern, Map, Measure and Manage. NIST emphasizes continuous lifecycle risk management rather than a
one-time checklist.

Conduit crosswalk:

| NIST function | Onboarding implementation |
|---|---|
| Govern | Named owners, separation of duties, approval policy, immutable evidence |
| Map | Capability context, authority, entities, users, neighbors, dependencies and impact |
| Measure | Contract probes, golden/held-out tests, routing regression, security and coverage gates |
| Manage | Verdict, remediation, controlled activation, drift detection and recertification |

### 19.5 OWASP agentic security

The [OWASP AI Agent Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/AI_Agent_Security_Cheat_Sheet.html)
and [Securing Agentic Applications Guide](https://genai.owasp.org/resource/securing-agentic-applications-guide-1-0/)
provide the security baseline for untrusted instructions, tool access, least privilege, memory/state,
human oversight and monitoring.

Conduit requirement:

- Treat uploaded documents, service descriptions and responses as untrusted data.
- Give inspection tools allowlisted, read-only, bounded authority.
- Keep model proposals separate from deterministic compilation and approval.
- Test prompt injection, SSRF, excessive agency, cross-tenant access and secret leakage.
- Preserve an auditable human decision for every governance-critical fact.

### 19.6 ISO/IEC 42001 alignment

[ISO/IEC 42001](https://www.iso.org/standard/42001) describes an organizational AI management
system built around policies, objectives, processes, accountability and continual improvement.
Certification against ISO/IEC 42001 is outside v1, but the dossier, approvals, evidence, change
history and recertification model must be exportable as management-system evidence.

---

## 20. Hardness model

The implementation must distinguish three kinds of decision. Mixing them is a release blocker.

### 20.1 Hard deterministic gates

These cannot be overridden by a model score:

- schema and referential validity;
- endpoint/protocol conformance;
- input/output contract compatibility;
- projection/condition/map validity and bounds;
- authentication, authorization and coverage behavior;
- required approval presence and separation of duties;
- artifact/certification hash freshness;
- prohibited/unsupported execution primitives;
- registry dry-run non-mutation.

### 20.2 Measured quality gates

These use datasets and configured thresholds but retain raw evidence:

- positive route accuracy;
- neighbor ownership confusion;
- abstention quality;
- existing-catalog regression;
- golden behavior and partial-result honesty;
- drift from the certified contract.

A threshold change is a governed policy version and invalidates affected certification results.

### 20.3 Human authority decisions

These cannot be discovered reliably from schemas or delegated to the onboarding model:

- business capability ownership;
- source-of-record authority;
- data classification and audience;
- coverage meaning and responsible owner;
- legal/business meaning of a condition;
- acceptable limitations and production activation.

The agent may explain and propose. Only an authorized human can declare/approve.

---

## 21. Manifest-generation agreement

The production generation contract is:

```text
untrusted documents + live protocol evidence + catalog snapshot
                         |
                         v
              model-assisted proposals
                         |
                         v
          typed dossier facts + human decisions
                         |
                         v
               required approvals complete
                         |
                         v
       deterministic versioned artifact compiler
                         |
                         v
          dry-run validators + admission suites
                         |
                         v
         evidence-backed candidate or typed gaps
```

Rules:

1. The model does not directly generate the production artifact accepted by the registry.
2. The compiler consumes only confirmed typed dossier fields and versioned defaults.
3. Every compiled value has provenance through the dossier or compiler version.
4. Observed wire facts never silently become declared business facts.
5. Generated examples remain distinguishable from submitted and held-out evidence.
6. Compiler output is canonical and byte-reproducible.
7. Certification pins every input hash and policy version.
8. Activation accepts only the certified artifact hash.
