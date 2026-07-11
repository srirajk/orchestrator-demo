#!/usr/bin/env python3
"""Re-baseline the resolver thresholds OFFLINE against frozen production-path score vectors.

Routing spec V2 Piece 6, "Re-baseline". Two phases, both driven by the operator (never live-tuning):

  DUMP  — hit the production decision endpoint (POST /debug/route) for every labeled row with its
          OWN persona token and freeze the FULL candidate score vector (every agent + domain + score +
          selected flag, incl. the below-floor tail) into a "C" (post-mask, production) baseline file,
          stamped with {preparation_version, mask corpus hash, model id, dataset rev}. The existing
          baselines/pre-deentity.json (A) and baselines/post-deentity.json (B, current de-entitied raw)
          stay as-is for delta comparison; C is what this script produces and searches over.

  SEARCH — a JOINT grid-search over ALL interacting deterministic resolver knobs — confidenceFloor,
          decisiveScore, domainMargin, routingMinScore, routingMinMargin, relativeFloorFactor — replaying
          the resolver's confident/abstain/floor logic over the frozen C vectors (the LLM reranker is held
          at its live config; an offline grid cannot re-run it — the operator re-validates the winning
          tuple with the LIVE harness, which DOES exercise rerank). It is a JOINT search because the knobs
          interact (AgentResolver.java:212-230,261-300). Feasible tuples must satisfy the HARD constraints
          — wrong-domain-substitution == 0, out-of-scope abstain == 100%, min per-capability recall ≥ floor
          — on the CALIBRATION fold; among feasible tuples it maximizes exact-capability accuracy on the
          HELD-OUT fold and prefers a STABLE PLATEAU (a tuple whose grid neighbours score within epsilon),
          not a lone spike. It RECOMMENDS a tuple and reports numerator/denominator; it does NOT apply it.

Usage:
  # 1) freeze the production-masked score vectors (needs the demo stack + seeded personas):
  python3 eval/goal-pick/rebaseline.py dump \\
      --dataset eval/goal-pick/labeled_queries.json \\
      --out eval/goal-pick/baselines/production-masked-C.json
  # 2) search offline (no stack needed) and print the recommended tuple:
  python3 eval/goal-pick/rebaseline.py search \\
      --vectors eval/goal-pick/baselines/production-masked-C.json \\
      --out eval/goal-pick/baselines/rebaseline-recommendation.json

Then review the recommendation, set the thresholds in application.yml yourself, and re-run
measure_goal_pick.py against the live stack to confirm (including the LLM reranker).
"""

from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

# Reuse the harness's manifest helpers + endpoint call so the two stay in lockstep.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from measure_goal_pick import (  # noqa: E402
    load_dag_goals, load_agent_domains, token_for, decide_route, query_shape,
)

# The effectiveFloor "decisive" gate in AgentResolver.select() is a fixed literal (topScore > 0.55),
# distinct from the searchable decisiveScore knob — held constant so the sim matches production.
EFFECTIVE_FLOOR_DECISIVE_GATE = 0.55

# Grid centred on the current live config (application.yml). Coarse on purpose — a joint search over
# six knobs, kept small enough to run in seconds while bracketing each knob on both sides.
GRID = {
    "confidenceFloor":    [0.25, 0.30, 0.35, 0.40],
    "decisiveScore":      [0.50, 0.55, 0.60],
    "domainMargin":       [0.03, 0.05, 0.08],
    "routingMinScore":    [0.35, 0.40, 0.45],
    "routingMinMargin":   [0.000, 0.005, 0.010],
    "relativeFloorFactor":[0.60, 0.65, 0.70],
}
KNOBS = list(GRID.keys())
CURRENT = {"confidenceFloor": 0.30, "decisiveScore": 0.55, "domainMargin": 0.05,
           "routingMinScore": 0.40, "routingMinMargin": 0.005, "relativeFloorFactor": 0.65}


# ── DUMP ─────────────────────────────────────────────────────────────────────────────────────

