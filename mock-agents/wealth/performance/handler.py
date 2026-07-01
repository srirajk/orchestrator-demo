"""Performance agent — GET /performance"""
import os
import logging
from fastapi import APIRouter, Query
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import PERFORMANCE
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail
from shared.error_schema import error_response

# Per-agent LLM overrides — fall back to the service-level WEALTH_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("PERFORMANCE_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("PERFORMANCE_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("PERFORMANCE_LLM_MODEL") or None

router = APIRouter(prefix="/performance", tags=["performance"])
AGENT_ID = "acme.wealth.performance"
log = logging.getLogger(__name__)


@function_tool
def get_performance_data(relationship_id: str) -> dict:
    """Retrieve YTD performance, returns, and P&L for a client relationship."""
    data = PERFORMANCE.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found in performance system"}
    return data


@router.get(
    "",
    summary="Get performance metrics for a relationship",
    description="Returns YTD return, P&L, and period breakdown. Fault knobs: ?_delay_ms=N ?_fail=true",
)
async def get_performance(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
    period: str = Query("YTD", description="Reporting period: YTD | 1Y | 3Y | Inception"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        span.set_attribute("query.period", period)
        raw = PERFORMANCE.get(relationship_id)
        if raw is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "relationship_not_found")
            return error_response(
                404,
                f"Relationship {relationship_id!r} not found in performance system",
                AGENT_ID,
            )
        span.set_attribute("result.total_return_pct", raw.get("total_return_pct", 0))
        span.set_attribute("result.alpha", raw.get("alpha", 0))
        span.set_attribute("result.period", period)

        agent = make_agent(
            "WealthPerformanceAgent",
            (
                "You are the Wealth Performance analyst for Meridian Bank. "
                "Call get_performance_data and give a brief professional summary of returns, "
                "P&L in dollar terms, and whether performance is tracking vs the prior period. "
                "Be concise — 2-3 sentences. Quote exact numbers."
            ),
            [get_performance_data],
            input_guardrails=[injection_guardrail, relationship_id_guardrail],
            output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
            llm_base_url=_LLM_BASE,
            llm_api_key=_LLM_KEY,
            llm_model=_LLM_MODEL,
        )
        try:
            result = await Runner.run(
                agent,
                f"Retrieve and summarise {period} performance for relationship_id={relationship_id}"
            )
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return {**raw, "period": period, "agent_narrative": result.final_output}
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return error_response(503, f"Agent LLM unavailable: {type(exc).__name__}", AGENT_ID)
