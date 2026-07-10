"""NAV MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails."""
import os
import json
import logging
import asyncio
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import NAV
from shared.error_schema import AgentToolError
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL, LLM_TIMEOUT_S
from shared.guardrails import injection_guardrail, fund_id_guardrail, length_guardrail, make_grounding_guardrail

# Per-agent LLM overrides — fall back to the service-level SERVICING_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("NAV_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("NAV_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("NAV_LLM_MODEL") or None

AGENT_ID = "meridian.servicing.nav"
log = logging.getLogger(__name__)


@function_tool
def get_nav_data(fund_id: str) -> dict:
    """Retrieve the latest Net Asset Value for a fund. Keyed by fund_id, not relationship_id."""
    data = NAV.get(fund_id)
    if data is None:
        return {"error": f"Fund {fund_id!r} not found. Known funds: {list(NAV)}"}
    return data


async def _run_nav_agent(
    fund_id: str,
    raw: dict,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> str:
    agent = make_agent(
        "ServicingNavAgent",
        (
            "You are the NAV specialist for Meridian Bank Asset Servicing. "
            "Call get_nav_data and summarise: the NAV per unit, total AUM, "
            "valuation date, and the day's NAV change percentage. "
            "Be concise — 2-3 sentences."
        ),
        [get_nav_data],
        input_guardrails=[injection_guardrail, fund_id_guardrail],
        output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
        llm_base_url=llm_base_url,
        llm_api_key=llm_api_key,
        llm_model=llm_model,
    )
    result = await Runner.run(agent, f"Retrieve and summarise NAV for fund_id={fund_id}")
    return result.final_output


async def get_nav(fund_id: str) -> str:
    """Get the latest Net Asset Value for a fund. Keyed by fund_id, not relationship_id."""
    with agent_span(AGENT_ID) as span:
        maybe_fault("get_nav")
        span.set_attribute("entity.fund_id", fund_id)
        data = NAV.get(fund_id)
        if data is None:
            span.set_attribute("error", True)
            raise AgentToolError(f"Fund '{fund_id}' not found", AGENT_ID, 404)
        span.set_attribute("result.nav_per_unit", data.get("nav", 0))
        span.set_attribute("result.total_aum", data.get("aum", 0))
        try:
            narrative = await asyncio.wait_for(_run_nav_agent(fund_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL), timeout=LLM_TIMEOUT_S)
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return json.dumps({**data, "agent_narrative": narrative})
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", fund_id, exc)
            raise AgentToolError(f"llm_unavailable: {type(exc).__name__}", AGENT_ID, 503) from exc
