# Conduit Business Line and Use Case Onboarding Specification

**Status:** Proposed implementation baseline  
**Scope:** Organization, Business Line, Use Case, capability, agent onboarding, routing admission,
tenant catalog activation, Studio UI/API, demo conversion, persistence, consent, testing and rollback  
**Primary product nouns:** Organization, Business Line, Use Case, Capability, Connected Agent,
Onboarding Project, Conduit Package  
**Runtime compatibility nouns:** tenant, domain, sub-domain/context group, skill, agent manifest,
catalog snapshot

---

## 1. Product decision

Conduit must not treat a Business Line, Use Case, capability and agent as synonyms.

```text
Organization / tenant
  -> Business Line
       -> Use Case
            -> one or more goal capabilities
                 -> one or more connected agents/services

Business Line
  -> one or more runtime context groups
       -> entity vocabulary, required context, coverage scope and clarification policy
```

The product hierarchy is business-facing. The runtime hierarchy remains an implementation detail:

| Product concept | Runtime projection | Meaning |
|---|---|---|
| Organization | tenant | Identity, data and catalog isolation boundary |
| Business Line | domain manifest | Shared business context, coverage authority and memory policy |
| Use Case | use-case definition plus routing/eval assets | A user outcome and its ownership boundaries |
| Capability | skill plus semantic I/O contract | A reusable function that can be routed or composed |
| Connected Agent | agent manifest and introspected wire contract | A provider of one or more capabilities |
| Runtime Context Group | current sub-domain manifest | Shared entities, required context, coverage behavior and membership |

The Studio UI must never present `domain`, `sub_domain`, vector scores, CEL, projections or manifest
JSON as the primary business workflow. Advanced evidence views may expose those artifacts read-only.

### 1.1 Core rule

Adding a Use Case does **not** normally modify the Business Line/domain manifest. It modifies only
the Use Case definition, affected routing targets, eval assets, capability/agent bindings and, when
needed, a context-group membership or entity contract.

A domain manifest changes only when a fact shared by the Business Line changes:

- Business Line identity or display context;
- shared clarification style/tone;
- coverage authority or endpoint templates;
- governed memory preservation, redaction or retention policy.

### 1.2 Empty Business Lines

A Business Line may exist in Studio as a `DRAFT` without any active runtime artifact. The compiler
creates and activates its domain manifest only with its first deployable Use Case. This prevents
empty runtime namespaces and false claims that a Business Line is available before it can serve a
request.

---

## 2. Current repository findings

The current demo contains four domain manifests, seven sub-domain manifests and eighteen agent
manifests. The platform already has strong primitives for manifest-driven behavior, semantic
routing, abstention, coverage, structural authorization, DAG composition, introspection, traces and
route measurement. The missing part is a tenant-scoped authoring and activation model that turns
business decisions into those primitives.

### 2.1 Current manifest behavior

- A domain manifest owns domain context, clarification policy, coverage templates and memory policy.
- A sub-domain manifest owns entity definitions, required context, resource scoping, user-facing
  clarification/denial copy and agent membership.
- An agent manifest owns service identity, protocol connection, skills/examples, constraints and
  semantic input/output declarations.
- The effective runtime contract merges domain plus sub-domain plus agent identity.

The present `sub_domain` is not reliably a business decomposition. `claims-servicing` includes
policy, claim and renewal-analysis behavior; `corporate-actions` includes NAV. It must therefore be
treated as a runtime context group, not displayed as a Use Case or assumed to be a Business Area.

### 2.2 Current relationship gap

Four agents declare a valid `sub_domain` but are absent from the corresponding sub-domain
`agents[]` membership:

- `meridian.insurance.renewal_risk`;
- `meridian.servicing.trade_penalty`;
- `meridian.wealth.concentration`;
- `meridian.wealth.concentration_review`.

The existing schema tests verify every listed member but do not verify the reverse relation. The
new catalog validator and importer must require bidirectional consistency:

```text
agent.sub_domain == context_group.id
AND
context_group.agents contains agent.agent_id
```

No compiler or activation path may rely on the loader's current fallback behavior.

### 2.3 Current tenant catalog-consistency gap

The existing ingestion, validation, introspection and indexing implementation originates in the
gateway codebase, but ingestion must execute outside the request-path gateway. Extract or reuse that
Java admission logic in an external registry-ingestion component; do not reimplement its semantics
in Studio. The serving gateway has read-only access to activated, immutable catalogs and has no
manifest/index mutation path. One external ingestion must install a matching domain, context-group,
agent and routing-index version for the request tenant. `DomainManifestStore` currently exposes
global catalog-wide entity unions while the routing index already has a per-tenant seam.

The target must provide:

1. an immutable, tenant- and environment-scoped catalog snapshot;
2. a shadow routing index keyed by the same snapshot hash;
3. a compare-and-set active catalog pointer;
4. gateway snapshot capture per request/conversation;
5. acknowledgment and rollback without partially mixing versions.

### 2.4 Current intent behavior

`IntentClassifier` is an interaction-mode classifier, not a Business Line or Use Case classifier. Its
four labels are:

- `FETCH_DATA`;
- `FOLLOW_UP`;
- `CLARIFY`;
- `CHITCHAT`.

Business intent is currently inferred later from embeddings of `skills[].examples`, optional
reranking, score/margin abstention and manifest-declared composition. This separation is correct.
New Business Lines and Use Cases must never add hard-coded enum values to the four-way interaction
classifier.

### 2.5 Existing IAM gap

Axiom already models tenants, groups/domains, roles and policy approval, but the current group service
uses the literal `default` tenant in important paths. A Business Line activation must not claim
multi-tenant correctness until IAM group/domain operations derive the tenant from the verified
request context and enforce tenant predicates on every read/write.

---

## 3. Canonical identities and invariants

### 3.1 Stable identifiers

```text
tenant_id             acme-bank
business_line_id      insurance
use_case_id           insurance.renewal-risk
capability_id         insurance.analyze-renewal-risk
route_target_id       insurance.renewal-risk.primary
context_group_id      claims-servicing
agent_id              meridian.insurance.renewal_risk
catalog_version       sha256:<canonical catalog hash>
```

