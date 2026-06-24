from fastapi import APIRouter, HTTPException, Query
from data.canned import PERFORMANCE

router = APIRouter(prefix="/performance", tags=["performance"])


@router.get(
    "",
    summary="Get performance metrics for a relationship",
    description=(
        "Returns YTD return, P&L, alpha, and risk-adjusted figures. "
        "Supports fault knobs: ?_delay_ms=<n> and ?_fail=true."
    ),
)
def get_performance(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
    period: str = Query("YTD", description="Reporting period: YTD | 1Y | 3Y | Inception"),
):
    data = PERFORMANCE.get(relationship_id)
    if data is None:
        raise HTTPException(
            status_code=404,
            detail=f"Relationship '{relationship_id}' not found.",
        )
    return {**data, "period": period}
