"""
Pure renewal-risk math for the Insurance Renewal Risk agent.

This module has ZERO FastAPI / shared / OTel dependencies on purpose: it is the
hermetic compute core that both the FastAPI handler and the unit tests import
directly. It is a 2-PRODUCER FAN-IN: it takes the merged blackboard payload
``{"policy_record": <policy_details output>, "claim_status": <claim_status
output>}`` and produces a renewal-risk analysis. It never fetches anything.

DOMAIN TRUTH (docs/DOMAIN-KNOWLEDGE-VERIFIED.md, V7 + V9):
  - Loss ratio = (incurred losses [+ LAE if available]) / earned premium.
  - Combined ratio = loss ratio + expense ratio; 100% = underwriting breakeven.
    Combined ratio measures UNDERWRITING profit only, not total profitability.
  - There is NO universal loss-ratio or combined-ratio renewal trigger — the
    target is CARRIER-SPECIFIC. We therefore COMPUTE the ratios (pure math,
    always valid from the real data) and FLAG against a firm-configurable
    target read from env, LABELED as firm policy — never a hardcoded
    "industry standard" cutoff.

REAL-DATA LIMITATION (be honest, do not fabricate — see mock-agents/insurance/
shared/canned_data.py):
  - The policy record carries a single ``premium`` figure with no written/
    earned distinction and no expense-ratio or LAE breakout. The claim record
    carries a single ``amount`` with no paid/reserved/LAE breakout. We treat
    ``premium`` as the earned-premium basis and the sum of claim ``amount``s
    as incurred losses (LOSS-ONLY, no LAE), and we say so explicitly in the
    output ``notes`` — we never invent an expense ratio, so combined ratio is
    OMITTED (not computed) with a note explaining why.
"""
from __future__ import annotations

import math
import os
from typing import Any, Optional


# --- Firm-configurable policy threshold (env-driven; never a magic constant) ---
# Fraction in [0, 1]. Default is the firm's stated demo policy, NOT a rule.
# V9: no universal loss-ratio renewal trigger exists — target is carrier-specific
# (1 - expense - profit - LAE loads). 0.60 (60%) is a plausible commercial-lines
# demo default, not an industry standard.
DEFAULT_RENEWAL_TARGET_LOSS_RATIO = 0.60

POLICY_NOTE = (
    "Target loss ratio is firm-configured policy, not a regulatory or industry "
    "standard. There is no universal commercial-lines loss-ratio renewal trigger; "
    "the target is carrier-specific (1 - expense - profit - LAE loads)."
)

LOSS_RATIO_QUALIFIER = (
    "Claims-based loss ratio: full-claimed-value incurred losses divided by the "
    "policy premium basis described in the disclosure notes."
)

LAE_NOTE = (
    "Loss ratio is LOSS-ONLY: the claim record carries a single 'amount' field "
    "with no paid/reserved/LAE breakout, so loss adjustment expense (LAE) is not "
    "included in the numerator."
)

PREMIUM_NOTE = (
    "The policy record carries a single 'premium' figure with no written/earned "
    "distinction; it is treated here as the earned-premium basis for this ratio."
)

COMBINED_RATIO_OMITTED_NOTE = (
    "Combined ratio omitted - no expense-ratio (or expense dollar) figure is "
    "present anywhere in the policy or claim record, so loss ratio + expense "
    "ratio cannot be computed without fabricating a number."
)

STATUS_NOTE = (
    "Incurred losses sum claim amounts at FULL claimed value. Non-incurred claim "
    "statuses such as withdrawn / rejected / closed-without-payment are EXCLUDED "
    "from the numerator; all other claims (including reserved / under-review) ARE "
    "counted at full claimed amount, which may overstate ultimate incurred losses "
    "versus paid+reserved figures."
)

# S2 (Opus review): claim statuses that do NOT contribute to incurred losses.
_NON_INCURRED_STATUSES = {
    "denied", "rejected", "withdrawn", "closed-without-payment",
    "closed_without_payment", "closed-no-payment", "no-payment",
}


