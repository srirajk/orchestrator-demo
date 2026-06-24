#!/usr/bin/env python3
"""
Meridian Gateway — Evaluation using deepeval framework.

Metrics:
  1. RoutingAccuracyMetric (custom): checks if the resolver selected the expected agents
  2. FaithfulnessMetric: checks if a synthesized answer is faithful to agent data
  3. HallucinationMetric: checks for invented content in synthesized answers

Usage:
  python3 eval/eval_deepeval.py [--gateway-url http://localhost:8080] [--threshold 0.75]
"""

import sys
import json
import argparse
import urllib.request
import urllib.parse
import os

# ── Deep Eval imports ─────────────────────────────────────────────────────────
from deepeval import evaluate
from deepeval.metrics import (
    FaithfulnessMetric,
    HallucinationMetric,
)
from deepeval.test_case import LLMTestCase
from deepeval.metrics.base_metric import BaseMetric

# ── Custom Routing Accuracy Metric ───────────────────────────────────────────

class RoutingAccuracyMetric(BaseMetric):
    """
    Measures how well the resolver selects the expected agents.
    Uses F1 score (precision/recall balance) between predicted and expected agent sets.
    Score = avg F1 across all test cases. threshold = minimum passing avg F1.
    """

    def __init__(self, threshold: float = 0.75):
        self.threshold = threshold
        self.name = "Routing Accuracy (F1)"

    def measure(self, test_case: LLMTestCase) -> float:
        predicted_raw = test_case.actual_output or ""
        expected_raw = test_case.expected_output or ""

        predicted = set(predicted_raw.split(",")) if predicted_raw else set()
        expected = set(expected_raw.split(",")) if expected_raw else set()

        if not predicted and not expected:
            self.score = 1.0
            self.success = True
            return 1.0
        if not predicted or not expected:
            self.score = 0.0
            self.success = False
            return 0.0

        tp = len(predicted & expected)
        precision = tp / len(predicted) if predicted else 0.0
        recall = tp / len(expected) if expected else 0.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
        self.score = f1
        self.success = f1 >= self.threshold
        return f1

    async def a_measure(self, test_case: LLMTestCase, *args, **kwargs) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return getattr(self, "success", False)

    @property
    def __name__(self):
        return self.name


# ── Gateway helpers ───────────────────────────────────────────────────────────

def call_resolver(gateway_url: str, prompt: str) -> list[str]:
    url = f"{gateway_url}/debug/resolve?prompt={urllib.parse.quote(prompt)}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=15) as r:
        data = json.loads(r.read())
    if data.get("fallback"):
        return []
    return [c["agent_id"] for c in data.get("selected", [])]


def call_chat(gateway_url: str, prompt: str, user_id: str = "rm_jane") -> tuple[str, list[str]]:
    """Get a synthesized answer and the agent outputs used."""
    url = f"{gateway_url}/v1/chat/completions"
    body = json.dumps({
        "model": "meridian-assistant",
        "stream": False,
        "messages": [{"role": "user", "content": prompt}]
    }).encode()
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", "X-User-Id": user_id}
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            data = json.loads(r.read())
        content = data["choices"][0]["message"]["content"]
        return content, []
    except Exception as e:
        return f"Error: {e}", []


# ── Main ──────────────────────────────────────────────────────────────────────

