# Codex task — MAP / bounded iteration (declared, terminating, partial-tolerant, with a real harness)

> GATEWAY code — **World-B applies** (no domain/client/entity/ID literals in Java; the collection ref,
> caps, and item shape are DATA in manifests; the engine is generic). Run `scripts/world-b-check.sh`
> before AND after; report CRITICAL (0). Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull latest — T3+T6
> committed). Stack: docker compose `orchestrator-demo`.
> **READ FIRST:** `docs/orchestration-architecture/CONTROL-FLOW-DESIGN-BASIS.md` (the "iteration = declared,
> finite Map" section). This is the HARDEST task so far — it introduces a **dynamic node count** into an
> executor that is currently static. Read the whole spec. **If anything is ambiguous, STOP and report** (that
> instruction caught a real spec bug on T3).

## Goal (enterprise capability, not a demo trick)
Add **bounded, declared iteration**: a node runs its agent **once per item** of a collection produced
upstream, aggregating the per-item results — the AWS Step Functions `Map` model. It must be **terminating by
construction** (finite collection), **blast-radius capped** (hard max-items), **bounded-concurrency**, and
**partial-tolerant** (a per-item failure never kills the map or the plan). NO unbounded loops, NO LLM in the
control flow. Scope is **map only** (no while-loops — those are a separate, later, carefully-specced task).

## Design — declared `io.map`, runtime expansion
A node declares iteration in its manifest:
```
"io": {
  "consumes": [ {"from": "<producer type>", "select": "..."} ],
  "map": {
    "over": "<JMESPath to an ARRAY within the node's merged bound input>",
    "item_select": "<optional JMESPath: project each element into the per-item agent input>",
    "max_items": 100,          // HARD blast-radius cap (see config for the global ceiling)
    "max_concurrency": 8       // bounded parallelism
  },
  "produces": [ {"name":"...","type":"..."} ]
}
```
**Semantics:** at runtime, once the map node's inputs are bound (its producer(s) done), the executor reads
the `over` array, and **invokes the node's agent once per element** (each element projected via `item_select`
or identity), up to `max_items`, with at most `max_concurrency` in flight. The node's OUTPUT is the ordered
**aggregate** of the per-item results (an array, in collection order). This is orchestrator-level fan-out —
the iteration lives in the GATEWAY, not pushed into the agent.

## Executor — the crux: dynamic expansion inside a static-plan model
Today `DagPlanExecutor` runs a fixed set of `PlanNode`s. For a map node:
1. When it becomes READY (producer done, collection known), read `over` from the bound input.
   - If `over` is absent/empty → the map produces an **empty aggregate** (a valid result, NOT a failure) —
     honest "0 items" (a downstream/synthesis must treat it as "none", not "missing").
   - If `over.length > max_items` → **cap** it: run the first `max_items`, and **report the truncation
     honestly** — a trace event + a field in the output (e.g. `_truncated: true, _total: N, _ran: max_items`)
     and a `dag_fallback{reason="map-capped"}`-style signal. NEVER silently drop the tail.
2. **Expand** into per-item invocations of the node's agent (bounded by `max_concurrency`), harvest to the
   deadline, and **tolerate per-item failures** (a failed item is recorded, does not abort the map).
3. **Aggregate** survivors into the node's output **in collection order** (deterministic), with an honest
   per-item status summary (`_items: N, _ok: M, _failed: K, _truncated: bool`).
4. Publish the aggregate to the blackboard for downstream nodes; the map node's `plan_graph` status reflects
   the summary (e.g. `map: M/N ok`), and dependents see it as OK if at least the contract is satisfiable
   (define: a map with ≥1 ok item, or an explicitly-allowed empty result, is OK; all-items-failed is a
   FAILED map that degrades through the existing partial path).
Keep it deterministic: same collection + same data → same aggregate, regardless of item completion order
(order the aggregate by input index, not arrival). Termination is guaranteed — a finite, capped collection.

## Boot validation (extend `SelectContractValidator`)
For a node with `io.map`: (a) `over` COMPILES and, on the merged-input synthetic sample, resolves to an
**array** (a scalar/object `over` → REJECT at boot); (b) `item_select` (if present) validates against the
array's **item** schema (reuse the T3 synthetic-sample+`InputContractValidator` machinery on one item); (c)
`max_items` and `max_concurrency` are positive integers within the global ceiling. Reject at registry load
with a precise error otherwise. Add tests that assert BOTH reject cases throw (non-array `over`; bad caps).

