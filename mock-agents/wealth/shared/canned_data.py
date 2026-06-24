"""
Canned responses for all demo relationships and all Wealth endpoints.
Numbers are internally consistent (e.g. MSFT value matches settlement amount)
so the Phase 4 grounding check passes.
"""

HOLDINGS: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "relationship_name": "Whitman Family Office",
        "positions": [
            {"ticker": "AAPL", "isin": "US0378331005", "qty": 1200, "value": 318000},
            {"ticker": "MSFT", "isin": "US5949181045", "qty": 800,  "value": 372000},
            {"ticker": "GOOGL","isin": "US02079K3059", "qty": 150,  "value": 289500},
            {"ticker": "JPM",  "isin": "US46625H1005", "qty": 2500, "value": 487500},
            {"ticker": "T-BILL-2026", "isin": "US912796YS72", "qty": 1, "value": 500000},
        ],
        "allocation_by_class": [
            {"asset_class": "Equity",       "pct": 68},
            {"asset_class": "Fixed Income", "pct": 24},
            {"asset_class": "Cash",         "pct": 8},
        ],
        "total_value": 1967000,
        "currency": "USD",
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "relationship_name": "Calderon Trust",
        "positions": [
            {"ticker": "BRK.B", "isin": "US0846707026", "qty": 600, "value": 264000},
            {"ticker": "V",     "isin": "US92826C8394", "qty": 1000,"value": 275000},
        ],
        "allocation_by_class": [
            {"asset_class": "Equity",       "pct": 75},
            {"asset_class": "Fixed Income", "pct": 20},
            {"asset_class": "Cash",         "pct": 5},
        ],
        "total_value": 539000,
        "currency": "USD",
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "relationship_name": "Okafor Family Account",
        "positions": [
            {"ticker": "AMZN", "isin": "US0231351067", "qty": 400, "value": 740000},
        ],
        "allocation_by_class": [
            {"asset_class": "Equity", "pct": 90},
            {"asset_class": "Cash",   "pct": 10},
        ],
        "total_value": 740000,
        "currency": "USD",
        "as_of_date": "2026-06-22",
    },
}

PERFORMANCE: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "period": "YTD",
        "total_return_pct": 12.4,
        "pnl": 243908,
        "benchmark_return_pct": 10.2,
        "alpha": 2.2,
        "volatility_pct": 8.7,
        "sharpe_ratio": 1.43,
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "period": "YTD",
        "total_return_pct": 9.1,
        "pnl": 49049,
        "benchmark_return_pct": 10.2,
        "alpha": -1.1,
        "volatility_pct": 6.2,
        "sharpe_ratio": 1.47,
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "period": "YTD",
        "total_return_pct": 18.9,
        "pnl": 139860,
        "benchmark_return_pct": 10.2,
        "alpha": 8.7,
        "volatility_pct": 22.1,
        "sharpe_ratio": 0.85,
        "as_of_date": "2026-06-22",
    },
}

GOAL_PLANNING: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "goals": [
            {
                "goal_id": "G-001",
                "name": "Retirement Corpus",
                "target_amount": 5000000,
                "current_amount": 1967000,
                "target_date": "2035-12-31",
                "on_track": True,
                "progress_pct": 39.3,
            },
            {
                "goal_id": "G-002",
                "name": "Education Fund",
                "target_amount": 250000,
                "current_amount": 187000,
                "target_date": "2028-09-01",
                "on_track": False,
                "progress_pct": 74.8,
                "shortfall": 63000,
            },
        ],
        "overall_on_track": False,
        "summary": "Retirement goal is on track; education fund is underfunded by 63,000 USD.",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "goals": [
            {
                "goal_id": "G-010",
                "name": "Estate Preservation",
                "target_amount": 600000,
                "current_amount": 539000,
                "target_date": "2030-12-31",
                "on_track": True,
                "progress_pct": 89.8,
            },
        ],
        "overall_on_track": True,
        "summary": "Estate preservation goal is on track.",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "goals": [
            {
                "goal_id": "G-020",
                "name": "Growth Target",
                "target_amount": 1000000,
                "current_amount": 740000,
                "target_date": "2028-06-30",
                "on_track": True,
                "progress_pct": 74.0,
            },
        ],
        "overall_on_track": True,
        "summary": "Growth target is on track.",
    },
}

RISK_PROFILE: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "risk_tolerance": "Moderate",
        "risk_score": 6,
        "max_drawdown_tolerance_pct": 15,
        "concentration_flags": [
            {
                "security": "AAPL",
                "current_pct": 16.2,
                "threshold_pct": 15.0,
                "flagged": True,
                "note": "AAPL exceeds 15% single-name concentration threshold by 1.2 ppt",
            }
        ],
        "review_due_date": "2026-09-30",
        "last_reviewed": "2026-03-15",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "risk_tolerance": "Conservative",
        "risk_score": 3,
        "max_drawdown_tolerance_pct": 8,
        "concentration_flags": [],
        "review_due_date": "2026-11-30",
        "last_reviewed": "2025-11-30",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "risk_tolerance": "Aggressive",
        "risk_score": 9,
        "max_drawdown_tolerance_pct": 35,
        "concentration_flags": [
            {
                "security": "AMZN",
                "current_pct": 90.0,
                "threshold_pct": 30.0,
                "flagged": True,
                "note": "Single-stock concentration well above threshold — client has acknowledged",
            }
        ],
        "review_due_date": "2026-07-15",
        "last_reviewed": "2026-06-01",
    },
}
