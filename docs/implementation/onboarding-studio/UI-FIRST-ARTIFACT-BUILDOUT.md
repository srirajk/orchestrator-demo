# Conduit Studio — UI-First Artifact Buildout Specification

**Status:** Authoritative first implementation sequence  
**Audience:** Product, design, frontend, Studio API, compiler and platform engineering  
**Decision:** Build the understandable catalog experience and deterministic artifact generator first.
External ingestion and request-path gateway integration are the final implementation phases.

---

## 1. Outcome

The first flagship milestone is not activation. It is a working Studio in which a user can:

1. see the organization as Business Lines, Use Cases, capabilities and connected agents;
2. understand the current catalog's strengths and integrity gaps;
3. start an Onboarding Project without learning Conduit manifest terminology;
4. supply or approve intent, boundary, unsupported, ambiguity, context and authority signals;
5. give separate consent for inspection, model assistance, evidence retention and package generation;
6. review exactly what Conduit understood;
7. generate a deterministic, content-addressed Conduit Package into a visible folder;
8. inspect the business contract, generated artifacts and snapshot-test proof side by side.

This milestone must work without external ingestion and without gateway changes. It produces the
exact versioned package those later components will consume.

---

## 2. Revalidated repository baseline

Validation performed against the current `registry/` tree and repository schema tests:

| Catalog concept | Current representation | Count | Studio interpretation |
|---|---|---:|---|
| Organization | Tenant/runtime scope | 1 demo organization | Organization boundary |
| Business Line | Domain manifest | 4 | Asset Servicing, HR, Insurance, Wealth Management |
| Context Group | Sub-domain manifest | 7 | Runtime vocabulary/security grouping; not primary UI hierarchy |
| Connected Agent | Agent manifest | 18 | Existing services available for reuse |
| Capability | Agent skill | 18 | One current skill per agent |
| Confirmed Use Case | Not represented today | 0 | Must be confirmed or created through Studio |

All current JSON documents validate against their individual Draft 2020-12 schemas. The catalog as
a whole does not yet pass its cross-reference contract:

- `cash-management` requires `relationship_id` but has no matching clarification schema entry;
- `custody-operations` requires `relationship_id` but has no matching clarification schema entry;
- `meridian.servicing.trade_penalty` is absent from its Context Group membership list;
- `meridian.insurance.renewal_risk` is absent from its Context Group membership list;
- `meridian.wealth.concentration` is absent from its Context Group membership list;
- `meridian.wealth.concentration_review` is absent from its Context Group membership list.

The Studio dashboard must therefore show **18 discovered agents**, not silently reduce the count to
14 by following only Context Group membership. The four reverse-membership gaps and two
clarification gaps appear as actionable catalog-health findings.

The existing manifests do not contain a Use Case contract. During import, every skill becomes a
`PROVISIONAL_ROUTE_TARGET` and every inferred business outcome remains
`NEEDS_BUSINESS_CONFIRMATION`. The UI must not label 18 technical skills as 18 confirmed Use Cases.

---

## 3. Canonical hierarchy

```text
Organization
  └── Business Line
        ├── Use Case
        │     ├── Goal capability
        │     ├── Dependency/optional capabilities
        │     └── Connected agent bindings
        └── Shared Business Line policy

Runtime support structures
  └── Context Groups, entity vocabulary, coverage and memory policy
```

The user navigates by Business Line and Use Case. Context Groups remain visible in an advanced
“Runtime structure” view and in impact previews, but never replace the business hierarchy.

### 3.1 Required first-version records

```text
OrganizationSummary
  organizationId, displayName, catalogHealth

BusinessLineSummary
  businessLineId, displayName, purpose, lifecycle, ownerRefs,
  confirmedUseCaseCount, provisionalTargetCount, connectedAgentCount,
  capabilityCount, contextGroupCount, readiness

UseCaseSummary
  useCaseId, businessLineId, displayName, outcome, ownerRefs,
  lifecycle, readiness, signalCoverage, capabilityPlan

CapabilitySummary
  capabilityId, displayName, kind, goalEligible, semanticInputs,
  semanticOutputs, boundAgentIds

AgentSummary
  agentId, displayName, businessLineId, contextGroupId, protocol,
  capabilityIds, observedContractStatus, membershipStatus

OnboardingProjectSummary
  projectId, projectType, targetRefs, workflowStatus, nextAction,
  responsibleRole, dossierVersion, generatedBundleHash

CatalogHealthFinding
  findingId, severity, code, affectedRefs, explanation, remediation
```

