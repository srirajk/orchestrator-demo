# Conduit Onboarding Studio — Product Requirements Document

**Status:** Proposed v1  
**Audience:** Product, platform engineering, domain teams, security reviewers, implementation agents  
**Product boundary:** Separate Studio/control plane; never the gateway request path  
**Working name:** Conduit Onboarding Studio, powered by the Conduit Onboarding Agent

---

## 1. Decision

Build a separate guided onboarding and admission Studio for teams that already own an agent or
business service. It does **not** build their agent. A bounded OpenAI guidance runtime interviews the team
in business language, inspects bounded evidence and explains gaps; deterministic Studio services
compile Conduit manifests/evaluation assets and report whether the submission is ready to join the
platform.

Teams never edit Conduit JSON or YAML. They review and approve a human-readable capability dossier.
The confirmed dossier is the source of truth; manifests are deterministic build artifacts.

The v1 claim is intentionally narrow:

> Conduit can guide and certify the three onboarding archetypes already proven in this repository.
> It must report an unsupported platform requirement rather than invent behavior outside them.

The three v1 archetypes are:

1. **Enterprise knowledge capability** — authenticated, non-resource-scoped, single-step, such as
   HR policy Q&A.
2. **Resource-scoped data capability** — entity resolution, coverage and structural authorization,
   such as policy details or holdings.
3. **Composable analytical capability** — typed producer/consumer edges with an optional declared
   condition or bounded map, such as concentration review or per-trade penalty analysis.

These examples are sufficient to build v1. They are not sufficient to claim universal onboarding.

---

## 2. Problem

Conduit onboarding currently assumes that a domain team understands platform concepts including:

- capability boundaries;
- positive routing examples;
- neighboring or confusing capabilities;
- negative and abstention examples;
- entity resolution and coverage;
- semantic `consumes` and `produces` contracts;
- JMESPath projections, conditions and map declarations;
- routing regression gates;
- manifest schemas and registry ingestion.

Domain teams usually understand their business and their service, not this vocabulary. Asking them
for "confusers" or "routing signals" transfers platform design work to the customer and produces
low-quality manifests.

Teams are more likely to possess:

- a running OpenAPI or MCP service (A2A is a later protocol extension);
- requirements and architecture documents;
- business owners and technical owners;
- representative user questions;
- a golden dataset, sometimes;
- test credentials and a non-production endpoint.

The onboarding agent must translate those materials into Conduit's technical contract while keeping
business, security and source-of-record decisions with named humans.

---

## 3. Product principles

1. **Business language in; platform artifacts out.** Teams answer understandable questions and
   never manipulate platform manifests.
2. **Observed, declared and derived facts stay separate.** A live schema is observed; capability
   ownership is declared; a routing neighbor is derived.
3. **The LLM proposes; deterministic code compiles and gates.** Production artifacts are never
   free-form model output.
4. **Uncertainty is a result.** `UNABLE_TO_ASSESS` and `UNSUPPORTED_REQUIREMENT` are successful,
   honest outcomes.
5. **Admission protects the existing catalog.** A new capability must not silently poach traffic
   from an existing one.
6. **No self-certification.** Model-generated test cases cannot be the only evidence used to pass
   a model-generated manifest.
7. **Activation is a separate authority.** Passing certification creates a reviewable candidate;
   it does not publish to production automatically.
8. **Every field has provenance.** Reviewers can see who or what supplied each material decision.

---

## 4. Users and responsibilities

### 4.1 Domain submitter

Usually an agent engineer, product owner or domain architect. Connects the service, supplies
requirements and examples, answers capability-boundary questions, and corrects observed facts.

### 4.2 Business owner

Approves what the capability does, what it does not do, its authoritative business meaning, entity
semantics, freshness and completeness expectations.

### 4.3 Security/data owner

Approves audience, classification, resource scoping, coverage behavior, retention and any proposed
authorization changes.

### 4.4 Platform reviewer

Reviews routing impact, composition contracts, unsupported requirements, validation evidence and the
generated artifact diff. Approves registry submission.

### 4.5 Onboarding agent

Explains, interviews, inspects, proposes, compiles, tests and reports. It never assumes any of the
human authorities above.

---

## 5. Inputs

### 5.1 Required

- Service protocol: HTTP/OpenAPI or MCP. An A2A submission receives
  `UNSUPPORTED_REQUIREMENT` until the registry introspector supports it.
- Non-production service or specification URL.
- Operation/tool/capability to onboard.
- Business and technical owner identities.
- Short requirements description or document.
- Intended users or organizational audience.

### 5.2 Strongly recommended

