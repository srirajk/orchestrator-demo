#!/usr/bin/env python3
"""
Conduit Gateway — Routing Accuracy Evaluation

Runs golden prompts through the resolver and prints per-prompt F1 and overall accuracy.
Exit code 0 = above threshold, 1 = below threshold.

Usage:
  python3 scripts/eval-routing.py [--gateway-url http://localhost:8080] [--iam-service-url http://localhost:8084]

The debug/resolve endpoint requires platform_admin or domain_admin role.
This script mints an admin JWT from iam-service automatically.
Override with --token <jwt> if the token endpoint is unavailable.
"""

import sys
import json
import argparse
import urllib.request
import urllib.error


def mint_admin_token(user_mgmt_url: str) -> str:
    """Mint a short-lived admin JWT from the iam-service service."""
    req = urllib.request.Request(
        f"{user_mgmt_url}/auth/token",
        data=json.dumps({"user_id": "admin"}).encode(),
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read())["access_token"]
    except Exception as e:
        print(f"Warning: could not mint admin token from {user_mgmt_url}: {e}", file=sys.stderr)
        return ""


def load_prompts(path="eval/golden-prompts.json"):
    with open(path) as f:
        return json.load(f)


def resolve(gateway_url: str, prompt: str, token: str = "") -> list:
    """Call GET /debug/resolve?prompt=... and return selected agent IDs."""
    import urllib.parse
    url = f"{gateway_url}/debug/resolve?prompt={urllib.parse.quote(prompt)}"
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers, method="GET")
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
    parser.add_argument("--gateway-url",  default="http://localhost:8080")
    parser.add_argument("--iam-service-url", default="http://localhost:8084")
    parser.add_argument("--prompts",      default="eval/golden-prompts.json")
    parser.add_argument("--token",        default="",
                        help="Admin JWT to use instead of auto-minting from iam-service")
    args = parser.parse_args()

    # Mint admin token if not provided
    token = args.token or mint_admin_token(args.user_mgmt_url)
    if not token:
        print("ERROR: no admin token available — debug/resolve requires platform_admin role",
              file=sys.stderr)
        sys.exit(2)

    data = load_prompts(args.prompts)
    threshold = data.get("threshold", 0.75)
    prompts   = data["prompts"]

    print(f"\nMeridian Routing Evaluation — {len(prompts)} golden prompts")
    print(f"Gateway:   {args.gateway_url}")
    print(f"Threshold: {threshold:.0%}\n")
    print(f"{'ID':<25} {'P':>5} {'R':>5} {'F1':>5}  {'Expected':<50} {'Got'}")
    print("-" * 140)

    total_f1 = 0.0
    errors = 0
    per_prompt = []

    for p in prompts:
        selected = resolve(args.gateway_url, p["prompt"], token)
        if selected is None:
            errors += 1
            print(f"{p['id']:<25} {'ERR':>5}")
            per_prompt.append({"id": p["id"], "f1": 0.0, "error": True})
            continue

        prec, rec, f1_score = f1(selected, p["expected_agents"])
        total_f1 += f1_score
        per_prompt.append({"id": p["id"], "f1": f1_score, "precision": prec, "recall": rec,
                           "expected": p["expected_agents"], "got": selected})

        expected_short = ",".join(a.split(".")[-1] for a in p["expected_agents"])
        got_short      = ",".join(a.split(".")[-1] for a in selected)
        flag = "✓" if f1_score >= 0.8 else ("~" if f1_score >= 0.5 else "✗")

        print(f"{p['id']:<25} {prec:>5.0%} {rec:>5.0%} {f1_score:>5.0%}  "
              f"{expected_short:<50} {got_short}  {flag}")

    evaluated = len(prompts) - errors
    accuracy  = total_f1 / evaluated if evaluated > 0 else 0.0

    # Breakdown by category (prefix before _NNN)
    by_cat = {}
    for entry in per_prompt:
        if entry.get("error"):
            continue
        cat = "_".join(entry["id"].split("_")[:-1])
        by_cat.setdefault(cat, []).append(entry["f1"])

    print("-" * 140)
    print(f"\n{'ROUTING ACCURACY (avg F1):':<35} {accuracy:.1%}  (threshold: {threshold:.0%})")
    print(f"\nPer-category breakdown:")
    for cat, scores in sorted(by_cat.items()):
        avg = sum(scores) / len(scores)
        flag = "✓" if avg >= 0.8 else ("~" if avg >= 0.5 else "✗")
        print(f"  {cat:<25} avg F1 {avg:.0%} over {len(scores)} prompt(s)  {flag}")

    if errors:
        print(f"\nErrors (connection/HTTP/auth): {errors}")

    if accuracy >= threshold:
        print(f"\n✓ PASS — routing accuracy {accuracy:.1%} >= {threshold:.0%}")
        sys.exit(0)
    else:
        print(f"\n✗ FAIL — routing accuracy {accuracy:.1%} < {threshold:.0%}")
        sys.exit(1)

if __name__ == "__main__":
    main()
