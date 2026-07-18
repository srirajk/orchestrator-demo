# Use Case Intake Workflow and Blocking Checks

## 1. First-build decision

The first Studio release is a workflow product for every Business Line. It does not begin with RBAC,
approval or production activation. It begins with a consistent intake experience that shows what is
known, what is missing, which checks can run now, and why the user cannot advance.

RBAC remains M3. M2 may use a clearly isolated demo principal/profile, but it must not introduce
hard-coded role decisions into UI components or workflow rules. Workflow blocking is based on
business/technical readiness checks, not user permissions.

## 2. Existing design system is the visual source of truth

Studio must look like the Conduit/Axiom product family already in the repository. Reference files:

```text
admin-ui/tailwind.config.js
admin-ui/src/index.css
admin-ui/src/components/Layout.tsx
admin-ui/src/components/Sidebar.tsx
admin-ui/src/components/ui/Button.tsx
admin-ui/src/components/ui/Badge.tsx
admin-ui/src/components/ui/Dialog.tsx
admin-ui/src/components/ui/EmptyState.tsx
admin-ui/src/components/ui/Input.tsx
admin-ui/src/components/ui/Skeleton.tsx
admin-ui/src/components/ui/Toast.tsx
apps/chat/web/src/components/TraceRail.tsx
```

The Admin and Chat Tailwind configurations already agree on the core system:

- Inter/system sans typography;
- `canvas #f5f7fa`, white `panel`, `line #d8dee8`;
- ink text scale;
- Axiom navy `50–950`;
- gold `50–900` accent/focus scale;
- `shadow-enterprise` and gold focus treatment;
- navy sidebar, white content surfaces, compact enterprise density;
- Lucide icons;
- rounded-md/rounded-lg controls and cards;
- visible gold keyboard focus.

### 2.1 Reuse rule

The first build creates Studio-local design-system primitives that are characterization-compatible
with these existing files. It does not redesign the brand and does not refactor Admin/Chat into a new
shared package during the flagship milestone.

Required Studio primitives:

```text
AppShell, SidebarNav, PageHeader, Breadcrumbs
Button, Badge, Input, TextArea, Select, Checkbox, RadioGroup
Dialog, Drawer, Toast, Skeleton, EmptyState
SurfaceCard, SurfacePanel, StatCard, DataTable
WorkflowStepper, ReadinessRail, CheckRow, MissingItemCard
ProvenanceBadge, StatusBadge, ImpactBadge
```

No packet may invent a second palette, gradient language, border radius, focus style or status color
system. Any new primitive must be justified by a Studio interaction absent from the existing system.

## 3. Business Line experience

The same page structure applies to Asset Servicing, HR, Insurance and Wealth Management and to every
future imported Business Line. No Business Line gets a custom React page or domain-specific code.

```text
Business Line header
  purpose · owner status · catalog health · counts

Tabs
  Overview | Use Cases | Capabilities | Connected Agents | Runtime Structure | Import History

Primary action
  Define a Use Case
```

The page is generated from the catalog read model. Business Line-specific wording, entity names,
coverage behavior and clarification copy come from manifests/dossiers, never UI constants.

Each Use Case card shows:

- business outcome and lifecycle;
- goal/dependency capability count;
- connected agent count;
- readiness percentage expressed as passed required checks, not an AI score;
- blocking check count and next required action;
- last successful check time and input hash;
- **Continue intake**.

## 4. Use Case intake workspace

Desktop uses three coordinated regions:

```text
┌──────────────────┬─────────────────────────────────────┬──────────────────────┐
│ Workflow steps   │ Active intake form/review           │ Readiness checks     │
│                  │                                     │                      │
│ 1 Outcome        │ Business-language questions         │ 8 passed             │
│ 2 Signals        │ Evidence and provenance             │ 2 blocking           │
│ 3 Capabilities   │ Suggested/reused agents             │ 1 not run            │
│ 4 Context        │ Impact preview                      │                      │
│ 5 Composition    │                                     │ Fix next: …          │
│ 6 Review         │                                     │ Run checks           │
│ 7 Package        │                                     │                      │
└──────────────────┴─────────────────────────────────────┴──────────────────────┘
```

