#!/usr/bin/env python3
"""Run a Langfuse experiment against the meridian-routing dataset.
Usage:
  python3 eval/langfuse_run_experiment.py --run-name "glm-4.6-baseline"
  python3 eval/langfuse_run_experiment.py --run-name "glm-4.7-flash-test" --model glm-4.7-flash
  python3 eval/langfuse_run_experiment.py --run-name "dry-run-check" --dry-run

After running: open Langfuse UI → Datasets → meridian-routing → Runs
You will see your experiment with per-item routing accuracy scores.
Run twice with different settings to compare experiments side by side.
"""

# ── LEARNING NOTE ──────────────────────────────────────────────────────────────
#
# WHAT IS A LANGFUSE DATASET?
#   A named collection of (input, expected_output) test cases, seeded via
#   langfuse_seed_datasets.py. Think of it as your eval fixture store —
#   versioned and browsable in the Langfuse UI under "Datasets".
#
# WHAT IS AN EXPERIMENT RUN?
#   One pass of your pipeline against every item in a dataset, tagged with a
#   run_name you choose. Each run is independent. Running again with a different
#   model or prompt creates a new run — they do not overwrite each other.
#
# HOW TO COMPARE RUNS IN THE UI:
#   Datasets → select "meridian-routing" → click the "Runs" tab.
#   Check any two runs → a side-by-side comparison view appears.
#   Each dataset item shows its score in each run so regressions are obvious.
#
# HOW SCORES APPEAR:
#   Each item gets a "routing_f1" score (0.0–1.0).
#   Langfuse aggregates these automatically to show the mean per run.
#   Click an item to drill into the trace and see exactly what the gateway returned.
#
# HOW DATASET ITEM LINKING WORKS (SDK v2+):
#   dataset = lf.get_dataset(name)          # returns dataset with .items
#   for item in dataset.items:              # each item is a DatasetItemClient
#       trace = lf.trace(...)               # StatefulTraceClient with .id
#       item.link(trace, run_name)          # links this trace to the run
#   This is the canonical SDK v2+ pattern — do NOT use direct API calls.
#
# ──────────────────────────────────────────────────────────────────────────────

import argparse
import json
import logging
import os
import re

import httpx

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# ── Config (all from env) ──────────────────────────────────────────────────────

LANGFUSE_PUBLIC_KEY: str = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
LANGFUSE_SECRET_KEY: str = os.environ.get("LANGFUSE_SECRET_KEY", "")
LANGFUSE_HOST: str = os.environ.get("LANGFUSE_HOST", "http://localhost:3030")

# ── Known agent IDs (for parsing gateway response text) ───────────────────────
#
# The gateway synthesizes a natural-language answer. We detect which agents were
# involved by searching for their canonical IDs in the response content.
# This is a heuristic — a richer approach would query /trace/stream or a
# dedicated /v1/routing-decision endpoint if the gateway exposes one.

ALL_AGENT_IDS: list[str] = [
    "acme.wealth.holdings",
    "acme.wealth.performance",
    "acme.wealth.risk_profile",
    "acme.wealth.goal_planning",
    "acme.servicing.settlement_status",
    "acme.servicing.cash_management",
    "acme.servicing.corporate_actions",
    "acme.servicing.custody_positions",
    "acme.servicing.nav",
]

# ── Dry-run mock: simulates a perfect-routing response ────────────────────────

DRY_RUN_MOCK_CONTENT = (
    "DRY RUN — gateway call skipped. "
    "Mock agents resolved: acme.wealth.holdings acme.wealth.performance "
    "acme.wealth.risk_profile acme.servicing.settlement_status "
    "acme.servicing.cash_management acme.wealth.goal_planning"
)


# ── F1 computation ────────────────────────────────────────────────────────────


def compute_f1(resolved: set[str], expected: set[str]) -> tuple[float, float, float]:
    """
    Compute routing F1 between the set of resolved agents and expected agents.

    precision = |resolved ∩ expected| / |resolved|  (if |resolved| > 0 else 0)
    recall    = |resolved ∩ expected| / |expected|  (if |expected| > 0 else 0)
    f1        = 2*p*r / (p+r)                       (if p+r > 0 else 0)

    Returns (precision, recall, f1) all as floats in [0.0, 1.0].
    """
    if not expected:
        # Nothing expected — trivially perfect (or undefined; treat as 1.0).
        return 1.0, 1.0, 1.0

    intersection = resolved & expected
    precision = len(intersection) / len(resolved) if resolved else 0.0
    recall = len(intersection) / len(expected) if expected else 0.0
    f1 = (2 * precision * recall) / (precision + recall) if (precision + recall) > 0 else 0.0
    return precision, recall, f1


