"""Deterministic per-item settlement penalty tool for gateway map-iteration tests."""
from __future__ import annotations

import json
import os
from datetime import date

from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "meridian.servicing.trade_penalty"


def _daily_penalty_bps() -> float:
    raw = os.environ.get("SETTLEMENT_CSDR_DAILY_PENALTY_BPS")
    if raw is None or raw == "":
        raise RuntimeError("SETTLEMENT_CSDR_DAILY_PENALTY_BPS is not configured")
    return float(raw)


def _age_days(settle_date: str, as_of_date: str) -> int:
    start = date.fromisoformat(settle_date)
    end = date.fromisoformat(as_of_date)
    return max(0, (end - start).days)


def calculate_trade_penalty(
    trade_id: str,
    security: str,
    settle_date: str,
    amount: float,
    side: str,
    as_of_date: str,
    isin: str = "",
    reason: str = "",
    fail_item: bool | None = False,
) -> str:
    """
    Compute CSDR-style daily cash penalty exposure for one failed settlement.

    Mandatory buy-in is intentionally not activated; this demo reports only cash-penalty exposure.
    """
    with agent_span(AGENT_ID, trade_id) as span:
        maybe_fault("calculate_trade_penalty")
        if bool(fail_item):
            span.set_attribute("error", True)
            raise RuntimeError(f"Injected per-item failure for trade {trade_id}.")
        try:
            bps = _daily_penalty_bps()
            age = _age_days(settle_date, as_of_date)
            daily = round(float(amount) * (bps / 10_000), 2)
            accrued = round(daily * age, 2)
            span.set_attribute("trade_id", trade_id)
            span.set_attribute("age_days", age)
            span.set_attribute("daily_penalty_usd", daily)
            return json.dumps({
                "trade_id": trade_id,
                "security": security,
                "isin": isin,
                "settle_date": settle_date,
                "as_of_date": as_of_date,
                "amount": amount,
                "side": side,
                "reason": reason,
                "age_days": age,
                "csdr_daily_penalty_bps": bps,
                "estimated_daily_penalty_usd": daily,
                "estimated_accrued_penalty_usd": accrued,
                "mandatory_buy_in_active": False,
                "agent_narrative": (
                    f"{trade_id} is {age} day(s) past contractual settlement; "
                    f"firm-configured daily cash penalty rate is {bps} bps. "
                    "Mandatory buy-in is not activated."
                ),
            })
        except Exception as exc:
            span.set_attribute("error", True)
            raise RuntimeError(f"penalty_calculation_failed: {exc}") from exc
