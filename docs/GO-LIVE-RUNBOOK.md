# Go-Live Runbook — Multi-Step Orchestration (D11)

> The orchestration engine is BUILT, PROVEN, and GREEN (DagResolver + Blackboard + DagPlanExecutor
> derive+execute the DAG from the REAL manifests; 90+ tests; World-B CRITICAL 0). The concentration
> analytics agent is live-tested. What remains is the **last mile**: wire it into the chat request
> path behind a flag, rebuild, and verify live. This is deliberately gated (it touches request-path
> authorization). Below is everything to flip it on safely — ~15 minutes.

## State today (flag OFF — nothing changed in the running demo)
- `ChatService.java` has a **zero-line diff**; the flat fan-out path is byte-for-byte unchanged.
- All new classes exist and are Spring-registered: `orchestration/planner/DagResolver`,
  `orchestration/executor/Blackboard`, `orchestration/executor/DagPlanExecutor`,
  `infrastructure/telemetry/event/PlanGraphData`.
- `registry/manifests/meridian.wealth.concentration.json` is registered; `io` now survives the registry
  re-stamp (bug-245 fixed) so the resolver sees producers on the real registry.

## Step A — wire the flag into ChatService (the one staged edit)
At the flat plan-build site (~ChatService line 738), wrap it so DAG is attempted only when enabled,
and ANY miss falls through to the unchanged flat path:
```
results = tryDag(...).orElseGet(() -> { <existing flat build + agent_start + execute, verbatim> });
```
`tryDag(...)`:
1. Inject `AgentRegistry`, `DagResolver`, `DagPlanExecutor`, and
   `@Value("${conduit.orchestration.dag-enabled:false}") boolean dagEnabled`.
2. Hoist `EntityBag effectiveBag` (currently scoped in the `if (preExtracted != null)` block ~689–698)
   so it's visible at the plan-build site.
3. Return `Optional.empty()` unless `dagEnabled && effectiveBag != null`.
4. **Goal** = the single selected+allowed manifest in `synthesis.inputs()` whose `io` has a `from`
   consume; if not exactly one, return empty.
5. `candidates = registry.listAll()`; `DagResolver.resolve(goalId, candidates, availableEntities)`;
   return empty on any error or a single-node plan.
6. **AUTHZ (critical):** run `entitlementService.filterAgents(principal, planManifests)` over EVERY
   manifest the plan pulled in; if the count shrinks, **return empty** — never invoke a
   resolver-pulled producer the principal isn't structurally entitled to. (The producers were not in
   the original gated set, so they MUST be re-gated here. This is the security-critical line.)
7. Bind leaf inputs via `inputSynthesizer.synthesize(effectiveBag, leafManifests)`; if any required
   leaf input is missing, return empty.
8. `new Blackboard(availableEntities, preBoundInputs, mapper)`; publish `plan_graph` (PlanGraphData)
   + one `agent_start` per node; `dagExecutor.execute(plan, blackboard)`.

## Step B — the ONE thing to verify live (why this was staged)
`availableEntities` must be the set of entity-key symbols that match `io.consumes[].entity`
(holdings consumes `relationship_id`). `EntityBag` exposes `references` (keyed by `extract_as`) and
`resolved` (keyed by the entity `key`). Use **`effectiveBag.resolved().keySet()`** — it is keyed by
the entity `key`, which is what `io.consumes[].entity` uses. Confirm with one live request that the
resolver receives `relationship_id` in the available set (log it once). If misaligned, `tryDag`
safely returns empty (falls through to flat) — so the failure mode is "flag is a no-op," never a bug.

## Step C — rebuild + boot (the demo needs the new code + the concentration endpoint)
```
docker compose -p orchestrator-demo build conduit-gateway wealth-http
docker compose -p orchestrator-demo up -d conduit-gateway wealth-http
# enable the flag (env or application.yml):  CONDUIT_ORCHESTRATION_DAG_ENABLED=true
```
Gateway boot log should show the registry loaded WITH `meridian.wealth.concentration`.

## Step D — run the demo
As `rm_jane` (entitled to the Whitman relationship) at http://localhost:8099:
> "Is the Whitman Family Office over-concentrated?"
Expect: trace shows `plan_graph` (holdings → concentration), holdings runs, concentration consumes
its output, answer cites single-name % + HHI/effective-positions, flags vs the firm policy. Then run
the negative: a user NOT entitled to Whitman → clean denial (proves the Step A-6 authz re-gate).

### Multi-turn verification (the DAG must work in a conversation, not just turn 1)
The DAG inherits the existing `FOLLOW_UP` → fetch fallthrough (`ChatService` ~line 808: a follow-up
with a grounded entity + confident route falls through to `handleFetchData`, where `tryDag` fires).
Verify a two-turn conversation:
1. Turn 1: "Show me the Whitman Family Office holdings." → single-agent holdings answer; entity
   `relationship_id` is now carried in the conversation.
2. Turn 2: "Is she over-concentrated?" (no name repeated) → MUST (a) fall through to FETCH_DATA
   (not history-only synthesis), and (b) route to the `concentration` goal so the DAG derives
   holdings → concentration with the CARRIED entity. Confirm `effectiveBag.resolved()` contains
   `relationship_id` from the prior turn at the plan-build site (log it once).
CONFIRM the two failure modes and their safe fallbacks: if turn 2 is classified history-only, it
answers from prior messages (no fresh concentration) — a UX gap, not a crash; if routing lands on
`holdings` not `concentration`, the DAG is single-node and you get the plain holdings answer. Both
are safe; both are tuning (intent-classifier follow-up bias + the concentration agent's example
prompts) — not architecture changes.

## Also pending (tracked, not forgotten)
- **F1 — servicing entity scope:** the servicing MCP handlers are relationship-scoped at runtime
  (`SETTLEMENTS.get(relationship_id)`), but their sub-domain manifests declare `fund_id`. Before the
  servicing cross-domain path (Servicing×Wealth) can be trusted, add `relationship_id` to the
  servicing sub-domain `entity_types` (and validate the servicing coverage config supports it). Fix
  deliberately; the wealth flagship does not depend on it.
- **F2 — test-fixture manifests** under `gateway/src/test/resources/manifests/` lack `io`; the
  resolver uses dedicated `dag/` fixtures, so no test is affected — mirror `io` there only if a future
  test loads them.
