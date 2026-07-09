"""Cash Management MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails."""
import os
import json
import logging
import asyncio
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import CASH
from shared.error_schema import AgentToolError
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL, LLM_TIMEOUT_S
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail

# Per-agent LLM overrides — fall back to the service-level SERVICING_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("CASH_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("CASH_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("CASH_LLM_MODEL") or None

AGENT_ID = "meridian.servicing.cash"
log = logging.getLogger(__name__)


@function_tool
def get_cash_data(relationship_id: str) -> dict:
    """Retrieve cash balances and projected cash position for a client relationship."""
    data = CASH.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found"}
    return data


async def _run_cash_agent(
    relationship_id: str,
    raw: dict,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> str:
    agent = make_agent(
        "ServicingCashAgent",
        (
            "You are the Cash Management specialist for Meridian Bank Asset Servicing. "
            "Call get_cash_data and summarise: total available settled cash in USD, "
            "any unsettled cash and what it relates to, and upcoming cash flows to watch. "
            "Be concise — 2-3 sentences."
        ),
        [get_cash_data],
        input_guardrails=[injection_guardrail, relationship_id_guardrail],
        output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
        llm_base_url=llm_base_url,
        llm_api_key=llm_api_key,
        llm_model=llm_model,
    )
    result = await Runner.run(agent, f"Retrieve and summarise cash position for relationship_id={relationship_id}")
    return result.final_output


async def get_cash(relationship_id: str) -> str:
    """Get cash balances and projected cash position for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_cash")
        data = CASH.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            raise AgentToolError(f"Relationship '{relationship_id}' not found.", AGENT_ID, 404)
        projected_usd = data.get("projected_cash_usd", 0)
        span.set_attribute("result.total_available_usd", projected_usd)
        span.set_attribute("result.currency_count", len(data.get("balances", [])))
        try:
            narrative = await asyncio.wait_for(_run_cash_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL), timeout=LLM_TIMEOUT_S)
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return json.dumps({**data, "agent_narrative": narrative})
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            raise AgentToolError(f"llm_unavailable: {type(exc).__name__}", AGENT_ID, 503) from exc
