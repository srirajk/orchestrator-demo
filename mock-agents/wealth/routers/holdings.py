from fastapi import APIRouter, HTTPException, Query
from data.canned import HOLDINGS

router = APIRouter(prefix="/holdings", tags=["holdings"])


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
    data = HOLDINGS.get(relationship_id)
    if data is None:
        raise HTTPException(
            status_code=404,
            detail=f"Relationship '{relationship_id}' not found. Known IDs: {list(HOLDINGS)}",
        )
    return data
