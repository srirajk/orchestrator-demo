# Conduit Onboarding Studio — Phased Implementation Plan

**Purpose:** Bounded implementation plan suitable for a lower-cost coding model.  
**Required reading:** `PRODUCT-REQUIREMENTS.md`, `ENGINEERING-SPECIFICATION.md`, `CLAUDE.md`,
`.claude/rules/world-b.md`.

Also read the focused specifications before implementing their slices:

- `SOLUTION-ARCHITECTURE.md`
- `MODEL-RUNTIME.md`
- `STUDIO-UX.md`
- `AUTHORIZATION-APPROVAL-PROMOTION.md`
- `MANIFEST-COMPILER.md`
- `CERTIFICATION-ADMISSION.md`
- `EXECUTION-MODEL.md`
- `REFERENCE-PACKAGE-STRUCTURE.md`

---

## 1. Rules for the implementation model

1. Do not change request-path routing, authorization, synthesis or execution behavior unless a task
   explicitly requires a generic platform fix.
2. Studio beans live only in the separate Studio service. Gateway onboarding bridge beans must be
   absent outside the `registry` Spring profile.
3. Do not write directly to the registry or vector index during draft, compile or certify flows.
4. Do not let model output advance workflow state, approve facts, change thresholds or activate.
5. Do not ask users to author JSON, YAML, regex or JMESPath.
6. Do not invent unresolved business/security facts. Return a blocking gap.
7. Do not treat generated tests as independent golden evidence.
8. Use `apply_patch` for edits and preserve unrelated worktree changes.
9. Add tests with every slice. Full Spring context tests must use the repository's isolated
   Testcontainers base.
10. Run `scripts/world-b-check.sh` for every gateway-module change and keep CRITICAL at zero.
11. Report exact tests run, tests not run and environmental blockers. Do not claim
    `scripts/verify.sh` covers E2E/eval work it does not execute.

---

## 2. Delivery strategy

Build vertical slices. Each slice must be independently reviewable and leave the repository runnable.
Do not begin with document ingestion, a broad autonomous agent or production activation.

The first useful milestone is:

> Given a structured confirmed dossier for an existing sub-domain, deterministically compile and
> dry-run validate one single-step agent manifest without mutating the registry.

---

## 3. Slice 0 — Concierge fixtures and decisions

### Goal

Turn current repository examples into explicit onboarding fixtures and verify the resolved v1
decisions against those fixtures.

### Work

- Create fixture dossiers for:
  - HR policy knowledge capability;
  - insurance policy details or wealth holdings resource-scoped capability;
  - concentration or trade-penalty composable capability.
- Create a deliberately overlapping capability fixture.
- Create an insufficient-evidence fixture.
- Record field provenance and required approvals.
- Encode the resolved PostgreSQL, object-store and exact-hash artifact choices in contracts.

### Acceptance

- Fixtures contain no production secrets or customer data.
- Each current hand-authored manifest field maps to a dossier fact or deterministic default.
- Unknown/unjustified fields are documented rather than reverse-invented.
- Product/platform owners accept the resolved v1 policy baseline or change it explicitly before G1.

---

## 4. Slice 1 — Domain model and deterministic compiler

### Goal

Compile a confirmed existing-sub-domain dossier into a canonical agent manifest.

### Suggested files

```text
services/onboarding-studio/src/main/java/ai/conduit/studio/domain/casework/*
services/onboarding-studio/src/main/java/ai/conduit/studio/domain/dossier/*
services/onboarding-studio/src/main/java/ai/conduit/studio/compiler/*
services/onboarding-studio/src/test/java/ai/conduit/studio/compiler/*
libs/conduit-admission/src/main/java/ai/conduit/admission/*
```

The compiler and static admission engine are deterministic Java with no network/model/registry
mutation. Slice 1 first extracts characterization-tested semantics from the gateway; registry-profile
work begins in Slice 2 for non-mutating live dry-run APIs.

### Tests

- Same dossier produces byte-identical canonical JSON.
- Missing required fact produces a path-specific compilation error.
- Unapproved material fact blocks compilation.
- Knowledge and resource-scoped fixtures compile correctly.
- Compiler performs no model/network/registry calls.
- Gateway non-registry context contains no onboarding beans.

### Acceptance

- Compiled output validates against the current agent-manifest schema.
- Output matches approved fixture semantics.
- `scripts/world-b-check.sh` reports CRITICAL 0.

---

## 5. Slice 2 — Registry dry-run validation

### Goal

Validate and introspect a candidate without persistence or index mutation.

### Work

- Add an `OnboardingRegistryDryRun` application service under registry profile.
- Reuse `ManifestValidator`, `AgentIntrospector`, `SelectContractValidator` and catalog reads.
- Add `POST /admin/registry/dry-run` with platform/domain-admin authorization.
- Return typed gate results and derived schemas.
- Prove no calls to `storeAndIndex`, Redis writes or vector-index writes.