def _env_fraction(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        val = float(raw)
    except (ValueError, TypeError):
        return default
    if val <= 0:
        return default
    # Tolerate a percent-style value (e.g. 60 meaning 60%).
    return val / 100.0 if val > 1 else val


def load_target_loss_ratio() -> float:
    """Read the firm-configured renewal target loss ratio from the environment."""
    return _env_fraction("RENEWAL_TARGET_LOSS_RATIO", DEFAULT_RENEWAL_TARGET_LOSS_RATIO)


class RenewalRiskInputError(ValueError):
    """Raised when the fan-in payload is empty or malformed (-> non-200)."""


def _pct(fraction: float) -> float:
    return round(fraction * 100.0, 4)


def _extract_claims(claim_status: dict[str, Any]) -> list[dict[str, Any]]:
    """
    Normalise EITHER claim_status shape into a flat list of claim dicts:
      - list shape:   {"policy_id":..., "claim_count":N, "claims":[{...}, ...]}
      - single shape: {"claim_id":..., "policy_id":..., "amount":..., "status":...}
    An empty/absent claims list is a legitimate "no open claims" state, not an
    error — a policy can genuinely have zero claims.
    """
    claims = claim_status.get("claims")
    if isinstance(claims, list):
        return [c for c in claims if isinstance(c, dict)]

    # Single-claim shape: has its own claim_id + amount directly on the object.
    if claim_status.get("claim_id") is not None and claim_status.get("amount") is not None:
        return [claim_status]

    return []


def compute_renewal_risk(
    payload: dict[str, Any],
    target_loss_ratio: Optional[float] = None,
) -> dict[str, Any]:
    """
    Compute a renewal-risk analysis from a merged fan-in payload.

    Args:
        payload: ``{"policy_record": <policy_details output>,
                     "claim_status": <claim_status output>}`` — the gateway
            blackboard merges the two producers' (optionally 'select'-projected)
            outputs keyed by each producer's ``io.produces[].name``.
        target_loss_ratio: firm-configured fraction; defaults to
            ``load_target_loss_ratio()``.

    Returns:
        A JSON-serialisable analysis dict.

    Raises:
        RenewalRiskInputError: if the policy record is missing/malformed, or
            premium is missing/non-numeric/non-finite/non-positive.
    """
    if target_loss_ratio is None:
        target_loss_ratio = load_target_loss_ratio()

    if not isinstance(payload, dict):
        raise RenewalRiskInputError("renewal_risk payload must be a JSON object")

    policy_record = payload.get("policy_record")
    if not isinstance(policy_record, dict) or not policy_record:
        raise RenewalRiskInputError(
            "renewal_risk payload has no 'policy_record' — the policy_details "
            "producer edge is missing or empty"
        )

    # S1 (Opus review): absent claims data must NEVER be reported as a favorable
    # 0% loss ratio. A missing claim_status is fail-safe, not a "no claims" state
    # (the manifest also marks this edge required, so the data-contract gate
    # normally rejects the node before this agent is even dispatched).
    claim_status = payload.get("claim_status")
    if claim_status is None:
        raise RenewalRiskInputError(
            "renewal_risk payload has no 'claim_status' — the claims feed is "
            "unavailable; renewal risk cannot be assessed without claims data. "
            "Absence of claims data must not be reported as a zero loss ratio."
        )
    if not isinstance(claim_status, dict):
        raise RenewalRiskInputError("'claim_status' must be a JSON object")

    premium = policy_record.get("premium")
    if premium is None or isinstance(premium, bool) or not isinstance(premium, (int, float)):
        raise RenewalRiskInputError(
            f"policy {policy_record.get('policy_id')!r} has a missing or "
            f"non-numeric 'premium' — cannot compute a loss ratio"
        )
    if not math.isfinite(premium):
        raise RenewalRiskInputError(
            f"policy {policy_record.get('policy_id')!r} has a non-finite "
            f"'premium' (NaN/inf)"
        )
    if premium <= 0:
        raise RenewalRiskInputError(
            f"policy {policy_record.get('policy_id')!r} has a non-positive "
            f"'premium' ({premium}) — cannot compute a loss ratio"
        )

    claims = _extract_claims(claim_status)

    incurred_losses = 0.0
    claim_lines: list[dict[str, Any]] = []
    excluded_claim_ids: list[Any] = []
    for c in claims:
        amount = c.get("amount")
        if amount is None or isinstance(amount, bool) or not isinstance(amount, (int, float)):
            raise RenewalRiskInputError(
                f"claim {c.get('claim_id')!r} has a missing or non-numeric 'amount'"
            )
        if not math.isfinite(amount):
            raise RenewalRiskInputError(
                f"claim {c.get('claim_id')!r} has a non-finite 'amount' (NaN/inf)"
            )
        if amount < 0:
            raise RenewalRiskInputError(
                f"claim {c.get('claim_id')!r} has a negative 'amount'"
            )
        # S2 (Opus review): denied/withdrawn/rejected/closed-without-payment claims
        # are NOT incurred losses — exclude them from the numerator (still listed).
        status = c.get("status")
        status_norm = (
            str(status).strip().lower().replace(" ", "-") if status is not None else None
        )
        excluded = status_norm in _NON_INCURRED_STATUSES
        if excluded:
            excluded_claim_ids.append(c.get("claim_id"))
        else:
            incurred_losses += float(amount)
        claim_lines.append(
            {
                "claim_id": c.get("claim_id"),
                "amount": round(float(amount), 2),
                "status": status,
                "counted_as_incurred": not excluded,
            }
        )

    loss_ratio = incurred_losses / float(premium)

    # No expense figure exists anywhere in the real data (policy or claim
    # record) — combined ratio is never fabricated; it is omitted with a note.
    expense_ratio = None
    combined_ratio = None

    flagged = loss_ratio > target_loss_ratio
    flag = {
        "type": "loss_ratio",
        "observed_pct": _pct(loss_ratio),
        "target_pct": _pct(target_loss_ratio),
        "breached": flagged,
        "policy": "firm-configured",
        "message": (
            f"Claims-based loss ratio is {_pct(loss_ratio)}%, "
            + (
                f"above the firm-configured renewal target of {_pct(target_loss_ratio)}% "
                f"(firm policy, not an industry-standard cutoff)."
                if flagged
                else f"at or below the firm-configured renewal target of "
                f"{_pct(target_loss_ratio)}% (firm policy, not an industry-standard cutoff)."
            )
        ),
    }

    notes = [LOSS_RATIO_QUALIFIER, PREMIUM_NOTE, LAE_NOTE, STATUS_NOTE, COMBINED_RATIO_OMITTED_NOTE]
    if not claims:
        notes.append("No open claims returned for this policy — incurred losses are $0.")
    if excluded_claim_ids:
        notes.append(
            f"Excluded {len(excluded_claim_ids)} non-incurred claim(s) "
            f"(denied/withdrawn/rejected/closed-without-payment) from the loss ratio: "
            f"{excluded_claim_ids}."
        )

    return {
        "analysis": "renewal_risk",
        "policy_id": policy_record.get("policy_id"),
        "policy_name": policy_record.get("policy_name"),
        "insured_name": policy_record.get("insured_name"),
        "line_of_business": policy_record.get("line_of_business"),
        "as_of_date": policy_record.get("as_of_date"),
        "premium": round(float(premium), 2),
        "premium_currency": policy_record.get("premium_currency"),
        "coverage_limit": policy_record.get("coverage_limit"),
        "policy_status": policy_record.get("status"),
        "expiry_date": policy_record.get("expiry_date"),
        "claim_count": len(claim_lines),
        "claims": claim_lines,
        "incurred_losses": round(incurred_losses, 2),
        "loss_ratio_pct": _pct(loss_ratio),
        "loss_ratio_label": "claims-based loss ratio",
        "expense_ratio_pct": expense_ratio,
        "combined_ratio_pct": combined_ratio,
        "policy": {
            "source": "firm-configured",
            "target_loss_ratio_pct": _pct(target_loss_ratio),
            "note": POLICY_NOTE,
        },
        "flags": [flag] if flagged else [],
        "flag": flag,
        "breach_count": 1 if flagged else 0,
        "notes": notes,
        "client_disclosure": " ".join(notes),
    }
