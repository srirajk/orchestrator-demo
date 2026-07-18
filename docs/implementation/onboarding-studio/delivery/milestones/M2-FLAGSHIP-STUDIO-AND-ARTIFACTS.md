# Milestone 2 — Flagship Studio and Artifact Generation

## Milestone objective

Deliver the product people can understand and demonstrate: a polished Studio that explains the
current catalog, guides one business Use Case and generates a deterministic Conduit Package into a
visible folder. This milestone has no external ingestion or gateway dependency.

All user-visible M2 stories are browser-in-the-loop. Each changed route must be exercised in the
running application with Playwright/controlled-browser evidence before its story can be `DONE`; a
component-test-only handoff is rejected.

## Exit gate G2

G2 passes when the Insurance renewal-risk golden journey completes through the UI, produces the exact
approved package bytes/hash, exposes impact/provenance/validation proof, passes accessibility and
browser snapshots, and never mutates `registry/`.

## M2-S01 — Build Studio API shell and project aggregate

**Outcome:** Spring Boot 4 API serves catalog reads and an in-process/filesystem-backed project flow.

**Dependencies:** G1. May run parallel with M2-S02 and M2-S06.

**Scope:**

- create `services/onboarding-studio` on Java 21/Spring Boot 4.x;
- serve M1 read endpoints through the real importer;
- implement Onboarding Project create/read/update with optimistic version and idempotency;
- represent answers, accepted proposals and consents as append-only project events;
- provide stable problem details and correlation IDs;
- keep persistence behind ports so M3 can add PostgreSQL without changing API semantics.

**Acceptance criteria:**

1. Application starts independently of gateway and ingestion.
2. M1 API fixtures match live responses for the current catalog.
3. Invalid transitions, stale versions and duplicate idempotency keys behave deterministically.
4. Cross-project identifiers cannot be used to read another project's artifacts.
5. No secret values or raw credentials are accepted/stored.
6. No ingestion, activation, rollback or gateway endpoint exists.

**Harness:** H3 API/unit/architecture tests.

## M2-S02 — Build web shell, tokens and navigation

**Outcome:** Accessible React application expresses the Business Line hierarchy.

**Dependencies:** G1. May use M1 generated fixtures while M2-S01 is in progress.

**Scope:**

- create `apps/onboarding-studio/web` with React, TypeScript, Vite, React Router and generated client;
- characterize and reuse the existing Admin/Chat Inter, canvas/panel/line, Axiom navy, gold,
  enterprise-shadow, focus, sidebar and UI-primitive system without redesigning the brand;
- implement Overview, Business Lines, Use Cases, Agent Network, Onboarding Projects, Generated
  Packages and Proof navigation;
- add layout, typography, color/state tokens, responsive shell, loading/empty/error patterns;
- implement breadcrumbs and assistant-panel shell without chatbot-first empty state.

**Acceptance criteria:**

1. All navigation is keyboard operable with visible focus.
2. No primary label uses case/domain/sub-domain/manifest as the business hierarchy.
3. Narrow viewport retains every primary task.
4. Errors show remediation and retry without losing entered work.
5. Generated API types are used; no parallel handwritten DTO model exists.
6. Token and primitive characterization snapshots match the existing Admin/Chat reference system;
   any deliberate difference has a reviewed Studio-specific interaction reason.

**Harness:** H4 component/a11y plus initial H5 visual snapshots.

## M2-S03 — Build Overview, Business Lines and Agent Network

**Outcome:** The first viewport proves Conduit's existing intelligence honestly.

**Dependencies:** M2-S01 and M2-S02.

**Required views:**

- Overview with 4 Business Lines, 18 agents, 18 capabilities, 0 confirmed Use Cases and a healthy
  current catalog, plus import history showing the six defects Conduit detected and M1 repaired;
- Business Line cards/detail with purpose, context groups, agents, provisional targets and health;
- Agent Network search/filter and agent detail;
- catalog-health panel linking each finding to affected records;
- `Define a Use Case`, `Use this agent` and `Start repair project` actions.

**Acceptance criteria:**

1. Counts come from API data, not constants in UI source.
2. All 18 agents display; the historical import view proves membership-defective agents were never
   silently dropped.
3. Zero confirmed Use Cases is explained as business confirmation work, not hidden.
4. Context Groups are secondary runtime structure, not main navigation.
5. Finding severity is not conveyed by color alone.

