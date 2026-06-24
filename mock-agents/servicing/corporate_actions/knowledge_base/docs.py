"""ISDA / Meridian Corporate Action Processing Rules — KB chunks."""

CORP_ACTION_DOCS = [
    {
        "id": "ca-proc-001",
        "title": "Meridian Corp Action SOP v1.4 — Section 2: Mandatory Events",
        "content": (
            "Mandatory corporate actions (stock splits, reverse splits, mergers) require no client election. "
            "The custodian processes automatically. The RM must notify the client within 2 business days of the record date. "
            "For cash dividends, the default is reinvestment unless the client has opted for cash distribution."
        ),
        "tags": ["mandatory", "split", "merger", "dividend", "reinvestment", "custodian"],
    },
    {
        "id": "ca-proc-002",
        "title": "Meridian Corp Action SOP v1.4 — Section 3: Voluntary Events",
        "content": (
            "Voluntary events (rights issues, tender offers, exchange offers) require a client response by the election deadline. "
            "Default action if no election received: lapse (no action taken). "
            "RM must chase client for elections at least 5 business days before deadline."
        ),
        "tags": ["voluntary", "rights", "tender", "election", "deadline", "lapse"],
    },
    {
        "id": "ca-proc-003",
        "title": "ISDA Corporate Action Rule 2023-07: Stock Split Adjustment",
        "content": (
            "On a stock split effective date, positions are adjusted using the split ratio. "
            "Example: 20:1 split — each existing share becomes 20 shares, price divided by 20. "
            "Open orders are cancelled and must be re-entered at the new price post-split."
        ),
        "tags": ["split", "ratio", "adjustment", "price", "orders", "effective_date"],
    },
    {
        "id": "ca-proc-004",
        "title": "Meridian Tax Guidance TG-2024-03: Dividend Withholding",
        "content": (
            "US-source dividends paid to non-US clients are subject to 30% withholding unless a tax treaty applies. "
            "Reduced treaty rates (15% for most DTA countries) are applied when W-8BEN/W-8BEN-E is on file. "
            "Withholding is processed by the custodian at the dividend payment date."
        ),
        "tags": ["dividend", "withholding", "tax", "treaty", "w8ben", "custodian"],
    },
]
