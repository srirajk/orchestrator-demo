"""Hermetic unit tests for the settlement_risk compute core."""
import math

import pytest

from settlement_risk.compute import (
    DEFAULT_BUYIN_AGE_DAYS,
    SettlementRiskInputError,
    compute_settlement_risk,
    load_policy,
)


SETTLEMENT_STATUS_FAILED = {
    "relationship_id": "REL-00188",
    "pending": [],
    "failed": [
        {
            "trade_id": "T-9844",
            "security": "AMZN",
            "settle_date": "2026-06-20",
            "amount": 185000,
            "side": "sell",
            "reason": "insufficient securities",
        }
    ],
    "as_of_date": "2026-06-22",
}

CUSTODY_POSITIONS = {
    "relationship_id": "REL-00188",
    "holdings_by_custodian": [
        {
            "custodian": "Goldman Sachs",
            "account": "ACC-99012",
            "holdings": [
                {"isin": "US0231351067", "security": "AMZN", "qty": 400, "value": 740000},
            ],
        },
    ],
    "as_of_date": "2026-06-22",
}

CASH_POSITION = {
    "relationship_id": "REL-00188",
    "balances": [
        {"currency": "USD", "settled": 74000, "unsettled": 0, "total": 74000},
    ],
    "projected_cash_usd": 74000,
    "note": "Failed AMZN sell (T-9844) released 185K back to account",
    "as_of_date": "2026-06-22",
}


def _payload(settlements=SETTLEMENT_STATUS_FAILED):
    return {
        "settlement_status": settlements,
        "custody_positions": CUSTODY_POSITIONS,
        "cash_position": CASH_POSITION,
    }


def test_failed_settlement_aging_and_exposure_from_real_shape():
    analysis = compute_settlement_risk(_payload())
    assert analysis["relationship_id"] == "REL-00188"
    assert analysis["failed_count"] == 1
    assert analysis["failed_amount_usd"] == 185000.0
    assert analysis["aging"]["max_failed_age_days"] == 2
    assert analysis["aging"]["failed_buckets"]["1_2_days"]["count"] == 1
    assert analysis["csdr_cash_penalty_exposure"]["exposure_amount_usd"] == 185000.0
    assert analysis["cash_context"]["failed_exposure_to_settled_cash_pct"] == 250.0
    assert analysis["custody_context"]["failed_exposure_to_custody_mv_pct"] == 25.0


def test_flags_are_firm_policy_not_mandatory_buyin():
    analysis = compute_settlement_risk(_payload())
    assert analysis["breach_count"] == 2
    messages = " ".join(flag["message"] for flag in analysis["flags"]).lower()
    notes = " ".join(analysis["notes"]).lower()
    assert "firm" in messages
    assert "mandatory buy-in" in messages
    assert "not activated" in notes
    assert "must not be read" in notes


def test_cash_penalty_dollars_omitted_without_configured_rate():
    analysis = compute_settlement_risk(_payload())
    exposure = analysis["csdr_cash_penalty_exposure"]
    assert exposure["estimated_daily_penalty_usd"] is None
    assert exposure["estimated_accrued_penalty_usd"] is None
    assert any("penalty rate" in note.lower() for note in analysis["notes"])


def test_configured_cash_penalty_rate_computes_simplified_estimate():
    policy = {
        "buyin_age_days": 2,
        "exposure_threshold_usd": 100000.0,
        "csdr_daily_penalty_bps": 1.0,
    }
    analysis = compute_settlement_risk(_payload(), policy)
    exposure = analysis["csdr_cash_penalty_exposure"]
    assert math.isclose(exposure["estimated_daily_penalty_usd"], 18.5, abs_tol=1e-9)
    assert math.isclose(exposure["estimated_accrued_penalty_usd"], 37.0, abs_tol=1e-9)


def test_pending_trade_not_reported_as_failed_exposure():
    settlements = {
        "relationship_id": "REL-00042",
        "pending": [
            {
                "trade_id": "T-9912",
                "security": "MSFT",
                "settle_date": "2026-06-25",
                "amount": 372000,
                "side": "buy",
                "custodian": "BNY Mellon",
                "status": "pending",
            }
        ],
        "failed": [],
        "as_of_date": "2026-06-22",
    }
    analysis = compute_settlement_risk(_payload(settlements))
    assert analysis["pending_amount_usd"] == 372000.0
    assert analysis["failed_amount_usd"] == 0.0
    assert analysis["breach_count"] == 0


def test_missing_producer_payload_fails_safe():
    with pytest.raises(SettlementRiskInputError):
        compute_settlement_risk({
            "settlement_status": SETTLEMENT_STATUS_FAILED,
            "custody_positions": CUSTODY_POSITIONS,
        })


def test_missing_settle_date_fails_safe():
    bad = {
        **SETTLEMENT_STATUS_FAILED,
        "failed": [{k: v for k, v in SETTLEMENT_STATUS_FAILED["failed"][0].items() if k != "settle_date"}],
    }
    with pytest.raises(SettlementRiskInputError):
        compute_settlement_risk(_payload(bad))


def test_negative_trade_amount_fails_safe():
    bad = {
        **SETTLEMENT_STATUS_FAILED,
        "failed": [{**SETTLEMENT_STATUS_FAILED["failed"][0], "amount": -1}],
    }
    with pytest.raises(SettlementRiskInputError):
        compute_settlement_risk(_payload(bad))


def test_env_policy_override(monkeypatch):
    monkeypatch.setenv("SETTLEMENT_BUYIN_AGE_DAYS", "5")
    monkeypatch.setenv("SETTLEMENT_EXPOSURE_THRESHOLD_USD", "250000")
    monkeypatch.setenv("SETTLEMENT_CSDR_DAILY_PENALTY_BPS", "2.5")
    policy = load_policy()
    assert policy["buyin_age_days"] == 5
    assert policy["exposure_threshold_usd"] == 250000.0
    assert policy["csdr_daily_penalty_bps"] == 2.5


def test_default_buyin_age_is_firm_demo_policy():
    assert DEFAULT_BUYIN_AGE_DAYS == 2
