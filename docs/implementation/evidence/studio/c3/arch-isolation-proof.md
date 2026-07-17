# C3.1 — Architectural isolation proof: the test oracle structurally never sees the candidate YAML

The moat only holds if the oracle **cannot** be predicated on the generated policy. C3 enforces that at
the **type level**, proven by `TestGenIsolationArchTest` (part of the iam-service suite, JDK 25).

## The oracle's input type has no YAML field

```java
public record TestScenarioRequest(
        String intent,                 // the ONLY behavioural signal — natural language
        ManifestVocabulary vocabulary, // closed manifest vocabulary (roles/actions/classifications/…)
        TenantScope authorScope,       // where to home principals/resources in probes
        BaseCeiling baseCeiling) {     // fall-through / totality reasoning
```

Every component is a **pre-generation grounding fact** — the same facts that existed *before* the C2
generator ran. There is **no `String yaml`, no `PolicyIR`, no `StudioGenerationResult`, and no
`CanonicalPolicyWriter`/`PolicyYamlParser`** anywhere in the type or the oracle seam:

```java
public interface TestScenarioModelClient {
    TestExpectationSet proposeExpectations(TestScenarioRequest request); // request has NO candidate
}
```

The only sanctioned way to build the oracle input from the generator's request is a **lossy, one-way
projection** that drops everything downstream of the intent:

```java
TestScenarioRequest.fromAuthoring(PolicyAuthoringRequest authoring); // keeps intent+vocab+scope+ceiling only
```

## What the test asserts (the tripwire)

`TestGenIsolationArchTest.testGenNeverReceivesYaml`:

1. **Record-component check** — every `TestScenarioRequest` component type is in the closed set
   `{String, ManifestVocabulary, TenantScope, BaseCeiling}`, none is a policy artifact
   (`PolicyIR` / `StudioGenerationResult` / `CanonicalPolicyWriter` / `PolicyYamlParser`), and no
   component is named `*yaml*`/`*candidate*`.
2. **Seam check** — no `TestScenarioModelClient` method parameter is a policy artifact; the seam accepts
   only `TestScenarioRequest`.
3. **Bytecode check (ArchUnit)** — neither `TestScenarioRequest` nor `TestScenarioModelClient` may
   *depend on* the candidate-lowering / materialising / IR types.

If someone later tries to "improve the tests" by wiring the generated YAML into the oracle, they must
add a field or parameter of a policy-artifact type — which turns this test **red**. That is the
structural guarantee that the moat cannot silently collapse back into the oracle problem.

## The asymmetry that keeps the moat real

The **generation** context is fenced; the **run** context is not (and must not be). You obviously run
the oracle *against* the candidate to evaluate it — `IndependentTestGenService` /
`PolicyExpectationEvaluator` see the `PolicyIR` at evaluation time. The fence is precisely on
`TestScenarioRequest` + `TestScenarioModelClient`: the oracle is *authored* blind, then *run* sighted.

## Authoring-plane confinement (reuses C2's fencing style)

`TestGenIsolationArchTest.oracleGenerationSurfaceIsAuthoringPlaneOnly` mirrors
`PolicyAuthoringBoundaryArchTest`: token verification (`..auth..`) must not depend on the policy-studio
authoring plane; `TestScenarioModelClient` and `IndependentTestGenService` are unreachable from outside
`com.openwolf.iam.policystudio`. The gateway module (the request path, the Cerbos entitlement adapter,
coverage enforcement, agent invocation) has no dependency on iam-service and cannot reference any of
these types at all.
