"""Custody Positions MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails."""
import os
import json
import logging
import asyncio
import concurrent.futures
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import CUSTODY_POSITIONS
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL, LLM_TIMEOUT_S
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail

# Per-agent LLM overrides — fall back to the service-level SERVICING_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("CUSTODY_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("CUSTODY_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("CUSTODY_LLM_MODEL") or None

AGENT_ID = "acme.servicing.custody_positions"
log = logging.getLogger(__name__)


@function_tool
def get_custody_positions_data(relationship_id: str) -> dict:
    """Retrieve assets held at each custodian for a client relationship."""
    data = CUSTODY_POSITIONS.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found"}
    return data


async def _run_custody_agent(
    relationship_id: str,
    raw: dict,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> str:
    agent = make_agent(
        "ServicingCustodyAgent",
        (
            "You are the Custody Positions specialist for Meridian Bank Asset Servicing. "
            "Call get_custody_positions_data and summarise: which custodians hold assets, "
            "total market value across all custodians, and the largest single holding. "
            "Be concise — 2-3 sentences."
        ),
        [get_custody_positions_data],
        input_guardrails=[injection_guardrail, relationship_id_guardrail],
        output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
        llm_base_url=llm_base_url,
        llm_api_key=llm_api_key,
        llm_model=llm_model,
    )
    result = await Runner.run(agent, f"Retrieve and summarise custody positions for relationship_id={relationship_id}")
    return result.final_output


def get_custody_positions(relationship_id: str) -> str:
    """Get assets held at each custodian for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_custody_positions")
        data = CUSTODY_POSITIONS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
        custodians = data.get("holdings_by_custodian", [])
        custodian_count = len(custodians)
        total_positions = sum(len(c.get("holdings", [])) for c in custodians)
        span.set_attribute("result.custodian_count", custodian_count)
        span.set_attribute("result.total_positions", total_positions)
        try:
            try:
                asyncio.get_running_loop()
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    future = pool.submit(asyncio.run, _run_custody_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL))
                    narrative = future.result(timeout=LLM_TIMEOUT_S)
            except RuntimeError:
                narrative = asyncio.run(_run_custody_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL))
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return json.dumps({**data, "agent_narrative": narrative})
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return json.dumps({"error": f"llm_unavailable: {type(exc).__name__}", "agent_id": AGENT_ID})