Rules:

- IDs are immutable after first activation.
- Display names may change by creating a new version.
- An ID rename is a migration with aliases and compatibility evidence, not an ordinary edit.
- Every row and artifact is tenant-scoped even when the demo tenant is `default`.
- Business Line IDs are unique within a tenant, not globally.
- Symbolic I/O types are namespace-scoped and stable.
- A Use Case may span multiple capabilities and agents.
- A capability may be reused by multiple Use Cases.
- An agent may provide multiple capabilities.
- A context group is not required to correspond one-to-one with a Use Case.

### 3.2 Ownership invariants

Every activated Business Line has a business owner, technical owner and security owner. Every
activated Use Case has an accountable business owner and at least one goal capability. Every
connected agent has a technical owner, observed service contract and declared failure behavior.

No generated value becomes owned merely because the model proposed it. Accepting a proposal creates
a human-declared fact with actor/time/provenance; required approval remains separate.

---

## 4. Source model and persistence

PostgreSQL is the authoritative control-plane store. Use normalized relational tables for stable
identity, relationships, lifecycle, uniqueness and authorization predicates. Use immutable `jsonb`
snapshots for versioned business dossiers and compiler inputs. Do not use an EAV model for core
fields and do not store the authoritative workflow in Redis.

### 4.1 Principal tables

```text
organization_refs
  tenant_id PK, axiom_tenant_ref, display_name, status, created_at

business_lines
  id UUID PK, tenant_id, business_line_id, current_version_id, lifecycle_status,
  active_catalog_version, optimistic_version, created_by/at
  UNIQUE (tenant_id, business_line_id)

business_line_versions
  id UUID PK, business_line_id FK, version, snapshot_jsonb, canonical_hash,
  provenance_jsonb, created_by/at
  UNIQUE (business_line_id, version)

use_cases
  id UUID PK, tenant_id, business_line_id FK, use_case_id, current_version_id,
  lifecycle_status, optimistic_version, created_by/at
  UNIQUE (tenant_id, use_case_id)

use_case_versions
  id UUID PK, use_case_id FK, version, snapshot_jsonb, canonical_hash,
  provenance_jsonb, created_by/at

capabilities
  id UUID PK, tenant_id, capability_id, current_version_id, kind, lifecycle_status

use_case_capabilities
  use_case_id FK, capability_id FK, role GOAL|DEPENDENCY|OPTIONAL,
  ordering, condition_ref

agent_connections
  id UUID PK, tenant_id, agent_id, protocol, connection_ref, credential_ref,
  environment, observed_contract_hash, lifecycle_status

capability_agent_bindings
  capability_id FK, agent_connection_id FK, skill_id, binding_status, priority

runtime_context_groups
  id UUID PK, tenant_id, business_line_id FK, context_group_id,
  current_version_id, lifecycle_status

context_group_memberships
  context_group_id FK, agent_connection_id FK

onboarding_projects
  id UUID PK, tenant_id, project_type, target ids, workflow_status,
  current_dossier_version, expected_catalog_version, optimistic_version

dossier_versions
  project_id FK, version, schema_version, snapshot_jsonb, canonical_hash,
  created_by/at

evidence_items
  id UUID PK, tenant_id, project_id, kind, sha256, object_ref,
  classification, redaction_status, captured_by/at

proposals, consents, approvals
artifact_bundles, artifact_entries
certification_runs, gate_results
catalog_snapshots, catalog_entries, active_catalog_pointers
promotion_records, activation_receipts
jobs, outbox_events, audit_events
```

All mutable aggregate commands require `expectedVersion` and an idempotency key. Versions and
evidence are append-only. `current_version_id` is a pointer, not an overwritten document.

### 4.2 Object storage

Use the existing S3-compatible object store for raw/redacted documents, OpenAPI snapshots, MCP tool
lists, probe request/response evidence, generated bundles, eval datasets and certification reports.
Every object reference includes a SHA-256 content hash. Credentials remain opaque secret-manager
references and are never stored in artifacts, prompts or evidence payloads.

### 4.3 Runtime stores

Redis remains a derived runtime materialization:

- content-addressed agent records by catalog version;
- shadow and active vector indexes;
- active catalog pointer cache;
- readiness/acknowledgment state;
- ephemeral locks and query/corpus caches.

Loss of Redis must be recoverable from the approved bundle and activation record. Redis is never the
only copy of a Business Line, Use Case, approval or consent.

---

## 5. Business and routing contracts

### 5.1 Business Line version

The Business Line dossier contains:

```text
identity
  id, displayName, purpose, explicitBoundary
ownership
  businessOwner, technicalOwner, securityOwner
audience
  intendedFunctions, organizationalSegments
domainLanguage
  neutralContext, vocabulary, synonyms
coveragePolicy
  authorityKind, coverageServiceRef, discover/check/resolve templates, cache policy
memoryPolicy
  mustPreserve, canDrop, retention, redaction, includedRuntimeEvents
clarificationPolicy
  style, tone, approved default meanings
governance
  classification baseline, policy refs, approvals
```

`coveragePolicy` is optional because an enterprise knowledge Business Line may have no
resource-scoped Use Cases. When any active context group is resource-scoped, the compiled target
environment must resolve a complete approved coverage contract.

### 5.2 Use Case version

A Use Case is a business outcome and routing ownership contract, not a service URL:

```text
identity
  useCaseId, businessLineId, displayName, outcome
ownership
  businessOwner, participatingOwners
actors
  intended personas/functions
scope
  inScopeQuestions, outOfScopeQuestions, limitations
signals
  positiveExamples, boundaryExamples, unsupportedExamples, ambiguityExamples
context
  requiredBusinessObjects, optionalBusinessObjects, freshness/completeness
authority
  sourceOfRecord, partialResultSemantics, refusal/clarification behavior
capabilityPlan
  goal capabilities, dependencies, optional capabilities, business conditions
governance
  audience, access mode, classification, SLA, approvals
acceptance
  submitted rows, reviewer-held-out refs, success assertions
```

### 5.3 Compiled Use Case definition

