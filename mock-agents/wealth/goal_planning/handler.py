"""Goal Planning agent — GET /goal-planning"""
import os
import logging
from fastapi import APIRouter, Query
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import GOAL_PLANNING
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail
from shared.error_schema import error_response

# Per-agent LLM overrides — fall back to the service-level WEALTH_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("GOAL_PLANNING_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("GOAL_PLANNING_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("GOAL_PLANNING_LLM_MODEL") or None

router = APIRouter(prefix="/goal-planning", tags=["goal_planning"])
AGENT_ID = "acme.wealth.goal_planning"
log = logging.getLogger(__name__)


@function_tool
def get_goal_planning_data(relationship_id: str) -> dict:
    """Retrieve financial goal status and funding progress for a client relationship."""
    data = GOAL_PLANNING.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found in goal planning system"}
    return data


@router.get(
    "",
    summary="Get goal planning status for a relationship",
    description="Returns goals, progress percentages, and overall on-track assessment. Fault knobs: ?_delay_ms=N ?_fail=true",
)
async def get_goal_status(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        raw = GOAL_PLANNING.get(relationship_id)
        if raw is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "relationship_not_found")
            return error_response(
                404,
                f"Relationship {relationship_id!r} not found in goal planning system",
                AGENT_ID,
            )
        goals = raw.get("goals", [])
        on_track = sum(1 for g in goals if g.get("on_track"))
        span.set_attribute("result.goal_count", len(goals))
        span.set_attribute("result.overall_on_track", raw.get("overall_on_track", False))
        span.set_attribute("result.on_track_count", on_track)

        agent = make_agent(
            "WealthGoalPlanningAgent",
            (
                "You are the Wealth Goal Planning advisor for Meridian Bank. "
                "Call get_goal_planning_data and summarise the client's progress toward their financial goals. "
                "Note which goals are on track and which need attention. "
                "Be concise — 2-3 sentences. Quote funding percentages and target dates."
            ),
            [get_goal_planning_data],
            input_guardrails=[injection_guardrail, relationship_id_guardrail],
            output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
            llm_base_url=_LLM_BASE,
            llm_api_key=_LLM_KEY,
            llm_model=_LLM_MODEL,
        )
        try:
            result = await Runner.run(
                agent,
                f"Retrieve and summarise goal planning status for relationship_id={relationship_id}"
            )
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return {**raw, "agent_narrative": result.final_output}
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return error_response(503, f"Agent LLM unavailable: {type(exc).__name__}", AGENT_ID)