# ── Agent ID extraction from gateway response ─────────────────────────────────


def extract_resolved_agents(content: str) -> set[str]:
    """
    Extract agent IDs from the gateway's synthesized response text.

    Agent IDs follow the pattern acme.<domain>.<capability> and may appear in
    the response when the gateway embeds routing metadata or mentions sources.
    """
    found = set()
    for agent_id in ALL_AGENT_IDS:
        # Match the ID even when followed by punctuation or whitespace.
        if re.search(re.escape(agent_id), content):
            found.add(agent_id)
    return found


# ── Gateway call ──────────────────────────────────────────────────────────────


def call_gateway(gateway_url: str, prompt: str, timeout: float = 30.0) -> str:
    """
    POST to {gateway_url}/v1/chat/completions (non-streaming) and return content.

    Returns the synthesized answer string, or an empty string on failure.
    The gateway returns an OpenAI-compatible JSON response when stream=false.
    """
    payload = {
        "model": "meridian",
        "stream": False,
        "messages": [{"role": "user", "content": prompt}],
    }

    try:
        with httpx.Client(timeout=timeout) as client:
            resp = client.post(
                f"{gateway_url}/v1/chat/completions",
                json=payload,
                headers={"Content-Type": "application/json"},
            )
            resp.raise_for_status()
            body = resp.json()
            content: str = body["choices"][0]["message"]["content"]
            return content
    except httpx.TimeoutException:
        logger.warning("Gateway timed out after %.0fs for prompt: %.60s...", timeout, prompt)
        return ""
    except httpx.HTTPStatusError as exc:
        logger.warning("Gateway HTTP error %s: %s", exc.response.status_code, exc)
        return ""
    except Exception as exc:
        logger.warning("Gateway call failed: %s", exc)
        return ""


# ── Main experiment loop ──────────────────────────────────────────────────────