Add a versioned control-plane artifact. It is stored in the candidate package and catalog even if
the first runtime release uses only parts of it:

```json
{
  "schema_version": "conduit.use-case.v1",
  "use_case_id": "insurance.renewal-risk",
  "business_line_id": "insurance",
  "display_name": "Assess renewal risk",
  "outcome": "Identify policies requiring renewal review using policy and claims evidence.",
  "actors": ["underwriter"],
  "route_targets": [
    {
      "route_target_id": "insurance.renewal-risk.primary",
      "capability_id": "insurance.analyze-renewal-risk",
      "goal_agent_id": "meridian.insurance.renewal_risk",
      "skill_id": "analyze_renewal_risk"
    }
  ],
  "context_groups": ["claims-servicing"],
  "limitations": ["Does not make an autonomous non-renewal decision."],
  "version": 1
}
```

Examples and eval rows remain in their provenance-specific assets. The definition references their
hashes rather than pretending all examples have the same evidentiary status.

### 5.4 Route-target index

The current vector index stores one document per agent example and returns an `agent_id`. Extend its
documents to carry:

```text
catalog_version
business_line_id/domain
use_case_id
route_target_id
capability_id
goal_agent_id
skill_id
context_group_id
access_mode
embedding
example_provenance
```

Retrieval chooses route targets; execution still resolves the goal agent and lets `DagResolver`
expand producer dependencies from semantic I/O. This avoids assuming one agent equals one Use Case
and makes the routing trace legible in business terms.

For backward compatibility, imported manifests without a Use Case definition receive a provisional
legacy route target per skill. Such targets may run but appear as `NEEDS_BUSINESS_CONFIRMATION` in
Studio until an owner confirms the Use Case contract.

---

## 6. Onboarding and change workflows

The UI term is **Onboarding Project**, not “case.” Internal legacy `case_id` fields may be migrated,
but no new public API or UI should introduce “case” as the hierarchy beneath a Business Line.

### 6.1 Project types

```text
IMPORT_EXISTING_AGENT
ADD_USE_CASE_TO_BUSINESS_LINE
ADD_AGENT_TO_USE_CASE
CREATE_BUSINESS_LINE
CHANGE_BUSINESS_LINE_POLICY
CHANGE_USE_CASE
CHANGE_AGENT_CONNECTION
RETIRE_USE_CASE
RETIRE_BUSINESS_LINE
```

### 6.2 Import an existing agent

1. User selects the Organization and an existing or new Business Line.
2. User provides an OpenAPI URL or MCP server URL and an opaque credential reference.
3. Studio explains the inspection and requests bounded non-production inspection consent.
4. Registry dry-run fetches the spec/tool list, selects an operation/tool and derives wire schemas.
5. Studio stores the immutable observed evidence snapshot.
6. Studio searches the current catalog for compatible capabilities, producers, context groups and
   neighboring ownership.
7. The user describes the business outcome; Studio proposes a Use Case and capability binding.
8. Owners confirm positive examples, boundaries, required context, source-of-record status, failure
   behavior and access classification.
9. The compiler creates a candidate package.
10. Certification builds a shadow catalog/index and runs routing, contract, authorization, coverage,
    composition and regression gates.
11. Required reviewers approve exact hashes.
12. Registry activates the exact catalog snapshot and returns a receipt.

Service inspection never establishes business ownership. An OpenAPI description may propose what a
service appears to do, but only the authorized owner can confirm the Use Case and its boundaries.

### 6.3 Add a Use Case to an existing Business Line

1. Reference the active Business Line version; do not copy its shared facts into the Use Case.
2. Search for reusable capabilities before asking for a new agent.
3. If all capabilities exist, create only the Use Case, route-target/eval assets and bindings.
4. If a capability is missing, onboard only the missing agent/capability.
5. Reuse a compatible context group or propose a new one when entity/access semantics differ.
6. Leave the domain manifest byte-identical unless a shared Business Line fact changed.
7. Re-certify the candidate plus every affected routing neighbor and dependency consumer.

### 6.4 Create a Business Line

1. Create a Studio draft immediately; create no runtime artifact yet.
2. Confirm identity, purpose, explicit boundary and accountable owners.
3. Define the first Use Case and determine whether it is knowledge, resource-scoped or composable.
4. Discover/confirm the context group, entity vocabulary and required context.
5. If resource-scoped, confirm the coverage authority and test DISCOVER/CHECK/RESOLVE behavior.
6. Confirm memory preservation, redaction and retention.
7. Inspect/connect at least one goal capability provider.
8. Generate domain, context-group, Use Case, agent, eval and optional policy-proposal artifacts.
9. Validate the new Business Line against the entire tenant catalog.
10. Activate one atomic catalog snapshot only after all required approvals pass.

Creation of an Axiom business-domain group and policy bindings is a separate approved activation
effect. Studio may propose it; it must not silently create identity or access grants while a user is
answering onboarding questions.

### 6.5 Change a Business Line

Classify each changed fact before compilation:

| Change | Rebuild/revalidate |
|---|---|
| Display name/context | Domain artifact; classifier framing; all child route regression |
| Clarification policy | Domain artifact; clarification contract tests |
| Coverage authority/templates | Domain artifact; every resource-scoped context group and Use Case |
| Memory policy | Domain artifact; memory compatibility and retention approval |
| Add Use Case only | No domain artifact; affected route targets/context group/agents |
| Entity or required-context change | Context group and all member Use Cases/agents |
| Agent connection/schema change | Agent binding plus all dependent capabilities/Use Cases |
| Routing examples/boundaries | Use Case route target plus full neighbor regression |

A Business Line edit creates a new immutable version. It never mutates an artifact already approved
or used by an active conversation.

### 6.6 Retirement

- Retire a Use Case by removing its route targets in a new catalog version; retain evidence/history.
- Block retirement when another active Use Case depends on an exclusive capability unless a
  replacement binding is approved.
- Retire a Business Line only when all Use Cases are retired and no active policy/memory/session
  dependency remains.
- Rollback is activation of a previously certified catalog hash, not a database reversal.

---

## 7. Guided questions, signals and consent

### 7.1 Question strategy

