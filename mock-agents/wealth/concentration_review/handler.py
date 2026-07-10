import logging
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from shared.telemetry import agent_span
from concentration_review.compute import build_review_flag

router = APIRouter(prefix="/concentration-review", tags=["concentration-review"])
AGENT_ID = "meridian.wealth.concentration_review"
log = logging.getLogger(__name__)


class ConcentrationAnalysisPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    relationship_id: str | None = None
    relationship_name: str | None = None
    breach_count: int = 0
    flags: list[dict[str, Any]] = Field(default_factory=list)
    policy: dict[str, Any] | None = None


@router.post(
    "",
    operation_id="post_concentration_review",
    summary="Create a firm-policy concentration review flag",
    description=(
        "Consumes an already-computed concentration analysis and surfaces a firm-policy "
        "review flag. It does not compute thresholds and does not provide investment advice."
    ),
)
async def post_concentration_review(payload: ConcentrationAnalysisPayload):
    body = payload.model_dump()
    with agent_span(AGENT_ID, body.get("relationship_id")) as span:
        result = build_review_flag(body)
        span.set_attribute("result.breach_count", result["breach_count"])
        return result
