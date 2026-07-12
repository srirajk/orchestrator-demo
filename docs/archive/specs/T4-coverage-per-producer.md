# Codex task T4 — per-producer / row-level coverage, fail-closed, single-source book (the last security half)

> SECURITY-CRITICAL, enterprise-grade. GATEWAY code — **World-B applies**: the gateway must identify
> entity-typed values via **manifest declarations**, NEVER by pattern-scanning JSON or hardcoding ID
> patterns. Run `scripts/world-b-check.sh` before/after; report CRITICAL (0). Do NOT commit (reviewer
> commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull —
> T2 committed). Stack: docker compose `orchestrator-demo`; BFF :8099. **Fail CLOSED everywhere. If anything
> is ambiguous, STOP and report** (that instruction has paid off twice now).

## Why (perimeter ✅, identity ✅ — coverage is the last gap)
Coverage today is checked only on the **routed goal entity** at the gateway. Verified holes:
1. **No per-producer re-check** — in a multi-node plan, downstream/resource-scoped nodes aren't
   independently coverage-checked; it's safe *only* because every current vertical uses a single shared
   entity. Not a guarantee — a defense-in-depth gap.
2. **Fail-OPEN on blank/unresolved id** — `EntitlementService.checkRelationship` returns `allowed=true`
   when the id is null/blank (Opus M2). An id that fails to resolve is currently *allowed*.
3. **Fail-OPEN on coverage-service outage** — the id pre-check at `ChatService.java:381-383` fails *open*
   to the normal pipeline on `CoverageUnavailableException`. Do NOT copy that precedent.
4. **Discovered entities are unfilterable** — if a producer's output contains entities *other than* the
   request-named one, there's no way today to filter to the covered subset (no manifest mechanism).
5. **Two books can drift** — coverage service + IAM `personal_resources` (the servicing seeding incident).

## Part A — Per-node coverage re-check + fail CLOSED (testable now)
For **every resource-scoped plan node** (its sub-domain manifest is `resource_scoped: true`), before the
node is released to the harness:
- Resolve the node's entity input(s) (the `entity` it consumes, from the bound input).
- Run `coverageClient.check(principal, entityId)` against **that node's domain coverage service**.
- **Fail CLOSED:** a `false` check → the node is **denied** (not dispatched), and if it's the goal, the
  request ends in an explicit **coverage deny with reason** ("not in your coverage") — NEVER a silent flat
  fallback. A blank/unresolved id → **DENY** (fix `EntitlementService` to fail closed on null/blank). A
  coverage-service **outage** (`CoverageUnavailableException`) → **DENY** the node, never fall through open.
Keep the raw, principal-agnostic agent output from ever crossing to synthesis un-checked.

## Part B — Row-level filtering of DISCOVERED entities (the F1/D12 mechanism)
Enterprise reviewers will ask "what happens when a producer returns entities the user doesn't cover?" Build
the mechanism, World-B-cleanly:
- **Manifest declaration `io.produces[].entities: [{ type, select }]`** — `select` is a JMESPath to the id
  field(s) in the producer's output; `type` is the entity type (whose coverage service + `id_pattern` come
  from the domain manifest). Add to the schema (3 copies) + `AgentManifest`; boot-validate the `select`
  (reuse the T3 SelectContractValidator machinery — it must resolve to a string or array of strings).
- **Runtime filter:** when a producer emits entities (per its `entities` declaration), the executor
  extracts the ids, coverage-checks each, and **filters the output to the covered subset BEFORE any
  downstream node OR synthesis sees it.** The uncovered rows never cross.
- **No count/existence leak — filter BEFORE any aggregation/count.** A partially-covered answer must not
  reveal the *unfiltered* set size (e.g. "you can see 3 of 7" leaks the 7). The trace records N−K per-entity
  denials for audit, but the answer/aggregate sees only K.
- **Prove it with a test scaffold** (this case has no current live vertical — that's expected): a fixture
  producer that emits N entities of which only K are in the principal's book → the downstream node and
  synthesis receive exactly K; the trace shows N−K coverage denials; the answer contains no unfiltered count.

## Part C — Honor the `T4-REVERIFY` notes (conditionals + map over FILTERED data)
Both notes are in the code (`DagPlanExecutor`): a conditional predicate (T6) and a map's per-item inputs
must evaluate over the **coverage-filtered** input, so a predicate can't reveal facts about uncovered
entities and a map item can't carry uncovered data. Route the filtered input into both evaluation sites and
**re-verify**: add a test where a condition/map input contains an uncovered entity → the predicate/map sees
only the covered subset (or the node denies), never the raw data. Remove the `T4-REVERIFY` comments once done.

## Part D — Single-source book (settle the drift)
The coverage service must be the **sole authority** for the entitlement CHECK. Verify the check consults ONLY
the coverage service (post the servicing fix it should). If IAM `personal_resources` is still *read for
entitlement* anywhere, remove that dependency so there's one authoritative book (IAM stays for **identity**;
the book lives at the **coverage layer**). If `personal_resources` turns out load-bearing beyond seed data,
**STOP and report** rather than rip it out — don't break identity.

