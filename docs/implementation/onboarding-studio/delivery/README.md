# Conduit Studio Delivery Package

**Status:** Authoritative execution package  
**Scope:** From repository truth through external ingestion and read-only gateway integration  
**Execution rule:** No implementation story begins until its predecessor gate and harness evidence are green.

This folder converts the product and architecture specifications into bounded work that coding agents
can execute without inventing scope, contracts or success criteria.

## Read order

1. [Master Plan](MASTER-PLAN.md) — four milestones, gates, dependency graph and outcome story.
2. [Story Map](STORY-MAP.md) — every child story, dependency and permitted parallel lane.
3. [Quality Harness](QUALITY-HARNESS.md) — fixtures, test layers, commands, evidence and gate rules.
4. [Intake Workflow and Checks](INTAKE-WORKFLOW-AND-CHECKS.md) — existing design system,
   Business Line workflow, blocking checks, gateway/admission mappings and transition rules.
5. [Build, Packaging and Deployment](BUILD-PACKAGING-AND-DEPLOYMENT.md) — Maven/JAR/ZIP, Docker,
   Compose, cache, image, migration and rollback contracts.
6. [Critical Review](CRITICAL-REVIEW.md) — weaknesses found, dispositions and non-delegable decisions.
7. [Model Allocation](MODEL-ALLOCATION.md) — cheap/standard/master task boundaries and review loop.
8. [Atomic Work Packets](ATOMIC-WORK-PACKETS.md) — one-output tasks, dependencies, owned roots and tier.
9. [Agent Execution Contract](AGENT-EXECUTION-CONTRACT.md) — work packet format, ownership and handoff.
10. [Milestone 1 — Catalog Truth and Contracts](milestones/M1-CATALOG-TRUTH-AND-CONTRACTS.md).
11. [Milestone 2 — Flagship Studio and Artifact Generation](milestones/M2-FLAGSHIP-STUDIO-AND-ARTIFACTS.md).
12. [Milestone 3 — Intelligence, Assurance and Governance](milestones/M3-INTELLIGENCE-ASSURANCE-GOVERNANCE.md).
13. [Milestone 4 — External Ingestion and Read-Only Gateway](milestones/M4-EXTERNAL-INGESTION-AND-GATEWAY.md).

## Governing product specifications

- `../UI-FIRST-ARTIFACT-BUILDOUT.md` governs the flagship experience and first implementation order.
- `../BUSINESS-LINE-USE-CASE-SPECIFICATION.md` governs vocabulary, hierarchy and catalog semantics.
- `../STUDIO-UX.md` governs interaction behavior and accessibility.
- `../MANIFEST-COMPILER.md` governs deterministic compilation.
- `../CERTIFICATION-ADMISSION.md` governs assurance verdicts.
- `../AUTHORIZATION-APPROVAL-PROMOTION.md` governs identity and authority.
- `../SOLUTION-ARCHITECTURE.md` governs deployable and trust boundaries.

If a child story conflicts with a governing specification, stop the story and resolve the contract.
An implementation agent must not silently choose one interpretation.

## Milestone summary

| Milestone | Demonstrable result | Runtime mutation allowed? |
|---|---|---|
| M1 — Catalog Truth and Contracts | Current catalog becomes a tested business read model and stable SDK/contracts | No |
| M2 — Flagship Studio and Artifacts | User defines a Use Case and generates a deterministic package folder through the UI | No |
| M3 — Intelligence, Assurance and Governance | Evidence-backed guidance, certification, approvals and durable audit over exact hashes | No production mutation |
| M4 — External Ingestion and Gateway | Exact approved package is ingested externally and served from one immutable catalog version | Yes, external ingestion only |

## Global definition of done

A milestone is done only when:

1. every child story meets its own acceptance criteria;
2. all required narrow, module, contract, security and end-to-end harnesses are green;
3. generated evidence is stored under the milestone evidence path defined by the harness;
4. schemas/OpenAPI and generated clients are synchronized;
5. no unresolved severity-1 or severity-2 defect remains;
6. accessibility and tenant-boundary checks applicable to that milestone pass;
7. documentation describes behavior actually implemented;
8. rollback or cleanup behavior is demonstrated where the milestone changes durable state;
9. `git diff --check` is clean and unrelated worktree changes are preserved;
10. a reviewer can reproduce the flagship scenario from the runbook without private knowledge.

Passing unit tests alone never completes a story or milestone.
