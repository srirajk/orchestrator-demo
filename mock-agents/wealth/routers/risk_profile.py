from fastapi import APIRouter, HTTPException, Query
from data.canned import RISK_PROFILE

router = APIRouter(prefix="/risk-profile", tags=["risk_profile"])


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
    data = RISK_PROFILE.get(relationship_id)
    if data is None:
        raise HTTPException(
            status_code=404,
            detail=f"Relationship '{relationship_id}' not found.",
        )
    return data
