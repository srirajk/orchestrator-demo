"""Holdings agent — GET /holdings"""
import os
import logging
from fastapi import APIRouter, Query
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import HOLDINGS
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail
from shared.error_schema import error_response

# Per-agent LLM overrides — fall back to the service-level WEALTH_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("HOLDINGS_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("HOLDINGS_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("HOLDINGS_LLM_MODEL") or None

router = APIRouter(prefix="/holdings", tags=["holdings"])
AGENT_ID = "meridian.wealth.holdings"
log = logging.getLogger(__name__)


@function_tool
def get_holdings_data(relationship_id: str) -> dict:
    """Retrieve current portfolio holdings and allocations for a client relationship."""
    data = HOLDINGS.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found in holdings system"}
    return data


@router.get(
    "",
    summary="Get portfolio holdings for a relationship",
    description="Returns current positions and allocation breakdown. Fault knobs: ?_delay_ms=N ?_fail=true",
)
async def get_holdings(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        raw = HOLDINGS.get(relationship_id)
        if raw is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "relationship_not_found")
            return error_response(
                404,
                f"Relationship {relationship_id!r} not found in holdings system",
                AGENT_ID,
            )
        span.set_attribute("result.position_count", len(raw.get("positions", [])))
        span.set_attribute("result.total_value_usd", raw.get("total_value", 0))

        agent = make_agent(
            "WealthHoldingsAgent",
            (
                "You are the Wealth Holdings specialist for Meridian Bank. "
                "Call get_holdings_data to retrieve the client portfolio, "
                "then provide a brief professional summary: total value, top positions, and asset allocation. "
                "Be concise — 2-3 sentences. Quote exact numbers from the data."
            ),
            [get_holdings_data],
            input_guardrails=[injection_guardrail, relationship_id_guardrail],
            output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
            llm_base_url=_LLM_BASE,
            llm_api_key=_LLM_KEY,
            llm_model=_LLM_MODEL,
        )
        try:
            result = await Runner.run(agent, f"Retrieve and summarise holdings for relationship_id={relationship_id}")
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            span.set_attribute("agent.guardrails", "injection,relationship_id,length,grounding")
            return {**raw, "agent_narrative": result.final_output}
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return error_response(503, f"Agent LLM unavailable: {type(exc).__name__}", AGENT_ID)