At narrow widths, the stepper becomes a top progress control and the readiness rail becomes a
persistent bottom-sheet/drawer with a visible blocking count. Checks are never hidden behind an
assistant conversation.

### 4.1 Workflow states

```text
DRAFT
OUTCOME_READY
SIGNALS_READY
CAPABILITIES_READY
CONTEXT_READY
COMPOSITION_READY
REVIEW_READY
PACKAGE_READY
GATEWAY_READY       M4 only
```

State is derived from required check results over one dossier version; it is not manually set by the
browser or model. A changed answer invalidates dependent checks and may move the derived readiness
backward while preserving history.

### 4.2 Navigation rule

- Users may revisit any completed or current step.
- Users may preview later steps read-only to understand what is coming.
- **Continue** is disabled when the current step has blocking `FAIL`, `BLOCKED`, `STALE` or required
  `NOT_RUN` checks.
- Clicking the disabled explanation focuses the first actionable missing item.
- Warnings do not block, but require acknowledgment on Review.
- No “Skip anyway” exists for required checks.
- Platform-gap checks produce `UNSUPPORTED_REQUIREMENT`; they do not trap the user in a spinner.

## 5. Check contract

```text
CheckDefinition
  code
  version
  title
  description
  stage
  severity: BLOCKING | WARNING | INFO
  executor: STUDIO_STATIC | SHARED_ADMISSION | REGISTRY_DRY_RUN | GATEWAY_RUNTIME | HUMAN_DECISION
  prerequisites[]
  invalidatedByDossierPaths[]
  remediationType

CheckResult
  checkCode
  checkVersion
  status: NOT_RUN | RUNNING | PASS | FAIL | BLOCKED | STALE | UNSUPPORTED
  inputHash
  catalogHash
  startedAt/completedAt
  summary
  issues[]: code, path, message, remediation, evidenceRefs
  executorVersion
```

Check results are append-only evidence. The current view selects the latest result whose input hash
matches the current dossier/catalog/policy. A result with an old hash is `STALE`, never silently
treated as pass.

## 6. Stage check catalog

Thresholds are supplied by versioned intake policy. The UI never hardcodes example counts.

### Stage 1 — Outcome

| Code | Blocks when | Executor |
|---|---|---|
| `UC_IDENTITY_COMPLETE` | ID, display name or outcome missing | Studio static |
| `UC_BUSINESS_LINE_VALID` | Business Line missing/unknown/retired | Studio static |
| `UC_OWNER_DECLARED` | accountable business owner absent | Human decision |
| `UC_ACTOR_AND_BOUNDARY_DECLARED` | intended actor or explicit non-decision absent | Human decision |
| `UC_ID_UNIQUE` | ID collides in tenant/catalog | Studio static |

### Stage 2 — Signals

| Code | Blocks when | Executor |
|---|---|---|
| `UC_POSITIVE_SIGNALS_SUFFICIENT` | approved positive set below policy | Studio static |
| `UC_BOUNDARY_SIGNALS_SUFFICIENT` | neighbor/boundary set below policy | Studio static |
| `UC_UNSUPPORTED_SIGNALS_SUFFICIENT` | refusal/abstention set below policy | Studio static |
| `UC_AMBIGUITY_BEHAVIOR_DECLARED` | ambiguity/clarification examples absent | Human decision |
| `UC_SIGNAL_PROVENANCE_VALID` | signal has no valid origin/owner | Studio static |
| `UC_SIGNAL_CONTRADICTIONS_RESOLVED` | same question has incompatible ownership | Human decision |

Demo and production thresholds use different policy versions. Demo policy may use a small explicit
set; production certification retains the larger evidence policy. Neither value appears in React.

### Stage 3 — Capabilities and agents

