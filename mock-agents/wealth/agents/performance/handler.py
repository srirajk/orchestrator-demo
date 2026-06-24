from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import PERFORMANCE
from shared.telemetry import agent_span

router = APIRouter(prefix="/performance", tags=["performance"])
AGENT_ID = "acme.wealth.performance"


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
    with agent_span(AGENT_ID, relationship_id) as span:
        data = PERFORMANCE.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            raise HTTPException(
                status_code=404,
                detail=f"Relationship '{relationship_id}' not found.",
            )
        span.set_attribute("result.period", period)
        return {**data, "period": period}
