"""
Pure settlement-risk math for the Asset Servicing Settlement Risk agent.

This module has ZERO MCP / shared / OTel dependencies on purpose: it is the
hermetic compute core that both the MCP tool and unit tests import directly. It
is a 3-PRODUCER FAN-IN over the gateway blackboard payload:
``{"settlement_status": ..., "custody_positions": ..., "cash_position": ...}``.

DOMAIN TRUTH (docs/DOMAIN-KNOWLEDGE-VERIFIED.md):
  - CSDR cash penalties are live since 1 Feb 2022.
  - Mandatory buy-in is NOT activated under the CSDR Refit, so any aging trigger
    here is firm policy only, never a regulatory buy-in statement.
  - The real servicing data carries settlement dates and amounts, but no CSDR
    penalty rate/category. We therefore report failed-settlement exposure basis
    and compute dollar penalties only when a firm-configured daily bps rate is
    supplied by env/config.
"""
from __future__ import annotations

import math
import os
from datetime import date
from typing import Any, Optional


DEFAULT_BUYIN_AGE_DAYS = 2
DEFAULT_EXPOSURE_THRESHOLD_USD = 100_000.0

POLICY_NOTE = (
    "Aging and exposure flags are firm-configured policy, not a regulatory or "
    "industry-standard cutoff. CSDR mandatory buy-in is not activated under the "
    "CSDR Refit; any buy-in-age flag is firm-discretionary."
)

CSDR_NOTE = (
    "CSDR cash penalties are live since 1 Feb 2022. Mandatory buy-in is not "
    "activated under the CSDR Refit, so this analysis must not be read as a "
    "mandatory buy-in determination."
)


class SettlementRiskInputError(ValueError):
    """Raised when the fan-in payload is missing or malformed."""


def _env_float(name: str, default: Optional[float]) -> Optional[float]:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        val = float(raw)
    except (TypeError, ValueError):
        return default
    return val if math.isfinite(val) and val >= 0 else default


def _env_int(name: str, default: int) -> int:
    val = _env_float(name, float(default))
    if val is None or val <= 0:
        return default
    return int(val)


def load_policy() -> dict[str, Optional[float] | int]:
    """Read firm-configured settlement-risk policy from the environment."""
    return {
        "buyin_age_days": _env_int("SETTLEMENT_BUYIN_AGE_DAYS", DEFAULT_BUYIN_AGE_DAYS),
        "exposure_threshold_usd": _env_float(
            "SETTLEMENT_EXPOSURE_THRESHOLD_USD",
            DEFAULT_EXPOSURE_THRESHOLD_USD,
        ),
        "csdr_daily_penalty_bps": _env_float("SETTLEMENT_CSDR_DAILY_PENALTY_BPS", None),
    }


def _parse_date(value: Any, field: str) -> date:
    if not isinstance(value, str) or not value.strip():
        raise SettlementRiskInputError(f"missing or non-string '{field}' date")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise SettlementRiskInputError(f"invalid ISO date for '{field}': {value!r}") from exc


def _amount(value: Any, field: str, trade_id: Any = None) -> float:
    if value is None or isinstance(value, bool) or not isinstance(value, (int, float)):
        raise SettlementRiskInputError(
            f"trade {trade_id!r} has a missing or non-numeric '{field}'"
        )
    if not math.isfinite(value):
        raise SettlementRiskInputError(
            f"trade {trade_id!r} has a non-finite '{field}' (NaN/inf)"
        )
    if value < 0:
        raise SettlementRiskInputError(f"trade {trade_id!r} has a negative '{field}'")
    return float(value)


def _age_days(trade: dict[str, Any], as_of: date) -> int:
    settle_date = _parse_date(trade.get("settle_date"), "settle_date")
    return max(0, (as_of - settle_date).days)


def _bucket(age: int) -> str:
    if age <= 0:
        return "not_due_or_due_today"
    if age <= 2:
        return "1_2_days"
    if age <= 5:
        return "3_5_days"
    return "over_5_days"


