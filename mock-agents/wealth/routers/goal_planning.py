from fastapi import APIRouter, HTTPException, Query
from data.canned import GOAL_PLANNING

router = APIRouter(prefix="/goal-planning", tags=["goal_planning"])


@router.get(
    "",
    summary="Get goal planning status for a relationship",
    description=(
        "Returns goals, progress percentages, and overall on-track assessment. "
        "Supports fault knobs: ?_delay_ms=<n> and ?_fail=true."
    ),
)
def get_goal_status(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    data = GOAL_PLANNING.get(relationship_id)
    if data is None:
        raise HTTPException(
            status_code=404,
            detail=f"Relationship '{relationship_id}' not found.",
        )
    return data