| Code | Blocks when | Gateway/admission relationship |
|---|---|---|
| `UC_GOAL_CAPABILITY_EXACTLY_ONE` | zero/multiple goals | DAG/route preparation invariant |
| `UC_CAPABILITY_BINDINGS_COMPLETE` | capability lacks agent/skill binding | Agent registry invariant |
| `AGENT_MANIFEST_SCHEMA_VALID` | candidate manifest invalid | `ManifestValidator` |
| `AGENT_PROTOCOL_SUPPORTED` | protocol/operation/tool unsupported | `AgentIntrospector` policy |
| `AGENT_SKILL_REFERENCE_VALID` | skill missing/duplicate | registrar/cross-reference validation |
| `AGENT_CONTEXT_MEMBERSHIP_BIDIRECTIONAL` | agent/Context Group disagree | catalog contract validator |
| `AGENT_OBSERVED_CONTRACT_MATCHES` | declared/live contract mismatch | registry dry-run introspection, M3/M4 |

In M2, schema/reference checks run locally through frozen/shared contracts. Live observed-contract
checks remain `NOT_RUN` until consent-bound inspection exists and block only the later Gateway Ready
state, not Package Ready.

### Stage 4 — Context, access and coverage design

| Code | Blocks when | Gateway/admission relationship |
|---|---|---|
| `CONTEXT_REQUIRED_KEYS_DEFINED` | required key lacks entity definition | `DomainManifestStore` contract |
| `CONTEXT_CLARIFICATION_DEFINED` | required key lacks clarification | deterministic CLARIFY invariant |
| `CONTEXT_KEYS_COMPATIBLE` | reused key has conflicting semantics | catalog activation invariant |
| `COVERAGE_DESIGN_COMPLETE` | resource-scoped Use Case lacks discover/check/resolve design | coverage gate contract |
| `AUTHORIZATION_REQUIREMENTS_DECLARED` | audience/access/classification absent | structural authorization input |

This stage captures security design facts but does not implement RBAC in M2. M3 later determines who
may edit/approve them.

### Stage 5 — Composition

| Code | Blocks when | Gateway/admission relationship |
|---|---|---|
| `PLAN_DEPENDENCIES_RESOLVABLE` | required producer missing | DAG resolution invariant |
| `PLAN_ACYCLIC` | dependency cycle exists | DAG validation |
| `PLAN_INPUT_CONTRACT_VALID` | bound input misses required fields | `InputContractValidator` |
| `PLAN_SELECT_CONTRACT_VALID` | projection cannot satisfy consumer | `SelectContractValidator` |
| `PLAN_CONDITION_VALID` | unsafe/invalid condition | `SelectContractValidator`/evaluation policy |
| `PLAN_MAP_BOUNDED` | map input/cap missing or exceeds policy | bounded-map admission |
| `PLAN_NODE_SECURITY_RECHECKABLE` | node lacks context for auth/coverage | per-node runtime gate invariant |

### Stage 6 — Review

| Code | Blocks when | Executor |
|---|---|---|
| `DOSSIER_MATERIAL_FACTS_CONFIRMED` | material proposal/unknown remains | Studio static/human |
| `DOSSIER_PROVENANCE_COMPLETE` | material field lacks provenance | Studio static |
| `CONSENT_GENERATION_ACTIVE` | package generation consent absent/withdrawn | Studio static |
| `IMPACT_PREVIEW_ACKNOWLEDGED` | changed Business Line/shared policy not acknowledged | Human decision |
| `REQUIRED_WARNINGS_ACKNOWLEDGED` | warning acknowledgment absent | Human decision |

### Stage 7 — Package

