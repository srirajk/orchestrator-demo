#!/usr/bin/env python3
"""Seed Langfuse datasets from eval/golden-prompts.json.
Run once (idempotent) to create the conduit-routing dataset in Langfuse.
After running: open Langfuse UI → Datasets to see all items.
Then run langfuse_run_experiment.py to run your first experiment.
"""

# ── LEARNING NOTE ──────────────────────────────────────────────────────────────
#
# WHAT IS A LANGFUSE DATASET?
#   A named collection of (input, expected_output) test cases stored in Langfuse.
#   Think of it as your eval fixtures, versioned and shareable via the UI.
#   Each item has an id (for idempotent upserts), an input, and an expected_output.
#
# WHAT IS A DATASET RUN (EXPERIMENT)?
#   One pass of your pipeline against all items in a dataset, tagged with a run name.
#   Running again with a different model/prompt creates a new run for side-by-side
#   comparison. See: langfuse_run_experiment.py
#
# HOW TO COMPARE IN THE UI:
#   Datasets → select "conduit-routing" → Runs tab → click any 2 runs → Compare.
#   Each item shows its score per run so you can spot regressions.
#
# SCORES:
#   Scores are attached to individual traces (one per dataset item per run) and
#   aggregated automatically per run. The UI shows averages and per-item breakdowns.
#
# IDEMPOTENCY:
#   Each item is created with a stable id (from the golden-prompts id field).
#   Re-running this script updates existing items rather than duplicating them.
#
# ──────────────────────────────────────────────────────────────────────────────

import json
import logging
import os
import pathlib

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# ── Config (all from env) ──────────────────────────────────────────────────────

LANGFUSE_PUBLIC_KEY: str = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
LANGFUSE_SECRET_KEY: str = os.environ.get("LANGFUSE_SECRET_KEY", "")
LANGFUSE_HOST: str = os.environ.get("LANGFUSE_HOST", "http://localhost:3030")

# ── Paths ─────────────────────────────────────────────────────────────────────

EVAL_DIR = pathlib.Path(__file__).parent
GOLDEN_PROMPTS_PATH = EVAL_DIR / "golden-prompts.json"

# ── Hardcoded synthesis test cases ────────────────────────────────────────────
#
# Five cases covering the key evaluation scenarios for the synthesis pipeline.
# Each has: input = {prompt, agent_outputs}, expected_output = {must_contain, must_not_contain}
#
# must_contain: numbers/facts that must appear verbatim in the synthesized answer
# must_not_contain: strings that reveal fabrication or unsafe behavior
#
SYNTHESIS_TEST_CASES = [
    {
        "id": "synth_hero_001",
        "description": "Normal hero prompt — Whitman full overview, all agents successful",
        "input": {
            "prompt": "Show me the complete picture for the Whitman Family Office account — holdings, performance, risk profile, settlement status, and cash position",
            "agent_outputs": [
                {
                    "agent_id": "meridian.wealth.holdings",
                    "data": {
                        "relationship_id": "REL-001",
                        "holdings": [
                            {"ticker": "MSFT", "shares": 800, "market_value": 372000},
                            {"ticker": "AAPL", "shares": 1200, "market_value": 228000},
                            {"ticker": "NVDA", "shares": 500, "market_value": 425000},
                        ],
                        "total_equity_value": 1025000,
                    },
                },
                {
                    "agent_id": "meridian.wealth.performance",
                    "data": {
                        "relationship_id": "REL-001",
                        "ytd_return_pct": 12.4,
                        "benchmark_ytd_pct": 9.8,
                        "total_value": 2150000,
                    },
                },
                {
                    "agent_id": "meridian.wealth.risk_profile",
                    "data": {
                        "relationship_id": "REL-001",
                        "risk_tolerance": "Moderate",
                        "concentration_flags": ["NVDA > 20% of equity"],
                    },
                },
                {
                    "agent_id": "meridian.servicing.settlement_status",
                    "data": {
                        "relationship_id": "REL-001",
                        "pending_settlements": 2,
                        "failed_settlements": 0,
                        "total_pending_value": 87500,
                    },
                },
                {
                    "agent_id": "meridian.servicing.cash_management",
                    "data": {
                        "relationship_id": "REL-001",
                        "cash_balance": 125000,
                        "projected_cash_7d": 212500,
                    },
                },
            ],
        },
        "expected_output": {
            "must_contain": ["12.4", "1025000", "125000", "87500", "MSFT", "NVDA"],
            "must_not_contain": ["fabricated", "I think", "I believe", "I recommend", "you should buy"],
        },
    },
    {
        "id": "synth_authz_001",
        "description": "Authorization denial — Okafor relationship, out of RM book",
        "input": {
            "prompt": "Show me the portfolio and performance for the Okafor relationship",
            "agent_outputs": [],
        },
        "expected_output": {
            "must_contain": ["not authorized", "denied"],
            "must_not_contain": ["Okafor portfolio", "holdings", "12.4", "fabricated"],
        },
    },
    {
        "id": "synth_resilience_001",
        "description": "Partial resilience — settlement agent failed, others succeeded",
        "input": {
            "prompt": "Show me the Whitman Family Office holdings, performance, and settlement status",
            "agent_outputs": [
                {
                    "agent_id": "meridian.wealth.holdings",
                    "data": {
                        "holdings": [
                            {"ticker": "MSFT", "shares": 800, "market_value": 372000},
                            {"ticker": "AAPL", "shares": 1200, "market_value": 228000},
                        ],
                        "total_equity_value": 600000,
                    },
                },
                {
                    "agent_id": "meridian.wealth.performance",
                    "data": {
                        "ytd_return_pct": 12.4,
                        "total_value": 2150000,
                    },
                },
                {
                    "agent_id": "meridian.servicing.settlement_status",
                    "data": None,
                    "error": "Agent timeout after 5000ms",
                    "status": "FAILED",
                },
            ],
        },
        "expected_output": {
            "must_contain": ["372000", "12.4", "unavailable", "missing"],
            "must_not_contain": ["fabricated", "I think", "settlement data shows"],
        },
    },
    {
        "id": "synth_chitchat_001",
        "description": "Chitchat / off-topic — no financial data should be invented",
        "input": {
            "prompt": "Hello, how are you today?",
            "agent_outputs": [],
        },
        "expected_output": {
            "must_contain": [],
            "must_not_contain": [
                "fabricated",
                "Whitman",
                "portfolio value",
                "I recommend",
                "you should invest",
                "12.4%",
            ],
        },
    },
    {
        "id": "synth_ambiguity_001",
        "description": "Entity ambiguity — no relationship ID resolved, clarification expected",
        "input": {
            "prompt": "Show me the account data for the Smith family",
            "agent_outputs": [],
        },
        "expected_output": {
            "must_contain": ["which", "clarif"],
            "must_not_contain": ["fabricated", "I invented", "relationship_id", "I assumed"],
        },
    },
]


