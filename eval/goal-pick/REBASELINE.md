# Goal-pick re-baseline — production-path threshold calibration (routing spec V2 Piece 6)

This directory holds the release gate for **capability-first routing**. The gate is measured on the
**production path** — the gateway's `POST /debug/route` endpoint (`RouteDecisionController` →
`ChatService.decideRoute`), which runs the *real* shared preparation (`RoutePreparer` → masking +
relaxation → `resolveContextual` → `buildRequestedPlan` → per-group structural authz) and stops before
any agent is invoked. There is no separate "debug resolver" scoring anymore; the harness asserts
`path == "production"` and the masking `preparationVersion` on every response.

## Files

| File | What it is |
|---|---|
| `measure_goal_pick.py` | The gate. Drives `/debug/route` per row with the row's **own persona token** (cached per persona), scores exact-capability accuracy, and enforces the hard invariants. |
| `labeled_queries.json` | The main labeled set (canonical / near_miss / held_out / cross_agent_confuser / out_of_scope). Confusers are **in** the gate. |
| `capability_entity_conflict.json` | Entity from domain A + capability from domain B, asked by a persona not entitled to B → route to B's capability, **deny** it. |
| `name_invariance.json` | One capability asked four ways (corpus name / novel name / bare id / no entity) → must route identically. |
| `routing_edge_cases.json` | multi-reference, same-name-cross-domain, multi-turn/facet-carry, entity-only abstain, alignment-miss. |
| `rebaseline.py` | The re-baseline tool: `dump` (freeze production-masked score vectors) + `search` (offline joint grid-search). |
| `baselines/` | Frozen score vectors: `pre-deentity.json` (A), `post-deentity.json` (B), and the `dump` output `production-masked-C.json` (C). |

## The gate (what `measure_goal_pick.py` enforces)

Run against the live demo stack (needs the seeded personas — `scripts/seed-users.sh` + the IAM demo
bankers, so `rm_jane`, `ops_analyst_singh`, `uw_sam`, `hr_partner_lund` can mint tokens):

```bash
python3 eval/goal-pick/measure_goal_pick.py \
    --dataset eval/goal-pick/labeled_queries.json \
    --gateway-url http://localhost:8080 --iam-url http://localhost:8084 \
    --expected-prep-version route-prep-v2
```

Hard invariants (any breach fails the run):

- **path/version drift** — every row must report `path=production` and the expected `preparationVersion`.
- **Wrong-domain substitution == 0** — no clear/confuser row may serve a capability in the *wrong domain*
  instead of abstaining or picking the right domain.
- **Out-of-scope abstain == 100%**.
- **Exact-capability accuracy ≥ floor** (`thresholds.capability_accuracy`) over all clear rows *including
  confusers*.
- **Min per-capability recall ≥ floor** (`thresholds.min_capability_recall`).
- **Top-k recall ≥ floor** (`thresholds.topk_recall`) — the expected capability appears somewhere in the
  candidate set (the reranker cannot recover an absent candidate).
- Per-row `expected_disposition` / `expected_denied_capability` (the conflict rows) must hold exactly.

It also reports per-agent precision/recall and top-k recall. The `capability_accuracy`,
`min_capability_recall`, and `topk_recall` floors in `labeled_queries.json` are **placeholders** until you
run the re-baseline below and freeze them to the recommended C-baseline.

## Re-baseline (do NOT live-tune — run these two steps, review, then set the thresholds by hand)

### 1. Freeze the production-masked score vectors (C)

```bash
python3 eval/goal-pick/rebaseline.py dump \
    --dataset eval/goal-pick/labeled_queries.json \
    --model-id all-MiniLM-L6-v2 --dataset-rev "$(git rev-parse --short HEAD)" \
    --out eval/goal-pick/baselines/production-masked-C.json
```

Hits `/debug/route` for every row with its persona token and writes the **full candidate score vector**
(every agent + domain + score + selected flag, including the below-floor tail) plus a stamp
`{preparation_version, corpus_hash, model_id, dataset, dataset_rev}`. It refuses to freeze a vector whose
`path != production`. `A` (`pre-deentity.json`) and `B` (`post-deentity.json`) are prior `/debug/resolve`
dumps kept for delta comparison; this produces `C`.

### 2. Offline joint grid-search (no stack needed)

```bash
python3 eval/goal-pick/rebaseline.py search \
    --vectors eval/goal-pick/baselines/production-masked-C.json \
    --min-capability-recall 0.6 \
    --out eval/goal-pick/baselines/rebaseline-recommendation.json
```

A **joint** search over all interacting deterministic resolver knobs — `confidenceFloor`, `decisiveScore`,
`domainMargin`, `routingMinScore`, `routingMinMargin`, `relativeFloorFactor` — replaying the resolver's
confident/abstain/floor logic over the frozen vectors. It:

- splits the rows **grouped-stratified by capability** into calibration / held-out (both folds cover every
  capability; no identical query in both);
- keeps only tuples that are **feasible on calibration** under the hard constraints (wrong-domain == 0,
  OOS abstain == 100%, min per-capability recall ≥ floor);
- among feasible tuples, **maximizes held-out exact-capability accuracy** and prefers a **stable plateau**
  (a tuple whose grid neighbours also score near the top), not a lone spike;
- prints the recommended tuple with calibration + held-out numerator/denominator, the current live config
  for reference, and writes the recommendation JSON. **It does not apply anything.**

> The LLM reranker is held at its live config during the offline search (an offline grid cannot re-run
> it). After you set the recommended tuple in `application.yml` (`conduit.resolver.*` +
> `conduit.routing.*`), re-run `measure_goal_pick.py` against the live stack to confirm **with** the
> reranker in the loop, then freeze the thresholds and the passing numerator/denominator into
> `labeled_queries.json`.

## Freeze checklist (one green commit)

1. `rebaseline.py dump` → fresh `production-masked-C.json` (stamp matches the live model + corpus).
2. `rebaseline.py search` → review the recommended tuple.
3. Set the tuple in `application.yml`; set `thresholds.capability_accuracy` etc. to the recommended
   C-baseline in `labeled_queries.json`.
4. `measure_goal_pick.py` on `labeled_queries.json`, `capability_entity_conflict.json`,
   `name_invariance.json`, `routing_edge_cases.json` — all PASS.
5. `bash eval/... multiturn` regression (incl. the new `mt_11_facet_carry_terse_fnd` FND- row).