Studio asks only questions connected to unresolved or conflicting typed fields. It first uses:

1. Axiom tenant/owner context;
2. current catalog facts;
3. uploaded requirements;
4. observed API/MCP evidence;
5. deterministic compatibility analysis;
6. bounded model proposals.

It then asks the smallest meaningful question. It never asks a business owner to write regex,
manifest JSON, vector thresholds, CEL, projections or URL placeholders.

### 7.2 Business Line questions

- What does this Business Line help the organization accomplish?
- What must it never be treated as responsible for?
- Who owns its business meaning, technical integration and security decisions?
- Which organizational functions should use it?
- Which business objects does it work with?
- Is access organization-wide or restricted to particular objects/books/accounts?
- Which service is authoritative for that access decision?
- What must memory preserve, redact and eventually discard?
- How should Conduit ask for missing information?

### 7.3 Use Case questions

- What should the user be able to accomplish?
- Provide real questions users ask, not keywords.
- Which similar questions belong elsewhere?
- Which requests are unsupported or unsafe?
- What context is required before an answer is safe?
- Which system is the source of record?
- What output, figures or disclosures must be returned?
- What does partial, stale or unavailable data mean?
- When should Conduit clarify, abstain, deny or provide a partial answer?

### 7.4 Signal taxonomy

The UI presents signals in plain language and records them separately:

| Signal | Purpose | Runtime projection |
|---|---|---|
| Positive intent | Questions this Use Case owns | route-target embeddings and submitted/held-out evals |
| Boundary/neighbor | Similar questions owned elsewhere | adversarial ownership tests, not positive embeddings |
| Unsupported/off-topic | Questions Conduit should decline | abstention tests |
| Ambiguity | Missing choice that requires clarification | clarification tests and required context |
| Entity/context | Business object and literal references | context-group entity definitions |
| Dependency | Upstream capability needed for the outcome | semantic `io.consumes/from` DAG contract |
| Authorization | Who may invoke/access data | policy/coverage gates, never routing evidence |

Authorization must not be encoded as semantic routing. Routing may identify a requested capability;
structural and coverage gates independently decide whether it may be served.

### 7.5 Consent records

Consent is purpose-specific, versioned and revocable:

```text
DOCUMENT_READ
SPEC_OR_TOOL_INSPECTION
NON_PRODUCTION_PROBE
REDACTED_EVIDENCE_RETENTION
MODEL_ASSISTED_ANALYSIS
SANDBOX_PUBLICATION
```

Each record contains actor, tenant, project, purpose, scope, target, dossier/evidence hash, granted
at, expiry/revocation and policy version. Consent to inspect does not imply consent to probe;
consent to probe does not imply approval to publish or activate.

---

## 8. Intent classification and routing architecture

### 8.1 Preserve the interaction classifier

The four-way classifier remains domain-blind. Rename its UI/telemetry label to `interaction_intent`
where practical so users do not confuse it with the business Use Case.

Do not generate an enum member such as `RENEWAL_RISK` or `HOLDINGS`. Business intent remains catalog
data and can grow without a gateway code release.

### 8.2 Demo-safe first runtime integration

This is the final runtime integration after the UI-first artifact milestone has frozen and proven the
package contract:

1. Resolve the request tenant and capture one catalog version.
2. Build the current combined interaction/entity prompt only from that tenant catalog.
3. Require compatible definitions when an entity key/extraction field is shared across context
   groups; otherwise namespace it or block activation.
4. Retrieve route targets from the tenant/catalog-version shadow or active index.
5. Preserve current masking, grounding, reranking, score/margin abstention, authorization and DAG
   execution behavior.
6. Add Use Case/route-target identity to the decision trace without changing authorization.

This minimizes risk while removing the current global cross-tenant domain/entity seam.

### 8.3 Scale-safe target

As the number of Business Lines grows, a catalog-wide entity prompt becomes large and can contain
incompatible entity semantics. The target pipeline is:

```text
verified tenant + pinned catalog
  -> domain-neutral interaction classification
  -> coarse top-K route-target retrieval (no service/data call)
  -> candidate context-group vocabulary
  -> scoped entity extraction and multi-interpretation grounding
  -> mask grounded entity spans
  -> final contextual route-target retrieval
  -> rerank / score+margin abstain
  -> requested Use Case/capability groups
  -> structural authorization
  -> coverage CHECK per group
  -> DAG expansion and per-node re-authorization/re-coverage
  -> invoke and grounded synthesis
```

The coarse pass is only a shortlist hint and cannot authorize, invoke or deny. The final masked pass
remains authoritative for routing. Ship this behind a parity feature flag after current routing
fixtures prove no regression.

### 8.4 Routing admission requirements

Every new/changed Use Case is evaluated in a shadow index containing the full target catalog:

- positive route ownership;
- same-Business-Line neighbor ownership;
- cross-Business-Line poaching;
- unsupported/off-topic abstention;
- ambiguity/required-context clarification;
- multi-facet grouping;
- entity-name invariance and entity/capability conflict;
- before/after top-K recall, selected goal and abstention margins;
- persona-aware production decision path for structural/coverage outcomes.

The candidate cannot pass only because its own prompts route correctly. A regression to an existing
Use Case is blocking unless the affected owner approves an explicit ownership transfer and held-out
evidence is rebaselined independently.

---

## 9. Deterministic compilation

The compiler consumes confirmed versions and produces canonical bytes with zero model/network calls.

### 9.1 Candidate package

```text
bundle.json
contract.md
dossier.json
business-lines/<business-line-id>.json          only when new/changed
use-cases/<use-case-id>.json
domains/<domain-id>.json                        only when new/changed
domains/<domain-id>/<context-group-id>.json     complete new version when changed
manifests/<domain-id>/<agent-id>.json            only when new/changed
routing/route-targets.json
routing/positive.json
routing/boundaries.json
routing/held-out.refs.json
eval/contract.json
eval/authorization.json
eval/coverage.json
eval/composition.json
policy/proposal.yaml                            optional, never auto-applied
iam/business-line-binding.json                  optional proposed activation effect
provenance.json
limitations.json
compile-report.json
```