- Ten or more real user questions.
- Golden request/response examples.
- Error and partial-result examples.
- Entity and identifier documentation.
- Source-of-record and freshness statement.
- Test bearer token or approved test-auth mechanism.

### 5.3 Optional

- Existing domain and sub-domain selection.
- Architecture documents.
- Synonym or glossary lists.
- SLOs, rate limits and cost constraints.
- Existing authorization and coverage specifications.

When no golden dataset exists, the system may draft a **seed dataset**. Seed data is visibly labeled
`PROPOSED`, cannot independently satisfy the admission gate, and requires owner approval plus held-out
review cases.

---

## 6. End-to-end journey

### Stage 1 — Start and scope

The submitter chooses one of:

- add an agent to an existing sub-domain;
- add a new sub-domain to an existing domain;
- add a new business domain.

The system records owners, environment, protocol and intended archetype. It explains the selected
journey and expected evidence.

### Stage 2 — Inspect the service

The system fetches OpenAPI or calls MCP discovery, identifies operations/tools, derives wire
input/output schemas, records connection details, and performs safe reachability probes.

Observed facts are displayed as a plain summary:

> The operation accepts `relationship_id` and `period`. It returns positions, total value, currency
> and an as-of date. The specification does not declare an error response schema.

The submitter can correct only interpretation, not the captured raw evidence.

### Stage 3 — Establish capability ownership

The system asks questions such as:

- What business outcome does this capability provide?
- What questions must never be sent to it?
- Is it authoritative, advisory, or a derived analysis?
- What should users ask another team or capability instead?
- Does it fetch source data or calculate from supplied data?

The system compares the proposed capability with the live catalog and presents contrastive ownership
exercises without platform jargon:

> “Why did this trade fail?” — your capability, `settlement_status`, or neither?

> “Estimate the penalty for each failed trade.” — your capability, `settlement_status`, or both in
> sequence?

Unresolved ownership conflicts block admission.

### Stage 4 — Establish business semantics

The system identifies candidate entities, literals, list inputs, load-bearing outputs and business
terms from requirements, schemas and examples. Owners confirm:

- canonical business entity and identifier patterns;
- required versus optional context;
- synonyms and common user phrasings;
- source-of-record authority;
- as-of/freshness meaning;
- complete, partial and not-applicable semantics;
- load-bearing figures and their display formats;
- error meanings and expected degradation behavior.

### Stage 5 — Establish security and coverage

The system determines whether the capability is enterprise or segment audience and whether it is
resource-scoped. It collects or validates:

- data classification;
- coverage DISCOVER, RESOLVE and CHECK endpoints;
- fail-closed behavior;
- intended roles/segments;
- whether produced entities require downstream coverage filtering.

Security decisions require a security/data-owner confirmation.

### Stage 6 — Establish composition

The system compares the capability's inputs with semantic outputs registered in the catalog. It may
propose:

- an entity leaf input;
- a `consumes.from` dependency;
- a `produces.type` semantic output;
- a deterministic projection;
- a condition when requirements explicitly say “only when”;
- a bounded map when requirements explicitly say “for every item.”

Each proposal is explained with a concrete sample. Conditions and maps cannot be inferred solely from
linguistic similarity; an owner must confirm their business meaning.

### Stage 7 — Build the evidence set

The system creates three separated suites:

1. **Submitted suite** — real questions and golden cases supplied by the team.
2. **Adversarial suite** — neighbors, ownership boundaries, off-topic prompts, missing context and
   authorization cases generated by the platform and reviewed by the team.
3. **Held-out suite** — cases approved by an independent platform/domain reviewer and hidden from
   manifest-example generation.

### Stage 8 — Confirm the dossier

Users review a human-readable capability dossier, not JSON. Material decisions show provenance and
required approver. Example:

```text
Capability: Failed Trade Penalty Analysis
Owner: Asset Servicing Operations
Authority: Derived analysis, not source of settlement status
Accepts: Failed settlement rows
Produces: One penalty result per failed trade
Runs when: Failed rows are present
Iteration cap: 100 rows
Classification: Confidential
Closest neighbor: Settlement Status
Boundary: Status explains failures; this capability estimates penalties
```

All blocking questions and approvals must be resolved before compilation.

### Stage 9 — Compile and certify

A deterministic compiler generates candidate artifacts. The certification runner validates schemas,
live contracts, routing, authorization, coverage, dataflow, conditions/maps, golden behavior and
existing-catalog regression.

### Stage 10 — Review and activate

The system produces a versioned candidate bundle, evidence report and human-readable diff. A platform
reviewer may submit it through the registry control plane or request changes. Production activation is
outside the onboarding agent's authority.

---

## 7. Required outputs

Depending on scope, the candidate bundle contains:

