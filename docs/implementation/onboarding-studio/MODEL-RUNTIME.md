# Conduit Onboarding Studio — Guidance Model Runtime Specification

**Status:** Proposed production runtime  
**Runtime:** Bounded OpenAI Responses inference behind a Java port  
**Boundary:** Conversational guidance and bounded analysis only

---

## 1. Runtime decision

Use bounded model inference rather than the OpenAI Agents SDK for v1. Studio is a deterministic
Java workflow and the useful model operations are typed suggestions, explanations and summaries;
they do not require autonomous handoffs or a second Python runtime. The adapter uses structured
outputs and records request, model, prompt, schema and usage metadata.

Do not use the model adapter as:

- the onboarding workflow engine;
- the dossier or approval system of record;
- the manifest compiler;
- the certification verdict authority;
- the promotion mechanism.

Studio code wraps OpenAI access behind a `GuidanceModel` Java port so provider/model upgrades do not
change dossier, compiler or certification contracts.

Official references:

- [Responses API](https://platform.openai.com/docs/api-reference/responses)
- [Structured outputs](https://platform.openai.com/docs/guides/structured-outputs)

---

## 2. Runtime topology

Start with one user-facing guidance facade and bounded typed operations. Do not begin with free
handoffs among autonomous agents.

### `OnboardingGuide`

Owns the conversation, explains the process, reads the case summary, asks unresolved questions,
presents proposals and explains certification gaps.

### `CapabilityBoundaryAnalyst` as a tool

Analyzes a bounded candidate-plus-neighbor evidence package and returns ownership exercises. It has
no service-inspection, dossier-mutation or promotion access.

### `CompositionAdvisor` as a tool

Analyzes confirmed schemas/requirements and returns typed entity/dependency/projection/condition/map
proposals. Deterministic validators decide technical validity.

### `EvidenceSummarizer` as a tool

Summarizes a bounded, redacted evidence item into typed claims with citations to evidence offsets or
JSON paths. It cannot establish approval or source-of-record authority.

Operations return structured output to the guide. They do not call one another or control workflow.

---

## 3. Agent context

Runtime context contains references, not secrets or the full system of record:

```java
public record GuidanceContext(
    String tenantId,
    String userId,
    UUID caseId,
    int dossierVersion,
    Set<String> userRoles,
    Set<String> authorizedDomainIds,
    String correlationId
) {
}
```

On every guidance operation, the server re-authorizes the current principal and case. Model request
records must not contain long-lived credentials or raw sensitive documents.

---

## 4. Tool inventory

### Read-only tools available to the guide

- `get_case_summary(case_id)`
- `get_unresolved_questions(case_id, dossier_version)`
- `get_fact_provenance(case_id, fact_paths)`
- `get_redacted_evidence_excerpt(evidence_id, selector)`
- `list_catalog_neighbors(case_id)`
- `get_ownership_exercises(case_id)`
- `get_certification_summary(case_id, run_id)`
- `explain_gate(gate_code, evidence_ids)`

### Proposal tools

- `propose_dossier_patch(case_id, expected_version, proposals[])`
- `propose_ownership_cases(case_id, cases[])`
- `propose_dataset_rows(case_id, provenance, rows[])`
- `propose_composition(case_id, proposal)`

Proposal tools append unapproved proposals. They never mutate confirmed facts.

### Controlled job-request tools

- `request_service_inspection(case_id, connection_ref)`
- `request_neighbor_analysis(case_id)`
- `request_draft_compilation(case_id, dossier_version)`
- `request_certification(case_id, bundle_hash)`

These tools call application services that independently validate state and authority. The agent
cannot force execution when preconditions fail.

### Tools deliberately absent

- approve fact;
- approve certification;
- change role/policy;
- change thresholds;
- promote/activate;
- access raw credentials;
- arbitrary HTTP, shell, code execution or database query.

Production promotion must never be representable as a model tool call.

---

## 5. Structured outputs

All guidance operations use strict JSON Schema output contracts mapped to immutable Java records.
No production behavior parses natural-language prose to recover decisions.

Example question proposal:

```java
public record QuestionProposal(
    String questionId,
    List<String> factPaths,
    String plainQuestion,
    String whyAsked,
    AnswerType answerType,
    List<String> choices,
    List<String> evidenceIds,
    String requiredApproverRole
) {
}
```

Example dossier proposal:

```java
public record FactProposal(
    String factPath,
    JsonNode proposedValue,
    String rationale,
    List<String> evidenceIds,
    double confidence,
    boolean requiresHumanConfirmation
) {
}
```

Server validation rejects unknown paths, evidence outside the case/domain, invalid enum values,
oversized values and proposals inconsistent with the current dossier version.

---

## 6. Instruction hierarchy

Every agent prompt includes:

1. Platform-owned immutable instructions.
2. Role and task instructions.
3. Current supported-archetype/schema policy.
4. Authorized dossier/evidence summaries clearly delimited as data.
5. User message.

Documents, OpenAPI descriptions, MCP annotations, agent responses and catalog descriptions are
untrusted data. Text inside them cannot change tools, authority or workflow policy.

Core behavioral rules:

- Ask in business language; do not ask users for JSON/YAML/JMESPath.
- Explain why each unresolved decision matters.
- Never fabricate evidence, schemas, owners, approvals or gate results.
- Prefer `unable to determine` over an unsupported inference.
- Distinguish observed facts from business declarations.
- Never imply that accepting a proposal is production approval.
- Cite evidence IDs for every material proposal.

---

## 7. Guardrails

### Deterministic pre-run guardrails

Run synchronously before model/tool execution for:

- authenticated active Studio session;
- case/tenant/domain access;
- supported case state;
- request size/type;
- prompt injection and secret-pattern policy where deterministic;
- spend/turn limits.

### Output guardrails

- strict output schema;
- known dossier paths and enums;
- evidence references authorized and present;
- no approval/promotion claims;
- no secret material;
- no unsupported manifest fields presented as valid;
- question count and text-size bounds.

### Tool guardrails

Apply per tool call because agent-level input/output guardrails alone do not protect every nested
tool invocation. Re-authorize principal/case/domain and validate arguments before execution. Validate
and redact tool output before returning it to the model.

### Tripwire behavior

A triggered security tripwire stops the run, creates a security event and presents a safe UI error.
It never silently falls back to a less protected model/tool path.

---

## 8. Sessions and durable runs

Store conversational continuity as ordinary Studio messages linked to tenant, case, user, dossier
version, prompt version and model request ID. Keep dossier facts in Studio PostgreSQL, separately
from generated conversation text.

Do not store approvals, certification truth or promotion state only in conversation history.
Long-running inspections are durable application jobs, not suspended model runs. On resume, the
application re-authorizes the user, checks the dossier version and expires stale consent.

If a case is updated while a run is paused, cancel or rebase the run rather than applying stale
proposals.

---

## 9. Human-in-the-loop distinction

Two approval classes must remain separate:

### Operational consent

An application-owned confirmation allowing a bounded action such as inspecting a supplied
non-production URL. This answers “may this operation execute now?”

### Governance approval

Studio domain/security/platform/release approval over exact dossier, bundle and certification
hashes. This answers “may this business contract progress or promote?”

Only Studio governance approvals affect workflow/promotion state.

---

## 10. Tracing, privacy and cost

The Studio records an OTel span and immutable audit event for every model request. It must:

- set a stable workflow name and case-correlated group ID;
- exclude sensitive trace data by default;
- place only tenant-safe identifiers/metadata in traces;
- route traces through enterprise-approved processors and storage;
- record usage totals in Studio cost metrics;
- enforce configured per-case/per-stage budgets and max turns;
- redact documents and tool outputs before model exposure.

Studio audit evidence is authoritative even when model-provider telemetry is disabled or unavailable.

---

## 11. Model strategy

Model names are configuration, not hardcoded product rules. Define tiers:

- guide/interview model;
- boundary/composition reasoning model;
- cheap extraction/classification model;
- independent eval/judge model where deterministic metrics are insufficient.

The same model must not be the sole author and sole judge of an admission decision. Model changes
require prompt/eval re-baselining, but do not change dossier/compiler schemas.

---

## 12. Failure behavior

- Structured-output failure: bounded retry, then typed agent-runtime error.
- Tool authorization failure: deny and audit; do not ask the model to work around it.
- Tool timeout: preserve partial evidence only if the tool marks it complete and valid.
- Model outage: deterministic questionnaire and Studio workflow remain available.
- Max turns/spend: pause with a resumable message and no dossier mutation.
- Stale dossier version: reject proposal and require a fresh authorized context.
- Trace export failure: meter separately; do not lose Studio audit record.

---

## 13. Runtime evaluation suite

- Question relevance to unresolved dossier paths.
- Plain-language comprehension by non-platform users.
- Evidence citation precision.
- Correct separation of observed/declared/derived facts.
- Refusal to fabricate missing business authority.
- Prompt injection resistance across documents, OpenAPI/MCP descriptions and tool outputs.
- No unauthorized cross-case/domain evidence reference.
- Correct use of proposal tools versus absent approval/promotion tools.
- Stable behavior across supported model upgrades.
- Recovery from pause/resume, stale state, model/tool outage and max-turn limits.

---

## 14. Runtime acceptance criteria

- A team can complete all three supported archetype interviews without seeing manifest syntax.
- Every material model proposal cites authorized evidence.
- Agent output cannot directly change an approved dossier fact.
- No runtime path can approve or promote.
- Tool calls are independently authorized and bounded.
- Paused runs resume safely or reject stale state.
- Disabling OpenAI tracing does not remove Studio auditability.
- Model outage does not corrupt or block deterministic case review.