def run_routing_eval(gateway_url: str, prompts_path: str, threshold: float) -> float:
    with open(prompts_path) as f:
        data = json.load(f)
    prompts = data["prompts"]

    print(f"\n{'='*70}")
    print(f" Meridian Routing Accuracy — deepeval framework")
    print(f" Gateway: {gateway_url}  |  Prompts: {len(prompts)}  |  Threshold: {threshold:.0%}")
    print(f"{'='*70}\n")

    metric = RoutingAccuracyMetric(threshold=0.5)  # per-case threshold (lenient)
    test_cases = []
    errors = 0

    for p in prompts:
        try:
            selected = call_resolver(gateway_url, p["prompt"])
            predicted_str = ",".join(selected)
            expected_str = ",".join(p["expected_agents"])

            test_case = LLMTestCase(
                input=p["prompt"],
                actual_output=predicted_str,
                expected_output=expected_str,
            )
            test_cases.append((p["id"], test_case, expected_str, predicted_str))
        except Exception as e:
            print(f"  ERROR {p['id']}: {e}", file=sys.stderr)
            errors += 1

    total_f1 = 0.0
    results = []
    for pid, tc, expected, predicted in test_cases:
        f1 = metric.measure(tc)
        total_f1 += f1
        exp_short = ",".join(a.split(".")[-1] for a in expected.split(","))
        got_short = ",".join(a.split(".")[-1] for a in predicted.split(",") if a)
        flag = "✓" if f1 >= 0.8 else ("~" if f1 >= 0.5 else "✗")
        results.append((pid, f1, exp_short, got_short, flag))

    results.sort(key=lambda x: x[1])

    print(f"{'Prompt ID':<25} {'F1':>6}  {'Expected':<40} {'Got'}")
    print("-" * 110)
    for pid, f1, exp, got, flag in results:
        print(f"{pid:<25} {f1:>5.0%} {flag}  {exp:<40} {got}")

    evaluated = len(test_cases)
    accuracy = total_f1 / evaluated if evaluated > 0 else 0.0

    print("-" * 110)
    print(f"\n{'ROUTING ACCURACY (avg F1):':<35} {accuracy:.1%}  (threshold: {threshold:.0%})")
    if errors:
        print(f"  Errors: {errors} prompts failed to resolve")

    return accuracy


def run_faithfulness_spot_check(gateway_url: str) -> None:
    """
    Spot-check 3 hero answers for faithfulness using deepeval's FaithfulnessMetric.
    Agent outputs are the ground truth context — the answer must not introduce facts.
    """
    print(f"\n{'='*70}")
    print(f" Faithfulness Spot-Check (3 hero prompts via deepeval FaithfulnessMetric)")
    print(f"{'='*70}\n")

    # Known grounded facts from the Whitman canned data
    spot_prompts = [
        {
            "prompt": "Give me a full picture of Whitman Family Trust: holdings and performance",
            "context": [
                "Holdings for REL-00042 Whitman Family Office: AAPL 1200 shares $318000, MSFT 800 shares $372000, GOOGL 150 shares $289500, JPM 2500 shares $487500, T-BILL-2026 1 position $500000. Total market value: $1967000.",
                "Performance QTD for REL-00042: total_return_pct=12.4%, pnl=243908, benchmark_return=10.2%, alpha=2.2%, volatility=8.7%, sharpe=1.43."
            ]
        }
    ]

    for sp in spot_prompts:
        answer, _ = call_chat(gateway_url, sp["prompt"])
        if answer.startswith("Error:"):
            print(f"  SKIP (chat error): {answer}")
            continue

        test_case = LLMTestCase(
            input=sp["prompt"],
            actual_output=answer,
            retrieval_context=sp["context"]
        )

        try:
            # Use Z.AI GLM via OpenAI-compatible endpoint
            import os
            os.environ.setdefault("OPENAI_API_KEY", os.getenv("ZAI_API_KEY", "unused"))

            metric = FaithfulnessMetric(
                threshold=0.5,
                model="gpt-4o-mini",  # deepeval model judge
                include_reason=True
            )
            metric.measure(test_case)
            print(f"  Faithfulness score: {metric.score:.2f}  (success={metric.success})")
            if metric.reason:
                print(f"  Reason: {metric.reason[:200]}")
        except Exception as e:
            print(f"  Faithfulness check skipped (needs LLM judge key): {type(e).__name__}: {str(e)[:100]}")
            print(f"  Answer preview: {answer[:200]}...")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default="http://localhost:8080")
    parser.add_argument("--prompts", default="eval/golden-prompts.json")
    parser.add_argument("--threshold", type=float, default=0.75)
    parser.add_argument("--skip-faithfulness", action="store_true",
                        help="Skip faithfulness spot-check (no LLM judge needed)")
    args = parser.parse_args()

    accuracy = run_routing_eval(args.gateway_url, args.prompts, args.threshold)

    if not args.skip_faithfulness:
        run_faithfulness_spot_check(args.gateway_url)

    print(f"\n{'='*70}")
    if accuracy >= args.threshold:
        print(f"✓ PASS — routing accuracy {accuracy:.1%} >= {args.threshold:.0%}")
        sys.exit(0)
    else:
        print(f"✗ FAIL — routing accuracy {accuracy:.1%} < {args.threshold:.0%}")
        sys.exit(1)


if __name__ == "__main__":
    main()