- domain manifest;
- sub-domain manifest;
- agent manifest;
- submitted, adversarial and held-out evaluation metadata;
- routing examples and ownership-boundary cases;
- proposed semantic I/O declarations;
- proposed authorization configuration change;
- coverage contract evidence;
- introspection snapshot and live-probe evidence;
- certification report;
- artifact hashes and dossier version;
- unresolved/unsupported-requirement report.

The bundle must identify every field as `OBSERVED`, `DECLARED`, `DERIVED` or `DEFAULTED`, with its
source and approval status.

---

## 8. Verdicts

### `READY`

Every mandatory gate passes, no blocking conflict remains, required approvals exist, and catalog
regression stays within configured thresholds.

### `CONDITIONALLY_READY`

The capability is safe within explicit, approved limitations. Limitations are machine-readable,
user-visible where relevant, time-bounded where appropriate, and do not waive security gates.

### `NOT_READY`

One or more actionable blocking gaps exist. The report identifies the evidence, owner and required
remediation.

### `UNABLE_TO_ASSESS`

Evidence is missing, contradictory, unreachable or insufficient. This is not treated as a failure by
the team and cannot be converted to `READY` by model confidence.

### `UNSUPPORTED_REQUIREMENT`

The capability requires a platform primitive outside the v1 contract, such as an unsupported
protocol, unbounded workflow, transactional approval, or write-side idempotency. The system produces
a platform-gap report and no runnable manifest.

---

## 9. Admission gates

The exact numeric thresholds are configuration, not PRD constants. V1 requires gates in these
categories:

1. Schema validity.
2. Domain/sub-domain referential integrity.
3. Live service reachability and introspection.
4. Wire request/response conformance.
5. Semantic edge/projection validity.
6. Condition Boolean validity.
7. Map array target and boundedness.
8. Positive routing quality.
9. Neighbor ownership/confusion quality.
10. Off-topic abstention.
11. Existing-catalog regression/poaching.
12. Entity resolution and coverage behavior.
13. Structural authorization behavior.
14. Golden dataset behavior.
15. Error and partial-result behavior.
16. Required human approvals.

No aggregate score may conceal a failed security, coverage, schema or contract gate.

---

## 10. Functional requirements

- **FR-1:** Create, resume, clone and cancel an onboarding case.
- **FR-2:** Support the three v1 archetypes.
- **FR-3:** Ingest text/Markdown/PDF requirements and structured golden datasets.
- **FR-4:** Introspect HTTP/OpenAPI and MCP services using existing registry capabilities.
- **FR-5:** Preserve raw evidence separately from interpretations.
- **FR-6:** Generate adaptive, plain-language questions from unresolved dossier fields.
- **FR-7:** Compare the candidate with the current registered catalog.
- **FR-8:** Present contrastive ownership exercises and record human decisions.
- **FR-9:** Draft seed tests when team tests are missing, with explicit provenance.
- **FR-10:** Collect named approvals for business and security decisions.
- **FR-11:** Deterministically compile manifests from the approved dossier.
- **FR-12:** Run dry-run validation without mutating the registry or routing index.
- **FR-13:** Run admission and catalog-regression suites.
- **FR-14:** Produce actionable, evidence-linked gaps.
- **FR-15:** Export a versioned candidate bundle and readable diff.
- **FR-16:** Prevent activation until the verdict and approvals satisfy policy.
- **FR-17:** Re-run certification against a newer catalog or schema version.
- **FR-18:** Detect drift between approved observations and the live service.

---

## 11. Non-functional requirements

- Stateless request-path gateway: onboarding work never executes in the gateway runtime profile.
- Durable and resumable onboarding cases.
- Tenant isolation and least-privilege service credentials.
- Immutable evidence and approval events.
- Secrets stored by reference; never embedded in dossiers or artifacts.
- Every model call records model, prompt version, input evidence IDs and output hash.
- Deterministic compilation: same confirmed dossier + schema version produces byte-identical
  canonical artifacts.
- Idempotent certification runs.
- Configured limits on documents, probes, candidate neighbors, test cases and model spend.
- No production customer data required for onboarding certification.
- Accessible UI with readable explanations for non-platform specialists.

---

## 12. Explicit non-goals for v1

- Building or repairing the submitted agent implementation.
- Inventing business ownership or source-of-record meaning.
- Authoring production access policy without review.
- Direct production activation.
- Supporting write/mutating agents.
- Free-form runtime graph generation.
- Unbounded loops or arbitrary code execution.
- Certifying unsupported protocols by falling back to prose.
- Treating generated examples as independent golden evidence.
- Guaranteeing universal-domain onboarding from the initial examples.

