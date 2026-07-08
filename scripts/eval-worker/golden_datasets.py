"""
Golden datasets for Meridian AI Gateway evals.

Each entry has:
  input       — the banker's question
  context     — ground-truth facts the answer must be grounded in (from canned agent data)
  expected    — key phrases that should appear in a correct answer
  agents      — which agents should be routed to (for routing accuracy eval)
"""

GOLDEN_DATASETS = [
    # ── Wealth: Holdings ─────────────────────────────────────────────────────
    {
        "name": "holdings-rel-00042",
        "input": "What are the current holdings for REL-00042?",
        "context": (
            "REL-00042 (James Whitman) portfolio: total value $4,850,000. "
            "Positions: Apple Inc 15% $727,500; Microsoft Corp 12% $582,000; "
            "US Treasury 10Y 20% $970,000; Vanguard S&P500 ETF 18% $873,000; "
            "BlackRock Bond Fund 15% $727,500; Cash 20% $970,000."
        ),
        "expected": ["4,850,000", "Apple", "Microsoft", "Treasury", "Whitman"],
        "agents": ["meridian.wealth.holdings"],
        "dataset": "wealth-holdings",
    },
    {
        "name": "holdings-rel-00099",
        "input": "Show me the portfolio breakdown for REL-00099",
        "context": (
            "REL-00099 (Elena Vasquez) portfolio: total value $2,100,000. "
            "Positions: Amazon 10% $210,000; Tesla 8% $168,000; "
            "Goldman Sachs Bond 25% $525,000; Real Estate Fund 20% $420,000; "
            "Cash 37% $777,000."
        ),
        "expected": ["2,100,000", "Amazon", "Goldman", "Vasquez"],
        "agents": ["meridian.wealth.holdings"],
        "dataset": "wealth-holdings",
    },

    # ── Wealth: Performance ───────────────────────────────────────────────────
    {
        "name": "performance-rel-00042",
        "input": "How has REL-00042 performed this year?",
        "context": (
            "REL-00042 YTD return: +8.3%. Benchmark (S&P500): +10.2%. "
            "1-year return: +12.1%. 3-year annualised: +9.4%. "
            "Best performing position: Apple +22%. Worst: US Treasury -1.2%."
        ),
        "expected": ["8.3", "12.1", "Apple", "benchmark"],
        "agents": ["meridian.wealth.performance"],
        "dataset": "wealth-performance",
    },

    # ── Wealth: Goal Planning ────────────────────────────────────────────────
    {
        "name": "goals-rel-00042",
        "input": "What is the goal planning status for REL-00042?",
        "context": (
            "REL-00042 goals: Retirement 2035 — target $8,000,000, "
            "current trajectory $7,200,000 (90% on track). "
            "College fund 2028 — target $500,000, on track. "
            "Recommended increase: $2,000/month additional contribution."
        ),
        "expected": ["8,000,000", "retirement", "2035", "90%"],
        "agents": ["meridian.wealth.goal_planning"],
        "dataset": "wealth-goals",
    },

    # ── Servicing: Settlements ────────────────────────────────────────────────
    {
        "name": "settlements-rel-00042",
        "input": "What settlements are pending for REL-00042?",
        "context": (
            "REL-00042 pending settlements: "
            "SETT-001 Apple 100 shares T+2 2026-06-27 $18,500; "
            "SETT-002 US Treasury bond $50,000 T+1 2026-06-26; "
            "SETT-003 Vanguard ETF 50 units T+2 2026-06-27 $12,000."
        ),
        "expected": ["SETT-001", "Apple", "18,500", "T+2"],
        "agents": ["meridian.servicing.settlement_status"],
        "dataset": "servicing-settlements",
    },

    # ── Servicing: Corporate Actions ──────────────────────────────────────────
    {
        "name": "corp-actions-rel-00042",
        "input": "Any corporate actions pending for REL-00042?",
        "context": (
            "REL-00042 corporate actions: Apple Inc dividend $0.25/share "
            "record date 2026-06-30 payment 2026-07-15; "
            "Microsoft stock split 2:1 effective 2026-07-01; "
            "Vanguard ETF distribution $1.20/unit 2026-06-28."
        ),
        "expected": ["Apple", "dividend", "0.25", "Microsoft", "split"],
        "agents": ["meridian.servicing.corporate_actions"],
        "dataset": "servicing-corporate-actions",
    },

    # ── Multi-agent: Hero prompt ──────────────────────────────────────────────
    {
        "name": "hero-full-picture-rel-00042",
        "input": (
            "Give me the full picture for REL-00042 — holdings, performance, "
            "goals, settlements and any corporate actions"
        ),
        "context": (
            "REL-00042 (James Whitman): portfolio $4,850,000, YTD +8.3%, "
            "retirement target $8M on track 90%, "
            "3 pending settlements total ~$80,500, "
            "Apple dividend and Microsoft split pending."
        ),
        "expected": ["4,850,000", "8.3", "retirement", "settlement", "Apple"],
        "agents": [
            "meridian.wealth.holdings", "meridian.wealth.performance",
            "meridian.wealth.goal_planning", "meridian.servicing.settlement_status",
            "meridian.servicing.corporate_actions",
        ],
        "dataset": "multi-agent-hero",
    },

    # ── Multi-agent: Servicing sweep ─────────────────────────────────────────
    {
        "name": "servicing-sweep-rel-00099",
        "input": "Show custody, settlements and cash for REL-00099",
        "context": (
            "REL-00099: cash position $777,000; "
            "custody — Goldman Sachs bond held at DTC, "
            "Real Estate Fund held at BNY Mellon; "
            "no pending settlements this week."
        ),
        "expected": ["777,000", "custody", "Goldman", "DTC"],
        "agents": [
            "meridian.servicing.custody",
            "meridian.servicing.settlement_status",
            "meridian.servicing.cash_management",
        ],
        "dataset": "multi-agent-hero",
    },

    # ── Risk Profile ──────────────────────────────────────────────────────────
    {
        "name": "risk-rel-00042",
        "input": "What is the risk profile for REL-00042?",
        "context": (
            "REL-00042 risk profile: Moderate-Aggressive. "
            "Equity allocation 45%, Fixed income 35%, Cash 20%. "
            "Beta 0.92, Sharpe ratio 1.34, VaR 95% = -$145,500/day."
        ),
        "expected": ["Moderate", "0.92", "Sharpe", "145,500"],
        "agents": ["meridian.wealth.risk_profile"],
        "dataset": "wealth-risk",
    },
]
