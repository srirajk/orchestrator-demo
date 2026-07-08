#!/usr/bin/env python3
"""Measure live goal-pick accuracy without changing routing behavior.

The harness calls the gateway's shipped /debug/resolve endpoint so every score comes
from the running resolver, embedding service, Redis vector index, and registry.

T1.6 fix (honest per-shape measurement): T1.5's headline number forced `picked_goal`
to always prefer a DAG-capable (fan-in) agent if ANY such agent appeared anywhere in
the selected/floor-passed candidate set — regardless of its rank. That is the correct
rule ONLY for a query whose true intended answer IS an analytics fan-in goal. Applied
to a flat/leaf query (e.g. "show top holdings" -> `wealth.holdings`, a non-fan-in leaf
agent), it could never score correct even when the router's top-scored agent was
exactly right, because some unrelated fan-in agent (e.g. `wealth.concentration`)
merely showed up lower in the same candidate list.

Each labeled query now has a well-defined shape derived from its OWN expected_goal
(not relabeled by the harness):
  1. out_of_scope  -> expected_goal == "abstain": correct iff the router abstains
     (fallback flag set, or no candidates cleared the floor).
  2. analytics     -> expected_goal is itself a DAG-capable (fan-in) agent id: correct
     iff the RESOLVED DAG goal equals expected_goal. The resolved goal mirrors the
     gateway's tryDag() rule verbatim (ChatService.tryDag, `hasProducedRefConsume`):
     the highest-ranked selected candidate whose manifest declares a produced-ref
     (`from`) consume. This is a real production rule, not a harness invention.
  3. flat          -> expected_goal is a non-fan-in (leaf/data) agent id: correct iff
     the router's TOP-SCORED candidate (selected[0], i.e. plain top-1 by embedding
     score) equals expected_goal. No DAG-goal override applies here — a flat query
     was never going to run the DAG for some other unrelated fan-in agent that
     happened to also clear the floor.

This is NOT a relabel: every query keeps the label of what it SHOULD route to (from
labeled_queries.json's expected_goal, untouched). The only change is which of the two
router-derived signals (top-1 vs resolved-DAG-goal) is the correct one to compare
against, decided from the query's OWN expected shape -- not picked to flatter the
score.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path
from typing import Any


def load_dag_goals(manifest_root: Path) -> set[str]:
    """Agent ids whose manifest declares a fan-in (produced-ref / `from`) consume.

    Mirrors ChatService.hasProducedRefConsume() verbatim: an agent is DAG-capable if
    ANY of its io.consumes entries has a `from` key (a produced-output reference to
    an upstream agent), rather than a plain entity reference.
    """
    goals: set[str] = set()
    for path in manifest_root.rglob("*.json"):
        with path.open() as f:
            manifest = json.load(f)
        consumes = ((manifest.get("io") or {}).get("consumes")) or []
        if any((c or {}).get("from") for c in consumes):
            goals.add(manifest["agent_id"])
    return goals


def load_agent_domains(manifest_root: Path) -> dict[str, str]:
    """agent_id -> domain, straight from each manifest's own `domain` field."""
    domains: dict[str, str] = {}
    for path in manifest_root.rglob("*.json"):
        with path.open() as f:
            manifest = json.load(f)
        aid = manifest.get("agent_id")
        dom = manifest.get("domain")
        if aid and dom:
            domains[aid] = dom
    return domains