| Code | Blocks when | Gateway/admission relationship |
|---|---|---|
| `PACKAGE_SCHEMA_VALID` | any generated artifact invalid | manifest/domain/sub-domain validators |
| `PACKAGE_REFERENCES_VALID` | cross-reference missing/inconsistent | registrar/catalog validation |
| `PACKAGE_DETERMINISTIC` | repeated canonical compile differs | artifact SDK invariant |
| `PACKAGE_SECRET_SCAN_CLEAR` | suspected secret appears | supply-chain gate |
| `PACKAGE_IMPACT_WITHIN_SCOPE` | undeclared artifact changed | activation safety invariant |
| `PACKAGE_SNAPSHOT_PROOF_GREEN` | required golden/snapshot failed | Studio harness |

### Stage 8 — Gateway Ready, M4

| Code | Blocks when | Executor |
|---|---|---|
| `REGISTRY_DRY_RUN_PASS` | validation/introspection fails | external registry profile |
| `SHADOW_INDEX_READY` | catalog/index incomplete or model mismatch | index writer/readiness |
| `ROUTING_REGRESSION_PASS` | candidate poaches protected catalog | shadow routing harness |
| `CATALOG_SNAPSHOT_COMPLETE` | mixed/missing version entries | immutable catalog validator |
| `GATEWAY_READINESS_PASS` | active index/catalog/model mismatch | `RegistryReadinessVerifier` |

The request-path gateway remains read-only. “Gateway checks” in the UI means compatibility/readiness
checks whose semantics are shared with or ultimately proven by registry/gateway components; it does
not mean adding mutation endpoints to the serving gateway.

## 7. Readiness rail behavior

The right rail groups checks as:

```text
Blocking now
Ready to run
Passed
Warnings
Later runtime proof
```

Every failed row shows:

- what is missing in business language;
- why Conduit requires it;
- which workflow field/artifact it affects;
- the shortest action to resolve it;
- executor/evidence and last-run input hash;
- **Fix this** or **Run check**.

The rail never shows a mysterious composite “AI readiness score.” Progress is `required checks
passed / required checks applicable` with blocking details always visible.

## 8. API surface

```text
GET  /studio/projects/{projectId}/workflow
GET  /studio/projects/{projectId}/checks
POST /studio/projects/{projectId}/checks:run
GET  /studio/projects/{projectId}/checks/{checkCode}/history
POST /studio/projects/{projectId}/transitions/{targetStage}
```

Run requests include expected project/dossier version and idempotency key. The server computes input
hashes and transitions. The browser cannot submit `PASS` or set workflow state directly.

## 9. Staleness and invalidation

Examples:

- changing outcome/Business Line invalidates identity, neighbor and package checks;
- changing signals invalidates ownership/routing/package snapshots;
- changing agent binding invalidates schema, observed contract, composition and package checks;
- changing required context invalidates clarification, coverage, composition and package checks;
- changing compiler/schema policy invalidates all package and later runtime checks;
- catalog hash change invalidates neighbor/regression and Gateway Ready checks, not owner declarations.

Invalidation is a dependency graph tested independently; it is not a list of UI `if` statements.

## 10. First-build acceptance criteria

1. All four current Business Lines render through one reusable page/component path.
2. Every Use Case intake uses the same seven M2 workflow stages and check rail.
3. Continue is impossible while the current stage has an applicable blocking failure/stale/not-run
   check.
4. Fix actions navigate/focus the exact missing field or evidence task.
5. Changing a fact invalidates only declared dependent checks and visibly moves readiness backward.
6. Static gateway-compatibility checks map to named existing validators/invariants.
7. Live registry/gateway checks appear as later proof and cannot be faked as pass in M2.
8. RBAC/approval is absent from M2 workflow logic and can be added in M3 without changing check codes.
9. The design uses the existing Axiom navy/gold system and characterized primitives.
10. Browser-in-the-loop tests cover pass, blocked, stale, warning and unsupported flows at desktop and
    narrow widths with zero unexplained console/network errors.

## 11. Definition of done

The workflow/check system is done when the server—not the browser/model—derives readiness from
versioned check results; every transition has deterministic blocking tests; checks map to shared or
real gateway/registry semantics; every Business Line uses the same data-driven UI; and a user can
always answer “what is missing, why does it matter, and what do I do next?”

