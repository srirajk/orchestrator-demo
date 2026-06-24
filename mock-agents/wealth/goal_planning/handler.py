"""Goal Planning agent — GET /goal-planning"""
from fastapi import APIRouter, HTTPException, Query
from shared.canned_data import GOAL_PLANNING
from shared.telemetry import agent_span

router = APIRouter(prefix="/goal-planning", tags=["goal_planning"])
AGENT_ID = "acme.wealth.goal_planning"


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
    with agent_span(AGENT_ID, relationship_id) as span:
        data = GOAL_PLANNING.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            raise HTTPException(
                status_code=404,
                detail=f"Relationship '{relationship_id}' not found.",
            )
        goal_count = len(data.get("goals", []))
        span.set_attribute("result.goal_count", goal_count)
        span.set_attribute("result.overall_on_track", data.get("overall_on_track", False))
        return data
