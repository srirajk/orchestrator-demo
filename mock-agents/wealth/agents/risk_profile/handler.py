from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import RISK_PROFILE
from shared.telemetry import agent_span

router = APIRouter(prefix="/risk-profile", tags=["risk_profile"])
AGENT_ID = "acme.wealth.risk_profile"


@router.get(
    "",
    summary="Get risk profile and concentration flags for a relationship",
    description=(
        "Returns risk tolerance score, drawdown tolerance, and any concentration flags. "
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
        flag_count = len(data.get("concentration_flags", []))
        span.set_attribute("result.concentration_flags", flag_count)
        return data
