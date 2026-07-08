#!/usr/bin/env python3
"""Measure live goal-pick accuracy without changing routing behavior.

The harness calls the gateway's shipped /debug/resolve endpoint so every score comes
from the running resolver, embedding service, Redis vector index, and registry.
For DAG-capable goals, it mirrors the chat service's documented goal rule: choose the
highest-ranked selected candidate whose manifest consumes an upstream produced output.
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


DAG_GOALS: set[str] = set()


def load_dag_goals(manifest_root: Path) -> set[str]:
    goals: set[str] = set()
    for path in manifest_root.rglob("*.json"):
        with path.open() as f:
            manifest = json.load(f)
        consumes = ((manifest.get("io") or {}).get("consumes")) or []
        if any((c or {}).get("from") for c in consumes):
            goals.add(manifest["agent_id"])
    return goals


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


def picked_goal(response: dict[str, Any], dag_goals: set[str]) -> str:
    selected = response.get("selected") or []
    if response.get("fallback") or not selected:
        return "abstain"
    for candidate in selected:
        agent_id = candidate.get("agent_id")
        if agent_id in dag_goals:
            return agent_id
    return selected[0].get("agent_id") or "abstain"


def summarize(rows: list[dict[str, Any]], thresholds: dict[str, float]) -> int:
    clear_categories = {"canonical", "near_miss"}
    by_category: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_category[row["category"]].append(row)

    clear_rows = [r for r in rows if r["category"] in clear_categories]
    clear_accuracy = sum(1 for r in clear_rows if r["correct"]) / max(len(clear_rows), 1)

    out_rows = by_category.get("out_of_scope", [])
    abstain_rate = sum(1 for r in out_rows if r["picked_goal"] == "abstain") / max(len(out_rows), 1)

    print("\nGoal-pick measurement")
    print(f"Queries: {len(rows)}")
    print(f"Clear goal accuracy (canonical + near_miss): {clear_accuracy:.1%} ({sum(1 for r in clear_rows if r['correct'])}/{len(clear_rows)})")
    print(f"Out-of-scope abstain rate: {abstain_rate:.1%} ({sum(1 for r in out_rows if r['picked_goal'] == 'abstain')}/{len(out_rows)})")
    print()

    for category in sorted(by_category):
        group = by_category[category]
        if category == "out_of_scope":
            hits = sum(1 for r in group if r["picked_goal"] == "abstain")
            label = "abstain"
        else:
            hits = sum(1 for r in group if r["correct"])
            label = "accuracy"
        print(f"{category:22} {label}: {hits / len(group):6.1%} ({hits}/{len(group)})")

    misses = [r for r in rows if not r["correct"]]
    if misses:
        print("\nMisses")
        for r in misses:
            selected = ", ".join(r["selected_agent_ids"])
            print(f"- {r['id']}: expected={r['expected_goal']} picked={r['picked_goal']} selected=[{selected}]")

    clear_floor = thresholds.get("clear_goal_accuracy", 0.9)
    abstain_floor = thresholds.get("abstain_rate", 0.9)
    if clear_accuracy < clear_floor or abstain_rate < abstain_floor:
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

        selected_ids = [c.get("agent_id") for c in response.get("selected", []) if c.get("agent_id")]
        goal = picked_goal(response, dag_goals)
        expected = item["expected_goal"]
        rows.append({
            **item,
            "picked_goal": goal,
            "correct": goal == expected,
            "fallback": bool(response.get("fallback")),
            "top_score": response.get("top_score"),
            "selected_agent_ids": selected_ids,
            "selected": response.get("selected", []),
        })

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