**Harness:** H3 response contracts, H4 semantic tests and H5 Overview/Business Line/Agent snapshots.

## M2-S04 — Build guided Onboarding Project workspace

**Outcome:** User defines a business change without editing platform artifacts.

**Dependencies:** M2-S01 and M2-S02.

**Steps:**

1. Choose project type and Business Line.
2. Describe outcome, audience, owner and explicit non-decision.
3. Select existing or connect proposed capabilities/agents.
4. Review required context and authority/source of record.
5. Resolve missing owners/facts.
6. Review Conduit's structured understanding.

**Acceptance criteria:**

1. Progress is resumable and next action/responsible role are explicit.
2. Observed, declared, derived, proposed and defaulted values are visually distinct.
3. Model-shaped proposal fixtures require explicit human acceptance.
4. Material unknowns block generation with exact questions and owners.
5. No JSON, YAML, regex or JMESPath editing exists in the golden path.

**Harness:** H3 transition tests, H4 project component tests and keyboard journey.

## M2-S05 — Build signals, consent and boundary lab

**Outcome:** Users understand what information helps Conduit and authorize each processing purpose.

**Dependencies:** M2-S04.

**Signal types:** positive intent, boundary, unsupported, ambiguity, context/entity, authority,
audience/access and service evidence.

**Consent purposes:** analyze content, bounded model assistance, inspect service contract, execute
named read-only probes, retain redacted evidence and generate local package.

**Acceptance criteria:**

1. Signal explanations appear before collection/use.
2. Optional consent is not preselected or bundled.
3. Generation consent does not imply inspection, probe, ingestion or activation.
4. Boundary lab records owner, provenance and effect on generated signal datasets.
5. “Unknown” and “not my decision” create assigned gaps rather than guessed facts.
6. Consent withdrawal behavior is defined and tested before generation.

**Harness:** H3 consent invariant tests; H4 screen-reader/keyboard tests; H5 signal/consent snapshot.

## M2-S06 — Build deterministic compiler and atomic artifact folder

**Outcome:** Confirmed dossier produces the content-addressed folder specified by the UI-first spec.

**Dependencies:** G1. May run parallel with M2-S01/S02.

**Scope:**

- implement dossier-to-Business Line/Use Case/route target/agent/signal/provenance mapping;
- write under `build/conduit-studio/artifacts/{organization}/{project}/{bundleHash}`;
- validate schema and cross-references before atomic publication;
- include unchanged source hashes rather than rewriting unaffected Business Line artifacts;
- emit impact, schema, cross-reference and snapshot reports;
- return typed compilation gaps.

**Acceptance criteria:**

1. Insurance renewal-risk produces the approved exact file set/hash.
2. Two clean runs are byte-identical.
3. The Insurance Business Line artifact is reported unchanged.
4. The current renewal-risk reverse-membership defect is reported, not hidden.
5. Validation failure leaves no final package folder.
6. Output contains no secrets, implicit wall-clock timestamps or writes to `registry/`.

**Harness:** H2 full golden, negative, no-op and planted-mutation tests.

## M2-S07 — Build impact preview, package explorer and proof view

**Outcome:** Users can understand the package without reading raw manifests.

**Dependencies:** M2-S02 and M2-S06.

**Required synchronized views:**

1. Business contract — outcome, ownership, signals, boundaries and required context.
2. Conduit plan — reused capabilities, connected agents and dependency flow.
3. Proof — file changes, hashes, provenance, validation and snapshots.

**Acceptance criteria:**

1. Impact preview distinguishes unchanged, added, changed and referenced artifacts.
2. Artifact tree exactly matches bundle manifest and prevents path escape.
3. Advanced raw JSON is read-only and secondary.
4. Failed checks link to human-readable remediation.
5. Downloaded bundle verifies through `BundleVerifier`.

**Harness:** H3 package API, H4 explorer tests and H5 package-reveal snapshot.

## M2-S08 — Implement Insurance renewal-risk flagship journey

**Outcome:** One reproducible story demonstrates the complete M2 value.

**Dependencies:** M2-S03 through M2-S07.

**Journey:**