---

## 13. Success metrics

Product metrics:

- Median time from connection to first complete dossier.
- Median time from complete dossier to an actionable verdict.
- Percentage of manifest fields derived without human correction.
- Number of material corrections after compilation.
- Percentage of gap messages resolved without platform-team assistance.
- Reviewer time per onboarding.
- First-pass and eventual admission rates.

Safety/quality metrics:

- Catalog routing regression after activation.
- Post-activation manifest/service drift rate.
- Escaped schema or semantic-contract defects.
- Incorrect `READY` verdicts.
- Authorization or coverage regressions: target zero.
- Percentage of generated tests supplemented by independent held-out evidence.

The primary v1 outcome is not “a manifest was generated.” It is:

> A team unfamiliar with Conduit safely onboarded a supported capability, understood every material
> decision, passed independent certification, and caused no unacceptable regression to the catalog.

---

## 14. Critical risks and mitigations

| Risk | Mitigation |
|---|---|
| The examples encode one team's assumptions | V1 scope labels; concierge trials with unrelated/poorly documented services before broad claims |
| The model invents semantics | Provenance separation, required confirmation, deterministic compiler |
| Self-generated tests self-certify | Submitted/adversarial/held-out separation; independent review requirement |
| A new agent poaches existing traffic | Catalog-neighbor and regression gate before activation |
| OpenAPI is technically valid but semantically weak | Live probes, owner questions, `UNABLE_TO_ASSESS` verdict |
| Manifest schema evolves during development | Versioned dossier/compiler; regenerate artifacts; compatibility tests |
| Teams approve without understanding | Plain-language contract cards and contrastive examples, not raw JSON |
| Sensitive data leaks into prompts | Test-data policy, redaction, secret references, model-provider controls |
| Onboarding becomes a second policy engine | It proposes; Cerbos and coverage remain authoritative |
| A chat session loses state or evidence | Durable workflow state and immutable evidence events |

---

## 15. Rollout

### Phase 0 — Concierge validation

Run the intended workflow manually against at least five cases: the three supported archetypes, a
poorly documented legacy service, and a capability that strongly overlaps an existing one. Record
every question, correction and missing schema concept.

### Phase 1 — Existing sub-domain, single-step

Guided dossier, HTTP/MCP introspection, deterministic agent-manifest compilation, routing neighbor
tests and downloadable candidate bundle. No activation.

### Phase 2 — Resource-scoped agents

Entity, coverage and authorization questioning and certification.

### Phase 3 — Composable agents

Semantic I/O, projections, conditions, maps and DAG simulation.

### Phase 4 — New domain/sub-domain

Domain and sub-domain compilation, policy proposal and broader catalog admission.

### Phase 5 — Governed activation and drift

Approval workflow, registry submission, continuous drift detection and recertification.

---

## 16. Product acceptance criteria for v1

V1 is complete only when:

1. A user can onboard one fixture of each supported archetype without editing JSON/YAML.
2. The compiler produces schema-valid, byte-stable artifacts from a confirmed dossier.
3. Every artifact field links to provenance.
4. A missing business/security decision blocks compilation or certification explicitly.
5. The system detects an intentionally ambiguous neighboring capability.
6. The system detects an intentionally broken live schema/response contract.
7. The system detects an invalid projection, condition and map target.
8. The system refuses to pass generated-only golden evidence.
9. The system reports existing-catalog regression before activation.
10. No onboarding endpoint or component is present in the request-path gateway profile.
11. Activation remains impossible without the configured human approvals.
12. The evidence report is understandable without reading a manifest.

---

## 17. Production-design confidence and claim boundary

This PRD is intended to support a production implementation. The architecture is not contingent on
future research: Conduit already has the required registry schemas, HTTP/MCP introspection,
deterministic contract validation, semantic routing, coverage/authorization enforcement, DAG
resolution, condition/map execution and evaluation seams.

The three existing onboarding archetypes are enough to define the v1 product and its acceptance
tests. They are deliberately not used to make the broader claim that every future enterprise agent
fits the current manifest language.

The production claim is:

> For the three supported archetypes, the onboarding agent can collect and verify evidence, guide
> plain-language decisions, deterministically compile the required Conduit artifacts, run
> non-compensable admission gates, and produce a governed activation candidate without requiring the
> submitting team to edit manifests.

The extension claim is:

> When a future onboarding requires a missing protocol, authority model or execution primitive, the
> system identifies a typed platform gap. The platform can evolve generically and add a new certified
> archetype without weakening the existing gates.

This distinction is a production-strength boundary, not lack of design confidence. A system that
silently forces unknown requirements into a known manifest would be less production-ready.
