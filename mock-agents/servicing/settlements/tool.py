"""Settlements MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails."""
import os
import json
import logging
import asyncio
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import SETTLEMENTS
from shared.error_schema import mcp_error_json
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL, LLM_TIMEOUT_S
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail

# Per-agent LLM overrides — fall back to the service-level SERVICING_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("SETTLEMENTS_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("SETTLEMENTS_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("SETTLEMENTS_LLM_MODEL") or None

AGENT_ID = "meridian.servicing.settlement_status"
log = logging.getLogger(__name__)


@function_tool
def get_settlements_data(relationship_id: str) -> dict:
    """Retrieve pending and failed trade settlements for a client relationship."""
    data = SETTLEMENTS.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found"}
    return data


async def _run_settlements_agent(
    relationship_id: str,
    raw: dict,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> str:
    agent = make_agent(
        "ServicingSettlementsAgent",
        (
            "You are the Settlement Status specialist for Meridian Bank Asset Servicing. "
            "Call get_settlements_data and summarise: count of pending settlements, "
            "any failed settlements (and their failure reason), and urgency level. "
            "Be concise — 2-3 sentences. Flag failed settlements clearly."
        ),
        [get_settlements_data],
        input_guardrails=[injection_guardrail, relationship_id_guardrail],
        output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
        llm_base_url=llm_base_url,
        llm_api_key=llm_api_key,
        llm_model=llm_model,
    )
    result = await Runner.run(agent, f"Retrieve and summarise settlements for relationship_id={relationship_id}")
    return result.final_output


async def get_settlements(relationship_id: str) -> str:
    """Get pending and failed settlement trades for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_settlements")
        data = SETTLEMENTS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return mcp_error_json(f"Relationship '{relationship_id}' not found.", AGENT_ID, 404)
        pending = len(data.get("pending", []))
        failed = len(data.get("failed", []))
        span.set_attribute("result.pending_count", pending)
        span.set_attribute("result.failed_count", failed)
        if failed > 0:
            span.set_attribute("alert.failed_settlements", True)
        try:
            narrative = await asyncio.wait_for(_run_settlements_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL), timeout=LLM_TIMEOUT_S)
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return json.dumps({**data, "agent_narrative": narrative})
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            narrative = (
                f"Settlement status for {relationship_id}: {pending} pending settlement(s) "
                f"and {failed} failed settlement(s)."
            )
            return json.dumps({**data, "agent_narrative": narrative})