def mint_admin_token(iam_url: str, password: str) -> str:
    req = urllib.request.Request(
        f"{iam_url.rstrip('/')}/auth/token",
        data=json.dumps({"username": "admin", "password": password}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        data = json.loads(resp.read())
        return data.get("accessToken") or data["access_token"]


def resolve(gateway_url: str, prompt: str, token: str) -> dict[str, Any]:
    url = f"{gateway_url.rstrip()}/debug/resolve?prompt={urllib.parse.quote(prompt)}"
    req = urllib.request.Request(
        url,
        headers={"Authorization": f"Bearer {token}"},
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read())


def is_abstain(response: dict[str, Any]) -> bool:
    return bool(response.get("fallback")) or not (response.get("selected") or [])


def top1_agent(response: dict[str, Any]) -> str:
    """Plain top-scored candidate — no DAG-goal override. Used for FLAT queries."""
    selected = response.get("selected") or []
    if not selected:
        return "abstain"
    return selected[0].get("agent_id") or "abstain"


def resolved_dag_goal(response: dict[str, Any], dag_goals: set[str]) -> str:
    """Mirrors ChatService.tryDag()'s goal-selection rule: the highest-ranked selected
    candidate that is DAG-capable. Used ONLY for ANALYTICS-shaped queries (queries
    whose expected_goal is itself a fan-in agent) — this is the real rule the
    production chat path applies once a fan-in candidate is in the routed set."""
    selected = response.get("selected") or []
    if is_abstain(response):
        return "abstain"
    for candidate in selected:
        agent_id = candidate.get("agent_id")
        if agent_id in dag_goals:
            return agent_id
    return "no-dag-goal-in-selected"


def query_shape(expected_goal: str, dag_goals: set[str]) -> str:
    if expected_goal == "abstain":
        return "out_of_scope"
    if expected_goal in dag_goals:
        return "analytics"
    return "flat"


def score_row(item: dict[str, Any], response: dict[str, Any], dag_goals: set[str],
              agent_domains: dict[str, str]) -> dict[str, Any]:
    expected = item["expected_goal"]
    shape = query_shape(expected, dag_goals)

    if shape == "out_of_scope":
        picked = "abstain" if is_abstain(response) else top1_agent(response)
        correct = is_abstain(response)
    elif shape == "analytics":
        picked = resolved_dag_goal(response, dag_goals)
        correct = picked == expected
    else:  # flat
        picked = top1_agent(response)
        correct = picked == expected

    # Domain-level accuracy — a SEPARATE, more lenient signal reported ALONGSIDE (never instead
    # of) the strict per-shape agent-exact accuracy above, per the T1.6 spec's explicit ask for
    # "an overall domain-level accuracy" in addition to the three per-shape numbers. Correct iff
    # the picked agent's manifest `domain` equals the expected agent's manifest `domain` — e.g.
    # picking `servicing.corporate_actions` when `servicing.settlement_status` was expected is a
    # wrong AGENT but a right DOMAIN. Not computed for out_of_scope (there is no "domain" for an
    # abstain-shaped query; its own correctness is the abstain rate above).
    expected_domain = agent_domains.get(expected)
    picked_domain = agent_domains.get(picked)
    domain_correct = (picked_domain is not None and picked_domain == expected_domain) if shape != "out_of_scope" else None

    selected_ids = [c.get("agent_id") for c in response.get("selected", []) if c.get("agent_id")]
    return {
        **item,
        "shape": shape,
        "picked_goal": picked,
        "correct": correct,
        "expected_domain": expected_domain,
        "picked_domain": picked_domain,
        "domain_correct": domain_correct,
        "fallback": bool(response.get("fallback")),
        "top_score": response.get("top_score"),
        "selected_agent_ids": selected_ids,
        "selected": response.get("selected", []),
    }


def summarize(rows: list[dict[str, Any]], thresholds: dict[str, float]) -> int:
    # held_out = T1.6 paraphrases NOT used as any skill example (see labeled_queries.json notes);
    # included in the "clear" tally so a passing gate reflects genuine generalization, not just
    # queries the manifest examples were tuned against.
    clear_categories = {"canonical", "near_miss", "held_out"}
    clear_rows = [r for r in rows if r["category"] in clear_categories]

    flat_rows = [r for r in clear_rows if r["shape"] == "flat"]
    analytics_rows = [r for r in clear_rows if r["shape"] == "analytics"]
    out_rows = [r for r in rows if r["shape"] == "out_of_scope"]

    def rate(group: list[dict[str, Any]]) -> tuple[int, int, float]:
        hits = sum(1 for r in group if r["correct"])
        n = len(group)
        return hits, n, (hits / n if n else 0.0)

    flat_hits, flat_n, flat_acc = rate(flat_rows)
    an_hits, an_n, an_acc = rate(analytics_rows)
    ab_hits, ab_n, ab_acc = rate(out_rows)
    clear_hits, clear_n, clear_acc = rate(clear_rows)  # exact-agent accuracy over ALL clear queries

    domain_rows = [r for r in clear_rows if r["domain_correct"] is not None]
    dom_hits = sum(1 for r in domain_rows if r["domain_correct"])
    dom_n = len(domain_rows)
    dom_acc = dom_hits / dom_n if dom_n else 0.0

    print("\nGoal-pick measurement (T1.6 — per-shape, honest)")
    print(f"Queries: {len(rows)}")
    print(f"1. Flat top-agent accuracy      : {flat_acc:6.1%} ({flat_hits}/{flat_n})")
    print(f"2. Analytics goal accuracy      : {an_acc:6.1%} ({an_hits}/{an_n})")
    print(f"3. Out-of-scope abstain rate    : {ab_acc:6.1%} ({ab_hits}/{ab_n})")
    print(f"Overall exact-agent accuracy (flat+analytics combined): {clear_acc:6.1%} ({clear_hits}/{clear_n})")
    print(f"Overall DOMAIN-level accuracy (right domain, agent may differ): {dom_acc:6.1%} ({dom_hits}/{dom_n})")
    print()

    by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_category[row["category"]].append(row)
    for category in sorted(by_category):
        group = by_category[category]
        if category == "out_of_scope":
            hits = sum(1 for r in group if r["correct"])
            label = "abstain"
        else:
            hits = sum(1 for r in group if r["correct"])
            label = "accuracy"
        print(f"{category:22} {label}: {hits / len(group):6.1%} ({hits}/{len(group)})")

    misses = [r for r in rows if not r["correct"]]
    if misses:
        print("\nConfusion list (misses)")
        for r in misses:
            selected = ", ".join(r["selected_agent_ids"])
            dom_flag = " [right-domain]" if r.get("domain_correct") else ""
            print(f"- {r['id']} [{r['shape']}]: expected={r['expected_goal']} picked={r['picked_goal']}{dom_flag} "
                  f"top_score={r['top_score']} selected=[{selected}]")

    flat_floor = thresholds.get("flat_accuracy", thresholds.get("clear_goal_accuracy", 0.9))
    analytics_floor = thresholds.get("analytics_accuracy", thresholds.get("clear_goal_accuracy", 0.9))
    abstain_floor = thresholds.get("abstain_rate", 0.9)
    domain_floor = thresholds.get("domain_accuracy", 0.9)
    ok = (flat_acc >= flat_floor and an_acc >= analytics_floor and ab_acc >= abstain_floor
          and dom_acc >= domain_floor)
    if not ok:
        print("\nFAIL: decision gate not met")
        return 1
    print("\nPASS: decision gate met")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default="eval/goal-pick/labeled_queries.json")
    parser.add_argument("--gateway-url", default="http://localhost:8080")
    parser.add_argument("--iam-url", default="http://localhost:8084")
    parser.add_argument("--password", default=os.environ.get("CONDUIT_DEMO_PASSWORD", "Meridian@2024"))
    parser.add_argument("--manifest-root", default="registry/manifests")
    parser.add_argument("--token", default="")
    parser.add_argument("--output", default="/tmp/goal-pick-measurement.json")
    args = parser.parse_args()

    dataset_path = Path(args.dataset)
    with dataset_path.open() as f:
        dataset = json.load(f)

    dag_goals = load_dag_goals(Path(args.manifest_root))
    agent_domains = load_agent_domains(Path(args.manifest_root))
    token = args.token or mint_admin_token(args.iam_url, args.password)
    rows: list[dict[str, Any]] = []

    for item in dataset["queries"]:
        try:
            response = resolve(args.gateway_url, item["query"], token)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode(errors="replace")
            print(f"ERROR {item['id']}: HTTP {exc.code}: {body}", file=sys.stderr)
            return 2
        except Exception as exc:
            print(f"ERROR {item['id']}: {exc}", file=sys.stderr)
            return 2

        rows.append(score_row(item, response, dag_goals, agent_domains))

    output = {
        "dataset": str(dataset_path),
        "gateway_url": args.gateway_url,
        "dag_goals": sorted(dag_goals),
        "summary": {
            "total": len(rows),
            "correct": sum(1 for r in rows if r["correct"]),
        },
        "rows": rows,
    }
    Path(args.output).write_text(json.dumps(output, indent=2) + "\n")
    print(f"Wrote {args.output}")
    return summarize(rows, dataset.get("thresholds", {}))


if __name__ == "__main__":
    raise SystemExit(main())