def run_experiment(
    run_name: str,
    dataset_name: str,
    gateway_url: str,
    dry_run: bool,
) -> None:
    """
    Fetch all items from dataset_name and run the gateway against each one.
    Each trace is linked to the named dataset run for comparison in the UI.
    """
    if not LANGFUSE_PUBLIC_KEY or not LANGFUSE_SECRET_KEY:
        logger.error(
            "LANGFUSE_PUBLIC_KEY and LANGFUSE_SECRET_KEY must be set in the environment"
        )
        raise SystemExit(1)

    try:
        from langfuse import Langfuse
    except ImportError:
        logger.error("langfuse package not installed. Run: pip install 'langfuse>=2.0.0'")
        raise SystemExit(1)

    lf = Langfuse(
        public_key=LANGFUSE_PUBLIC_KEY,
        secret_key=LANGFUSE_SECRET_KEY,
        host=LANGFUSE_HOST,
    )
    logger.info("Connected to Langfuse at %s", LANGFUSE_HOST)

    # ── Fetch dataset ──────────────────────────────────────────────────────────
    try:
        dataset = lf.get_dataset(dataset_name)
    except Exception as exc:
        logger.error(
            "Could not fetch dataset '%s': %s\n"
            "Run langfuse_seed_datasets.py first to create it.",
            dataset_name,
            exc,
        )
        raise SystemExit(1)

    items = dataset.items
    if not items:
        logger.warning("Dataset '%s' has no items — nothing to run.", dataset_name)
        lf.flush()
        return

    logger.info(
        "Running experiment '%s' against %d items from dataset '%s'%s",
        run_name,
        len(items),
        dataset_name,
        " (DRY RUN)" if dry_run else "",
    )

    # ── Per-item loop ──────────────────────────────────────────────────────────
    results: list[dict] = []

    for item in items:
        item_id: str = getattr(item, "id", "unknown")
        input_data: dict = item.input if isinstance(item.input, dict) else {}
        expected_output: dict = item.expected_output if isinstance(item.expected_output, dict) else {}

        prompt: str = input_data.get("prompt", "")
        expected_agents: list[str] = expected_output.get("expected_agents", [])

        logger.info("  [%s] prompt: %.70s...", item_id, prompt)

        # ── a. Create Langfuse trace ───────────────────────────────────────────
        trace = lf.trace(
            name="experiment-item",
            input=input_data,
            metadata={
                "dataset": dataset_name,
                "run_name": run_name,
                "item_id": item_id,
                "dry_run": dry_run,
            },
        )

        # ── b. Call gateway (or use mock) ─────────────────────────────────────
        if dry_run:
            content = DRY_RUN_MOCK_CONTENT
        else:
            content = call_gateway(gateway_url, prompt)

        # ── c. Extract resolved agents from response text ─────────────────────
        resolved_agents: set[str] = extract_resolved_agents(content)

        # ── d. Compute routing F1 ─────────────────────────────────────────────
        expected_set = set(expected_agents)
        precision, recall, f1 = compute_f1(resolved_agents, expected_set)

        # ── e. Update trace output + score ────────────────────────────────────
        trace.update(
            output={
                "content": content[:500],  # truncate for trace display
                "resolved_agents": sorted(resolved_agents),
            }
        )
        lf.score(
            trace_id=trace.id,
            name="routing_f1",
            value=f1,
            comment=(
                f"precision={precision:.2f} recall={recall:.2f} "
                f"resolved={sorted(resolved_agents)} "
                f"expected={sorted(expected_set)}"
            ),
        )

        # ── f. Link trace to this dataset run (SDK v2+ pattern) ───────────────
        # item.link(trace_or_observation, run_name) is the canonical SDK v2+ API.
        # This attaches the trace to the run so the UI can aggregate and compare.
        item.link(trace, run_name)

        # ── Print per-item result ──────────────────────────────────────────────
        hit = resolved_agents & expected_set
        miss = expected_set - resolved_agents
        extra = resolved_agents - expected_set
        print(
            f"  {item_id:<22} F1={f1:.2f}  P={precision:.2f}  R={recall:.2f}"
            f"  hit={len(hit)} miss={len(miss)} extra={len(extra)}"
        )
        if miss:
            logger.debug("    missing agents: %s", sorted(miss))
        if extra:
            logger.debug("    extra agents:   %s", sorted(extra))

        results.append(
            {
                "item_id": item_id,
                "f1": f1,
                "precision": precision,
                "recall": recall,
                "resolved": sorted(resolved_agents),
                "expected": sorted(expected_set),
            }
        )

    # ── Summary ────────────────────────────────────────────────────────────────
    n = len(results)
    avg_f1 = sum(r["f1"] for r in results) / n if n else 0.0
    avg_p = sum(r["precision"] for r in results) / n if n else 0.0
    avg_r = sum(r["recall"] for r in results) / n if n else 0.0

    col_w = [24, 8, 8, 8]
    sep = "=" * sum(col_w)
    print("\n" + sep)
    print(f"{'ITEM ID':<{col_w[0]}}{'F1':>{col_w[1]}}{'PREC':>{col_w[2]}}{'REC':>{col_w[3]}}")
    print("-" * sum(col_w))
    for r in results:
        print(
            f"{r['item_id']:<{col_w[0]}}"
            f"{r['f1']:>{col_w[1]}.2f}"
            f"{r['precision']:>{col_w[2]}.2f}"
            f"{r['recall']:>{col_w[3]}.2f}"
        )
    print("-" * sum(col_w))
    print(
        f"{'AVERAGE':<{col_w[0]}}"
        f"{avg_f1:>{col_w[1]}.2f}"
        f"{avg_p:>{col_w[2]}.2f}"
        f"{avg_r:>{col_w[3]}.2f}"
    )
    print(sep)
    print(
        f"\nExperiment '{run_name}' — {n} item(s) | avg_F1={avg_f1:.3f} | "
        f"dataset={dataset_name}{' | DRY RUN' if dry_run else ''}"
    )
    print(f"Compare runs in UI: {LANGFUSE_HOST}/datasets/{dataset_name}/runs")

    # ── Flush async queue ──────────────────────────────────────────────────────
    lf.flush()


# ── CLI ───────────────────────────────────────────────────────────────────────


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a Langfuse experiment against the meridian-routing dataset.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  python3 eval/langfuse_run_experiment.py --run-name glm-4.6-baseline\n"
            "  python3 eval/langfuse_run_experiment.py --run-name glm-4.7-flash --dry-run\n"
            "  python3 eval/langfuse_run_experiment.py --run-name staging "
            "--gateway-url http://staging:8080\n"
        ),
    )
    parser.add_argument(
        "--run-name",
        required=True,
        help="Name for this experiment run (e.g. 'glm-4.6-baseline', 'after-prompt-fix')",
    )
    parser.add_argument(
        "--dataset",
        default="meridian-routing",
        help="Langfuse dataset name to run against (default: meridian-routing)",
    )
    parser.add_argument(
        "--gateway-url",
        default="http://localhost:8080",
        help="Gateway base URL (default: http://localhost:8080)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Skip gateway calls; use a mock response to verify the eval pipeline itself",
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    run_experiment(
        run_name=args.run_name,
        dataset_name=args.dataset,
        gateway_url=args.gateway_url,
        dry_run=args.dry_run,
    )
