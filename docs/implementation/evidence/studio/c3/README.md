# Axiom Story C3 — Independent test-scenario generation (the oracle-problem fix)

This directory is the checked-in evidence for C3. The **headline artifact is
[`catch-rate-table.md`](./catch-rate-table.md)** — the measured catch-rate over a hand-reviewed
known-bad corpus, now produced with the **real pinned-Cerbos compile layer switched ON** (H9).
Everything here is generated deterministically by the JUnit harness in `iam-service`
(`com.openwolf.iam.policystudio`); **no real LLM is ever called.**

## Read this first — what the catch-rate is, and what it is NOT

- The oracle is a **hand-seeded deterministic stub** (`C3TestGenFixtures.SeededIntentOracle`),
  authored in the **same commit** as the bad YAML it is measured against. The corpus is therefore a
  **closed loop**: the "expected decisions" and the "known-bad policies" were written together, by hand.
- The catch-rate is a **floor over KNOWN bad pairs**. It is **not** a guarantee, an upper bound, or a
  completeness proof over unseen policy. A 100% here means "every pair we hand-authored is caught by the
  gate", nothing stronger.
- Two things push past the closed loop, and only these:
  1. **The compile layer is the REAL gate, not Java.** Under H9 the assess runs `runCompile = true`, so
     each candidate is compiled by the **pinned Cerbos `0.53.0`** — the same binary/version as the
     runtime PDP — over the immutable base bundle. The number is no longer produced by two hand-rolled
     Java layers with Cerbos switched off.
  2. **A held-out probe** ([`held-out-probe.md`](./held-out-probe.md)) throws bad policies the corpus
     never contained at the **same trained gate**. See below.
- The genuinely strong, non-empirical guarantee here is **C3.1** (structural independence, proved by
  ArchUnit), not the catch-rate. The catch-rate is supporting evidence; the isolation proof is the moat.

## The problem C3 addresses (the oracle / circular-trust problem)

If one LLM generates BOTH a policy and its tests, the tests are predicated on the generated YAML's
behaviour, not on the author's intent. A *wrong* policy then produces *self-consistent green tests* —
the suite asserts exactly what the policy does, so it always passes. The bug ships with a green tick.

## The fix

1. **Generate the test ORACLE from the NL intent only**, in a context that **structurally can never
   see the generated YAML**. The oracle's sole input is
   [`TestScenarioRequest`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/TestScenarioRequest.java)
   — a record of *pre-generation grounding facts* (intent + manifest vocabulary + author scope + base
   ceiling) with **no YAML field, no `PolicyIR`, no path to any candidate**. A *different* process /
   prompt from the C2 generator ([`TestScenarioModelClient`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/TestScenarioModelClient.java)).
   This is the part proved structurally (C3.1), not just measured.
2. **Mechanically inject negative probes.** For EVERY intent-implied ALLOW, inject a **cross-tenant**,
   a **wrong-segment**, and a **missing-attribute** variant — all expected **DENY**
   ([`NegativeProbeInjector`](../../../../../iam-service/src/main/java/com/openwolf/iam/policystudio/NegativeProbeInjector.java)).
3. **Run the hand-owned B3 invariant suite on every draft** — the pinned-Cerbos compile over the base
   bundle (which embeds `infra/cerbos/policies/tests/invariants/`) executes those hand-authored
   invariants against the candidate. Under H9 this layer is ON for the measurement.

## Acceptance mapping

| Item | Claim | Evidence |
|---|---|---|
| **C3.1** | Test-gen structurally never sees YAML | [`arch-isolation-proof.md`](./arch-isolation-proof.md); `TestGenIsolationArchTest.testGenNeverReceivesYaml` — the input-type has no YAML/`PolicyIR` component and the oracle seam takes only `TestScenarioRequest`. **This is the strong, non-empirical guarantee.** |
| **C3.2** | **MEASURED** catch-rate, real compile ON | [`catch-rate-table.md`](./catch-rate-table.md) — 100.0% (31/31), 100% cross-tenant & wildcard, 13 moat-critical, 0 misses, **compile layer ran for all 31 (pinned Cerbos 0.53.0)**. A **floor over known bad**, not a completeness proof. Corpus in `C3Corpus.java`. |
| **C3.2-holdout** | Sliver of unseen-policy signal | [`held-out-probe.md`](./held-out-probe.md) — 5 bad policies **not** in the corpus, run through the same trained gate: **5/5 caught, 1 moat-critical** (the oracle caught an unseen validator-clean over-grant). |
| **C3.3** | Negative probes per allow | `NegativeProbeInjectionTest` — exactly **3** probes (cross-tenant / wrong-segment / missing-attribute) per intent-implied ALLOW, each expected DENY. |
| **C3.4** | Invariant suite runs on every draft | `IndependentTestGenService.assess(..., runCompile=true)` compiles candidate + base bundle; `scripts/cerbos-policy-gate.sh` → **74/74** (incl. the 3 hand-owned invariant suites). Sample rendered oracle: [`sample-oracle-test.yaml`](./sample-oracle-test.yaml). |