# ── Helpers ───────────────────────────────────────────────────────────────────


def connect_langfuse():
    """Connect to Langfuse and return the client."""
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
    return lf


def ensure_dataset(lf, name: str, description: str):
    """Create dataset or continue silently if it already exists."""
    try:
        lf.create_dataset(name=name, description=description)
        logger.info("Created dataset: %s", name)
    except Exception as exc:
        # Langfuse raises an error when the dataset already exists.
        # Treat any exception here as "already exists" — the items will be
        # upserted below regardless.
        logger.info("Dataset '%s' already exists (or creation skipped): %s", name, exc)


# ── Main ──────────────────────────────────────────────────────────────────────


def seed_routing_dataset(lf) -> int:
    """Seed the conduit-routing dataset from golden-prompts.json. Returns item count."""
    with open(GOLDEN_PROMPTS_PATH, "r") as f:
        data = json.load(f)

    prompts = data.get("prompts", [])
    if not prompts:
        logger.error("No prompts found in %s", GOLDEN_PROMPTS_PATH)
        return 0

    ensure_dataset(
        lf,
        name="conduit-routing",
        description="Golden routing prompts for gateway eval — expected_agents reflects realistic multi-agent banking queries",
    )

    seeded = 0
    for item in prompts:
        item_id: str = item["id"]
        try:
            lf.create_dataset_item(
                dataset_name="conduit-routing",
                id=item_id,
                input={"prompt": item["prompt"]},
                expected_output={"expected_agents": item["expected_agents"]},
            )
            seeded += 1
            logger.debug("Upserted routing item: %s", item_id)
        except Exception as exc:
            logger.warning("Failed to upsert item %s: %s", item_id, exc)

    logger.info("Seeded %d items into dataset conduit-routing", seeded)
    return seeded


def seed_synthesis_dataset(lf) -> int:
    """Seed the conduit-synthesis dataset from hardcoded test cases. Returns item count."""
    ensure_dataset(
        lf,
        name="conduit-synthesis",
        description="Synthesis pipeline test cases — grounding, partial honesty, authz denial, chitchat, entity ambiguity",
    )

    seeded = 0
    for case in SYNTHESIS_TEST_CASES:
        item_id: str = case["id"]
        try:
            lf.create_dataset_item(
                dataset_name="conduit-synthesis",
                id=item_id,
                input={
                    "prompt": case["input"]["prompt"],
                    "agent_outputs": case["input"]["agent_outputs"],
                },
                expected_output={
                    "must_contain": case["expected_output"]["must_contain"],
                    "must_not_contain": case["expected_output"]["must_not_contain"],
                },
            )
            seeded += 1
            logger.debug("Upserted synthesis item: %s (%s)", item_id, case["description"])
        except Exception as exc:
            logger.warning("Failed to upsert item %s: %s", item_id, exc)

    logger.info("Seeded %d items into dataset conduit-synthesis", seeded)
    return seeded


def main() -> None:
    lf = connect_langfuse()

    routing_count = seed_routing_dataset(lf)
    synthesis_count = seed_synthesis_dataset(lf)

    # Flush the async Langfuse SDK queue before exit
    lf.flush()

    print(f"\nSeeded {routing_count} items into dataset conduit-routing")
    print(f"Seeded {synthesis_count} items into dataset conduit-synthesis")
    print(f"\nOpen Langfuse UI → Datasets: {LANGFUSE_HOST}/datasets")
    print("Then run: python3 eval/langfuse_run_experiment.py --run-name <name>")


if __name__ == "__main__":
    main()