## HARNESS (first-class, repeatable — extend `tests/e2e/security_harness/`)
Unit (JUnit, hermetic):
- per-node coverage check denies an uncovered node; **blank/unresolved id → DENY**; **coverage outage →
  DENY** (not open); discovered-entity filter returns only the covered subset; **no count leak** (aggregate
  computed on K, not N); condition/map evaluate over filtered input.
Live BFF:
- an in-book user is served; an **out-of-book** entity is **denied with reason** (regression of the existing
  denial); a multi-node plan re-checks each node; the discovered-entity scaffold shows downstream seeing only
  covered rows; **stop the coverage container → the request denies, never answers** (the fail-closed proof).

### Scenario matrix (build ALL — this is what makes it enterprise-provable)
Cover every angle a security review will probe. Each row is a harness case (unit or live as noted); use
the real seeded personas/relationships. ★ = critical proof, must not be skipped.

**Book isolation & the check-gate:**
- S1  In-book served — rm_jane → Whitman (REL-00042) → served.
- S2 ★ Peer isolation — rm_carlos (book = Sterling only) → Whitman → DENIED "not in your coverage" (a *different* RM's client).
- S3 ★ **RESOLVE-agnostic, CHECK-gates** — rm_carlos asks by name "Whitman": resolution SUCCEEDS (the name resolves) but the CHECK DENIES. Proves resolution never leaks entitlement; the gate is the coverage check alone.
- S4  Empty book — a principal with no book → every resource-scoped query denied.
- S5  Single-relationship scoping — ops_analyst_singh (book = {REL-00188}) → REL-00188 served, REL-00042 denied.

**The three gates, independently:**
- S6  Segment gate — a user whose segment can't reach a domain → denied before any agent runs (structural).
- S7 ★ Classification gate — rm_jane (servicing clearance = confidential) → a confidential-pii servicing agent → denied at classification (right book, insufficient clearance — the T2-era case).
- S8  All-three-pass — ops_analyst_singh → servicing settlement_risk on REL-00188 → served (segment + classification + coverage all green).

**Fail-closed (★ the whole point of T4):**
- S9 ★ Blank/unresolved id → DENY (not allow).
- S10 ★ Coverage-service outage (stop the coverage container) → DENY, never answer.
- S11 Ambiguous reference ("the trust" → Calderon + Okafor) → CLARIFY, never guess (guessing one would leak/mis-serve).

**Row-level / per-producer (★ the F1/D12 finding):**
- S12 ★ Discovered-entity filter — producer emits N entities, K covered → downstream + synthesis see exactly K; trace shows N−K denials; answer contains NO unfiltered count.
- S13 Per-producer in a fan-in — one producer scoped to an uncovered entity → that producer denied, plan degrades honestly (survivors + honest "missing"), not a whole-plan failure or a leak.
- S14 ★ Condition/map over filtered data (T4-REVERIFY) — a predicate/map input containing an uncovered entity → the predicate/map sees only the covered subset (or denies), never the raw data.

**Identity × coverage (T2 + T4 compose):**
- S15 ★ Valid token, uncovered entity — a *validly authenticated* rm_carlos token asking about Whitman → denied at coverage. Proves authentication ≠ entitlement.
- S16 Multi-turn re-check — a follow-up turn re-runs coverage; entitlement is NOT cached/trusted across turns.

If any scenario can't be built with the current seed, add the minimal fixture (a persona/relationship) — do
NOT skip a ★ scenario. If a ★ scenario reveals the system does the WRONG thing, that's a real finding — STOP
and report, don't paper over it.

## GATE (fail-closed is non-negotiable; do not weaken)
- All unit + live harness checks pass, INCLUDING: blank-id deny, outage deny (coverage container stopped),
  discovered-entity filter + no-count-leak, condition/map over filtered data.
- Regression: the 3 verticals + conditionals + map + T2 identity all still pass live for entitled users;
  out-of-book still denied.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0 (entity extraction is manifest-driven,
  no JSON-scanning / ID-pattern literals in Java).

## Constraints / anti-gaming
- FAIL CLOSED: remove EVERY fail-open path (blank id, outage, missing coverage); add none.
- Entity extraction is by **manifest declaration** (`io.produces[].entities`), never regex/JSON-scan in
  gateway Java (World-B). Filter BEFORE aggregation (no leak). Do NOT weaken the deny to make a test pass; if
  a real vertical breaks, that's a finding.
- Do NOT commit.

## Report
Files changed; the per-node check + fail-closed points (blank id, outage) with the removed fail-open sites;
the `io.produces[].entities` mechanism + boot validation; the discovered-entity filter + how it filters
before aggregation; the condition/map REVERIFY changes; the single-source-book finding (what reads
`personal_resources` and what you changed/left); the unit + live harness evidence (esp. the outage-deny and
discovered-entity-filter proofs, with request ids); mvn / World-B / regression results. STOP and report
anything the spec didn't anticipate.
