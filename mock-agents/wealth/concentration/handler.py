"""
Concentration agent — POST /concentration

A FAN-IN analytics agent: it consumes the OUTPUT of the wealth.holdings agent
(a holdings payload) and produces a concentration analysis. It does NOT take a
relationship_id and does NOT fetch data — it computes from the payload it is
given. All heavy math lives in ``concentration/compute.py`` (hermetic, no infra
deps) so it is unit-testable without the gateway or docker.

Threshold policy is firm-configured and read from env in compute.load_thresholds()
(CONCENTRATION_SINGLE_NAME_THRESHOLD, default 0.10). We never assert a percentage
is a regulatory/industry standard — see docs/DOMAIN-KNOWLEDGE-VERIFIED.md.

Infra is inherited from main.py (JWT middleware, /health, fault-knob middleware,
OTel via setup_telemetry). This module only reuses shared/: agent_span + error_response.
"""
import logging
from typing import Any, Optional

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from shared.telemetry import agent_span
from shared.error_schema import error_response
from concentration.compute import compute_concentration, ConcentrationInputError

router = APIRouter(prefix="/concentration", tags=["concentration"])
AGENT_ID = "meridian.wealth.concentration"
log = logging.getLogger(__name__)


class HoldingsPayload(BaseModel):
    """
    The concentration request body IS the holdings agent's output. We accept the
    real holdings shape and tolerate extra fields (e.g. agent_narrative) so the
    gateway can pipe the upstream output straight through.
    """

    model_config = ConfigDict(extra="allow")

    positions: list[dict[str, Any]] = Field(
        default_factory=list,
        description="Portfolio positions, each with at least a numeric 'value' "
        "and a 'ticker'/'isin' identifier.",
    )
    total_value: Optional[float] = None
    allocation_by_class: Optional[list[dict[str, Any]]] = None
    relationship_id: Optional[str] = None
    relationship_name: Optional[str] = None
    currency: Optional[str] = None
    as_of_date: Optional[str] = None
    risk_profile: Optional[dict[str, Any]] = Field(
        default=None,
        description="Optional wealth.risk_profile output; used only to annotate "
        "context, never required.",
    )


@router.post(
    "",
    operation_id="post_concentration",
    summary="Analyse portfolio concentration from a holdings payload",
    description=(
        "Fan-in analytics: consumes the holdings agent's output and returns "
        "single-name / asset-class concentration, HHI + effective number of "
        "positions, and firm-configured breach flags. Computes from the payload; "
        "does not fetch. Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def post_concentration(payload: HoldingsPayload):
    body = payload.model_dump()
    with agent_span(AGENT_ID, body.get("relationship_id")) as span:
        try:
            analysis = compute_concentration(body)
        except ConcentrationInputError as exc:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "invalid_holdings_payload")
            # Non-200 on empty/malformed input — never a silent 200.
            return error_response(422, str(exc), AGENT_ID)
        except Exception as exc:  # defensive: unexpected compute failure
            log.error("Concentration compute failed: %s", exc)
            span.set_attribute("error", True)
            span.set_attribute("error.type", type(exc).__name__)
            return error_response(500, f"Concentration analysis failed: {type(exc).__name__}", AGENT_ID)

        span.set_attribute("result.position_count", analysis["position_count"])
        span.set_attribute("result.breach_count", analysis["breach_count"])
        span.set_attribute("result.hhi", analysis["diversification"]["hhi"])
        span.set_attribute(
            "result.top_single_name_pct",
            analysis["single_name"]["top"]["weight_pct"],
        )
        return analysis