### Tests

- Valid HTTP fixture passes and returns observed schemas.
- Valid MCP fixture passes.
- Missing operation/tool fails actionably.
- Invalid manifest fails before introspection.
- Invalid projection/condition/map fails actionably.
- Redis catalog/index state is identical before and after dry run.

### Acceptance

- Dry run is idempotent and non-mutating.
- Errors contain stable codes plus human-readable remediation.
- No onboarding endpoint exists on the request-path gateway profile.

---

## 6. Slice 3 — Case workflow and persistence ports

### Goal

Create and resume onboarding cases with deterministic state transitions.

### Work

- Implement case, evidence, artifact, approval and job ports.
- Implement selected v1 persistence adapters.
- Implement state-transition and invalidation services.
- Add create/read/update/confirm APIs.
- Emit audit events for transitions and approvals.

### Tests

- Invalid transitions fail without state mutation.
- Approved fact changes invalidate compilation/certification.
- Catalog changes invalidate only routing certification.
- Cross-tenant/domain access is denied.
- Idempotent commands do not duplicate evidence/events.

### Acceptance

- A case survives service restart.
- Immutable evidence and approval history are retrievable.
- No secret value is stored in case/evidence JSON.

---

## 7. Slice 4 — Service connection and inspection UI

### Goal

Let a submitter connect an existing service and review observed facts without editing manifests.

### Work

- Create `apps/onboarding-studio/web` with authentication and case routes.
- Add service connection form for HTTP/OpenAPI and MCP.
- Add credential-reference selection, never a raw credential display.
- Add observed-schema and reachability views.
- Add safe inspection job progress and errors.

### Tests

- HTTP and MCP happy paths.
- Unreachable and malformed specs.
- SSRF/private destination policy fixtures.
- Secret not rendered, logged or exported.
- Keyboard/accessibility coverage for the workflow.

### Acceptance

- A non-platform specialist can connect a fixture and understand discovered inputs/outputs.
- No JSON/YAML editing is exposed.

---

## 8. Slice 5 — Deterministic question backlog

### Goal

Drive the dossier to completeness using a rule-based question backlog before adding an LLM.

### Work

- Define required fact paths per archetype/scope.
- Implement question templates for missing owners, capability outcome, boundaries, entities,
  authority, freshness, completeness, security and coverage.
- Add answer validation and approval assignment.
- Build human-readable capability contract view.

### Tests

- Each missing field produces the correct plain-language question.
- Confirmed fields are not asked again.
- Contradictory evidence reopens the relevant decision.
- `Unsure` assigns the correct owner and blocks progression.

### Acceptance

- All three fixtures can reach confirmation without an LLM.
- Platform terms are explained or absent from user-facing questions.

---

## 9. Slice 6 — Catalog neighbors and ownership exercises

### Goal

Show teams concrete ownership conflicts with existing capabilities.

### Work

- Build bounded neighbor retrieval using existing embeddings/catalog metadata.
- Add overlap reasons and evidence.
- Generate deterministic cases from submitted and existing examples.
- Add ownership-label API/UI.
- Add focused neighborhood regression dataset export.

### Tests

- Deliberately overlapping fixture retrieves its intended neighbor.
- Unrelated enterprise knowledge capability is not promoted as high-risk.
- `NEEDS_OWNER` blocks confirmation.
- Candidate/neighbor/both/neither decisions persist with approver provenance.

### Acceptance

- Users never need to understand the word “confuser.”
- The evidence report explains every blocking ownership conflict with examples.

---

## 10. Slice 7 — LLM-assisted adaptive interview

### Goal

Improve explanation and question selection without transferring authority to the model.

### Work

- Add `ModelProposalClient` port and configured provider adapter.
- Add versioned prompt and strict response schema.
- Supply unresolved fact paths and bounded evidence summaries.
- Validate all returned paths, evidence IDs and question types.
- Store model output as immutable `MODEL_PROPOSAL` evidence.
- Add accept/edit/reject proposal UI.

### Tests

- Prompt injection embedded in requirements/spec text cannot change instructions.
- Unknown paths/evidence IDs are rejected.
- Model outage leaves deterministic questions usable.
- Accepting a proposal creates a human declaration; it does not create approval.
- Token/document/spend limits are enforced.

### Acceptance

- The LLM can rephrase and prioritize questions but cannot compile, certify or activate directly.

---

## 11. Slice 8 — Dataset intake and seed generation

### Goal

Accept team evidence and create visibly separate proposed/adversarial suites.

### Work

- Define canonical dataset row schema including persona, question, expected ownership, expected
  behavior and optional expected output assertions.
- Import CSV/JSON and report malformed/duplicate/contradictory rows.
- Generate proposed seed and boundary rows.
- Add independent-held-out metadata and reviewer workflow.