def dump(args) -> int:
    dataset = json.load(open(args.dataset))
    token_cache: dict[str, str] = {}
    agents_seen: set[str] = set()
    vectors: list[dict[str, Any]] = []
    prep_versions: set[str] = set()

    for item in dataset["queries"]:
        persona = item.get("persona")
        if not persona:
            print(f"ERROR {item['id']}: no persona", file=sys.stderr)
            return 2
        try:
            token = token_for(persona, args.iam_url, args.password, token_cache)
            decision = decide_route(args.gateway_url, item, token)
        except urllib.error.HTTPError as exc:
            print(f"ERROR {item['id']} (persona={persona}): HTTP {exc.code}: {exc.read().decode(errors='replace')}", file=sys.stderr)
            return 2
        except Exception as exc:
            print(f"ERROR {item['id']} (persona={persona}): {exc}", file=sys.stderr)
            return 2

        if decision.get("path") != "production":
            print(f"ERROR {item['id']}: path={decision.get('path')} (expected 'production') — refusing to freeze a non-production vector", file=sys.stderr)
            return 3
        prep_versions.add(decision.get("preparationVersion"))
        cands = [{
            "agentId": c.get("agentId"), "domain": c.get("domain"),
            "subDomain": c.get("subDomain"), "score": c.get("score"),
            "selected": c.get("selected"),
        } for c in (decision.get("candidates") or [])]
        for c in cands:
            if c["agentId"]:
                agents_seen.add(c["agentId"])
        vectors.append({
            "id": item["id"], "category": item.get("category"), "persona": persona,
            "expected_goal": item["expected_goal"],
            "relaxation_allowed": bool(decision.get("relaxationAllowed")),
            "mask_mode": decision.get("maskMode"),
            "candidates": cands,
        })

    corpus_hash = hashlib.sha256(
        "\n".join(sorted(agents_seen)).encode()).hexdigest()[:16]
    out = {
        "stamp": {
            "preparation_version": sorted(v for v in prep_versions if v),
            "corpus_hash": corpus_hash,
            "model_id": args.model_id,
            "dataset": str(args.dataset),
            "dataset_rev": args.dataset_rev,
            "endpoint": "/debug/route",
            "note": "Frozen production-masked (C) candidate score vectors. A=pre-deentity.json, "
                    "B=post-deentity.json are prior /debug/resolve dumps kept for delta.",
        },
        "vectors": vectors,
    }
    Path(args.out).write_text(json.dumps(out, indent=2) + "\n")
    print(f"Wrote {args.out}: {len(vectors)} vectors, {len(agents_seen)} agents, corpus_hash={corpus_hash}, "
          f"prep_version={sorted(v for v in prep_versions if v)}")
    return 0


# ── resolver simulation over a frozen candidate vector ───────────────────────────────────────

def simulate(cands: list[dict[str, Any]], entity_known: bool, t: dict[str, float]) -> list[dict[str, Any]]:
    """Replays AgentResolver.resolveContextual()+select() (deterministic knobs only; no rerank) over
    a frozen candidate list, returning the SELECTED candidates (empty ⇒ abstain)."""
    broad = sorted(cands, key=lambda c: c.get("score") or 0.0, reverse=True)
    if not broad:
        return []
    leader = broad[0]
    leader_score = leader.get("score") or 0.0
    dom = leader.get("domain")
    best_other = max((c.get("score") or 0.0 for c in broad
                      if (c.get("domain") != dom)), default=0.0)
    margin = leader_score - best_other
    confident = leader_score >= t["confidenceFloor"] and (
        entity_known or leader_score >= t["decisiveScore"] or margin >= t["domainMargin"])
    if not confident:
        return []
    top = broad[0].get("score") or 0.0
    runner = broad[1].get("score") if len(broad) > 1 else 0.0
    top_margin = (top - (runner or 0.0)) if len(broad) > 1 else float("inf")
    routing_abstain = (not entity_known) and (
        top < t["routingMinScore"] or top_margin < t["routingMinMargin"])
    if routing_abstain:
        return []
    eff = max(t["confidenceFloor"], top * t["relativeFloorFactor"]) if top > EFFECTIVE_FLOOR_DECISIVE_GATE else t["confidenceFloor"]
    return [c for c in broad if (c.get("score") or 0.0) >= eff]


def picked_for(row: dict[str, Any], t: dict[str, float], dag_goals: set[str]) -> str:
    selected = simulate(row["candidates"], row.get("relaxation_allowed", False), t)
    if not selected:
        return "abstain"
    shape = query_shape(row["expected_goal"], dag_goals)
    if shape == "analytics":
        for c in selected:
            if c.get("agentId") in dag_goals:
                return c["agentId"]
        return "no-dag-goal-in-selected"
    return selected[0].get("agentId")


# ── grouped calibration / held-out split ─────────────────────────────────────────────────────

def grouped_split(vectors: list[dict[str, Any]]):
    """Stratified-by-capability split: within each expected_goal group the rows are ordered by a stable
    hash and alternately assigned, so BOTH folds cover every capability and no identical query appears in
    both. Deterministic (hash of row id) — reproducible across runs."""
    by_goal: dict[str, list[dict[str, Any]]] = {}
    for v in vectors:
        by_goal.setdefault(v["expected_goal"], []).append(v)
    cal, held = [], []
    for goal, rows in by_goal.items():
        rows = sorted(rows, key=lambda r: hashlib.sha256(r["id"].encode()).hexdigest())
        for i, r in enumerate(rows):
            (cal if i % 2 == 0 else held).append(r)
    return cal, held


