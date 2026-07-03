"""
Market Research agent — GET /market-research

A KNOWLEDGE agent: returns house-view commentary and sector outlooks.
No client entity required — this is general internal research available
to any authenticated Wealth segment user.

data_classification: internal (all Wealth segment members can access)
audience:            segment
"""
import logging
from typing import Optional
from fastapi import APIRouter, Query

from shared.canned_data import MARKET_RESEARCH, VALID_TOPICS, TOPICS_SUMMARY
from shared.telemetry import agent_span
from shared.error_schema import error_response

router = APIRouter(prefix="/market-research", tags=["market_research"])
AGENT_ID = "acme.wealth.market_research"
log = logging.getLogger(__name__)


@router.get(
    "",
    operation_id="get_market_research",
    summary="Get Meridian house view and market research",
    description=(
        "Returns Meridian's current house view, market commentary, and sector outlooks. "
        "Optional ?topic= filters to a specific area: equities, fixed_income, alternatives, macro. "
        "Omitting topic returns the broad quarterly overview. "
        "This is a knowledge agent — no client entity required. "
        "Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def get_market_research(
    topic: Optional[str] = Query(
        None,
        description=(
            "Research topic to retrieve. One of: equities, fixed_income, alternatives, macro. "
            "Omit for the broad quarterly outlook."
        ),
    ),
):
    with agent_span(AGENT_ID, input_value=topic or "broad_overview") as span:
        # Validate topic when provided
        normalised = topic.lower().strip() if topic else None
        if normalised and normalised not in VALID_TOPICS:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "unknown_topic")
            return error_response(
                400,
                (
                    f"Unknown topic '{topic}'. Valid topics: "
                    + ", ".join(sorted(VALID_TOPICS))
                    + ". Omit ?topic to retrieve the broad quarterly outlook."
                ),
                AGENT_ID,
            )

        research = MARKET_RESEARCH.get(normalised)  # None key = broad overview
        if research is None:
            # Shouldn't happen with valid data, but guard defensively
            span.set_attribute("error", True)
            span.set_attribute("error.type", "research_not_found")
            return error_response(404, "Research content not found", AGENT_ID)

        span.set_attribute("result.topic", research["topic"])
        span.set_attribute("result.section_count", len(research.get("sections", [])))
        span.set_attribute("result.as_of_date", research.get("as_of_date", ""))

        # Attach a topics index so the synthesiser can mention other available research areas
        return {
            **research,
            "available_topics": TOPICS_SUMMARY,
            "agent_id": AGENT_ID,
        }
