"""Corporate Actions MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails."""
import os
import json
import logging
import asyncio
import concurrent.futures
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import CORPORATE_ACTIONS
from shared.error_schema import mcp_error_json
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL, LLM_TIMEOUT_S
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail

# Per-agent LLM overrides — fall back to the service-level SERVICING_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("CORPORATE_ACTIONS_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("CORPORATE_ACTIONS_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("CORPORATE_ACTIONS_LLM_MODEL") or None

AGENT_ID = "meridian.servicing.corporate_actions"
log = logging.getLogger(__name__)


@function_tool
def get_corporate_actions_data(relationship_id: str) -> dict:
    """Retrieve upcoming corporate actions (dividends, splits, elections) for a client relationship."""
    data = CORPORATE_ACTIONS.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found"}
    return data


async def _run_corporate_actions_agent(
    relationship_id: str,
    raw: dict,
    llm_base_url: str | None = None,
    llm_api_key: str | None = None,
    llm_model: str | None = None,
) -> str:
    agent = make_agent(
        "ServicingCorporateActionsAgent",
        (
            "You are the Corporate Actions specialist for Meridian Bank Asset Servicing. "
            "Call get_corporate_actions_data and highlight: which actions require an election, "
            "the nearest deadline, and any dividend income expected. "
            "Be concise — 2-3 sentences. Highlight urgent deadlines."
        ),
        [get_corporate_actions_data],
        input_guardrails=[injection_guardrail, relationship_id_guardrail],
        output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
        llm_base_url=llm_base_url,
        llm_api_key=llm_api_key,
        llm_model=llm_model,
    )
    result = await Runner.run(agent, f"Retrieve and summarise corporate actions for relationship_id={relationship_id}")
    return result.final_output


def get_corporate_actions(relationship_id: str) -> str:
    """Get upcoming corporate actions for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_corporate_actions")
        data = CORPORATE_ACTIONS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return mcp_error_json(f"Relationship '{relationship_id}' not found.", AGENT_ID, 404)
        actions = data.get("upcoming_actions", [])
        elections_due = sum(1 for a in actions if a.get("election_required"))
        span.set_attribute("result.action_count", len(actions))
        span.set_attribute("result.elections_due", elections_due)
        try:
            try:
                asyncio.get_running_loop()
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    future = pool.submit(asyncio.run, _run_corporate_actions_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL))
                    narrative = future.result(timeout=LLM_TIMEOUT_S)
            except RuntimeError:
                narrative = asyncio.run(_run_corporate_actions_agent(relationship_id, data, _LLM_BASE, _LLM_KEY, _LLM_MODEL))
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return json.dumps({**data, "agent_narrative": narrative})
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return mcp_error_json(f"llm_unavailable: {type(exc).__name__}", AGENT_ID, 503)