## Config (enterprise safety rails)
Global ceilings in `application.yml` (`conduit.orchestration.map.max-items`, `max-concurrency`), `@Value`-
injected — NOT magic constants. A manifest may set caps **only at or below** the global ceiling (a manifest
asking for more than the global max is clamped to the global max, logged). This prevents one manifest from
uncapping the blast radius.

## Coverage note (T4-REVERIFY)
Per-item inputs must be coverage-filtered once T4 lands (an item must not carry data about an uncovered
entity). Coverage is deferred — build over current bound inputs and add a `// T4-REVERIFY` note at the
expansion site.

## Demo vertical — a REAL "for each" (honest finance)
Build a genuine iteration case, copying the reference-agent pattern (pure `compute.py` + handler + manifest).
Natural fit: **per-failed-trade CSDR penalty aging in servicing** — map over the failed settlements
(collection from `settlement_status`), compute each trade's aging + CSDR cash-penalty exposure per item, then
aggregate to a portfolio-level exposure. This is a case where per-item iteration produces a materially better,
itemized answer than one lumped number. **Finance discipline (`docs/DOMAIN-KNOWLEDGE-VERIFIED.md`):** CSDR
cash penalties are LIVE, mandatory buy-in is NOT — never claim buy-in; the daily penalty rate is a
firm-configured env parameter (invent no rate); if a field is missing, STOP/report, don't fabricate. Prove
the empty case too (a relationship with zero failed trades → empty aggregate, honest "no failing trades",
not "missing data").

## HARNESS (first-class — the user wants this explicit and repeatable)
Deliver BOTH layers, runnable and regression-guarding:
1. **Unit tests** (JUnit, hermetic) for the executor map engine: N items → N invocations; `max_items` cap
   truncates + flags honestly; `max_concurrency` bounds in-flight count; a per-item failure is tolerated and
   survivors aggregate; **deterministic aggregate order** (shuffle completion, assert output order by index);
   empty `over` → empty aggregate (not failure); all-failed → FAILED map. Plus the boot-reject tests above.
2. **Live BFF harness** in `tests/e2e/security_harness/` (reuse its login/trace lib, the same way the other
   harness tests do): a `test_map_iteration` that drives the map query live and asserts — the `plan_graph`
   shows the map expansion with the item count; the answer aggregates the per-item results; the honest empty
   case; and the cap is reported when the collection exceeds `max_items` (seed >max_items items for one
   fixture, or lower the cap via config for the test). If you can inject a per-item failure, assert the map
   still returns survivors and the answer states what was skipped. Make it repeatable (no hard-coded
   one-off ids beyond seeded fixtures).

## GATE (prove it; do not weaken to pass)
- Boot-reject test rejects a non-array `over` and out-of-range caps.
- Live: a map query fans out per item (plan_graph shows N), aggregates in order, tolerates a per-item
  failure, and honestly reports truncation at the cap and the empty case.
- Determinism: same query → same aggregate across repeats.
- No regression: the 3 verticals + T6 conditionals still pass live.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; unit + `test_map_iteration` harness
  green.

## Constraints / anti-gaming
- Bounded + terminating is NON-NEGOTIABLE: a hard global max-items ceiling; manifests can only cap lower.
- Truncation and per-item failures are reported HONESTLY (never silent drops; never surfaced as "missing
  data" when it's "not applicable"/"none"). Aggregation is DETERMINISTIC (by index). No LLM, no arbitrary
  code, World-B clean.
- Do NOT build while-loops. Do NOT build coverage. Do NOT commit.

## Report
Files changed; the `io.map` grammar; the executor expansion design (how dynamic per-item nodes fit the
static-plan model; how caps/concurrency/partial/determinism are enforced); boot-reject tests; the unit-test
list; the live `test_map_iteration` evidence (plan_graph expansion + aggregate + empty + capped + injected
failure, with request ids); determinism check; mvn / World-B / regression results; the `T4-REVERIFY` note
location. STOP and report anything the spec didn't anticipate.
