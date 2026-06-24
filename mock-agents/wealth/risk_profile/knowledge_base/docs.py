"""
Meridian Risk Policy Knowledge Base — pre-embedded policy document chunks.

In production this would be a vector database (Pinecone, Weaviate, ChromaDB).
For the demo we store the chunks in-memory with pre-computed TF-IDF-like weights.
The retriever.py uses cosine similarity over keyword vectors.
"""

POLICY_DOCS = [
    {
        "id": "risk-pol-001",
        "title": "Meridian Risk Tolerance Framework v2.3 — Section 4: Concentration Limits",
        "content": (
            "Single-name equity concentration must not exceed 15% of total portfolio value. "
            "Sector concentration must not exceed 40%. "
            "A concentration breach triggers automatic review within 5 business days. "
            "The RM must document a rebalancing plan or obtain CIO waiver."
        ),
        "tags": ["concentration", "equity", "limit", "breach", "rebalancing"],
    },
    {
        "id": "risk-pol-002",
        "title": "Meridian Risk Tolerance Framework v2.3 — Section 6: Risk Score Bands",
        "content": (
            "Risk scores 1-3: Conservative. Max equity 30%, no alternatives. "
            "Risk scores 4-6: Moderate. Max equity 60%, alternatives up to 10%. "
            "Risk scores 7-9: Aggressive. Max equity 90%, alternatives up to 25%. "
            "Risk scores 10: Unconstrained (institutional only)."
        ),
        "tags": ["risk_score", "conservative", "moderate", "aggressive", "equity_limit"],
    },
    {
        "id": "risk-pol-003",
        "title": "Meridian Risk Tolerance Framework v2.3 — Section 8: Drawdown Limits",
        "content": (
            "Maximum drawdown tolerance is the client's stated threshold from the risk questionnaire. "
            "If realised drawdown breaches 80% of stated tolerance, the RM must notify the client within 24h. "
            "If it breaches 100%, the account enters review status and no new positions may be opened."
        ),
        "tags": ["drawdown", "tolerance", "notification", "review", "breach"],
    },
    {
        "id": "risk-pol-004",
        "title": "Meridian Compliance Note CN-2024-11 — Single-Stock ESG Exposure",
        "content": (
            "Holdings in companies rated ESG-D or below by our approved provider must not exceed 5% of portfolio. "
            "Current approved ESG rating provider: MSCI. "
            "Non-compliant positions existing before CN-2024-11 are grandfathered until Q4 2025."
        ),
        "tags": ["esg", "compliance", "single_stock", "msci", "limit"],
    },
    {
        "id": "risk-pol-005",
        "title": "Meridian Portfolio Review SOP — Section 3: Periodic Risk Review",
        "content": (
            "Risk profiles must be reviewed at least annually (semi-annually for AUM > $5M). "
            "A material life event (divorce, inheritance, retirement) triggers an immediate review. "
            "The review must be documented in the CRM with the client signature."
        ),
        "tags": ["review", "annual", "semi-annual", "life_event", "crm"],
    },
]
