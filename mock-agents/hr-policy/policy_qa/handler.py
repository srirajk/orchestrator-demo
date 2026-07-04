"""
HR Policy Q&A agent — GET /policy-qa

A KNOWLEDGE + ENTERPRISE agent: returns HR policy information.
No client entity required and open to all authenticated users (audience:enterprise).

data_classification: internal
audience:            enterprise (every authenticated user)
"""
import logging
from typing import Optional
from fastapi import APIRouter, Query

from shared.canned_data import HR_POLICIES, VALID_TOPICS, TOPICS_SUMMARY
from shared.telemetry import agent_span
from shared.error_schema import error_response

router = APIRouter(prefix="/policy-qa", tags=["policy_qa"])
AGENT_ID = "acme.hr.policy_qa"
log = logging.getLogger(__name__)


@router.get(
    "",
    operation_id="get_policy_qa",
    summary="Get Meridian HR policy information",
    description=(
        "Returns Meridian HR policy details for the requested topic. "
        "Available topics: parental_leave, pto, benefits, conduct, performance, learning. "
        "Omitting topic returns a summary index of all available policies. "
        "This is an enterprise knowledge agent — open to all authenticated users. "
        "Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def get_policy_qa(
    topic: Optional[str] = Query(
        None,
        description=(
            "HR policy topic. One of: parental_leave, pto, benefits, conduct, performance, learning. "
            "Omit to get a summary of all available topics."
        ),
    ),
):
    with agent_span(AGENT_ID, input_value=topic or "topics_index") as span:
        # No topic → return the topics index so the user knows what to ask about
        if not topic:
            span.set_attribute("result.response_type", "topics_index")
            return {
                "response_type": "topics_index",
                "message": (
                    "Meridian HR policies are available for the following topics. "
                    "Request a specific topic to get the full policy details."
                ),
                "available_topics": TOPICS_SUMMARY,
                "agent_id": AGENT_ID,
            }

        normalised = topic.lower().strip()
        if normalised not in VALID_TOPICS:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "unknown_topic")
            return error_response(
                400,
                (
                    f"Unknown policy topic '{topic}'. Valid topics: "
                    + ", ".join(sorted(VALID_TOPICS))
                    + "."
                ),
                AGENT_ID,
            )

        policy = HR_POLICIES.get(normalised)
        if policy is None:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "policy_not_found")
            return error_response(404, f"Policy content for topic '{topic}' not found", AGENT_ID)

        span.set_attribute("result.topic", policy["topic"])
        span.set_attribute("result.policy_id", policy["policy_id"])
        span.set_attribute("result.section_count", len(policy.get("sections", [])))

        return {
            **policy,
            "available_topics": TOPICS_SUMMARY,
            "agent_id": AGENT_ID,
        }
