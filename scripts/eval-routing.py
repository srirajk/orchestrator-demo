#!/usr/bin/env python3
"""
Meridian Gateway — Routing Accuracy Evaluation

Runs golden prompts through the resolver and prints per-prompt F1 and overall accuracy.
Exit code 0 = above threshold, 1 = below threshold.

Usage: python3 scripts/eval-routing.py [--gateway-url http://localhost:8080]
"""

import sys
import json
import argparse
import urllib.request
import urllib.error

def load_prompts(path="eval/golden-prompts.json"):
    with open(path) as f:
        return json.load(f)

def resolve(gateway_url, prompt):
    """Call GET /debug/resolve?prompt=... and return selected agent IDs."""
    import urllib.parse
    url = f"{gateway_url}/debug/resolve?prompt={urllib.parse.quote(prompt)}"
    req = urllib.request.Request(url, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read())
            if data.get("fallback"):
                return []
            return [c["agent_id"] for c in data.get("selected", [])]
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode()}", file=sys.stderr)
        return None
    except Exception as e:
        print(f"  Error: {e}", file=sys.stderr)
        return None

def f1(predicted, expected):
    if not predicted and not expected:
        return 1.0, 1.0, 1.0
    if not predicted or not expected:
        return 0.0, 0.0, 0.0
    p_set = set(predicted)
    e_set = set(expected)
    tp = len(p_set & e_set)
    precision = tp / len(p_set) if p_set else 0.0
    recall    = tp / len(e_set) if e_set else 0.0
    f1_score  = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    return precision, recall, f1_score

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default="http://localhost:8080")
    parser.add_argument("--prompts", default="eval/golden-prompts.json")
    args = parser.parse_args()

    data = load_prompts(args.prompts)
    threshold = data.get("threshold", 0.75)
    prompts   = data["prompts"]

    print(f"\nMeridian Routing Evaluation — {len(prompts)} golden prompts")
    print(f"Gateway: {args.gateway_url}")
    print(f"Threshold: {threshold:.0%}\n")
    print(f"{'ID':<25} {'P':>5} {'R':>5} {'F1':>5}  {'Expected':<45} {'Got'}")
    print("-" * 130)

    total_f1 = 0.0
    errors = 0

    for p in prompts:
        selected = resolve(args.gateway_url, p["prompt"])
        if selected is None:
            errors += 1
            print(f"{p['id']:<25} {'ERR':>5}")
            continue

        prec, rec, f1_score = f1(selected, p["expected_agents"])
        total_f1 += f1_score

        expected_short = ",".join(a.split(".")[-1] for a in p["expected_agents"])
        got_short      = ",".join(a.split(".")[-1] for a in selected)
        flag = "✓" if f1_score >= 0.8 else ("~" if f1_score >= 0.5 else "✗")

        print(f"{p['id']:<25} {prec:>5.0%} {rec:>5.0%} {f1_score:>5.0%}  "
              f"{expected_short:<45} {got_short}  {flag}")

    evaluated = len(prompts) - errors
    accuracy  = total_f1 / evaluated if evaluated > 0 else 0.0

    print("-" * 130)
    print(f"\n{'ROUTING ACCURACY (avg F1):':<35} {accuracy:.1%}  (threshold: {threshold:.0%})")

    if errors:
        print(f"Errors (connection/HTTP): {errors}")

    if accuracy >= threshold:
        print(f"\n✓ PASS — routing accuracy {accuracy:.1%} >= {threshold:.0%}")
        sys.exit(0)
    else:
        print(f"\n✗ FAIL — routing accuracy {accuracy:.1%} < {threshold:.0%}")
        sys.exit(1)

if __name__ == "__main__":
    main()
