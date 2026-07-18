# Conduit Studio Quality Harness

## 1. Harness principle

Every claim must have a deterministic or inspectable proof. A harness is part of the story, not
cleanup after implementation. Generated tests do not self-certify generated artifacts; hand-owned
fixtures and negative cases remain separate.

## 2. Required harness entrypoint

Create one repository entrypoint during M1-S06:

```text
scripts/studio-check.sh --milestone M1|M2|M3|M4 [--update-approved-snapshots]
```

Default behavior never rewrites snapshots. The update flag requires an explicit story whose review
explains every changed canonical hash and visual baseline.

Evidence is written beneath:

```text
build/conduit-studio/evidence/{milestone}/{run-id}/
  evidence-manifest.json
  commands.json
  test-results/
  schema/
  snapshots/
  accessibility/
  security/
  traces/
  environment.json
```

`evidence-manifest.json` records commit/worktree identifier, contract hashes, fixture hashes,
commands, exit codes, tool versions, start/end time and artifact hashes. It contains no secrets.

## 3. Fixture families

| Fixture | Purpose | Owner |
|---|---|---|
| `catalog-legacy-broken` | Exact original 4/7/18/18 import plus six expected findings | Platform contract owner |
| `catalog-current-clean` | Repaired 4/7/18/18 working catalog with zero blocking findings | Platform contract owner |
| `insurance-renewal-risk` | Existing Business Line, new confirmed Use Case | Business fixture owner |
| `hr-policy-knowledge` | Enterprise knowledge archetype | Business fixture owner |
| `wealth-holdings` | Resource-scoped coverage archetype | Security fixture owner |
| `wealth-concentration-review` | Composable producer/condition archetype | Composition fixture owner |
| `trade-penalty-map` | Bounded map and partial-failure archetype | Composition fixture owner |
| `neighbor-overlap` | Candidate poaches existing ownership | Independent eval owner |
| `insufficient-evidence` | Honest blocked result | Assurance owner |
| `cross-tenant` | Tenant isolation and non-discovery | Security owner |
| `malicious-document` | Prompt injection and unsafe proposal attempt | Security owner |

Submitted, model-generated, adversarial and held-out fixtures live in separate directories and carry
origin metadata. A model-generated row cannot move into the held-out set.

## 4. Test layers

### H1 — Schema and contract harness

Proves:

- every schema is valid Draft 2020-12;
- examples validate;
- identifiers, references and reverse memberships are consistent;
- OpenAPI validates and generated clients match its hash;
- current catalog defects are reported with stable codes, not ignored.

Required negative cases include missing owner, missing route target, unknown capability, Context Group
membership mismatch, required context without clarification, invalid consent purpose and unsupported
schema version.

### H2 — SDK and compiler harness

Proves:

- Spring-free dependency boundary;
- canonical key/array ordering and LF normalization;
- identical input creates identical bytes and bundle hash;
- one material input change produces only the approved file/hash diff;
- failed validation publishes no final folder;
- temporary folders do not become discoverable packages;
- no secret-like value enters output;
- existing Business Line hash remains unchanged for a Use Case-only addition.

Use byte-level golden comparisons, not parsed-object equality alone.

### H3 — Studio API harness

Proves optimistic versioning, idempotency, tenant scoping, stable error codes, provenance transitions,
consent purpose separation and generated-artifact path safety. Contract tests run against fixture,
filesystem and PostgreSQL adapters where those adapters exist.

### H4 — Web component and accessibility harness

Use Vitest, Testing Library and automated accessibility checks for:

- real catalog counts and finding labels;
- navigation and breadcrumbs;
- keyboard/focus behavior;
- provenance and unresolved-assumption presentation;
- role-hidden versus disabled actions;
- consent controls with no preselected optional consent;
- package generation progress/error/retry behavior;
- no raw manifest editor in the golden path.

DOM snapshots are limited to stable, small components. Prefer semantic assertions.

### H5 — Visual and browser journey harness

Use Playwright for desktop and narrow viewport baselines:

1. Overview;
2. Business Line detail;
3. Agent detail with integrity finding;
4. project signal/consent step;
5. Conduit-understanding review;
6. package reveal and artifact diff.

Retain trace and screenshot on failure. Visual snapshots require human review for every update.
The Studio Playwright configuration must not reuse the current chat suite's shared-session setting;
Studio scenarios create isolated projects and may run in parallel only with isolated tenants/data.

#### Browser-in-the-loop rule

Every packet that adds or changes a user-visible screen, route, interaction, responsive layout,
loading/error state or browser API integration must be developed and accepted in a real browser.
Component tests and a successful TypeScript build are necessary but insufficient.

Before a UI packet can be marked `DONE`, its owner must:

1. start the real Studio UI with either the frozen API fixture adapter or live Studio API required by
   that packet;
2. navigate to the changed route using Playwright or the controlled browser harness;
3. exercise the primary interaction plus at least one error/empty/permission state;
4. inspect browser console and failed network requests, with no unexplained errors;
5. verify keyboard focus/order and accessible names for changed controls;
6. inspect desktop and narrow viewport rendering;
7. retain a screenshot and, for interactive flows, a Playwright trace in story evidence;
8. add or update a repeatable Playwright assertion before review.

