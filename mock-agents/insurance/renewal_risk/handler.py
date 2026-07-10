"""
Renewal Risk agent — POST /renewal-risk

A 2-PRODUCER FAN-IN analytics agent: it consumes the merged OUTPUT of the
policy_details agent (``policy_record``) AND the claim_status agent
(``claim_status``) and produces a renewal-risk analysis (loss ratio, and
combined ratio if an expense figure is ever available). It does NOT take a
policy_id and does NOT fetch data — it computes from the payload the gateway's
blackboard binds to it. All heavy math lives in ``renewal_risk/compute.py``
(hermetic, no infra deps) so it is unit-testable without the gateway or docker.

Threshold policy is firm-configured and read from env in
compute.load_target_loss_ratio() (RENEWAL_TARGET_LOSS_RATIO, default 0.60).
We never assert a percentage is a regulatory/industry standard — see
docs/DOMAIN-KNOWLEDGE-VERIFIED.md (V7, V9).

Infra is inherited from main.py (JWT middleware, /health, fault-knob
middleware, OTel via setup_telemetry). This module only reuses shared/:
agent_span + error_response.
"""
import logging
from typing import Any, Optional

from fastapi import APIRouter
from pydantic import BaseModel, ConfigDict, Field

from shared.telemetry import agent_span
from shared.error_schema import error_response
from renewal_risk.compute import compute_renewal_risk, RenewalRiskInputError

router = APIRouter(prefix="/renewal-risk", tags=["renewal_risk"])
AGENT_ID = "meridian.insurance.renewal_risk"
log = logging.getLogger(__name__)


class RenewalRiskPayload(BaseModel):
    """
    The renewal_risk request body IS the merged fan-in of the two upstream
    producers, keyed by their declared ``io.produces[].name``:
      - ``policy_record``: the policy_details agent's output (or its
        'select'-projected subset).
      - ``claim_status``: the claim_status agent's output (or its
        'select'-projected subset) — either the single-claim shape or the
        "all claims on a policy" list shape.
    Extra fields are tolerated so the gateway can pipe upstream output straight
    through without the mock agent breaking on an unexpected field.
    """

    model_config = ConfigDict(extra="allow")

    policy_record: dict[str, Any] = Field(
        default_factory=dict,
        description="policy_details agent output — must include at least a "
        "numeric 'premium'.",
    )
    claim_status: Optional[dict[str, Any]] = Field(
        default=None,
        description="claim_status agent output — either the single-claim "
        "shape or the {policy_id, claim_count, claims:[...]} list shape.",
    )


@router.post(
    "",
    operation_id="post_renewal_risk",
    summary="Analyse renewal risk (loss ratio) from policy + claim payloads",
    description=(
        "Fan-in analytics: consumes the policy_details agent's output "
        "(policy_record) AND the claim_status agent's output (claim_status) "
        "and returns loss ratio, combined ratio when an expense figure is "
        "available, and a firm-configured renewal-target breach flag. "
        "Computes from the payload; does not fetch. "
        "Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def post_renewal_risk(payload: RenewalRiskPayload):
    body = payload.model_dump()
    with agent_span(AGENT_ID, body.get("policy_record", {}).get("policy_id")) as span:
        try:
            analysis = compute_renewal_risk(body)
        except RenewalRiskInputError as exc:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "invalid_renewal_risk_payload")
            # Non-200 on missing/malformed input — never a silent 200 with a
            # fabricated number.
            return error_response(422, str(exc), AGENT_ID)
        except Exception as exc:  # defensive: unexpected compute failure
            log.error("Renewal risk compute failed: %s", exc)
            span.set_attribute("error", True)
            span.set_attribute("error.type", type(exc).__name__)
            return error_response(500, f"Renewal risk analysis failed: {type(exc).__name__}", AGENT_ID)

        span.set_attribute("result.policy_id", analysis.get("policy_id") or "")
        span.set_attribute("result.claim_count", analysis["claim_count"])
        span.set_attribute("result.loss_ratio_pct", analysis["loss_ratio_pct"])
        span.set_attribute("result.breach_count", analysis["breach_count"])
        return analysis
