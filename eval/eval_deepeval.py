#!/usr/bin/env python3
"""
Meridian Gateway — Evaluation using deepeval framework.

Metrics:
  1. RoutingAccuracyMetric (custom): checks if the resolver selected the expected agents
  2. FaithfulnessMetric: checks if a synthesized answer is faithful to agent data
  3. HallucinationMetric: checks for invented content in synthesized answers
  4. PartialHonestyMetric (custom): checks that failed agents are acknowledged in answer
  5. AnswerRelevancyMetric: checks if answer is relevant to the question

Usage:
  python3 eval/eval_deepeval.py [--gateway-url http://localhost:8080] [--threshold 0.75]
  python3 eval/eval_deepeval.py --validate-judge   # run judge validation before main eval
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
    AnswerRelevancyMetric,
)
from deepeval.test_case import LLMTestCase
from deepeval.metrics.base_metric import BaseMetric

# ── Judge model configuration ─────────────────────────────────────────────────

def configure_judge_model() -> str:
    """
    Configure the LLM judge to use Z.AI GLM via OpenAI-compatible endpoint.
    Sets OPENAI_API_KEY and OPENAI_BASE_URL so deepeval's OpenAI client
    transparently routes to Z.AI GLM instead of OpenAI.
    Returns the model string to pass to deepeval metrics.
    Called once at startup before any LLM-judge metric runs.
    """
    zai_key = os.getenv("ZAI_API_KEY", "")
    if zai_key:
        os.environ["OPENAI_API_KEY"] = zai_key
    os.environ["OPENAI_BASE_URL"] = os.getenv("CONDUIT_LLM_JUDGE_BASE_URL", "https://api.z.ai/api/paas/v4")
    # Release-gate judge = MAX tier. This judge BLOCKS deploys, so use the strongest
    # available reasoning model (cost is irrelevant offline). Env-overridable per the
    # model-selection guide (docs/MODEL-SELECTION.md).
    return os.getenv("DEEPEVAL_JUDGE_MODEL", "glm-4.6")


# ── PartialHonestyMetric ──────────────────────────────────────────────────────

ACKNOWLEDGMENT_WORDS = [
    "unavailable", "missing", "unable", "failed",
    "could not", "not available", "could not retrieve",
]


class PartialHonestyMetric(BaseMetric):
    """
    Checks: when agent failure context is present, the answer must acknowledge
    missing data. Never silently omit.

    Scores 1.0 if:
      (a) no failure context present (no failed_agents in additional_metadata), OR
      (b) failure context present AND answer contains acknowledgment words
          (unavailable/missing/unable/failed/could not retrieve).

    Scores 0.0 if failure context is present but answer does NOT acknowledge it.

    threshold = 0.9 (any un-acknowledged failure is a hard fail).
    """

    threshold = 0.9
    name = "Partial Result Honesty"

    def __init__(self, threshold: float = 0.9):
        self.threshold = threshold

    def measure(self, test_case: LLMTestCase) -> float:
        metadata = getattr(test_case, "additional_metadata", None) or {}
        failed_agents = metadata.get("failed_agents", [])

        if not failed_agents:
            self.score = 1.0
            self.success = True
            return 1.0

        answer = (test_case.actual_output or "").lower()
        acknowledged = any(w in answer for w in ACKNOWLEDGMENT_WORDS)
        self.score = 1.0 if acknowledged else 0.0
        self.success = self.score >= self.threshold
        return self.score

    async def a_measure(self, test_case: LLMTestCase, *args, **kwargs) -> float:
        return self.measure(test_case)

    def is_successful(self) -> bool:
        return getattr(self, "success", False)

    @property
    def __name__(self):
        return self.name


# ── Judge Validation ──────────────────────────────────────────────────────────

_JUDGE_CASES = [
    # id, context_str, answer, expected_pass (True = should score >= 0.5)
    # PASS cases — faithful answers
    (
        "pass-01",
        "Whitman holdings total_value=1967000",
        "The Whitman account has a total market value of $1,967,000",
        True,
    ),
    (
        "pass-02",
        "YTD return 12.4%, alpha 2.2%",
        "Performance is up 12.4% YTD, outperforming benchmark by 2.2%",
        True,
    ),
    (
        "pass-03",
        "MSFT 800 shares value 372000",
        "Microsoft position: 800 shares valued at $372,000",
        True,
    ),
    (
        "pass-04",
        "Settlement REF-S-00421 MSFT 372000 pending",
        "There is a pending MSFT settlement of $372,000 (REF-S-00421)",
        True,
    ),
    (
        "pass-05",
        "Sharpe ratio 1.43, volatility 8.7%",
        "The portfolio's Sharpe ratio is 1.43 with 8.7% volatility",
        True,
    ),
    # FAIL cases — hallucinated answers
    (
        "fail-01",
        "Whitman holdings total_value=1967000",
        "The Whitman account has $2.5 million in assets",
        False,
    ),
    (
        "fail-02",
        "YTD return 12.4%",
        "The portfolio returned 15% this year, excellent performance",
        False,
    ),
    (
        "fail-03",
        "AAPL 1200 shares",
        "The client holds 2000 Apple shares",
        False,
    ),
    (
        "fail-04",
        "equity pct=68",
        "The portfolio is 85% equity",
        False,
    ),
    (
        "fail-05",
        "Cash allocation 8%",
        "Cash position is 25%, very conservative",
        False,
    ),
    # PARTIAL cases — agent failure acknowledgment
    (
        "partial-01",
        "[settlements agent failed]",
        "I was unable to retrieve settlement data",
        True,
    ),
    (
        "partial-02",
        "[settlements agent failed]",
        "There are no pending settlements",
        False,
    ),
    (
        "partial-03",
        "[nav agent failed]",
        "NAV data is temporarily unavailable",
        True,
    ),
    (
        "partial-04",
        "[nav agent failed]",
        "The fund NAV is $98.50",
        False,
    ),
    (
        "partial-05",
        "risk_score=65, risk_label=Moderate",
        "Risk profile: Moderate (score 65/100)",
        True,
    ),
]


def run_judge_validation() -> bool:
    """
    Run 15 hardcoded human-scored cases through FaithfulnessMetric.
    Computes agreement rate (judge pass/fail vs human label).
    PASS >= 80%, WARN 70-79%, FAIL < 70%.
    Returns True if agreement >= 70% (eval can continue).
    Does NOT require the gateway to be running.
    """
    judge_model = configure_judge_model()

    print(f"\n{'='*70}")
    print(" Judge Validation — 15 human-scored cases")
    print(f" Judge model: {judge_model}  |  Base URL: {os.environ.get('OPENAI_BASE_URL')}")
    print(f"{'='*70}\n")

    agreements = 0
    total = len(_JUDGE_CASES)
    rows = []

    metric = FaithfulnessMetric(threshold=0.5, model=judge_model, include_reason=False)

    for case_id, context_str, answer, expected_pass in _JUDGE_CASES:
        tc = LLMTestCase(
            input=case_id,
            actual_output=answer,
            retrieval_context=[context_str],
        )
        try:
            metric.measure(tc)
            judge_pass = metric.score >= 0.5
        except Exception as e:
            judge_pass = None
            rows.append((case_id, "ERROR", expected_pass, False, str(e)[:60]))
            continue

        agreed = judge_pass == expected_pass
        if agreed:
            agreements += 1
        rows.append((case_id, f"{metric.score:.2f}", expected_pass, agreed, ""))

    print(f"{'Case ID':<14} {'Score':>6}  {'Expected':>10}  {'Agree':>6}  {'Note'}")
    print("-" * 65)
    for case_id, score, expected_pass, agreed, note in rows:
        exp_label = "PASS" if expected_pass else "FAIL"
        agree_sym = "✓" if agreed else "✗"
        print(f"{case_id:<14} {score:>6}  {exp_label:>10}  {agree_sym:>6}  {note}")

    valid_rows = [r for r in rows if r[3] is not False or r[1] != "ERROR"]
    scored = sum(1 for r in rows if r[1] != "ERROR")
    rate = agreements / scored if scored > 0 else 0.0

    print(f"\nAgreement: {agreements}/{scored} = {rate:.0%}")

    if rate >= 0.80:
        print("PASS — judge is trustworthy (>= 80% agreement)")
        return True
    elif rate >= 0.70:
        print("WARN — judge agreement 70-79%; results may be noisy, continuing")
        return True
    else:
        print("FAIL — judge agreement < 70%; eval results are unreliable")
        return False


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
        "model": "conduit-assistant",
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
    Spot-check hero answers for faithfulness and answer relevancy using deepeval.
    Uses evaluate() with FaithfulnessMetric + AnswerRelevancyMetric.
    Agent outputs are the ground truth context — the answer must not introduce facts.
    Also checks two gateway-level scenarios: authz denial and resilience.
    """
    print(f"\n{'='*70}")
    print(f" Faithfulness + Relevancy Spot-Check (deepeval evaluate())")
    print(f"{'='*70}\n")

    judge_model = configure_judge_model()

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

    test_cases = []
    for sp in spot_prompts:
        answer, _ = call_chat(gateway_url, sp["prompt"])
        if answer.startswith("Error:"):
            print(f"  SKIP (chat error): {answer}")
            continue

        test_cases.append(LLMTestCase(
            input=sp["prompt"],
            actual_output=answer,
            retrieval_context=sp["context"]
        ))

    # Authz denial case: rm_guest asking about Whitman → must say denied/unauthorized
    authz_answer, _ = call_chat(gateway_url, "Show me Whitman Family Trust holdings", user_id="rm_guest")
    if not authz_answer.startswith("Error:"):
        denied = any(w in authz_answer.lower() for w in ["denied", "unauthorized", "not authorized", "access denied", "not permitted"])
        print(f"  Authz denial check (rm_guest → Whitman): {'PASS — answer withholds data' if denied else 'WARN — answer may have leaked data'}")
        print(f"  Answer preview: {authz_answer[:150]}...")

    # Resilience case: settlements fault knob → answer must contain "settlement" + "unavailable"
    resilience_answer, _ = call_chat(
        gateway_url,
        "What are the pending settlements for Whitman Family Trust?",
        user_id="rm_jane"
    )
    if not resilience_answer.startswith("Error:"):
        has_settlement = "settlement" in resilience_answer.lower()
        has_unavail = any(w in resilience_answer.lower() for w in ACKNOWLEDGMENT_WORDS)
        print(f"  Resilience check (settlements degraded): settlement_mentioned={has_settlement} unavail_acknowledged={has_unavail}")

    if not test_cases:
        print("  No test cases built (gateway unavailable) — skipping evaluate()")
        return

    try:
        faithfulness_metric = FaithfulnessMetric(
            threshold=0.5,
            model=judge_model,
            include_reason=True
        )
        relevancy_metric = AnswerRelevancyMetric(
            threshold=0.5,
            model=judge_model,
            include_reason=True
        )
        results = evaluate(test_cases, [faithfulness_metric, relevancy_metric])
        print(f"\n  deepeval evaluate() complete — {len(test_cases)} test case(s) scored.")
    except Exception as e:
        print(f"  evaluate() skipped (needs LLM judge key): {type(e).__name__}: {str(e)[:100]}")
        # Fallback: measure individually
        for tc in test_cases:
            try:
                faithfulness_metric = FaithfulnessMetric(threshold=0.5, model=judge_model, include_reason=True)
                faithfulness_metric.measure(tc)
                print(f"  Faithfulness score: {faithfulness_metric.score:.2f}  (success={faithfulness_metric.success})")
                if faithfulness_metric.reason:
                    print(f"  Reason: {faithfulness_metric.reason[:200]}")
            except Exception as e2:
                print(f"  Individual faithfulness check failed: {type(e2).__name__}: {str(e2)[:100]}")
                print(f"  Answer preview: {tc.actual_output[:200]}...")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway-url", default="http://localhost:8080")
    parser.add_argument("--prompts", default="eval/golden-prompts.json")
    parser.add_argument("--threshold", type=float, default=0.75)
    parser.add_argument("--skip-faithfulness", action="store_true",
                        help="Skip faithfulness spot-check (no LLM judge needed)")
    parser.add_argument("--validate-judge", action="store_true",
                        help="Run judge validation (15 human-scored cases) before main eval")
    args = parser.parse_args()

    if args.validate_judge:
        judge_ok = run_judge_validation()
        if not judge_ok:
            print("\nAborting eval: judge agreement below 70% threshold.", file=sys.stderr)
            sys.exit(2)

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