An implementation agent must not claim a UI packet complete from source inspection, unit tests,
Storybook-like isolation or screenshots generated without loading the running application.

### H6 — Model and inspection harness

Proves:

- model output conforms to typed proposal schemas;
- unsupported/unknown facts remain unresolved;
- prompt-injected documents cannot trigger tools, consent, approval or activation;
- model outage leaves deterministic project/review/generation usable;
- token/cost limits and redaction policy are enforced;
- inspection performs only consented read-only calls against allowlisted non-production targets;
- captured evidence is redacted, hashed and attributed.

Deterministic hard assertions wrap any model-judged quality measure.

### H7 — Certification and governance harness

Proves pinned input hashes, hard/measured/advisory gate composition, stale-result invalidation,
separation of duties, approval scope, audit reconstruction and `NOT_RUN` handling. A missing external
ingestion/runtime check cannot become a passing result in M3.

### H8 — External ingestion harness

Proves dry-run non-mutation, shared-admission parity, introspection bounds, shadow-index isolation,
complete catalog materialization, compare-and-set activation, idempotent receipt lookup, concurrent
activation conflict and rollback.

### H9 — Gateway regression harness

Proves Spring Boot 4 migration parity, absence of ingestion endpoints/beans/write credentials,
single catalog capture, conversation pinning/drain behavior, tenant isolation, structural
authorization, coverage, DAG-node rechecks, readiness and World-B invariants.

### H10 — End-to-end flagship harness

Runs the exact story from current catalog import to package generation in M2, through approval in M3,
and through sandbox activation/rollback in M4. The same package hash must appear in Studio, evidence,
certification, approval, ingestion receipt, gateway trace and rollback history.

### H11 — Build, image and supply-chain harness

Proves Maven/JAR/ZIP reproducibility, Docker layer/build-context discipline, non-root/read-only image
behavior, exact image/JAR/UI hashes, SBOM/vulnerability policy, health transitions, targeted service
rebuild and image rollback compatibility. It implements `BUILD-PACKAGING-AND-DEPLOYMENT.md`.

### H12 — Intake workflow and gateway-compatibility check harness

Proves every stage transition against the versioned check matrix, including pass, fail, blocked,
stale, warning, unsupported and required-not-run states. It verifies check-code mapping to shared or
existing manifest/input/select/readiness/introspection semantics, invalidation dependencies, no
browser/model-authored pass state, exact remediation focus and browser-visible blocking behavior for
all Business Lines. It implements `INTAKE-WORKFLOW-AND-CHECKS.md`.

## 5. Commands

Existing commands that remain authoritative:

```text
python3 -m pytest -p no:rerunfailures tests/schema/test_registry_contracts.py -q
cd gateway && mvn verify -q
cd iam-service && mvn verify -q
scripts/world-b-check.sh --quiet
scripts/verify.sh
cd tests/e2e && npm run e2e
```

Commands introduced by the Studio build:

```text
mvn -pl libs/conduit-manifest-contracts,libs/conduit-artifact-sdk verify
mvn -pl services/onboarding-studio verify
npm --prefix apps/onboarding-studio/web run test
npm --prefix apps/onboarding-studio/web run build
npm --prefix tests/studio-e2e run e2e
scripts/studio-check.sh --milestone M1|M2|M3|M4
```

Use `scripts/verify.sh` only when its Docker/full-stack prerequisites are intentionally in scope. A
story report lists commands not run and why; it never implies `verify.sh` covers Studio browser or
model evals unless the script is explicitly extended and proven to do so.

## 6. Milestone gate matrix

| Harness | M1 | M2 | M3 | M4 |
|---|:---:|:---:|:---:|:---:|
| H1 Schema/contracts | Required | Required | Required | Required |
| H2 SDK/compiler | Required | Required | Required | Required |
| H3 Studio API | Fixture contract | Required | Required | Required |
| H4 Web/a11y | Fixture contract | Required | Required | Required |
| H5 Browser/visual | Baseline only | Required | Required | Required |
| H6 Model/inspection | Not run | Deterministic fallback | Required | Required |
| H7 Certification/governance | Not run | Package proof only | Required | Required |
| H8 External ingestion | Not run | Not run | Not run | Required |
| H9 Gateway regression | No changes | No changes | No changes | Required |
| H10 Flagship E2E | Import only | Generate | Approve | Activate/rollback |
| H11 Build/image | SDK reproducibility | Studio image | Durable topology/rollback | Runtime image/profile parity |
| H12 Intake/checks | Contract only | Required | Required | Required + live runtime checks |

## 7. Failure and flake policy

- A deterministic failure blocks immediately.
- A flaky test is a defect, not a permitted retry-based pass.
- CI retries may collect evidence but the final gate requires a clean non-retried run.
- Network/model-dependent evaluations record provider/model/version and may be quarantined only with
  an owner, expiry and deterministic fallback gate.
- Snapshot changes without a linked contract/story explanation block review.
- Skipped security, tenant or hard certification tests fail a release gate unless the milestone
  matrix explicitly marks them `Not run`.

## 8. Harness definition of done

The harness is complete when one command can reproduce each milestone gate, evidence manifests are
hashable and secret-free, failures identify the responsible story/contract path, snapshot updates
are controlled, and CI preserves enough artifacts to diagnose a failure without rerunning against
mutable external state.
