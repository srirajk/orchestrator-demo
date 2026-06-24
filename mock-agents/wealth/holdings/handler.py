"""
Holdings agent — GET /holdings

Returns current portfolio positions for a relationship.
Wraps the response in an OTel span with position count attributes.
"""
from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import HOLDINGS
from shared.telemetry import agent_span

router = APIRouter(prefix="/holdings", tags=["holdings"])
AGENT_ID = "acme.wealth.holdings"


@router.get(
    "",
    summary="Get portfolio holdings for a relationship",
    description=(
        "Returns current positions and allocation breakdown. "
        "Fault knobs: ?_delay_ms=N  ?_fail=true"
    ),
)
def get_holdings(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        data = HOLDINGS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "relationship_not_found")
            raise HTTPException(
                status_code=404,
                detail=f"Relationship '{relationship_id}' not found. Known: {list(HOLDINGS)}",
            )
        span.set_attribute("result.position_count", len(data.get("positions", [])))
        span.set_attribute("result.total_value_usd", data.get("total_value", 0))
        return data