Every read-model response carries `catalogSourceHash`, `schemaVersion`, `importedAt` and
`integrityStatus` so the UI never presents stale or partially loaded counts as fact.

---

## 4. Primary navigation and routes

The application shell uses these user-facing sections:

```text
Overview
Business Lines
Use Cases
Agent Network
Onboarding Projects
Generated Packages
Proof
```

Approvals, activation and runtime gateway views are not primary navigation in the first milestone.
They appear later when their real workflows exist.

```text
/studio
/studio/business-lines
/studio/business-lines/:businessLineId
/studio/use-cases
/studio/use-cases/:useCaseId
/studio/agents
/studio/agents/:agentId
/studio/projects
/studio/projects/new
/studio/projects/:projectId/:step
/studio/packages/:bundleHash
/studio/proof
```

Breadcrumbs always retain the business context, for example:

```text
Business Lines / Insurance / Assess renewal risk / Generated Package
```

---

## 5. Flagship overview

### 5.1 First viewport

The first screen answers “What intelligence does Conduit already have?” within five seconds.

Top row:

```text
4 Business Lines     18 Connected Agents     18 Capabilities
0 Confirmed Use Cases     6 Catalog Findings
```

The zero is intentionally framed as **“Ready to define business outcomes”**, not as an empty-product
failure. As the demo confirms the first Use Case, the number changes from 0 to 1 and the selected
Business Line changes from `Needs business context` to `Business contract started`.

### 5.2 Business landscape

Show four Business Line cards. Each card contains:

- plain-language purpose;
- connected agents and capabilities;
- confirmed and provisional Use Cases;
- owner/readiness state;
- Context Group count as secondary information;
- catalog-health findings affecting that line;
- primary action: **Explore** or **Define a Use Case**.

### 5.3 Agent network

The Agent Network is a searchable inventory, not an infrastructure topology. It groups agents by
Business Line and shows capability, protocol, context binding and integrity state. Selecting an agent
opens:

1. what it currently appears able to do;
2. what was observed versus inferred;
3. the Business Lines/Use Cases that use it;
4. missing membership or ownership information;
5. **Use this agent in a Use Case**.

### 5.4 Catalog-health honesty

Health findings use plain language and never prevent exploration. A finding links to affected
records and offers **Start repair project**. Counts distinguish:

- discovered;
- structurally linked;
- business-confirmed;
- generated;
- eligible for later ingestion.

---

## 6. Onboarding Project journey

The primary call to action is **Define a Use Case**. “Compile manifest” is never user-facing copy.

### Step 1 — Choose the business change

- Define a Use Case in an existing Business Line.
- Create a new Business Line and its first Use Case.
- Connect an agent to an existing Use Case.
- Repair an imported catalog relationship.

### Step 2 — Describe the outcome

Ask what the user wants people to accomplish, who needs it and what the agent must never decide.
The assistant may turn supplied context into typed proposals, but the structured outcome card is the
record the user confirms.

### Step 3 — Select or connect capabilities

Show reusable existing agents first. Search by business language, not agent ID. Explain why an agent
was suggested and whether its service contract is observed, declared or unknown.

### Step 4 — Establish signals and consent

Before analyzing content, the UI explains the signals Conduit can use:

| Signal | Example | Why it matters |
|---|---|---|
| Positive intent | “Which policies have high renewal risk?” | Defines ownership |
| Boundary | “Why was my claim denied?” | Separates neighboring Use Cases |
| Unsupported | “Cancel this policy now” | Establishes abstention/refusal |
| Ambiguity | “How is this account doing?” | Defines clarification behavior |
| Context/entity | Policy, relationship, period | Establishes required information |
| Authority | Policy platform is source of record | Prevents unsupported claims |
| Audience/access | Underwriters with covered policies | Establishes structural and resource gates |
| Service evidence | OpenAPI/tool contract and probe | Grounds executable capability |

Consent is separate and purpose-specific:

- analyze supplied text/documents;
- use bounded model assistance;
- inspect a supplied service contract;
- execute named read-only probes;
- retain redacted evidence;
- generate a local Conduit Package.

None of these consents authorizes ingestion, activation or production publication.

### Step 5 — Resolve ownership boundaries

Present concrete questions and ask which Use Case owns each one. Show nearby existing agents and the
effect of the answer. The user never sees “vector score” as the primary decision.

### Step 6 — Review Conduit's understanding

Use a three-column proof view:

| Business contract | Conduit interpretation | Evidence/provenance |
|---|---|---|
| Outcome and owner | Use Case identity | Human-confirmed answer |
| Questions it owns | Positive routing signals | Submitted examples |
| Questions it rejects | Boundary/unsupported signals | Owner decisions |
| Required information | Context/entity contract | Manifest plus service evidence |
| Capabilities reused | Capability plan | Current catalog |
| New/changed records | Artifact impact | Deterministic compiler diff |

Unresolved assumptions are visually distinct and block package generation when material.

### Step 7 — Generate Conduit Package

The button reads **Generate Conduit Package**. Generation shows deterministic stages:

```text
Freezing confirmed facts
Mapping business contracts
Generating catalog artifacts
Validating schemas and references
Creating snapshot proof
Hashing package
Package ready
```

### Step 8 — Package reveal

The final screen synchronizes:

1. the new business experience;
2. the capabilities and agents Conduit will reuse;
3. the exact generated folder and changed artifacts;
4. schema/reference validation results;
5. snapshot-test results and content hash.

The user can download the bundle or open an artifact read-only. There is no activation button in the
first milestone.

---

## 7. Deterministic artifact folder

The compiler writes outside the source registry. The default local root is configurable and resolves
to:

```text
build/conduit-studio/artifacts/
  {organization-id}/
    {project-id}/
      {bundle-hash}/
        bundle.json
        dossier/
          confirmed-dossier.json
        catalog/
          business-lines/
          use-cases/
          context-groups/
          agents/
          route-targets/
        evidence/
          evidence-index.json
        tests/
          routing-signals.jsonl
          boundary-signals.jsonl
          unsupported-signals.jsonl
          ambiguity-signals.jsonl
          expected-behavior.json
        provenance/
          field-provenance.json
          consent-receipts.json
        reports/
          schema-validation.json
          cross-reference-validation.json
          impact-summary.json
          snapshot-results.json
```

Generation rules:

- write to a unique temporary directory and atomically move only after validation succeeds;
- identical canonical inputs and compiler policy produce byte-identical files and bundle hash;
- sort object keys and stable arrays according to schema-owned ordering rules;
- normalize line endings to LF and timestamps to values already present in the frozen dossier;
- never include secrets, raw credentials or unredacted model prompts;
- include only changed Business Line/Context Group artifacts, plus references to unchanged hashes;
- never write into `registry/` during Studio generation;
- retain a human-readable impact report alongside machine-readable artifacts.

### 7.1 Minimum first generated package

For “Assess renewal risk” in existing Insurance, the first package contains:

- one confirmed Use Case definition;
- one route-target definition;
- the existing renewal-risk agent manifest, normalized and referenced;
- positive, boundary, unsupported and ambiguity signals;
- provenance and consent receipts;
- a report proving the Insurance Business Line manifest is unchanged;
- a report identifying the pre-existing Context Group membership gap;
- schema, cross-reference and snapshot results.

The package is useful before ingestion: it is inspectable, diffable, hashable and ready to hand to a
later external ingestion component.

---

## 8. UI-first API contract

The first API surface is read/import/project/generate only:

```text
GET  /studio/overview
GET  /studio/business-lines
GET  /studio/business-lines/{id}
GET  /studio/use-cases
GET  /studio/agents
GET  /studio/agents/{id}
GET  /studio/catalog-health

POST /studio/projects
GET  /studio/projects/{id}
POST /studio/projects/{id}/answers
POST /studio/projects/{id}/consents
POST /studio/projects/{id}/proposals/{proposalId}:accept
GET  /studio/projects/{id}/impact-preview

POST /studio/projects/{id}/packages:generate
GET  /studio/packages/{bundleHash}
GET  /studio/packages/{bundleHash}/artifacts
GET  /studio/packages/{bundleHash}/artifacts/{path}
```

There are deliberately no ingestion, activation, rollback or gateway endpoints in this phase. The
frontend consumes a generated OpenAPI client. Fixture adapters and the real API must implement the
same contract so visual work can start without inventing a second data model.

---

## 9. First implementation layout

```text
libs/
  conduit-manifest-contracts/       Spring-free Java records and schemas
  conduit-artifact-sdk/             canonical JSON, hashing, bundle reader/writer

services/
  onboarding-studio/                Spring Boot 4.x, Java 21
    catalogimport/                   read-only importer for current registry
    project/                         workflow and confirmed dossier
    compiler/                        deterministic artifact generation
    artifactstore/                   filesystem first, object-store port later

apps/
  onboarding-studio/web/            React + TypeScript
    features/overview/
    features/business-lines/
    features/use-cases/
    features/agent-network/
    features/projects/
    features/packages/
    features/proof/

contracts/
  onboarding-studio/
    studio-api.openapi.yaml
    business-line.schema.json
    use-case.schema.json
    route-target.schema.json
    confirmed-dossier.schema.json
    bundle.schema.json
```