# ── scoring under a tuple ────────────────────────────────────────────────────────────────────

CLEAR = {"canonical", "near_miss", "held_out", "cross_agent_confuser",
         "name_invariance", "multi_reference", "same_name_cross_domain", "multi_turn"}


def evaluate(rows, t, dag_goals, agent_domains):
    clear = [r for r in rows if r["category"] in CLEAR]
    out = [r for r in rows if r["expected_goal"] == "abstain"]
    cap_hits = cap_n = 0
    wrong_domain = 0
    per_cap_expected: dict[str, int] = {}
    per_cap_correct: dict[str, int] = {}
    for r in clear:
        exp = r["expected_goal"]
        pick = picked_for(r, t, dag_goals)
        correct = pick == exp
        cap_n += 1
        cap_hits += int(correct)
        per_cap_expected[exp] = per_cap_expected.get(exp, 0) + 1
        per_cap_correct[exp] = per_cap_correct.get(exp, 0) + int(correct)
        if not correct and pick != "abstain":
            if agent_domains.get(pick) != agent_domains.get(exp):
                wrong_domain += 1
    abstain_ok = sum(1 for r in out if picked_for(r, t, dag_goals) == "abstain")
    min_recall = min((per_cap_correct[g] / per_cap_expected[g] for g in per_cap_expected), default=1.0)
    return {
        "cap_hits": cap_hits, "cap_n": cap_n,
        "cap_acc": (cap_hits / cap_n) if cap_n else 0.0,
        "wrong_domain": wrong_domain,
        "abstain_ok": abstain_ok, "abstain_n": len(out),
        "abstain_rate": (abstain_ok / len(out)) if out else 1.0,
        "min_recall": min_recall,
    }


def feasible(m, min_recall_floor):
    return (m["wrong_domain"] == 0
            and m["abstain_rate"] >= 1.0
            and m["min_recall"] >= min_recall_floor)


# ── SEARCH ───────────────────────────────────────────────────────────────────────────────────

