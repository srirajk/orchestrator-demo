from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import HOLDINGS
from shared.telemetry import agent_span

router = APIRouter(prefix="/holdings", tags=["holdings"])
AGENT_ID = "acme.wealth.holdings"


@router.get(
    "",
    summary="Get portfolio holdings for a relationship",
    description=(
        "Returns current positions and allocation breakdown for the given relationship. "
        "Supports fault knobs: ?_delay_ms=<n> (latency) and ?_fail=true (force 503)."
    ),
)
def get_holdings(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        data = HOLDINGS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            raise HTTPException(
                status_code=404,
                detail=f"Relationship '{relationship_id}' not found. Known IDs: {list(HOLDINGS)}",
            )
        span.set_attribute("result.record_count", len(data.get("positions", [])))
        return data