### 9.2 Projection rules

- Business Line shared facts compile to the domain manifest.
- Context, entity, coverage-scope and clarification facts compile to a complete context-group
  manifest version.
- Use Case business ownership compiles to the Use Case definition, route targets and eval assets.
- Agent identity, service connection, skills, constraints and semantic I/O compile to the agent
  manifest.
- An existing domain is referenced byte-for-byte when no shared fact changed.
- A context-group update emits the complete membership list, never an append patch.
- Generated positive examples cannot be labeled as team-submitted or reviewer-held-out.
- Secrets never enter package artifacts.
- Every artifact field maps to a dossier fact, observed evidence item or versioned compiler rule.
- Same inputs plus schema/compiler/config versions produce the same package hash.

### 9.3 Snapshot tests

Canonical snapshot tests are the primary lightweight regression layer, but they do not replace:

- JSON Schema validation;
- bidirectional reference validation;
- semantic I/O and DAG validation;
- security/coverage hard assertions;
- shadow routing regression;
- one approved live non-production contract probe.

---

## 10. Catalog activation and gateway changes

### 10.1 Immutable catalog snapshot

One catalog snapshot contains exact references/hashes for Business Lines, Use Cases, context groups,
agent manifests, route targets, schema versions, expression dialect, embedding model and policy
bindings.

The external registry-ingestion component (currently assembled from the registry profile), not
Studio, the browser or the request-path gateway, owns runtime materialization.

### 10.2 Activation protocol

```text
1. Studio submits exact approved bundle hash + expected base catalog hash.
2. Registry verifies signature/context, approvals and certification freshness.
3. Registry loads and revalidates every artifact and cross-reference.
4. Registry introspects changed agents and verifies pinned observed contracts.
5. Registry writes versioned agent records under the candidate catalog hash.
6. Registry builds a shadow vector index named by tenant + catalog hash.
7. Registry runs readiness probes against the complete candidate.
8. Registry writes the immutable catalog snapshot.
9. Registry compare-and-sets the tenant/environment active pointer.
10. Gateway replicas load/ack the new snapshot before the release is declared complete.
11. Registry returns an immutable activation receipt.
```

No in-place mutation of the active index, agent keys or `DomainManifestStore` maps is allowed.

### 10.3 External ingestion and gateway read seam

Separate the mutation and read responsibilities explicitly:

```text
external registry ingestion:
  ingest(candidatePackage, tenantId, expectedCatalogVersion) -> ActivationReceipt

request-path gateway:
  capture(tenantId, requestedCatalogVersion?) -> ImmutableCatalogSnapshot
```

The external ingestion component reuses the shared admission implementation; it is not a second
manifest implementation. The request-path gateway has no ingestion endpoint, ingestion bean or
registry write credential. It captures the installed snapshot and passes it through
interaction/entity extraction, grounding, routing, effective-manifest resolution, authorization,
planning, invocation and telemetry. A request must never read “current” twice and observe two
catalog versions.

The chat BFF pins the catalog version when a conversation starts and sends it on subsequent turns.
The registry retains old versions for a configured drain/rollback period. A retired version fails
closed after that period with a resumable “catalog changed” response rather than mixing state.

### 10.4 Compatibility import

During bootstrap/migration, import the current filesystem registry as catalog version
`legacy-import-v1`:

- preserve canonical artifact bytes;
- create provisional Use Cases from current skills and goal agents;
- detect and report reverse-membership gaps;
- require owners to confirm provisional business contracts before later material edits;
- prove the imported catalog produces the same current routing decisions before switching reads.

Filesystem loading remains a developer bootstrap only. It is not the production mutation path.

---

## 11. Studio APIs

All mutations require authenticated Axiom identity, tenant scope, idempotency key and expected
aggregate/catalog version.

```text
GET/POST   /studio/business-lines
GET/PATCH  /studio/business-lines/{id}
POST       /studio/business-lines/{id}/change-projects

GET/POST   /studio/business-lines/{id}/use-cases
GET/PATCH  /studio/use-cases/{id}
POST       /studio/use-cases/{id}/change-projects

POST       /studio/projects
GET        /studio/projects/{id}
GET        /studio/projects/{id}/questions
POST       /studio/projects/{id}/answers
POST       /studio/projects/{id}/proposals/{proposalId}:accept
POST       /studio/projects/{id}/consents

POST       /studio/projects/{id}/connections:inspect
POST       /studio/projects/{id}/connections:probe
GET        /studio/projects/{id}/evidence

POST       /studio/projects/{id}/packages:generate
GET        /studio/packages/{bundleHash}
POST       /studio/packages/{bundleHash}:certify
POST       /studio/packages/{bundleHash}:submit-approval
POST       /studio/packages/{bundleHash}:promote

GET        /studio/catalogs/active
GET        /studio/catalogs/{hash}/diff
POST       /studio/catalogs/{hash}:rollback
GET        /studio/audit
```

Registry-profile-only bridge:

```text
GET  /registry/v1/catalogs/{tenant}/{environment}/active
POST /registry/v1/candidates:dry-run
POST /registry/v1/candidates:shadow-index
POST /registry/v1/activations
GET  /registry/v1/activations/by-idempotency-key/{key}
POST /registry/v1/rollbacks
```

The browser never receives registry mutation credentials.

---

## 12. Studio UI

Build a separate `onboarding-studio-web`; do not hide this workflow inside Axiom Policy Studio or
the end-user Chat application.

### 12.1 Primary navigation

```text
Business Lines
Use Cases
Onboarding Projects
Catalog
Approvals
Audit
```

### 12.2 Business Line page

Show:

- purpose, owners and lifecycle;
- active Use Cases and their readiness;
- shared access/coverage/memory policy;
- reusable capabilities and connected agents;
- current catalog version and last activation;
- “Add Use Case” and role-gated “Change Business Line policy” actions.

Do not show a list of sub-domain JSON files.

### 12.3 Use Case workspace

Sections:

1. Outcome and owner.
2. Users and business context.
3. Questions it owns.
4. Similar questions it does not own.
5. Required information.
6. Source of record and output.
7. Access and coverage.
8. Reused and missing capabilities.
9. Connected agent inspection.
10. Tests and routing neighbors.
11. Contract review.
12. Generated Conduit Package.
13. Certification, approvals and activation.

An assistant panel may propose answers and explain gaps, but the structured contract remains the
primary record.

### 12.4 Impact preview

Before saving any material change, show:

```text
Business Line manifest: unchanged | changed
Context group: unchanged | changed
Use Case route target: changed
Agent manifest: unchanged | changed
Affected existing Use Cases: [list]
Approvals invalidated: [list]
Tests to rerun: [list]
```

This is essential to explaining why adding a Use Case usually does not rewrite the Business Line.

### 12.5 Flagship “wow” screen

The final review presents three synchronized views:

1. **Business contract:** what users can now ask and where boundaries lie.
2. **Conduit plan:** existing capabilities reused, missing agent connected, dependencies discovered.
3. **Proof:** before/after routing, denial/clarification behavior, package hash and activation receipt.

The main action is **Generate Conduit Package**, not “Compile manifest.”

---

## 13. Authorization, approval and audit

### 13.1 Roles

```text
onboarding_submitter
business_line_editor
business_line_owner
technical_owner
security_reviewer
platform_reviewer
release_manager
auditor
platform_admin
```

Role plus tenant plus Business Line scope plus project state are required. `platform_admin` is not
automatically the business or security approver.

### 13.2 Required approvals

| Change | Required approval |
|---|---|
| Existing enterprise knowledge Use Case | Business Line owner + platform reviewer |
| Resource-scoped Use Case | Business Line owner + security + platform |
| New Business Line | Business Line owner + security + platform |
| Shared coverage/memory policy | Business Line owner + security + platform |
| Composable capability | Business owner + platform; security when classified/resource-scoped |
| Production activation | Release manager distinct from last material editor |

Approvals bind exact dossier, package, certification and target catalog hashes. Any material change
invalidates dependent approvals.

### 13.3 Axiom integration

- Add `conduit-onboarding-studio` OIDC audience/client.
- Derive tenant from the verified token/session, never request body or `default` constants.
- Add Business Line scopes to principal/team claims or query through an authorized service seam.
- Create/update Axiom domain groups only during approved activation.
- Generate policy proposals from confirmed access semantics; never auto-apply model-generated policy.

Every proposal, answer, consent, compile, certification, approval, activation and rollback emits a
tenant-partitioned audit event.

---

## 14. Existing demo conversion

The flagship demo should prove both onboarding shapes without inventing a large new business system.

### 14.1 Demo A: new Use Case in an existing Business Line

Use the existing Insurance services:

1. Baseline catalog contains Policy Details and Claim Status but excludes Renewal Risk.
2. In Studio, open Insurance and choose **Add Use Case**.
3. Describe: “Assess whether a commercial policy needs renewal review using policy and claims
   evidence.”
4. Connect the already-running `meridian.insurance.renewal_risk` service.
5. Studio inspects the service and discovers that the goal consumes existing
   `insurance.policy_record` and `insurance.claim_status` outputs.
6. Studio proposes reuse of the existing two capabilities and the `claims-servicing` context group.
7. The owner confirms positive questions, boundaries and the limitation that Conduit does not make
   an autonomous non-renewal decision.
8. The package diff proves: domain manifest unchanged; context-group membership changed; Use Case,
   agent and eval artifacts added.
9. Shadow routing shows the renewal question moving from no correct goal to the Renewal Risk goal,
   with no Policy Details/Claim Status regression.
10. Activate the catalog hash and ask the question in Chat; the existing DAG executes.

This is the clearest proof that a Use Case can reuse multiple existing capabilities while one agent
acts as the routed goal.

### 14.2 Demo B: first Use Case in a new Business Line

Use the existing HR policy service:

1. Baseline catalog excludes HR.
2. Create Business Line **Human Resources**.
3. Define first Use Case **Answer employee policy questions**.
4. Connect the existing HR policy agent and inspect its HTTP contract.
5. Confirm enterprise audience, internal classification, no resource-scoped coverage, HR topic
   vocabulary and memory policy.
6. Generate a package containing a new domain, context group, Use Case and agent manifest.
7. Before activation, “What is the parental leave policy?” abstains in the baseline catalog.
8. After the generated package passes the external registry-ingestion path, the same question is
   served by HR.
9. Show the route trace labeled Business Line `Human Resources`, Use Case `Answer employee policy
   questions`, goal capability and structural authorization result.
10. Roll back to the prior catalog hash and show the question abstaining again.

### 14.3 Demo topology changes

Add:

```text
services/onboarding-studio/      Spring Boot 4, Java 21
apps/studio/web/                 React + TypeScript
PostgreSQL schema/database       studio control-plane state
existing MinIO                   evidence and packages
registry-ingestion API         dry-run, shadow index, exact-hash activation
gateway catalog view           tenant/version-scoped immutable read only
```

For the local demo, reuse the existing PostgreSQL and MinIO containers with separate schema/bucket
and credentials. Production keeps Studio persistence logically isolated from IAM. The existing
read-only `./registry` mount remains the legacy import seed; activated catalogs use versioned
runtime materialization, not writes into the source tree.

### 14.4 Demo runbook correction

Replace the current manual-file claim with:

> “Describe the Business Line and Use Case, connect the existing service, confirm ownership and
> boundaries, then generate and certify a Conduit Package. Conduit submits that exact package to the
> external registry-ingestion engine, which validates, introspects, indexes and activates one matching
> tenant catalog version. The serving gateway only reads and executes that activated version.”

The demo claim is successful governed ingestion, not a particular container-startup mechanism.

---

## 15. Repository change map

This map makes the implementation impact explicit. File names describe the current seams; new types
may be placed in extracted modules after characterization.