def search(args) -> int:
    frozen = json.load(open(args.vectors))
    vectors = frozen["vectors"]
    dag_goals = load_dag_goals(Path(args.manifest_root))
    agent_domains = load_agent_domains(Path(args.manifest_root))
    cal, held = grouped_split(vectors)

    tuples = [dict(zip(KNOBS, combo)) for combo in itertools.product(*(GRID[k] for k in KNOBS))]
    print(f"Frozen vectors: {len(vectors)}  calibration: {len(cal)}  held-out: {len(held)}  "
          f"grid tuples: {len(tuples)}  min-recall floor: {args.min_capability_recall}")

    # Feasible on calibration; score held-out; keep an index → held cap_acc for plateau lookup.
    feas: list[tuple[dict, dict, dict]] = []
    for t in tuples:
        mc = evaluate(cal, t, dag_goals, agent_domains)
        if not feasible(mc, args.min_capability_recall):
            continue
        mh = evaluate(held, t, dag_goals, agent_domains)
        feas.append((t, mc, mh))

    if not feas:
        print("\nNo tuple satisfies the hard constraints on the calibration fold "
              "(wrong-domain=0, OOS abstain=100%, min per-capability recall). "
              "Widen the grid or relax --min-capability-recall, or the frozen vectors need re-dumping.")
        # Still report the CURRENT config's standing for context.
        report_current(cal, held, dag_goals, agent_domains)
        return 1

    best_held = max(m["cap_acc"] for _, _, m in feas)
    top = [(t, mc, mh) for (t, mc, mh) in feas if best_held - mh["cap_acc"] <= args.plateau_epsilon]

    # Plateau preference: among the top cluster, pick the tuple whose GRID NEIGHBOURS (one knob stepped)
    # are also in the top cluster most often — the most robust, least spiky setting.
    top_keys = {tuple(t[k] for k in KNOBS) for (t, _, _) in top}

    def neighbour_support(t):
        support = 0
        for k in KNOBS:
            vals = GRID[k]
            i = vals.index(t[k])
            for j in (i - 1, i + 1):
                if 0 <= j < len(vals):
                    nb = dict(t); nb[k] = vals[j]
                    if tuple(nb[kk] for kk in KNOBS) in top_keys:
                        support += 1
        return support

    top_sorted = sorted(top, key=lambda x: (neighbour_support(x[0]), x[2]["cap_acc"]), reverse=True)
    rec_t, rec_cal, rec_held = top_sorted[0]

    print(f"\nFeasible tuples: {len(feas)}   top-plateau (within {args.plateau_epsilon:.3f} of best held-out {best_held:.1%}): {len(top)}")
    print("\nRECOMMENDED threshold tuple (NOT applied — set it yourself after review):")
    for k in KNOBS:
        cur = CURRENT[k]
        flag = "" if rec_t[k] == cur else f"   (current {cur})"
        print(f"  {k:20} = {rec_t[k]}{flag}")
    print("\nRecommended tuple performance:")
    print(f"  calibration: exact-capability {rec_cal['cap_acc']:.1%} ({rec_cal['cap_hits']}/{rec_cal['cap_n']}), "
          f"OOS abstain {rec_cal['abstain_ok']}/{rec_cal['abstain_n']}, wrong-domain {rec_cal['wrong_domain']}, "
          f"min-recall {rec_cal['min_recall']:.1%}")
    print(f"  held-out   : exact-capability {rec_held['cap_acc']:.1%} ({rec_held['cap_hits']}/{rec_held['cap_n']}), "
          f"OOS abstain {rec_held['abstain_ok']}/{rec_held['abstain_n']}, wrong-domain {rec_held['wrong_domain']}, "
          f"min-recall {rec_held['min_recall']:.1%}")
    report_current(cal, held, dag_goals, agent_domains)

    out = {
        "stamp": frozen.get("stamp", {}),
        "grid": GRID,
        "constraints": {"wrong_domain_substitution": 0, "oos_abstain_rate": 1.0,
                        "min_capability_recall": args.min_capability_recall},
        "split": {"calibration": len(cal), "held_out": len(held), "method": "grouped-stratified-by-capability"},
        "recommended_thresholds": rec_t,
        "recommended_performance": {"calibration": rec_cal, "held_out": rec_held},
        "plateau_size": len(top), "feasible_tuples": len(feas),
        "note": "Deterministic-knob search only; the LLM reranker was held at live config. Set the "
                "recommended tuple in application.yml (conduit.resolver.* + conduit.routing.*) by hand, "
                "then re-run measure_goal_pick.py against the live stack to confirm WITH rerank.",
    }
    Path(args.out).write_text(json.dumps(out, indent=2) + "\n")
    print(f"\nWrote {args.out}")
    return 0


def report_current(cal, held, dag_goals, agent_domains):
    mc = evaluate(cal, CURRENT, dag_goals, agent_domains)
    mh = evaluate(held, CURRENT, dag_goals, agent_domains)
    print("\nCurrent live config, for reference:")
    print(f"  calibration: exact-capability {mc['cap_acc']:.1%} ({mc['cap_hits']}/{mc['cap_n']}), "
          f"OOS abstain {mc['abstain_ok']}/{mc['abstain_n']}, wrong-domain {mc['wrong_domain']}, min-recall {mc['min_recall']:.1%}")
    print(f"  held-out   : exact-capability {mh['cap_acc']:.1%} ({mh['cap_hits']}/{mh['cap_n']}), "
          f"OOS abstain {mh['abstain_ok']}/{mh['abstain_n']}, wrong-domain {mh['wrong_domain']}, min-recall {mh['min_recall']:.1%}")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("dump", help="freeze production-masked (C) candidate vectors from /debug/route")
    d.add_argument("--dataset", default="eval/goal-pick/labeled_queries.json")
    d.add_argument("--gateway-url", default="http://localhost:8080")
    d.add_argument("--iam-url", default="http://localhost:8084")
    d.add_argument("--password", default=os.environ.get("CONDUIT_DEMO_PASSWORD", "Meridian@2024"))
    d.add_argument("--model-id", default=os.environ.get("CONDUIT_EMBEDDING_MODEL_ID", "all-MiniLM-L6-v2"))
    d.add_argument("--dataset-rev", default="")
    d.add_argument("--out", default="eval/goal-pick/baselines/production-masked-C.json")
    d.set_defaults(func=dump)

    s = sub.add_parser("search", help="offline joint grid-search over the frozen vectors")
    s.add_argument("--vectors", default="eval/goal-pick/baselines/production-masked-C.json")
    s.add_argument("--manifest-root", default="registry/manifests")
    s.add_argument("--min-capability-recall", type=float, default=0.6)
    s.add_argument("--plateau-epsilon", type=float, default=0.02)
    s.add_argument("--out", default="eval/goal-pick/baselines/rebaseline-recommendation.json")
    s.set_defaults(func=search)

    args = p.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
