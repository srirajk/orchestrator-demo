"""Policy Details agent — GET /policy-details

Returns the policy record for a commercial insurance policy. Unlike the wealth
agents this is a *deterministic* mock: it returns the canned policy JSON
directly (no in-agent LLM narration). The gateway synthesizes the final answer
from this grounded DATA.
"""
import logging
from fastapi import APIRouter, Query

from shared.canned_data import POLICIES
from shared.telemetry import agent_span
from shared.error_schema import error_response

router = APIRouter(prefix="/policy-details", tags=["policy_details"])
AGENT_ID = "acme.insurance.policy_details"
log = logging.getLogger(__name__)


@router.get(
    "",
    operation_id="get_policy_details",
    summary="Get policy details for an insurance policy",
    description=(
        "Returns line of business, premium, coverage limit, status, and effective "
        "date for a commercial policy. Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def get_policy_details(
    policy_id: str = Query(..., description="Policy identifier, e.g. POL-77001"),
):
    with agent_span(AGENT_ID, policy_id) as span:
        policy = POLICIES.get(policy_id)
        if policy is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "policy_not_found")
            return error_response(
                404,
                f"Policy {policy_id!r} not found in policy administration system",
                AGENT_ID,
            )
        span.set_attribute("result.line_of_business", policy.get("line_of_business", ""))
        span.set_attribute("result.coverage_limit", policy.get("coverage_limit", 0))
        span.set_attribute("result.status", policy.get("status", ""))
        return policy