| Area | Existing seam | Required change |
|---|---|---|
| Manifest contracts | `registry/*-manifest.schema.json`, gateway resource copies | Add Use Case, route-target and catalog-snapshot schemas; keep runtime schema copies hash-pinned |
| Cross-reference tests | `tests/schema/test_registry_contracts.py` | Validate both directions of context-group membership plus Use Case/capability/route-target references |
| Shared semantics | gateway manifest models/validators, `SelectContractValidator`, DAG validation | Extract domain-free models and static admission into `libs/conduit-admission` with parity fixtures |
| Domain/context reads | `DomainManifestStore` | Make it an adapter over one captured `ImmutableCatalogSnapshot`; remove global request-path unions/current rereads |
| Interaction/entity extraction | `IntentClassifier` | Accept tenant/catalog snapshot; label result interaction intent; initially use tenant-compatible vocabulary, later split shortlist-scoped extraction |
| Routing corpus | `VectorIndexWriter`, `VectorIndex`, `RoutingCandidate` | Version index by catalog hash and carry Use Case/route-target/capability/skill metadata |
| Route selection | `AgentResolver` | Select route targets, preserve current score/margin/rerank/abstain semantics, resolve goal agent after selection |
| Production route | `RoutePreparer`, `ChatService`, `RequestedPlan`, `RouteDecision` | Thread one catalog snapshot; group by requested Use Case/capability; keep structural/coverage/DAG gates unchanged |
| Agent runtime registry | `AgentRegistry`, `AgentRegistrar`, `RegistryIngestor` | Keep `AgentRegistry` read-only in the serving gateway; run registrar/ingestor externally to import legacy folders, dry-run candidates, build shadow indexes and activate/roll back atomically |
| Readiness | `RegistryReadinessVerifier` | Verify tenant, catalog hash, schema/expression dialect, embedding model, complete snapshot and non-empty matching index |
| Registry API | current registration/admin controllers | Host service-identity-protected dry-run, shadow, activation, receipt and rollback endpoints only in the external registry-ingestion component; expose none in the serving gateway |
| Tenant directory | gateway tenancy snapshot classes | Carry or resolve active catalog hash alongside tenant/policy readiness without mixing directory versions |
| Conversation pin | `apps/chat/bff` `Conversation`, `GatewayClient`, controllers/tests | Persist `catalogVersion`; send `X-Conduit-Catalog-Version`; handle expired/drained version explicitly |
| Chat glass box | `apps/chat/web` trace parsing/rail | Show Business Line, Use Case, goal capability, catalog version and gate outcome |
| IAM tenancy | `GroupService`, domain/team controllers and repositories | Remove `default` constants; derive verified tenant; add Business Line-scoped roles and approved activation seam |
| Studio backend | new `services/onboarding-studio` | Spring Boot 4 API, workflow, persistence, compiler, jobs, consent, evidence, certification and promotion clients |
| Studio frontend | new `apps/studio/web` | Business Line/Use Case/project workflow and package proof UI |
| Local topology | `docker-compose.yml`, Flyway/bootstrap, MinIO init | Add Studio API/web, Studio schema/database config, object bucket and external registry-ingestion connectivity |
| Routing evidence | `eval/goal-pick/*`, `scripts/smoke-route.sh` | Add expected Use Case/route target, candidate before/after gates and HR/renewal activation sentinels |
| End-to-end evidence | gateway/unit tests, security harness, Playwright | Add import, compile, consent, tenant isolation, activation concurrency, version pin/drain and rollback suites |
| Demo narrative | leadership runbook | Replace manual file editing with the exact Studio/package/external-ingestion/gateway-read behavior |

### 15.1 Request contract addition

The Chat BFF, not the browser, obtains and pins the catalog version. It sends:

```text
X-Conduit-Catalog-Version: sha256:<catalog hash>
```

The gateway verifies that the requested version belongs to the resolved tenant and is active or
within its allowed drain window. A client cannot use this header to select another tenant's catalog.
The debug route endpoint accepts an explicit target catalog only under the certification service
identity; ordinary persona requests use their conversation pin.

### 15.2 Read-path migration rule

Do not simultaneously change routing thresholds, embedding model, intent prompt, entity masking and
catalog storage during the migration. First reproduce the legacy imported catalog's current route
decisions with the versioned gateway catalog view. Only after parity passes should route-target metadata and
the optional two-pass scoped extractor be enabled in separately measured changes.

---

## 16. Test and certification matrix

### 16.1 Import and schema

- Import all current domain/sub-domain/agent artifacts byte-identically.
- Detect all four current reverse-membership gaps.
- Reject unknown parent, context group, agent, Use Case, capability and route-target references.
- Reject required context missing from entity definitions/clarification contracts.
- Reject duplicate/conflicting entity keys in one tenant catalog.
- Reject resource-scoped context without a complete resolvable coverage contract.

### 16.2 Compiler snapshot fixtures

Required canonical fixtures:

1. existing Business Line, reused capability, no agent change;
2. existing Business Line, new single-step agent;
3. existing Business Line, new composable goal reusing two producers;
4. new enterprise knowledge Business Line;
5. new resource-scoped Business Line;
6. changed Business Line coverage policy;
7. retirement and rollback;
8. insufficient/contradictory evidence failure.

Each fixture pins dossier, compiler, schemas, configuration, expected file set, canonical bytes and
bundle hash.

### 16.3 Routing

- Extend `eval/goal-pick` rows with `expected_use_case`, `expected_route_target` and expected goal.
- Preserve existing capability/domain accuracy and off-topic abstention sentinels.
- Add same-Business-Line and cross-Business-Line neighbor rows.
- Add before/after candidate admission comparison.
- Add entity-name invariance, multi-turn carry/switch and entity/capability conflict rows.
- Add route-target tests for multiple skills on one agent and one capability reused by multiple Use
  Cases.

### 16.4 Authorization and coverage

- cross-tenant Studio resource denial;
- Business Line scoped editor denial outside scope;
- submitter cannot self-approve;
- policy/coverage changes invalidate approvals;
- structurally denied route never invokes;
- out-of-coverage and empty-book fail closed;
- coverage outage fails closed;
- resolution remains principal-agnostic;
- DAG-added producers are re-authorized/re-covered;
- no consent purpose is widened implicitly.

### 16.5 Activation and concurrency

