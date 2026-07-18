# Conduit Onboarding Studio — Codex Handoff

## Start here

Read, in order:

1. `README.md`
2. `delivery/README.md`
3. `delivery/MASTER-PLAN.md`
4. the assigned milestone and child story from `delivery/`
5. `UI-FIRST-ARTIFACT-BUILDOUT.md`
6. `ADR-001-STUDIO-RUNTIME.md`
7. only the focused governing specification referenced by the story
8. repository `CLAUDE.md` and applicable `.claude/rules/*`

## First implementation assignment

Implement M1-S01 first, then follow the dependency order in `delivery/STORY-MAP.md`. Do not change
external ingestion or the request-path gateway before Milestone 4.

Required result:

- a deterministic imported-catalog snapshot proving the 4/7/18/18 inventory and six current
  integrity findings;
- versioned Business Line, Use Case, route-target, dossier and bundle contracts;
- sanitized Insurance renewal-risk and supporting boundary/insufficient-evidence fixtures;
- Spring-free manifest/artifact SDK skeletons with canonical serialization tests;
- typed UI/API fixtures generated from the same contracts;
- no registry mutation, ingestion implementation or gateway runtime behavior change.

## Hard constraints

- Studio is a separate Spring Boot control-plane service.
- React/TypeScript is the Studio UI.
- OpenAI output is an unapproved typed proposal.
- Do not use the Agents SDK in v1.
- Do not reimplement Java condition/map/DAG semantics in Python or TypeScript.
- Do not move live `DagPlanExecutor` execution into Studio.
- Do not add Studio workflow endpoints to the request-path gateway.
- No model-accessible approval, policy mutation, activation or promotion operation.
- Preserve unrelated dirty-worktree changes.

## Verification contract

For every slice, run the narrow tests first, then the impacted module tests. For gateway changes also
run `bash scripts/world-b-check.sh --quiet`. Use the repository’s isolated full-context test support;
never point tests at the live demo Redis.

Report:

```text
Gate completed:
Changed files:
Behavior added or pinned:
Tests run and results:
Tests not run and why:
World-B result:
Contract/schema changes:
Known risks:
Next gate:
```

Stop when an assigned gate is complete. Do not opportunistically implement the next wave.
