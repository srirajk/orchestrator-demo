# Conduit Onboarding Studio — Certification and Admission Specification

**Status:** Proposed  
**Purpose:** Decide whether an exact candidate bundle can safely join a target Conduit catalog  
**Verdicts:** READY, CONDITIONALLY_READY, NOT_READY, UNABLE_TO_ASSESS, UNSUPPORTED_REQUIREMENT

---

## 1. Certification principle

Certification is evidence-based and pinned. It tests an exact bundle against an exact service,
catalog, policy, dataset and environment snapshot. It never certifies “the latest draft.”

No aggregate score can compensate for a failed security, coverage, schema, approval or contract
gate.

---

## 2. Certification input manifest

```text
caseId, dossierVersion/hash
bundleVersion/hash
targetEnvironment
catalogSnapshot/hash
manifestSchemaVersions/hashes
compilerVersion
serviceSpec/tool-list hashes
coverage/policy snapshot hashes
submitted/adversarial/held-out dataset hashes
gate policy version
model/prompt versions for model-assisted evals
```

Any missing required input produces `UNABLE_TO_ASSESS`; no implicit “current” value is allowed once a
run starts.

---

## 3. Gate classes

### Hard non-compensable

- artifact/schema/referential validity;
- registry dry-run and live introspection;
- request/response contract conformance;
- semantic projection/condition/map validity;
- graph determinism, cycle and bounds;
- authentication and per-hop identity;
- structural authorization;
- entity resolution and fail-closed coverage;
- produced-entity filtering;
- required approvals/evidence separation;
- artifact/policy/catalog freshness;
- prohibited protocol/access/execution primitives.

### Measured threshold

- positive routing accuracy;
- neighbor ownership/confusion;
- off-topic abstention;
- existing-catalog poaching regression;
- golden functional behavior;
- partial-result honesty;
- latency/SLO/rate behavior;
- model-assisted relevance/safety where appropriate.

### Advisory

- documentation completeness;
- optional operational metadata;
- non-blocking cost/usability recommendations.

Advisory warnings cannot conceal or relabel blocking results.

---

## 4. Dataset separation

### Submitted

Real team questions/cases with owner provenance. May be used for manifest design and acceptance.

### Adversarial

Platform-generated and reviewer-approved neighbors, abstentions, ambiguity, injection, failure and
authorization cases. Origin remains visible.

### Held-out

Independent reviewer-owned cases unavailable to manifest example generation and interview model.
Required for READY according to archetype policy.

The same row cannot exist in multiple sets under cosmetic rephrasing. Deduplication uses normalized
text plus semantic similarity review.

---

## 5. Routing admission

Evaluate the candidate in a shadow index built from the target catalog plus candidate bundle.

Measure:

- candidate positive ownership;
- neighbor false-positive/false-negative ownership;
- cross-domain and same-domain poaching;
- abstention on unsupported/off-topic cases;
- clarify behavior on missing required context;
- persona-aware production route path where applicable;
- before/after metrics for every affected neighbor.

The candidate cannot pass solely because its own examples route correctly. Any material existing
capability regression is blocking unless resolved by approved ownership changes and rebaselined
held-out evidence.

---

## 6. Service and contract admission

- Re-fetch pinned protocol document/tool list from allowlisted target.
- Verify selected operation/tool and authentication requirements.
- Derive schemas using registry dry run.
- Execute approved non-production requests.
- Validate live output/error/partial shapes.
- Validate max response/token/size behavior.
- Compare observed contract with candidate and golden evidence.
- Fail on schema drift or unverifiable output used by composition.

MCP annotations and API descriptions are untrusted explanatory evidence, not authority.

---

## 7. Security and coverage admission

Test personas include allowed, structurally denied, out-of-coverage, empty-book, ambiguous reference,
unknown reference, expired identity and coverage-unavailable cases.

Assertions:

- no data call before required coverage allow;
- resolution is principal-agnostic;
- CHECK is authoritative and fails closed;
- denied/filtered produced entities never reach downstream nodes or synthesis;
- agent verifies forwarded identity;
- secrets/tokens do not appear in evidence, trace or response;
- enterprise audience still requires authentication;
- authorization changes have approved policy evidence.

Any failure is blocking and security-owned.

---

## 8. Composition admission

- Unique or explicitly resolved producer for each required semantic type.
- Projection evaluates against real/synthetic producer samples.
- Consumer required input schema is satisfied.
- Condition references existing fields and returns Boolean across true/false/error cases.
- Map target is an array; item projection satisfies input; caps are within global ceilings.
- DAG resolves deterministically without cycles.
- Every resolver-pulled node is re-authorized and re-covered where required.
- Failed/condition-false/truncated upstream behavior matches declared semantics.
- Load-bearing figures resolve to scalar outputs and supported formats.

---

## 9. Functional/golden admission

Prefer deterministic assertions for IDs, fields, status, numerical values, authorization and
required disclosures. Use model judges only for qualities that cannot be captured deterministically,
such as relevance or clarity.

An LLM judge cannot overturn a deterministic failure. The same model family should not be the sole
author and judge of cases. Record judge prompt/model/version and raw reason as evidence.

---

## 10. Certification run and gate result

```text
CertificationRun
  id, pinnedInputs, state, startedAt/completedAt
  gateResults[], metrics, verdict
  runnerVersion, receipts, error

GateResult
  code, class, blocking, status
  summary, evidenceIds, metrics
  remediation, ownerRole
```

Gate status: PASS, FAIL, WARN, NOT_RUN, UNABLE_TO_ASSESS.

---

## 11. Verdict composition

### READY

All required hard gates pass, measured gates meet policy, held-out evidence is sufficient, approvals
are available for next environment and no unsupported requirement remains.

### CONDITIONALLY_READY

Only policy-allowed measured/advisory limitations remain; each has owner, expiry, user impact and
approval. Never used for security, coverage, schema, contract or approval failures.

### NOT_READY

Evidence is sufficient and one or more supported, actionable gates fail.

### UNABLE_TO_ASSESS

Required evidence/service/policy is unavailable or contradictory.

### UNSUPPORTED_REQUIREMENT

The requested capability needs a primitive outside the certified platform contract.

---

## 12. Remediation loop

Every blocking result identifies:

- what failed in business language;
- exact evidence;
- responsible role/team;
- whether the fix belongs in service, dossier, dataset, manifest compiler, policy or platform;
- shortest safe remediation;
- gates invalidated/rerun after change.

Changes create new dossier/bundle versions. Runs are immutable and never edited to “pass.”

---

## 13. Promotion freshness

Before approval and promotion, re-check hashes for bundle, catalog, policy, schemas, service contract
and required datasets. Policy defines age limits for live probes/security evidence. Stale results
cannot be waived through agent prose.

---

## 14. Certification acceptance scenarios

- Valid knowledge capability passes.
- Valid resource-scoped capability passes all persona cases.
- Valid composable capability resolves/executes deterministically.
- Candidate-positive but catalog-poaching case fails.
- Generated-only golden evidence is unable to assess.
- Coverage outage fails closed and blocks.
- Malformed/changed live output blocks.
- Multiple unresolved producers returns unsupported/not ready according to platform policy.
- Prompt injection cannot change a gate.
- Stale catalog/policy invalidates prior certification.

---

## 15. Acceptance criteria

- Same pinned inputs/policy produce reproducible deterministic gate results.
- Every verdict is explainable from gate evidence.
- Non-compensable gates cannot be waived by score/model.
- Existing catalog is protected, not merely candidate accuracy.
- READY identifies the exact promotable bundle hash.
- Certification runner has no promotion authority.