- stale expected catalog hash rejected;
- duplicate idempotency key returns same receipt;
- failed shadow build leaves active catalog unchanged;
- partial agent/index/snapshot materialization is unreachable;
- two concurrent activations yield one winner and one typed conflict;
- every request reads one catalog version;
- conversation version pin drains correctly;
- rollback activates exact previous hash;
- gateway replica acknowledgment timeout produces a failed/partial promotion, not false success.

### 16.6 End to end

- Insurance renewal-risk before/after route and DAG execution.
- HR before/after new Business Line ingestion and activation.
- Browser journey from project creation through consent, inspection, contract confirmation, package,
  certification, approval and activation.
- Audit export reconstructs who declared, approved, generated, certified and activated every field.

---

## 17. Observability and product metrics

Add catalog/use-case attributes to existing traces without recording raw sensitive examples:

```text
conduit.catalog.version
conduit.business_line.id
conduit.use_case.id
conduit.route_target.id
conduit.capability.id
conduit.project.id
conduit.bundle.hash
conduit.activation.receipt_id
```

Metrics:

- time from project creation to first complete contract;
- percentage of questions auto-answered from evidence versus requiring owner input;
- capabilities reused versus newly connected;
- package generation/certification duration;
- routing neighbor regressions caught before activation;
- activation/rollback success and replica acknowledgment latency;
- post-activation abstention, misroute and clarification change by Use Case;
- drift between observed agent contract and certified hash.

Never use a single “AI onboarding score” to conceal a blocking security, coverage, schema or ownership
failure.

---

## 18. Technology and module boundaries

- Studio API/control plane: Spring Boot 4, Java 21.
- Shared manifest/admission/compiler models: pure Java module with no Spring dependency.
- External registry ingestion: evolve the existing `registry` profile/container to consume shared
  admission/artifact SDKs; a new deployable requires an ADR.
- Request-path gateway: no ingestion surface; adopts shared read contracts during final integration.
  Its Spring Boot 4 target requires the Jackson/JMESPath/SSE compatibility ADR before migration.
- UI: React + TypeScript with generated API client after contracts freeze.
- Database: PostgreSQL with Flyway, optimistic locking and optional tenant RLS defense in depth.
- Evidence/package store: S3-compatible MinIO locally.
- Jobs/outbox: PostgreSQL leased workers; `SKIP LOCKED` only for queue-like job claims.
- Runtime routing/index: Redis Stack, catalog-versioned and derived.
- Identity: Axiom OIDC BFF session; no reusable token in browser local storage.

Do not clone gateway source into Studio. Extract domain-free admission behavior into
`libs/conduit-admission`, characterize it, and make both services consume the same semantics.

---

## 19. Execution order

### Gate 0 — Freeze language and fixtures

- Accept this hierarchy and replace public “Case/Domain/Sub-domain” Studio nouns.
- Add Use Case/route-target schemas.
- Freeze legacy import plus Insurance/HR before-after fixtures.
- Add reverse-membership validation to expose current drift.

### Gate 1 — Catalog and admission foundation

- Extract/shared admission models and characterize current behavior.
- Implement canonical `CatalogSnapshot` and importer.
- Implement PostgreSQL Studio core, versioning, audit and outbox.
- Implement deterministic package compiler and snapshot tests.

### Gate 2 — Non-mutating flagship path

- Build Studio API/web shell.
- Implement connection inspection and purpose-specific consent.
- Implement Business Line and Use Case contract workflow.
- Generate packages and run registry dry-run/shadow routing without mutation.

### Gate 3 — Tenant/version runtime

- Implement catalog-versioned agent records and vector indexes.
- Implement the versioned gateway catalog view and per-request version capture.
- Remove global tenant-unsafe domain/entity reads from the request path.
- Add Use Case/route-target identity to route decision and traces.

### Gate 4 — Approval and activation

- Implement exact-hash certification, approvals and separation of duties.
- Implement compare-and-set activation, replica acknowledgment and receipt lookup.
- Implement conversation pinning, drain and rollback.

### Gate 5 — Demo and hardening

- Rehearse Insurance Use Case and HR Business Line demos.
- Update leadership runbook and screenshots.
- Run full routing, security, coverage, DAG, E2E, failure and concurrency suites.
- Enable production mutation only after rollback rehearsal succeeds.

The UI can be built against frozen fixtures after Gate 0, but it must not define backend semantics.
The guidance model can be temporarily stubbed; deterministic contracts, compilation and admission are
the critical path.

---

## 20. Definition of done

The flagship slice is complete only when all of the following are true:

1. The UI visibly distinguishes Organization, Business Line, Use Case, Capability and Connected
   Agent.
2. An existing agent can be inspected and onboarded without users editing JSON/YAML.
3. Positive, boundary, unsupported and ambiguity signals are collected with explicit provenance.
4. Inspection/probe/model/storage/publication consents are distinct and auditable.
5. Adding a Use Case to Insurance proves the Insurance domain manifest is unchanged.
6. Creating HR proves a new domain manifest is generated from confirmed shared facts.
7. The compiler produces byte-identical packages for identical inputs.
8. The shadow index proves candidate gain and no unapproved catalog poaching.
9. Activation swaps one tenant catalog hash atomically and returns a receipt.
10. The external registry-ingestion engine accepts the package and the read-only gateway serves the
    new Business Line from the activated catalog version.
11. Every request and conversation observes one pinned catalog version.
12. Structural authorization, resource coverage and DAG per-node gates remain fail closed.
13. A rollback to the previous catalog hash succeeds and is visible in Chat and audit.
14. Existing routing, coverage, security, observability and World-B checks remain green.
15. The demo statement matches the behavior actually implemented.

---

## 21. Explicit non-goals for the first flagship release

- Building or rewriting the customer's agent/service.
- Write/mutating agents.
- Arbitrary workflow/control-flow generation.
- Autonomous policy application or production activation by a model/agent.
- A graph database.
- A general-purpose BPM engine.
- Replacing Axiom as identity authority.
- Replacing the gateway's deterministic routing, coverage, authorization or execution gates.
- Treating generated tests as independent held-out evidence.
- Renaming the existing runtime schema from `sub_domain` before compatibility migration is proven;
  the product hides/reinterprets it as Context Group first.
