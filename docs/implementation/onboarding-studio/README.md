# Conduit Onboarding Studio — Implementation Specification Index

**Status:** Production design package  
**Purpose:** Guided, evidence-backed onboarding and admission for existing agents/business services

---

## Product decision

Conduit Onboarding Studio is a separate control-plane application. Teams provide their service,
requirements, examples and evidence; they answer plain-language questions and approve a capability
contract. Studio generates, validates and certifies the manifests and supporting artifacts behind
the scenes. Teams never edit Conduit JSON/YAML.

Bounded OpenAI inference powers conversational guidance and typed proposals. The Java/Spring control
plane owns dossier truth, compilation, composition validation, certification, authorization,
approvals and promotion. It reuses the same Java semantics as the gateway instead of translating
conditions, JMESPath projections, DAG layers or bounded maps into another language.

---

## Specifications

For implementation, begin with the **[four-milestone Delivery Package](delivery/README.md)**. It is
the authoritative source for child stories, dependency/parallelism rules, harnesses, acceptance
gates, evidence and definition of done. The documents below govern product and technical behavior.

Read in this order:

1. **[UI-First Artifact Buildout](UI-FIRST-ARTIFACT-BUILDOUT.md)**
   Authoritative implementation order, revalidated catalog inventory, flagship navigation,
   deterministic artifact folder, snapshot proof and the rule that ingestion/gateway work comes
   last.

2. **[Business Line and Use Case Onboarding](BUSINESS-LINE-USE-CASE-SPECIFICATION.md)**
   Canonical Organization → Business Line → Use Case → Capability model, existing-agent import,
   intent/routing integration, tenant catalog activation, UI/API, persistence, demo conversion,
   testing and end-to-end definition of done.

3. **[Product Requirements](PRODUCT-REQUIREMENTS.md)**
   Users, problem, journeys, verdicts, requirements, success metrics, risks and v1 claim.

4. **[Studio Architecture](SOLUTION-ARCHITECTURE.md)**
   Separate deployables, data stores, trust boundaries, APIs, jobs, environments and failure model.

5. **[Studio UX](STUDIO-UX.md)**
   Information architecture, guided questioning, capability review, routing lab, evidence,
   certification, approvals, promotion and accessibility.

6. **[Identity, Authorization and Promotion](AUTHORIZATION-APPROVAL-PROMOTION.md)**
   Axiom OIDC, roles, field ownership, separation of duties, environment gates, exact-hash promotion,
   rollback and audit.

7. **[Guidance Model Runtime](MODEL-RUNTIME.md)**
   Bounded inference, typed proposals, prompt-injection boundaries, privacy, cost and runtime evals.

8. **[Manifest Compiler](MANIFEST-COMPILER.md)**
   Deterministic dossier-to-artifact mappings, provenance, canonical output, versioning and failures.

9. **[Certification and Admission](CERTIFICATION-ADMISSION.md)**
   Pinned inputs, hard/measured gates, dataset separation, routing regression, security, composition,
   verdicts and remediation.

10. **[Cross-cutting Engineering Specification](ENGINEERING-SPECIFICATION.md)**
   Canonical dossier/evidence model, workflow, APIs, standards crosswalk and hardness/generation
   agreements.

11. **[Phased Implementation Plan](IMPLEMENTATION-PLAN.md)**
   Bounded slices, tests, acceptance criteria and eleven production implementation scenarios for a
   lower-cost coding model.

12. **[Execution Model](EXECUTION-MODEL.md)**
    Dependency gates, safe parallel lanes, sequential work, critical path and Codex work packets.

13. **[Reference Package Structure](REFERENCE-PACKAGE-STRUCTURE.md)**
    Java, React, shared contracts and optional eval-worker packages with enforced dependency rules.

14. **[ADR-001: Studio Runtime](ADR-001-STUDIO-RUNTIME.md)**
    Why Java/Spring owns the Studio backend and OpenAI inference remains a bounded adapter.

15. **[Codex Handoff](CODEX-HANDOFF.md)**
    Exact implementation entrypoint, constraints, commands and completion reporting contract.

The Business Line and Use Case specification governs product vocabulary and catalog activation.
Older documents still use `case`, `domain` and `sub-domain` in some API/internal descriptions; treat
those as legacy compatibility terms until their contracts are migrated, not as the intended UI
hierarchy.

---

## Non-negotiable boundaries

- Studio does not build the submitted agent.
- Studio users never edit manifests.
- Model output never becomes an approved fact automatically.
- The model runtime is not the workflow/approval/promotion system of record.
- The compiler makes no model or network calls.
- Certification protects the existing catalog, not only the candidate.
- Generated tests cannot self-certify generated artifacts.
- No agent/model has production activation authority or tool.
- The external registry-ingestion subsystem remains the only manifest/index mutation authority.
- The request-path gateway has no ingestion endpoint, ingestion bean or registry write credential.
- Request-path gateway contains no Studio workflow state/endpoints.

---

## Supported v1 archetypes

1. Enterprise knowledge capability.
2. Resource-scoped data capability with coverage and authorization.
3. Composable analytical capability with declared dependency, condition or bounded map.

Anything requiring an unimplemented protocol, write authority, arbitrary control flow or other
missing primitive produces `UNSUPPORTED_REQUIREMENT` plus a platform-gap record.

---

## How to hand this to an implementation model

Give the model:

1. This index.
2. The PRD and architecture.
3. Only the focused specification for the current slice.
4. The corresponding build-plan slice.
5. The versioned v1 policy baseline in the engineering specification.

Do not ask it to implement the entire package at once. Require tests and review before advancing.