The shared libraries contain no Spring dependencies. This lets the current gateway consume them
later even while its Spring Boot 4 migration is completed separately. No first-phase implementation
requires changing the gateway runtime.

---

## 10. Lightweight proof strategy

Snapshot testing is the first proof layer, not the only eventual certification layer.

### Contract and catalog snapshots

- schema snapshots for every generated artifact type;
- a canonical imported-inventory snapshot showing 4/7/18/18 counts and six integrity findings;
- JSON snapshots for Business Line, Use Case, agent and project API views;
- a reverse-membership test that fails on the current four gaps until repaired.

### Compiler snapshots

- byte-for-byte golden package for Insurance renewal risk;
- repeated compilation produces the same bundle hash;
- one approved input change produces only the expected artifact diff;
- no-op Use Case addition leaves the Business Line artifact hash unchanged;
- missing material fact produces no final package folder.

### UI proof

- semantic component tests for counts, labels, provenance and disabled actions;
- Playwright screenshot snapshots for Overview, Business Line detail, project review and package
  reveal at desktop and narrow widths;
- accessibility assertions and keyboard navigation for the complete golden path;
- screenshots are seeded from the real imported catalog snapshot, not invented marketing data.

Avoid large brittle DOM snapshots. Snapshot stable contracts, canonical artifacts and a small number
of flagship visual states.

---

## 11. Authoritative implementation order

### Phase 0 — Freeze contracts and inventory

Create the Spring-free manifest/artifact contracts, import the current registry read-only, codify
the 4/7/18/18 inventory and six integrity findings, and generate typed API fixtures.

### Phase 1 — Build the flagship catalog UI

Build the application shell, Overview, Business Lines, Agent Network, catalog-health panel and
Business Line detail using the frozen real-data snapshot.

### Phase 2 — Build the guided Onboarding Project

Implement project navigation, outcome capture, agent selection, signal explanation, granular
consent, ownership lab and Conduit-understanding review.

### Phase 3 — Generate real artifacts into the folder

Implement deterministic compilation, validation, hashing, atomic folder publication, package
explorer and impact preview. Demonstrate Insurance renewal risk without changing `registry/`.

### Phase 4 — Add bounded intelligence and richer proof

Add typed model proposals, service-contract inspection adapters, composition visualization,
provenance, catalog-neighbor exercises and snapshot evidence. The deterministic workflow remains
fully usable when the model is unavailable.

### Phase 5 — Add durable governance

Add PostgreSQL workflow persistence, evidence object storage, certification, approvals and audit
over the exact package hashes already produced by Phase 3.

### Phase 6 — Implement external ingestion

Evolve the existing external `registry` profile/container to consume the shared SDKs and exact
package format already proven by the UI and artifact workflow. Introduce a new deployable only if an
ADR proves the existing profile boundary is insufficient.

### Phase 7 — Integrate the read-only gateway

Run the Jackson/JMESPath/SSE compatibility ADR, then complete the approved gateway Spring Boot 4
target, consume immutable catalog snapshots through the shared contracts, pin catalog versions per
conversation and prove activation/rollback. This is the final runtime integration, not a prerequisite
for proving the Studio experience.

---

## 12. Flagship acceptance criteria

1. The Overview derives and displays 4 Business Lines, 7 Context Groups, 18 connected agents and 18
   capabilities from the current repository.
2. The UI explains that no Use Cases are yet business-confirmed and converts one provisional target
   into a confirmed Use Case during the demo.
3. All six current integrity findings are visible and actionable.
4. A user reaches every primary screen without encountering domain/sub-domain/manifest jargon as the
   organizing hierarchy.
5. Signal types and requested processing are explained before content analysis, with separate
   consent receipts.
6. A user completes the Insurance renewal-risk project without editing JSON or YAML.
7. **Generate Conduit Package** creates the specified folder atomically.
8. Identical input generates identical bytes and bundle hash.
9. The package explorer shows why the Business Line is unchanged and which Use Case/route artifacts
   are new.
10. Snapshot proof covers imported inventory, compiler output and the four flagship UI states.
11. The first milestone makes no registry mutation and requires no gateway change.
12. External ingestion and read-only gateway integration consume the already-versioned SDK and
    package contract only in the final phases.
