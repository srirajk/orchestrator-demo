# Conduit Onboarding Studio — UX Specification

**Status:** Proposed  
**Audience:** Product design, frontend, accessibility, platform reviewers  
**Principle:** Users review business contracts and evidence; they never edit platform manifests.

---

## 1. Product experience

Studio is a guided workspace, not a chat page and not a JSON editor. Conversation helps users
understand and answer questions, while structured screens show evidence, decisions, readiness and
promotion state.

Primary navigation:

```text
Overview
Business Lines
Use Cases
Agent Network
Onboarding Projects
Generated Packages
Proof
```

The first implementation follows `UI-FIRST-ARTIFACT-BUILDOUT.md`; approvals, activation and gateway
views appear only after their real workflows exist. Visibility is role-scoped. A submitter does not
see organization-wide audit or unrelated Business Lines.

---

## 2. Core information architecture

### Onboarding Project list

Filters by ownership, Business Line, Use Case, project type, state, verdict and age. Each row shows
next action and responsible role, not merely a technical status.

### Onboarding Project workspace

Persistent left navigation:

1. Overview
2. Service
3. Capability
4. Business context
5. Access & coverage
6. Routing ownership
7. Composition
8. Evidence & tests
9. Contract review
10. Certification
11. Approvals
12. Promotion
13. Audit

Sections unlock progressively but remain readable. Users may revisit earlier evidence; changing an
approved decision clearly shows which later results become stale.

### Assistant panel

Collapsible side panel tied to the active section. It explains, asks bounded questions and presents
proposals. It never hides the structured record in chat history.

---

## 3. Onboarding Project creation

Step 1: choose the business change.

- Define a Use Case in an existing Business Line.
- Create a new Business Line and its first Use Case.
- Connect an agent to an existing Use Case.
- Repair an imported catalog relationship.

Step 2: choose supported archetype.

- Enterprise knowledge.
- Resource-scoped data.
- Composable analytics.
- Unsure — Studio determines candidates through questions.

Step 3: owners and intended environment.

Step 4: service protocol/URL and credential reference.

The UI explains unsupported choices before submission. A2A or write-agent input can still create an
assessment project, but the expected result is a platform-gap assessment rather than false
onboarding.

---

## 4. Service inspection experience

Before inspection, show exactly what Studio will do:

- fetch a specification or tool list;
- issue only approved read-only test calls;
- store a redacted evidence snapshot;
- never modify the submitted service.

The user confirms runtime tool consent. Progress uses meaningful stages:

```text
Connecting
Reading specification
Locating operation/tool
Deriving input contract
Deriving output contract
Running approved probe
Comparing live response
```

Results use business-friendly cards:

- Inputs discovered.
- Outputs discovered.
- Authentication observed.
- Errors/unknowns.
- Evidence source and capture time.

Raw JSON is available only in an advanced evidence viewer for authorized technical users; it is
never the primary editing surface.

---

## 5. Guided questioning

Question cards contain:

- one plain-language question;
- why Studio is asking;
- evidence that triggered it;
- answer controls;
- “I’m not the right owner” and “We don’t know yet” actions;
- responsible approval role;
- downstream effect.

Example:

```text
Who owns questions about why a trade failed?

We ask because your service description overlaps Settlement Status.

( ) This service
( ) Settlement Status
( ) Both, used in sequence
( ) Neither
( ) Needs the Asset Servicing owner

This answer affects routing ownership and cannot be inferred from the API schema.
```

Do not display “confuser,” “routing signal,” “semantic edge,” or “JMESPath” without an explanation.

---

## 6. Capability contract view

The contract is the primary review artifact. Sections:

- Identity and ownership.
- What it does.
- What it must not do.
- Intended users.
- Entities and required context.
- Authority/source of record.
- Freshness/completeness.
- Access and coverage.
- Inputs, outputs and load-bearing figures.
- Neighbor boundaries.
- Dependencies/conditions/iteration.
- SLO and failure behavior.

Every row shows provenance:

- Observed from service.
- Declared by person.
- Proposed by Studio.
- Defaulted by compiler.
- Approved by role/person.

Changing a row shows an impact preview before save.

---

## 7. Routing ownership lab

Avoid asking teams to create routing datasets. Present concrete cases in a two-column or multi-choice
ownership lab.

For each question:

- show the candidate and nearest existing capabilities;
- show why the case is ambiguous;
- ask who owns it;
- allow “both in sequence” and “neither”;
- show the effect on proposed examples/tests;
- identify cases requiring another owner.

Provide progress by resolved business boundary, not number of generated examples.

Catalog regression results show before/after ownership and affected existing capabilities. Never
reduce this to one green score when a specific capability is being poached.

---

## 8. Composition designer

The user sees a business flow, not a graph editor by default:

```text
Holdings data
    -> Concentration analysis
        -> only when breaches exist
            -> Review flag
```

or:

```text
Settlement status
    -> for each failed trade (maximum 100)
        -> Penalty calculation
```

Studio explains proposed mappings and validates them behind the scenes. Advanced technical view may
show semantic types, projections and schema paths read-only. Manual expression overrides require a
platform reviewer and rerun all affected gates.

---

## 9. Evidence and datasets

Tabs:

- Team evidence.
- Studio-proposed cases.
- Independent held-out evidence.
- Live probes.
- Validation outputs.

Origin badges cannot be removed or relabeled. Generated rows never visually masquerade as golden
evidence. Contradictions are grouped and require resolution.

Users can upload supported files, map dataset columns through a guided mapper, review rejected rows
and assign cases to an owner.

---

## 10. Certification report

Top-level verdict:

- Ready.
- Conditionally ready.
- Not ready.
- Unable to assess.
- Unsupported requirement.

Below it, show:

1. Blocking gaps.
2. Required actions with owners.
3. Hard-gate results.
4. Measured quality/regression.
5. Warnings and limitations.
6. Evidence and pinned versions.

Example gap:

```text
BLOCKING — Existing capability regression

Seven Settlement Status questions now route to this candidate.
Owner: Asset Servicing domain owner
Action: Resolve the three remaining ownership boundaries and rerun certification.
Evidence: Routing run CR-204, catalog snapshot cat_91d...
```

Do not blame the user. Explain the platform requirement and the shortest safe remediation.

---

## 11. Approvals UX

Approvers receive an inbox containing only projects they are authorized and required to decide.

Approval screen shows:

- exact dossier/bundle/certification hashes;
- what changed since the last approval;
- decisions within the approver's responsibility;
- unresolved warnings;
- environment requested;
- approve, reject or request changes;
- mandatory reason for rejection/conditional approval.

Approval buttons never appear inside agent chat. The assistant may explain the decision but cannot
render or trigger the authoritative control.

---

## 12. Promotion UX

Promotion timeline:

```text
Draft -> Sandbox -> Staging -> Production
```

Each environment shows prerequisites, current catalog, last certification and receipt. The promote
dialog includes:

- artifact hash;
- source/target environment;
- certification freshness;
- approvals;
- expected catalog change;
- rollback target;
- release notes;
- final typed confirmation for production.

Only authorized release managers see the final action. The assistant has no promote action.

---

## 13. Notifications and next actions

Notify only actionable roles for:

- question assigned;
- contradictory evidence;
- inspection/certification completion;
- approval requested/rejected;
- certification stale;
- promotion success/failure;
- post-activation drift.

Every status page answers: “What happens next, who owns it, and what evidence is missing?”

---

## 14. Accessibility and design requirements

- WCAG 2.2 AA target.
- Full keyboard operation.
- No color-only state/verdict communication.
- Visible focus and meaningful headings.
- Accessible tables with responsive card alternatives.
- Plain-language summaries before technical detail.
- Progress saved continuously and resumable.
- Timeouts never discard typed answers.
- Large documents/results virtualized and searchable.
- Diff views readable by screen readers.

Visual direction: serious enterprise studio, evidence-forward, calm and legible. Avoid chatbot-first
empty screens, decorative AI gradients and false “magic” language.

---

## 15. UX acceptance scenarios

- New submitter completes knowledge-agent onboarding without platform assistance.
- Business Line owner resolves an overlap using concrete ownership examples.
- Security owner sees only classification/coverage decisions requiring them.
- Technical owner diagnoses an invalid output contract from linked evidence.
- Reviewer understands a generated DAG without reading JMESPath.
- Release manager promotes an exact approved hash and sees receipt.
- Auditor reconstructs who declared, proposed, approved and promoted every material field.
- User with insufficient Business Line access cannot discover project names or evidence.
