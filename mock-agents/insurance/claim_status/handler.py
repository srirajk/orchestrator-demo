"""Claim Status agent — GET /claim-status

Returns the status of an insurance claim. Multi-entity: a claim is looked up by
claim_id; if no claim_id is supplied but a policy_id is, it returns all claims
filed against that policy. Deterministic mock — returns canned JSON directly.
"""
import logging
from typing import Optional
from fastapi import APIRouter, Query

from shared.canned_data import CLAIMS, claims_for_policy
from shared.telemetry import agent_span
from shared.error_schema import error_response

router = APIRouter(prefix="/claim-status", tags=["claim_status"])
AGENT_ID = "acme.insurance.claim_status"
log = logging.getLogger(__name__)


@router.get(
    "",
    operation_id="get_claim_status",
    summary="Get claim status for an insurance claim or policy",
    description=(
        "Returns claim amount, status, incident date, and adjuster. Look up a single "
        "claim by claim_id, or list all claims on a policy by policy_id. "
        "Fault knobs: ?_delay_ms=N ?_fail=true"
    ),
)
async def get_claim_status(
    claim_id: Optional[str] = Query(
        None, description="Claim identifier, e.g. CLM-5501 (optional)"
    ),
    policy_id: Optional[str] = Query(
        None, description="Policy identifier, e.g. POL-77001 — lists all claims on the policy"
    ),
):
    with agent_span(AGENT_ID, claim_id or policy_id) as span:
        # Single-claim lookup takes precedence.
        if claim_id:
            claim = CLAIMS.get(claim_id)
            if claim is None:
                span.set_attribute("error", True)
                span.set_attribute("error.type", "claim_not_found")
                return error_response(
                    404,
                    f"Claim {claim_id!r} not found in claims system",
                    AGENT_ID,
                )
            span.set_attribute("result.status", claim.get("status", ""))
            span.set_attribute("result.amount", claim.get("amount", 0))
            return claim

        # Otherwise list claims for the policy.
        if policy_id:
            claims = claims_for_policy(policy_id)
            span.set_attribute("result.claim_count", len(claims))
            return {
                "policy_id": policy_id,
                "claim_count": len(claims),
                "claims": claims,
            }

        span.set_attribute("error", True)
        span.set_attribute("error.type", "missing_query")
        return error_response(
            400,
            "Provide a claim_id or a policy_id to look up claim status",
            AGENT_ID,
        )