def _cash_usd(cash_position: dict[str, Any]) -> dict[str, float]:
    settled = 0.0
    unsettled = 0.0
    for bal in cash_position.get("balances") or []:
        if not isinstance(bal, dict) or bal.get("currency") != "USD":
            continue
        settled += _amount(bal.get("settled"), "settled", "cash_balance")
        unsettled += _amount(bal.get("unsettled"), "unsettled", "cash_balance")
    projected = cash_position.get("projected_cash_usd")
    projected_usd = _amount(projected, "projected_cash_usd", "cash_position")
    return {"settled": settled, "unsettled": unsettled, "projected": projected_usd}


def _custody_value(custody_positions: dict[str, Any]) -> float:
    total = 0.0
    for custodian in custody_positions.get("holdings_by_custodian") or []:
        if not isinstance(custodian, dict):
            continue
        for holding in custodian.get("holdings") or []:
            if isinstance(holding, dict):
                total += _amount(holding.get("value"), "value", holding.get("security"))
    return total


def _trade_line(trade: dict[str, Any], as_of: date) -> dict[str, Any]:
    trade_id = trade.get("trade_id")
    amount = _amount(trade.get("amount"), "amount", trade_id)
    age = _age_days(trade, as_of)
    return {
        "trade_id": trade_id,
        "security": trade.get("security"),
        "isin": trade.get("isin"),
        "side": trade.get("side"),
        "status": trade.get("status", "failed" if trade.get("reason") else "pending"),
        "settle_date": trade.get("settle_date"),
        "amount": round(amount, 2),
        "age_days": age,
        "age_bucket": _bucket(age),
        "reason": trade.get("reason"),
    }


