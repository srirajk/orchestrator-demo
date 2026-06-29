#!/usr/bin/env python3
"""
Cerbos Policy Evaluator — runs the golden dataset against the live Cerbos PDP.

Usage:
  python3 eval/cerbos_policy_eval.py                  # run all cases
  python3 eval/cerbos_policy_eval.py --tags critical  # run critical invariants only
  python3 eval/cerbos_policy_eval.py --tags agent     # run agent cases only
  python3 eval/cerbos_policy_eval.py --fail-fast      # stop on first failure
  python3 eval/cerbos_policy_eval.py --cerbos http://cerbos:3592  # custom URL

Exit codes:
  0 — all selected cases passed
  1 — one or more cases failed
  2 — dataset file not found or Cerbos unreachable
"""

import argparse
import json
import sys
import urllib.request
import urllib.error
from pathlib import Path

DATASET_PATH = Path(__file__).parent / "cerbos_golden_dataset.json"
DEFAULT_CERBOS = "http://localhost:3594"


def check(cerbos_url: str, case: dict) -> tuple[str, str | None]:
    """Returns (actual_effect, error_message). actual_effect is 'ALLOW' or 'DENY'."""
    payload = {
        "principal": {
            "id": case["principal"]["id"],
            "roles": case["principal"]["roles"],
            "attr": case["principal"].get("attr", {}),
        },
        "resources": [{
            "resource": {
                "kind": case["resource"]["kind"],
                "id": case["resource"]["id"],
                "attr": case["resource"].get("attr", {}),
            },
            "actions": [case["action"]],
        }],
    }
    req = urllib.request.Request(
        f"{cerbos_url}/api/check/resources",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            result = json.loads(resp.read())
        effect = result["results"][0]["actions"][case["action"]]
        return ("ALLOW" if effect == "EFFECT_ALLOW" else "DENY"), None
    except urllib.error.HTTPError as e:
        return "", f"HTTP {e.code}: {e.read().decode()[:200]}"
    except Exception as e:
        return "", str(e)


def run(args):
    if not DATASET_PATH.exists():
        print(f"ERROR: dataset not found at {DATASET_PATH}", file=sys.stderr)
        sys.exit(2)

    with open(DATASET_PATH) as f:
        dataset = json.load(f)

    cases = dataset["cases"]

    # Filter by tags if requested
    if args.tags:
        requested = set(args.tags.split(","))
        cases = [c for c in cases if requested.intersection(c.get("tags", []))]
        if not cases:
            print(f"No cases match tags: {args.tags}")
            sys.exit(0)

    # Check Cerbos reachability
    try:
        urllib.request.urlopen(f"{args.cerbos}/api/server_info", timeout=5)
    except Exception as e:
        print(f"ERROR: Cerbos not reachable at {args.cerbos} — {e}", file=sys.stderr)
        sys.exit(2)

    passed, failed_cases = [], []

    for case in cases:
        actual, err = check(args.cerbos, case)
        case_id = case["id"]
        desc = case["description"]
        tags = case.get("tags", [])
        is_critical = "critical" in tags or "invariant" in tags

        if err:
            failed_cases.append((case_id, desc, case["expected"], f"ERROR: {err}", is_critical))
        elif actual == case["expected"]:
            passed.append(case_id)
            if args.verbose:
                print(f"  ✓  [{case_id}] {desc[:70]}")
        else:
            failed_cases.append((case_id, desc, case["expected"], actual, is_critical))
            if args.fail_fast:
                break

    # Summary
    total = len(cases)
    n_passed = len(passed)
    n_failed = len(failed_cases)
    score = n_passed / total if total else 0
    critical_failures = [f for f in failed_cases if f[4]]

    print(f"\n{'═' * 60}")
    print(f"  CERBOS GOLDEN DATASET — {n_passed}/{total} passed ({score:.0%})")
    print(f"{'═' * 60}")

    if failed_cases:
        print(f"\n  FAILURES ({n_failed}):\n")
        for case_id, desc, expected, actual, is_crit in failed_cases:
            marker = "🔴 CRITICAL" if is_crit else "🟡"
            print(f"  {marker} [{case_id}] {desc}")
            print(f"     expected={expected}  actual={actual}\n")

    if critical_failures:
        print(f"  ⛔  {len(critical_failures)} CRITICAL INVARIANT(S) VIOLATED — policy is unsafe to deploy\n")
    elif n_failed:
        print(f"  ⚠️   {n_failed} non-critical case(s) failed\n")
    else:
        print(f"  ✅  All {total} cases passed — policies are correctly configured\n")

    sys.exit(0 if n_failed == 0 else 1)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Validate Cerbos policies against the golden dataset")
    parser.add_argument("--cerbos", default=DEFAULT_CERBOS, help="Cerbos PDP base URL")
    parser.add_argument("--tags", default=None, help="Comma-separated tags to filter cases (e.g. critical,agent)")
    parser.add_argument("--fail-fast", action="store_true", help="Stop on first failure")
    parser.add_argument("--verbose", "-v", action="store_true", help="Print passing cases too")
    run(parser.parse_args())