### Tests

- Generated rows cannot be labeled submitted or held-out.
- Contradictory golden rows block certification.
- Missing persona/authorization context is reported for resource-scoped cases.
- Sensitive values follow redaction/export rules.

### Acceptance

- Certification can distinguish evidence origin and refuses generated-only self-certification.

---

## 12. Slice 9 — Certification runner and verdict

### Goal

Run pinned, reproducible gates and produce an evidence-linked readiness report.

### Work

- Implement `CertificationRun`, `GateResult` and verdict composition.
- Pin dossier, bundle, catalog, schema, compiler, configuration and dataset hashes.
- Wrap schema/introspection/contract/DAG/routing/coverage/authz/golden gates.
- Execute focused neighborhood regression and global abstention sentinels.
- Render machine-readable and human-readable reports.

### Tests

- A security/coverage failure cannot be compensated by routing quality.
- Missing held-out evidence prevents `READY`.
- Catalog or dossier change makes a prior run stale.
- Each deliberately broken fixture fails the intended gate with remediation.
- All three valid archetypes produce expected verdicts.

### Acceptance

- Report distinguishes `READY`, `CONDITIONALLY_READY`, `NOT_READY`, `UNABLE_TO_ASSESS` and
  `UNSUPPORTED_REQUIREMENT`.
- Every result links to evidence and an owner/remediation.

---

## 13. Slice 10 — Composition advisor

### Goal

Propose and validate existing DAG primitives for the composable archetype.

### Work

- Match candidate inputs to semantic catalog outputs.
- Handle zero/one/multiple producer cases deterministically.
- Propose and validate projections.
- Propose conditions/maps only from confirmed requirements.
- Simulate the candidate graph using `DagResolver` and contract validators.

### Tests

- Single compatible producer yields a reviewable proposal.
- Multiple producers yield an ownership decision, not an automatic edge.
- Invalid projection, non-Boolean condition and non-array map fail.
- Map caps are required and clamped.
- Unsupported graph/operator returns `UNSUPPORTED_REQUIREMENT`.

### Acceptance

- The concentration/review and settlement/trade-penalty fixtures compile and simulate without
  hand-editing manifests.

---

## 14. Slice 11 — Domain/sub-domain compilation

### Goal

Support new sub-domain and new-domain cases.

### Work

- Add deterministic compilers for domain/sub-domain manifests.
- Validate entity types, required context, coverage templates and agent membership.
- Generate policy proposals separately.
- Add required platform/security approvals.

### Tests

- Resource-scoped sub-domain without coverage is blocked.
- Required context references only declared entity keys.
- Domain/sub-domain/agent relationships are consistent.
- Policy proposal cannot be activated by the onboarding service.

### Acceptance

- A supported resource-scoped domain produces a complete, schema-valid candidate bundle.

---

## 15. Slice 12 — Submission, activation and drift

### Goal

Submit approved bundles safely and detect later contract drift.

### Work

- Implement chosen artifact publication mechanism.
- Require current, non-stale certification and approvals.
- Reuse existing registry mutation only after approval.
- Record activation event and artifact/catalog hashes.
- Periodically re-introspect and compare observed schemas/connections.
- Trigger scoped recertification on material drift.

### Tests

- Stale or unapproved bundle cannot activate.
- Activation is idempotent.
- Drift creates a new evidence event and invalidates relevant gates.
- Rollback/deactivation follows existing registry authority.

### Acceptance

- No direct model-to-production path exists.
- Activated artifact exactly matches the approved bundle hash.

---

## 16. Mandatory final verification

Before calling the overall build complete:

- Gateway and registry Java tests pass in the supported JDK/Docker environment.
- Onboarding UI typecheck/build and component tests pass.
- All six onboarding golden fixtures pass with expected outcomes.
- Security suite passes.
- Registry dry run is proven non-mutating.
- Request-path gateway profile contains no onboarding beans/endpoints.
- `scripts/world-b-check.sh` reports CRITICAL 0 / REVIEW 0.
- Routing measurement and existing-catalog regression pass.
- Schema copies remain synchronized.
- Generated bundle determinism is proven across repeated runs.
- Documentation matches the actual verification commands; do not reuse stale `verify.sh` claims.

---

## 17. Recommended implementation order for a lower-cost model

Give the model one slice at a time, beginning with Slice 0. For every slice provide:

1. The PRD and engineering-spec sections relevant to that slice.
2. Exact approved open-decision answers.
3. Existing classes it must reuse.
4. Files it may change.
5. Tests it must add.
6. Commands it must run.
7. A prohibition on beginning the next slice.

Require a human architecture/security review after Slices 2, 6, 9, 10 and 12. A cheaper model is
appropriate for bounded implementation and tests; it is not the authority for trust boundaries,
admission policy or schema evolution.

