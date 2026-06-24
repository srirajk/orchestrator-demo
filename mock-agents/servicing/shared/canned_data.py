"""
Canned responses for Asset Servicing MCP tools.
Numbers cross-reference Wealth data: MSFT settlement 372K matches Wealth holdings value.
"""

CUSTODY_POSITIONS: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "holdings_by_custodian": [
            {
                "custodian": "BNY Mellon",
                "account": "ACC-78823",
                "holdings": [
                    {"isin": "US0378331005", "security": "AAPL",  "qty": 1200, "value": 318000},
                    {"isin": "US5949181045", "security": "MSFT",  "qty": 800,  "value": 372000},
                ],
            },
            {
                "custodian": "State Street",
                "account": "ACC-34421",
                "holdings": [
                    {"isin": "US02079K3059", "security": "GOOGL", "qty": 150, "value": 289500},
                    {"isin": "US46625H1005", "security": "JPM",   "qty": 2500,"value": 487500},
                ],
            },
        ],
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "holdings_by_custodian": [
            {
                "custodian": "JPMorgan",
                "account": "ACC-55610",
                "holdings": [
                    {"isin": "US0846707026", "security": "BRK.B", "qty": 600, "value": 264000},
                    {"isin": "US92826C8394", "security": "V",     "qty": 1000,"value": 275000},
                ],
            },
        ],
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "holdings_by_custodian": [
            {
                "custodian": "Goldman Sachs",
                "account": "ACC-99012",
                "holdings": [
                    {"isin": "US0231351067", "security": "AMZN", "qty": 400, "value": 740000},
                ],
            },
        ],
        "as_of_date": "2026-06-22",
    },
}

SETTLEMENTS: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "pending": [
            {
                "trade_id": "T-9912",
                "security": "MSFT",
                "isin": "US5949181045",
                "settle_date": "2026-06-25",
                "amount": 372000,
                "side": "buy",
                "custodian": "BNY Mellon",
                "status": "pending",
            }
        ],
        "failed": [],
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "pending": [],
        "failed": [],
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "pending": [],
        "failed": [
            {
                "trade_id": "T-9844",
                "security": "AMZN",
                "settle_date": "2026-06-20",
                "amount": 185000,
                "side": "sell",
                "reason": "insufficient securities",
            }
        ],
        "as_of_date": "2026-06-22",
    },
}

CORPORATE_ACTIONS: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "upcoming_actions": [
            {
                "action_id": "CA-2245",
                "security": "AAPL",
                "isin": "US0378331005",
                "type": "Dividend",
                "amount_per_share": 0.25,
                "total_amount": 300.0,
                "record_date": "2026-07-05",
                "payment_date": "2026-07-15",
                "election_required": False,
            },
            {
                "action_id": "CA-2301",
                "security": "GOOGL",
                "isin": "US02079K3059",
                "type": "Stock Split",
                "ratio": "20:1",
                "effective_date": "2026-07-10",
                "election_required": False,
                "notes": "20-for-1 split; no action required",
            },
        ],
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "upcoming_actions": [],
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "upcoming_actions": [
            {
                "action_id": "CA-2310",
                "security": "AMZN",
                "isin": "US0231351067",
                "type": "Rights Issue",
                "ratio": "1:10",
                "price": 140.00,
                "election_deadline": "2026-07-01",
                "election_required": True,
            }
        ],
        "as_of_date": "2026-06-22",
    },
}

# Nav is keyed by fund_id, not relationship_id
NAV: dict = {
    "FND-7781": {
        "fund_id": "FND-7781",
        "fund_name": "Meridian Global Equity Fund",
        "nav": 128.45,
        "as_of_date": "2026-06-22",
        "currency": "USD",
        "aum": 4_500_000_000,
        "shares_outstanding": 35_030_000,
        "nav_change_pct": 0.82,
    },
    "FND-4423": {
        "fund_id": "FND-4423",
        "fund_name": "Meridian Short Duration Bond Fund",
        "nav": 102.18,
        "as_of_date": "2026-06-22",
        "currency": "USD",
        "aum": 1_200_000_000,
        "shares_outstanding": 11_744_000,
        "nav_change_pct": 0.05,
    },
}

CASH: dict = {
    "REL-00042": {
        "relationship_id": "REL-00042",
        "balances": [
            {"currency": "USD", "settled": 157000, "unsettled": 372000, "total": 529000},
            {"currency": "GBP", "settled": 45000,  "unsettled": 0,      "total": 45000},
        ],
        "projected_cash_usd": 529000,
        "note": "USD unsettled 372K = pending MSFT buy (T-9912, settles 2026-06-25)",
        "as_of_date": "2026-06-22",
    },
    "REL-00099": {
        "relationship_id": "REL-00099",
        "balances": [
            {"currency": "USD", "settled": 26950, "unsettled": 0, "total": 26950},
        ],
        "projected_cash_usd": 26950,
        "as_of_date": "2026-06-22",
    },
    "REL-00188": {
        "relationship_id": "REL-00188",
        "balances": [
            {"currency": "USD", "settled": 74000, "unsettled": 0, "total": 74000},
        ],
        "projected_cash_usd": 74000,
        "note": "Failed AMZN sell (T-9844) released 185K back to account",
        "as_of_date": "2026-06-22",
    },
}
