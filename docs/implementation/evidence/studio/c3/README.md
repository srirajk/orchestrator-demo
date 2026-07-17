# Axiom Story C3 — Independent test-scenario generation (the oracle-problem fix / THE MOAT)

This directory is the checked-in evidence for C3. The **headline artifact is
[`catch-rate-table.md`](./catch-rate-table.md)** — the measured catch-rate over a hand-reviewed
known-bad corpus. Everything here is generated deterministically by the JUnit harness in
`iam-service` (`com.openwolf.iam.policystudio`); **no real LLM is ever called.**

## The problem C3 fixes (the oracle / circular-trust problem)

If one LLM generates BOTH a policy and its tests, the tests are predicated on the generated YAML's
behaviour, not on the author's intent. A *wrong* policy then produces *self-consistent green tests* —
the suite asserts exactly what the policy does, so it always passes. The bug ships with a green tick.

## The fix (what nobody ships)

1. **Generate the test ORACLE from the NL intent only**, in a context that **structurally can never
   see the generated YAML**. The oracle's sole input is
   [`TestScenarioRequest`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/TestScenarioRequest.java)
   — a record of *pre-generation grounding facts* (intent + manifest vocabulary + author scope + base
   ceiling) with **no YAML field, no `PolicyIR`, no path to any candidate**. A *different* process /
   prompt from the C2 generator ([`TestScenarioModelClient`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/TestScenarioModelClient.java)).
2. **Mechanically inject negative probes.** For EVERY intent-implied ALLOW, inject a **cross-tenant**,
   a **wrong-segment**, and a **missing-attribute** variant — all expected **DENY**
   ([`NegativeProbeInjector`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/NegativeProbeInjector.java)).
   These are the negatives a self-consistent co-generated suite would never write.
3. **Run the hand-owned B3 invariant suite on every draft** — the pinned-Cerbos compile over the base
   bundle (which embeds `infra/cerbos/policies/tests/invariants/`) executes those hand-authored
   invariants against the candidate.

## Acceptance mapping

| Item | Claim | Evidence |
|---|---|---|
| **C3.1** | Test-gen structurally never sees YAML | [`arch-isolation-proof.md`](./arch-isolation-proof.md); `TestGenIsolationArchTest.testGenNeverReceivesYaml` — the input-type has no YAML/`PolicyIR` component and the oracle seam takes only `TestScenarioRequest`. |
| **C3.2** | **MEASURED** catch-rate | [`catch-rate-table.md`](./catch-rate-table.md) — 100.0% (31/31), 100% cross-tenant & wildcard, 13 moat-critical, 0 misses. Corpus in `C3Corpus.java`. |
| **C3.3** | Negative probes per allow | `NegativeProbeInjectionTest` — exactly **3** probes (cross-tenant / wrong-segment / missing-attribute) per intent-implied ALLOW, each expected DENY. |
| **C3.4** | Invariant suite runs on every draft | `IndependentTestGenService.assess(..., runCompile=true)` compiles candidate + base bundle; `scripts/cerbos-policy-gate.sh` → **74/74** (incl. the 3 hand-owned invariant suites). Sample rendered oracle: [`sample-oracle-test.yaml`](./sample-oracle-test.yaml). |

## C3.2 is a VALIDATED HYPOTHESIS, not formal completeness

The catch-rate is an **empirical measurement over a living corpus**, not a proof that all bad policies
are caught. The corpus (`C3Corpus.java`) has **31 hand-reviewed known-bad pairs** across the seven
classes the story calls out: cross-tenant grants, wrong role/segment, omitted conditions, wildcard
action/resource, missing-attribute fail-open, incomplete child restriction / fall-through, and
scope-boundary confusion. **Add adversarial pairs over time**; the gate must keep clearing the bars.
We **publish misses** (currently none) rather than claim completeness.

### The moat, demonstrated (not assumed)

**13 of 31 items are "moat-critical": C2's deterministic gate ACCEPTED the policy, and only the
independent oracle caught it.** These are precisely the cases a co-generated suite would have passed
green (it would assert the policy's own behaviour). See the `Moat-critical = yes` rows (WR-*, OC-*,
MA-*) in the table. The remaining 18 are caught by the deterministic structural gate (wildcard, scope,
totality, tenant-structure) — defence in depth.

## Reproducibility (pinned)

The oracle is a **deterministic, seeded stub** (`C3TestGenFixtures.SeededIntentOracle`), so the
measurement reproduces byte-for-byte on any machine with JDK 25:

| Parameter | Value |
|---|---|
| Oracle model | `deterministic-seeded-stub` (NO network, NO LLM) |
| Temperature / top-p | N/A (deterministic replay of the hand-authored intent spec) |
| Seed | corpus-fixed (`C3Corpus.items()` order) |
| Retry policy | none (a single pure evaluation per item) |
| Probe injection | pure function of the positive expectation + manifest vocabulary |
| Decision evaluator | `PolicyExpectationEvaluator` (constrained CEL subset; throws rather than guess) |
| Cerbos (belt-and-braces) | pinned `0.53.0` (parity with the runtime PDP) |

**Why a stubbed oracle is the right harness:** the catch-rate measures **the GATE** (probes +
invariants + the deterministic validator + compile), not live-LLM answer quality. A seeded oracle over
a fixed known-bad corpus isolates the gate as the thing under test. Live-LLM oracle quality is a
separate axis (below) and does not move this number.

## Honest deferrals

- **Live-LLM oracle (`ZaiTestScenarioModelClient`)** is intentionally NOT built here — the harness must
  never call a real LLM, and live oracle quality is orthogonal to the measured gate. The interface +
  isolated input type are in place so the live impl drops behind the same fence later.
- **`PolicyExpectationEvaluator` is a constrained evaluator, not a Cerbos re-implementation.** It
  faithfully models the generated tenant-restriction shape (rule match, DENY-overrides, fall-through to
  the base ceiling, the tenant-equality backstop, and a tight CEL subset with fail-closed
  missing-attribute semantics). Outside that shape it throws `IndeterminateException` rather than guess;
  the pinned-Cerbos compile (C3.4) is the belt-and-braces layer. No corpus item is scored on a guess.
- **C4 (consequence-diff)** — showing an operator the *decision delta* a draft would introduce — is the
  next story and is not attempted here.