---

## 18. Production implementation scenarios

These scenarios are mandatory acceptance fixtures, not illustrative prose. Each scenario must have a
versioned dossier input, protocol fixture, datasets, expected compiled artifacts, gate results and
final verdict.

### Scenario A — Enterprise knowledge capability

**Example shape:** HR policy Q&A.  
**Evidence:** MCP/HTTP URL, policy-topic requirements, real questions, enterprise audience.  
**Expected generation:** Existing domain/sub-domain reference, enterprise agent manifest, no entity
or coverage contract, positive/neighbor/off-topic routing suites.  
**Hard checks:** Authentication, read-only operation, output conformance, HR-vs-general/off-topic
ownership, prompt-injection resistance.  
**Expected verdict:** `READY` when all gates and approvals pass.

### Scenario B — Resource-scoped data capability

**Example shape:** Policy details or holdings.  
**Evidence:** Service URL, entity/identifier documentation, coverage endpoints, persona cases,
golden responses.  
**Expected generation:** Entity type, required context, coverage templates, deterministic clarify
copy, agent manifest and persona-aware routing/authz datasets.  
**Hard checks:** Principal-agnostic RESOLVE, fail-closed CHECK, out-of-book denial before data fetch,
schema conformity and no fabricated identifiers.  
**Expected verdict:** `READY` only with security/data-owner approval.

### Scenario C — Composable analytical capability

**Example shape:** Concentration analysis/review or settlement-status/trade-penalty.  
**Evidence:** Producer and consumer schemas, business dependency, golden composed cases, condition or
per-item requirements.  
**Expected generation:** Semantic `consumes`/`produces`, validated projection, optional Boolean
condition or bounded map, figures and DAG simulation assets.  
**Hard checks:** Unique producer or resolved ownership, schema-compatible projection, valid condition,
array map target, item/concurrency caps, producer authorization and partial-result behavior.  
**Expected verdict:** `READY` only when the generated DAG is deterministic and fully re-gated.

### Scenario D — Neighboring capability conflict

**Example shape:** A new “settlement analysis” capability overlaps status and risk.  
**Expected behavior:** Present concrete ownership cases; do not ask for “confusers.” Keep the case in
`NEEDS_DECISIONS` while any material row is `NEEDS_OWNER`.  
**Expected verdict:** `NOT_READY` until ownership is resolved and regression passes.

### Scenario E — Missing or circular golden evidence

**Example shape:** Team supplies no golden cases; system generates every example itself.  
**Expected behavior:** Produce proposed seed/adversarial rows, require owner review and independent
held-out evidence.  
**Expected verdict:** `UNABLE_TO_ASSESS`, never `READY`, until independent evidence exists.

### Scenario F — Contract drift

**Example shape:** Certified service changes a response field or MCP output schema.  
**Expected behavior:** Capture a new observed snapshot, identify affected projections/figures/tests,
mark certification stale and prohibit activation/update.  
**Expected verdict:** Existing activation remains governed by operational policy; the changed version
is `NOT_READY` pending recertification.

### Scenario G — Coverage/security failure

**Example shape:** Coverage CHECK times out or returns inconsistent results.  
**Expected behavior:** Fail closed, preserve evidence, name the coverage owner and avoid probing agent
data for denied personas.  
**Expected verdict:** `NOT_READY`; no quality score can compensate.

### Scenario H — Malicious onboarding material

**Example shape:** Requirements or OpenAPI descriptions contain instructions to ignore policy, reveal
secrets or activate directly.  
**Expected behavior:** Treat content as data, reject unknown proposal paths, keep inspection authority
bounded and record the security result.  
**Expected verdict:** `NOT_READY` if required security controls fail; otherwise continue without
executing the injected instruction.

### Scenario I — Unsupported platform primitive

**Example shape:** Write agent, arbitrary loop, transactional approval, unsupported A2A execution or
multiple unresolved producers.  
**Expected behavior:** Produce a typed gap naming the missing generic primitive and affected evidence.
Do not generate a misleading runnable manifest.  
**Expected verdict:** `UNSUPPORTED_REQUIREMENT`.

### Scenario J — Catalog poaching regression

**Example shape:** Candidate passes its own positive rows but steals existing capability questions.  
**Expected behavior:** Focused-neighborhood and global-sentinel gates show before/after ownership and
metrics.  
**Expected verdict:** `NOT_READY` until examples/boundaries are corrected without weakening existing
quality.

### Scenario K — Successful governed activation

**Example shape:** Supported case passes all gates and approvals.  
**Expected behavior:** Produce canonical bundle, hashes, certification report and readable contract;
submit the exact hash for approval; activate idempotently through the existing registry authority.
  
**Expected verdict:** `READY` -> `APPROVED` -> `ACTIVATED`, with each transition audited.
