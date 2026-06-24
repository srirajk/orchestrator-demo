"""
Risk Profile agent — GET /risk-profile

Returns risk tolerance, drawdown limits, and concentration flags.
RAG: retrieves relevant policy snippets from the Meridian Risk Policy KB
and includes them in the response as `policy_context`.
"""
from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import RISK_PROFILE
from shared.telemetry import agent_span
from .knowledge_base.retriever import retrieve

router = APIRouter(prefix="/risk-profile", tags=["risk_profile"])
AGENT_ID = "acme.wealth.risk_profile"


@router.get(
    "",
    summary="Get risk profile and concentration flags for a relationship",
    description=(
        "Returns risk tolerance score, drawdown tolerance, and any concentration flags. "
        "RAG-augmented: also returns relevant Meridian Risk Policy sections. "
        "Supports fault knobs: ?_delay_ms=<n> and ?_fail=true."
    ),
)
def get_risk_profile(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        data = RISK_PROFILE.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            raise HTTPException(
                status_code=404,
                detail=f"Relationship '{relationship_id}' not found.",
            )

        # RAG: retrieve policy context relevant to this risk profile
        risk_tolerance = data.get("risk_tolerance", "")
        concentration_flags = data.get("concentration_flags", [])
        query = f"risk tolerance {risk_tolerance} concentration limit"
        if concentration_flags:
            tickers = " ".join(f.get("security", "") for f in concentration_flags)
            query += f" breach {tickers}"

        policy_snippets = retrieve(query, top_k=2)
        data_with_policy = dict(data)
        data_with_policy["policy_context"] = policy_snippets

        flag_count = len(concentration_flags)
        span.set_attribute("result.risk_score", data.get("risk_score", 0))
        span.set_attribute("result.concentration_flags", flag_count)
        span.set_attribute("rag.retrieved_docs", len(policy_snippets))

        return data_with_policy
