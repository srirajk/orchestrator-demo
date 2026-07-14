# Conduit Onboarding Studio — Codex Handoff

## Start here

Read, in order:

1. `README.md`
2. `ADR-001-STUDIO-RUNTIME.md`
3. `EXECUTION-MODEL.md`
4. `REFERENCE-PACKAGE-STRUCTURE.md`
5. `IMPLEMENTATION-PLAN.md`
6. only the focused specification for the assigned slice
7. repository `CLAUDE.md` and applicable `.claude/rules/*`

## First implementation assignment

Implement Wave 0 and the characterization half of Wave 1 only. Do not scaffold the entire Studio.

Required result:

- sanitized fixtures for knowledge, resource-scoped and composable archetypes;
- overlap and insufficient-evidence fixtures;
- characterization tests pinning current manifest, projection, condition and bounded-map behavior;
- a proposed Maven aggregator/module change presented separately if it affects existing builds;
- no runtime behavior change.

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