## The closed loop, stated plainly

The 31-item corpus (`C3Corpus.java`) is **31 hand-reviewed (intent, known-bad-policy) pairs** across the
seven classes the story calls out: cross-tenant grants, wrong role/segment, omitted conditions, wildcard
action/resource, missing-attribute fail-open, incomplete child restriction / fall-through, and
scope-boundary confusion. Both halves of every pair — the intent the oracle replays and the bad YAML the
candidate carries — were written by hand in the same commit. So the catch-rate measures **the gate over
an adversary we chose**, and its honest reading is: *no pair we thought of slips*. It does not bound the
policies we did not think of. **Add adversarial pairs over time**; the gate must keep clearing the bars.
We **publish misses** (currently none) rather than claim completeness.

### The moat, demonstrated on the corpus (and, once, on an unseen policy)

**13 of 31 corpus items are "moat-critical": C2's deterministic gate ACCEPTED the policy, and only the
independent oracle caught it.** These are precisely the cases a co-generated suite would have passed
green. See the `Moat-critical = yes` rows (WR-*, OC-*, MA-*) in the table. The remaining 18 are caught by
the deterministic structural gate (wildcard, scope, totality, tenant-structure) — defence in depth.

The held-out probe adds one data point **outside** the closed loop: `HO-04` is a validator-clean policy
the corpus never contained (chat_user retains `invoke_membership` via a split rule), and **only the
oracle caught it** — the moat generalized to a policy it was never shown. One data point is a sliver, not
a proof, and it is labeled as such.

### On the compile layer adding no net catch

With the compile ON, the number stays 100% (31/31) — the validator + oracle already catch every corpus
item, so the real Cerbos compile is **belt-and-braces** here and independently confirms the structural
rejects (compile ran for all 31). Its value is twofold: the measured gate is now the REAL one (H9's whole
point), and a future regression that only Cerbos would catch is now inside the measurement rather than
switched off.

## Reproducibility (pinned)

The oracle is a **deterministic, seeded stub** (`C3TestGenFixtures.SeededIntentOracle`), so the
in-process layers reproduce byte-for-byte on any machine with JDK 25; the compile layer requires Docker.

| Parameter | Value |
|---|---|
| Oracle model | `deterministic-seeded-stub` (NO network, NO LLM) |
| Temperature / top-p | N/A (deterministic replay of the hand-authored intent spec) |
| Seed | corpus-fixed (`C3Corpus.items()` order) |
| Retry policy | none (a single pure evaluation per item) |
| Probe injection | pure function of the positive expectation + manifest vocabulary |
| Decision evaluator | `PolicyExpectationEvaluator` (constrained CEL subset; throws rather than guess) |
| Cerbos compile | pinned `0.53.0` (parity with the runtime PDP), ephemeral `docker run --rm` |
| Docker gate | H7 hard-require: `CONDUIT_DOCKER_REQUIRED=true` fails rather than skips green in CI |

**Why a stubbed oracle for the measurement:** the catch-rate isolates **the GATE** (probes + validator +
real compile) over a fixed known-bad adversary; a seeded oracle keeps that reproducible. It is explicitly
**not** a measure of live-LLM oracle quality, which is a separate axis and does not move this number.

## Honest deferrals

- **Live-LLM oracle (`ZaiTestScenarioModelClient`)** is intentionally NOT built here — the harness must
  never call a real LLM, and live oracle quality is orthogonal to the measured gate. The interface +
  isolated input type are in place so the live impl drops behind the same fence later. Until then, the
  catch-rate says nothing about how good a real oracle would be.
- **`PolicyExpectationEvaluator` is a constrained evaluator, not a Cerbos re-implementation.** It
  faithfully models the generated tenant-restriction shape (rule match, DENY-overrides, fall-through to
  the base ceiling, the tenant-equality backstop, and a tight CEL subset with fail-closed
  missing-attribute semantics). Outside that shape it throws `IndeterminateException` rather than guess.
  The pinned-Cerbos compile (C3.4) is the belt-and-braces layer. No corpus item is scored on a guess.
- **The corpus is closed-loop and hand-sized.** 31 pairs + a 5-item held-out probe is a starting floor,
  not a population. Growing the corpus (and the held-out set) is the ongoing work; the number only means
  as much as the adversary behind it.
- **C4 (consequence-diff)** — showing an operator the *decision delta* a draft would introduce — is a
  separate story and is not attempted here.
