"""
Risk Profile agent — GET /risk-profile

Returns risk tolerance, drawdown limits, and concentration flags.
RAG-augmented: adds relevant Meridian Risk Policy snippets.
"""
import os
import logging
from fastapi import APIRouter, Query
from agents import Runner, function_tool, InputGuardrailTripwireTriggered, OutputGuardrailTripwireTriggered
from shared.canned_data import RISK_PROFILE
from shared.telemetry import agent_span
from shared.agent_client import make_agent, LLM_MODEL
from shared.guardrails import injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail
from .knowledge_base.retriever import retrieve
from shared.error_schema import error_response

# Per-agent LLM overrides — fall back to the service-level WEALTH_AGENT_LLM_* defaults.
_LLM_BASE  = os.environ.get("RISK_PROFILE_LLM_BASE_URL") or None
_LLM_KEY   = os.environ.get("RISK_PROFILE_LLM_API_KEY") or None
_LLM_MODEL = os.environ.get("RISK_PROFILE_LLM_MODEL") or None

router = APIRouter(prefix="/risk-profile", tags=["risk_profile"])
AGENT_ID = "meridian.wealth.risk_profile"
log = logging.getLogger(__name__)


@function_tool
def get_risk_profile_data(relationship_id: str) -> dict:
    """Retrieve risk tolerance, risk score, and concentration flags for a client relationship."""
    data = RISK_PROFILE.get(relationship_id)
    if data is None:
        return {"error": f"Relationship {relationship_id!r} not found in risk profile system"}
    return data


@router.get(
    "",
    summary="Get risk profile and concentration flags for a relationship",
    description=(
        "Returns risk tolerance score, drawdown tolerance, and any concentration flags. "
        "RAG-augmented: also returns relevant Meridian Risk Policy sections. "
        "Supports fault knobs: ?_delay_ms=<n> and ?_fail=true."
    ),
)
async def get_risk_profile(
    relationship_id: str = Query(..., description="Relationship identifier, e.g. REL-00042"),
):
    with agent_span(AGENT_ID, relationship_id) as span:
        raw = RISK_PROFILE.get(relationship_id)
        if raw is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "relationship_not_found")
            return error_response(
                404,
                f"Relationship {relationship_id!r} not found in risk profile system",
                AGENT_ID,
            )

        # RAG: retrieve policy context
        risk_tolerance = raw.get("risk_tolerance", "")
        concentration_flags = raw.get("concentration_flags", [])
        query = f"risk tolerance {risk_tolerance} concentration limit"
        if concentration_flags:
            tickers = " ".join(f.get("security", "") for f in concentration_flags)
            query += f" breach {tickers}"

        policy_snippets = retrieve(query, top_k=2)
        data_with_policy = dict(raw)
        data_with_policy["policy_context"] = policy_snippets

        flag_count = len(concentration_flags)
        span.set_attribute("result.risk_score", raw.get("risk_score", 0))
        span.set_attribute("result.concentration_flags", flag_count)
        span.set_attribute("rag.retrieved_docs", len(policy_snippets))

        agent = make_agent(
            "WealthRiskProfileAgent",
            (
                "You are the Wealth Risk analyst for Meridian Bank. "
                "Call get_risk_profile_data and highlight: risk tolerance category, numeric score, "
                "and any concentration flags that need attention. "
                "Be concise — 2-3 sentences. Flag any compliance concerns clearly."
            ),
            [get_risk_profile_data],
            input_guardrails=[injection_guardrail, relationship_id_guardrail],
            output_guardrails=[length_guardrail, make_grounding_guardrail(raw)],
            llm_base_url=_LLM_BASE,
            llm_api_key=_LLM_KEY,
            llm_model=_LLM_MODEL,
        )
        try:
            result = await Runner.run(
                agent,
                f"Retrieve and summarise risk profile for relationship_id={relationship_id}"
            )
            span.set_attribute("agent.model", _LLM_MODEL or LLM_MODEL)
            return {**data_with_policy, "agent_narrative": result.final_output}
        except Exception as exc:
            log.error("Agent LLM call failed for %s: %s", relationship_id, exc)
            return error_response(503, f"Agent LLM unavailable: {type(exc).__name__}", AGENT_ID)