1. Start at Overview and open Insurance.
2. See three discovered Insurance agents and the resolved renewal-risk import finding/history.
3. Define “Assess renewal risk” as the first confirmed Use Case.
4. Reuse policy details, claim status and renewal-risk capabilities as appropriate.
5. Confirm positive/boundary/unsupported/ambiguity signals and consent.
6. Review structured understanding and impact.
7. Generate package.
8. Verify exact hash, unchanged Business Line and new Use Case/route-target artifacts.

**Acceptance criteria:**

1. A clean environment completes the journey from documented commands.
2. Confirmed Use Case count changes from 0 to 1 in project preview without mutating source catalog.
3. Browser, API and filesystem show the same bundle hash.
4. Refresh/retry does not duplicate the project or package.
5. No ingestion/gateway endpoint is called.

**Harness:** H10 M2 path with trace and evidence manifest.

## M2-S09 — Complete accessibility, responsive and visual QA

**Outcome:** Flagship quality is credible in a room, on a laptop and for assistive technology.

**Dependencies:** M2-S08.

**Acceptance criteria:**

1. WCAG 2.2 AA automated checks pass for all flagship screens.
2. Complete journey works by keyboard and with screen-reader-friendly names/status announcements.
3. Desktop and narrow visual baselines have no clipping, hidden action or unreadable diff.
4. Loading, empty, partial-health, validation-error and retry states are visually reviewed.
5. Motion respects reduced-motion preferences.
6. Lighthouse/performance budgets defined by the story are met on the fixture catalog.

**Harness:** H4/H5 plus manual accessibility checklist retained as evidence.

## M2-S10 — Package and run the targeted Studio image

**Outcome:** The flagship UI/API ships as one reproducible cached container without rebuilding or
starting unrelated services.

**Dependencies:** M2-S08. Build/Compose files are integration-owner-only.

**Scope:** Follow `../BUILD-PACKAGING-AND-DEPLOYMENT.md` for the three-stage UI → JAR → runtime image,
root build context, `.dockerignore`, stable JAR name, artifact volume, read-only registry mount,
non-root runtime, health checks and targeted Compose rebuild.

**Acceptance criteria:**

1. Existing repository `FROM` lineage is reused; normal build does not request a base-image pull.
2. Only Studio SDK/JAR/UI sources enter the build layers; secrets and tenant packages do not.
3. `docker compose build onboarding-studio` reuses unchanged base/dependency layers.
4. `docker compose up -d --no-deps onboarding-studio` starts without gateway/registry.
5. Image/JAR/UI hashes are recorded and two clean JAR builds are reproducible.
6. Container runs non-root and writes only to declared temp/artifact paths.

**Harness:** H11 reproducibility, image contents, health, cache and targeted-rebuild proof.

## M2-S11 — Build intake workflow and blocking readiness checks

**Outcome:** Every Business Line and Use Case uses one data-driven intake workflow that makes missing
requirements actionable and prevents invalid progression.

**Dependencies:** M2-S01 and M2-S02. M2-S04 consumes this frozen workflow/check contract.

**Scope:** Implement `../INTAKE-WORKFLOW-AND-CHECKS.md`: existing Axiom navy/gold design primitives,
seven M2 intake stages, server-derived workflow state, versioned check definitions/results,
dependency-based staleness, three-column workspace, readiness rail, remediation focus, and named
mapping to manifest/input/select/readiness/introspection semantics.

**Acceptance criteria:**

1. All Business Lines use the same components and check definitions; no domain-specific UI code.
2. Continue is disabled for applicable blocking fail/blocked/stale/required-not-run results.
3. Browser/model cannot submit pass or set workflow state directly.
4. Fix actions navigate to the exact missing field/evidence task.
5. A changed dossier fact invalidates only declared dependent checks.
6. Static checks run in M2; live registry/gateway checks remain honest later-proof states.
7. RBAC/approval logic is absent from M2 workflow and can be layered in M3.
8. Browser tests prove pass, blocked, stale, warning and unsupported behavior at two viewports with
   zero unexplained console/network failures.

**Harness:** H12 transition/check/invalidation/API/browser suite.

## Milestone definition of done

All eleven stories are `DONE`; `scripts/studio-check.sh --milestone M2` passes; a reviewer completes the
renewal-risk runbook without private guidance; exact artifact bytes/hashes match golden proof; and
the repository has no ingestion or gateway runtime change attributable to M2.
