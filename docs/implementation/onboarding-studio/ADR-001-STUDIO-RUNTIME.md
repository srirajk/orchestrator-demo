# ADR-001 — Java Studio Control Plane with Bounded OpenAI Inference

**Status:** Accepted for implementation  
**Date:** 2026-07-12

## Context

The Studio must guide teams through onboarding and generate artifacts, but its decisive job is to
prove those artifacts mean exactly what the Conduit gateway will execute. In this repository,
composition semantics are already implemented in Java:

- `SelectContractValidator` validates producer/consumer projections, conditions and map contracts;
- `Blackboard` binds and merges layer inputs;
- `DagPlanExecutor` evaluates JMESPath conditions, clean skips and failures;
- bounded map execution enforces item and concurrency caps;
- virtual-thread executors run independent siblings concurrently;
- gateway tests compare parallel execution against serial reference oracles.

A FastAPI implementation could make the interview experience quickly, but a Python reimplementation
of those rules would create semantic drift at the most dangerous boundary: Studio could certify a
manifest that the Java gateway rejects or executes differently.

## Decision

Build the Studio backend as a separate Spring Boot 3.5.16 service using the repository convention:
Java 25 runtime with bytecode target 21. Build the UI in React/TypeScript.
Use bounded OpenAI Responses inference with strict structured outputs behind a Java
`GuidanceModel` port. Do not use the OpenAI Agents SDK in v1.

Extract domain-free static admission semantics into a shared Maven module,
`libs/conduit-admission`, consumed by both gateway and Studio. Move behavior only after
characterization tests pin current behavior. Keep live/shadow execution and registry mutation behind
narrow, registry-profile gateway APIs.

The Studio remains a separate control-plane process. Selecting Java does not authorize placing its
workflow database, model calls, UI endpoints or approval state in the request-path gateway.

## Consequences

### Benefits

- one executable definition of manifest and composition admission;
- direct reuse of Jackson, JSON Schema and JMESPath behavior;
- Java tests can validate generated condition/map contracts against actual gateway semantics;
- fewer production runtimes and no Python-to-Java translation layer for core rules;
- model choice remains replaceable and non-authoritative.

### Costs

- shared-code extraction requires careful characterization tests;
- a separate Spring service needs its own build/deploy configuration;
- Java model integration is less turnkey than the Python Agents SDK;
- durable workflow, prompt/version and structured-output handling must be implemented explicitly.

These costs are acceptable because the model is advisory. The difficult, safety-critical behavior is
admission and execution parity, not autonomous agent handoffs.

## Rejected alternatives

### FastAPI/Python Studio owning compilation and validation

Rejected. It would duplicate Java manifest, JMESPath, DAG and map behavior. Calling Java only after a
Python certification pass would discover drift too late.

### FastAPI UI/API plus Java validator endpoint

Viable but rejected for v1. It keeps validation authoritative but splits dossier/compiler/workflow
types across languages without a product requirement that justifies the additional boundary.

### Java Studio plus Python Agents SDK sidecar

Deferred. Add only if later evidence shows a genuine need for SDK handoffs or resumable agent runs.
The v1 interaction is a guided deterministic workflow with bounded inference operations.

### Put Studio inside the gateway

Rejected. Long-running case state, documents, model outages and promotion workflows must be isolated
from serving traffic.

## Required proof before acceptance

1. Characterization tests cover current `SelectContractValidator`, condition and bounded-map rules.
2. Gateway tests pass unchanged after depending on `conduit-admission`.
3. Studio and gateway report identical results for the three canonical onboarding fixtures.
4. No Studio package is present in a normal request-path gateway Spring context.
5. OpenAI outage leaves deterministic dossier review, compilation and admission usable.