def compute_settlement_risk(
    payload: dict[str, Any],
    policy: Optional[dict[str, Optional[float] | int]] = None,
) -> dict[str, Any]:
    """
    Compute settlement-risk analysis from merged settlement/custody/cash outputs.

    Raises:
        SettlementRiskInputError: if any required producer payload or required
        numeric/date field is absent or malformed.
    """
    if policy is None:
        policy = load_policy()

    if not isinstance(payload, dict):
        raise SettlementRiskInputError("settlement_risk payload must be a JSON object")

    settlement_status = payload.get("settlement_status")
    custody_positions = payload.get("custody_positions")
    cash_position = payload.get("cash_position")
    if not isinstance(settlement_status, dict) or not settlement_status:
        raise SettlementRiskInputError("missing 'settlement_status' producer payload")
    if not isinstance(custody_positions, dict) or not custody_positions:
        raise SettlementRiskInputError("missing 'custody_positions' producer payload")
    if not isinstance(cash_position, dict) or not cash_position:
        raise SettlementRiskInputError("missing 'cash_position' producer payload")

    as_of = _parse_date(settlement_status.get("as_of_date"), "as_of_date")
    pending_raw = settlement_status.get("pending")
    failed_raw = settlement_status.get("failed")
    if not isinstance(pending_raw, list):
        raise SettlementRiskInputError("'settlement_status.pending' must be a list")
    if not isinstance(failed_raw, list):
        raise SettlementRiskInputError("'settlement_status.failed' must be a list")

    pending = [_trade_line(t, as_of) for t in pending_raw if isinstance(t, dict)]
    failed = [_trade_line(t, as_of) for t in failed_raw if isinstance(t, dict)]
    failed_amount = sum(t["amount"] for t in failed)
    pending_amount = sum(t["amount"] for t in pending)
    max_failed_age = max((t["age_days"] for t in failed), default=0)

    buckets: dict[str, dict[str, float | int]] = {
        "not_due_or_due_today": {"count": 0, "amount": 0.0},
        "1_2_days": {"count": 0, "amount": 0.0},
        "3_5_days": {"count": 0, "amount": 0.0},
        "over_5_days": {"count": 0, "amount": 0.0},
    }
    for trade in failed:
        bucket = buckets[trade["age_bucket"]]
        bucket["count"] = int(bucket["count"]) + 1
        bucket["amount"] = round(float(bucket["amount"]) + trade["amount"], 2)

    cash = _cash_usd(cash_position)
    custody_market_value = _custody_value(custody_positions)

    daily_bps = policy.get("csdr_daily_penalty_bps")
    estimated_daily_penalty = None
    estimated_accrued_penalty = None
    if isinstance(daily_bps, (int, float)) and math.isfinite(daily_bps) and daily_bps > 0:
        estimated_daily_penalty = failed_amount * float(daily_bps) / 10_000.0
        estimated_accrued_penalty = sum(
            t["amount"] * max(1, t["age_days"]) * float(daily_bps) / 10_000.0
            for t in failed
        )

    buyin_age_days = int(policy["buyin_age_days"])
    exposure_threshold = float(policy["exposure_threshold_usd"] or 0.0)
    flags: list[dict[str, Any]] = []
    if failed and max_failed_age >= buyin_age_days:
        flags.append({
            "type": "failed_settlement_age",
            "observed_days": max_failed_age,
            "threshold_days": buyin_age_days,
            "breached": True,
            "policy": "firm-configured",
            "message": (
                f"Oldest failed settlement is {max_failed_age} day(s), at or above "
                f"the firm-configured review age of {buyin_age_days} day(s). "
                "This is firm policy, not an activated CSDR mandatory buy-in trigger."
            ),
        })
    if failed_amount >= exposure_threshold > 0:
        flags.append({
            "type": "failed_settlement_exposure",
            "observed_usd": round(failed_amount, 2),
            "threshold_usd": round(exposure_threshold, 2),
            "breached": True,
            "policy": "firm-configured",
            "message": (
                f"Failed settlement exposure is ${failed_amount:,.2f}, at or above "
                f"the firm-configured exposure threshold of ${exposure_threshold:,.2f}."
            ),
        })

    notes = [
        CSDR_NOTE,
        POLICY_NOTE,
        "Aging is computed as max(0, as_of_date - settle_date) using settlement_status dates.",
        "CSDR exposure basis uses failed settlement amount only; pending settlements are listed separately.",
    ]
    if estimated_daily_penalty is None:
        notes.append(
            "Dollar CSDR cash-penalty estimate omitted — servicing data has trade amounts "
            "but no CSDR penalty rate/category, and SETTLEMENT_CSDR_DAILY_PENALTY_BPS is not configured."
        )
    else:
        notes.append(
            "Dollar CSDR cash-penalty estimate uses firm-configured "
            "SETTLEMENT_CSDR_DAILY_PENALTY_BPS; it is a simplified demo estimate."
        )

    return {
        "analysis": "settlement_risk",
        "relationship_id": settlement_status.get("relationship_id"),
        "as_of_date": settlement_status.get("as_of_date"),
        "pending_count": len(pending),
        "failed_count": len(failed),
        "pending_amount_usd": round(pending_amount, 2),
        "failed_amount_usd": round(failed_amount, 2),
        "failed_trades": failed,
        "pending_trades": pending,
        "aging": {
            "max_failed_age_days": max_failed_age,
            "failed_buckets": buckets,
        },
        "csdr_cash_penalty_exposure": {
            "basis": "failed_settlement_amount",
            "exposure_amount_usd": round(failed_amount, 2),
            "daily_penalty_bps": daily_bps,
            "estimated_daily_penalty_usd": (
                round(estimated_daily_penalty, 2) if estimated_daily_penalty is not None else None
            ),
            "estimated_accrued_penalty_usd": (
                round(estimated_accrued_penalty, 2) if estimated_accrued_penalty is not None else None
            ),
        },
        "cash_context": {
            "settled_usd": round(cash["settled"], 2),
            "unsettled_usd": round(cash["unsettled"], 2),
            "projected_cash_usd": round(cash["projected"], 2),
            "failed_exposure_to_settled_cash_pct": (
                round(failed_amount / cash["settled"] * 100.0, 4) if cash["settled"] > 0 else None
            ),
        },
        "custody_context": {
            "custody_market_value_usd": round(custody_market_value, 2),
            "failed_exposure_to_custody_mv_pct": (
                round(failed_amount / custody_market_value * 100.0, 4)
                if custody_market_value > 0 else None
            ),
        },
        "policy": {
            "source": "firm-configured",
            "buyin_age_days": buyin_age_days,
            "exposure_threshold_usd": round(exposure_threshold, 2),
            "csdr_daily_penalty_bps": daily_bps,
            "note": POLICY_NOTE,
        },
        "flags": flags,
        "breach_count": len(flags),
        "notes": notes,
    }
