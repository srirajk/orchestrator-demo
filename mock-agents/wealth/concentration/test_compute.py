"""
Hermetic unit tests for the concentration compute core.

Imports the pure compute function directly — no FastAPI, no shared/, no gateway,
no docker. Run from mock-agents/wealth/:

    python3 -m pytest concentration/test_compute.py -v
"""
import math

import pytest

from concentration.compute import (
    compute_concentration,
    load_thresholds,
    ConcentrationInputError,
    DEFAULT_SINGLE_NAME_THRESHOLD,
)

# The real holdings shape (mirrors shared/canned_data.py HOLDINGS["REL-00042"]).
REL_00042 = {
    "relationship_id": "REL-00042",
    "relationship_name": "Whitman Family Office",
    "positions": [
        {"ticker": "AAPL", "isin": "US0378331005", "qty": 1200, "value": 318000},
        {"ticker": "MSFT", "isin": "US5949181045", "qty": 800, "value": 372000},
        {"ticker": "GOOGL", "isin": "US02079K3059", "qty": 150, "value": 289500},
        {"ticker": "JPM", "isin": "US46625H1005", "qty": 2500, "value": 487500},
        {"ticker": "T-BILL-2026", "isin": "US912796YS72", "qty": 1, "value": 500000},
    ],
    "allocation_by_class": [
        {"asset_class": "Equity", "pct": 68},
        {"asset_class": "Fixed Income", "pct": 24},
        {"asset_class": "Cash", "pct": 8},
    ],
    "total_value": 1967000,
    "currency": "USD",
    "as_of_date": "2026-06-22",
}

DEFAULT_THRESHOLDS = {"single_name": 0.10, "asset_class": 0.40, "sector": 0.25}


def test_weights_sum_to_one():
    a = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    # ranked weights are rounded to 6dp for display; sum is ~1.0 within rounding.
    total = sum(r["weight"] for r in a["single_name"]["ranked"])
    assert math.isclose(total, 1.0, abs_tol=1e-5)


def test_top_single_name_correct():
    a = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    # T-BILL-2026 = 500000 / 1967000 = 25.4194%
    top = a["single_name"]["top"]
    assert top["entity"] == "T-BILL-2026"
    assert math.isclose(top["weight_pct"], 25.4194, abs_tol=1e-3)
    # ranked is sorted descending
    pcts = [r["weight_pct"] for r in a["single_name"]["ranked"]]
    assert pcts == sorted(pcts, reverse=True)


def test_hhi_and_effective_positions():
    a = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    hhi = a["diversification"]["hhi"]
    eff = a["diversification"]["effective_number_of_positions"]
    assert 0.0 < hhi <= 1.0
    # effective number of positions == 1 / HHI
    assert math.isclose(eff, 1.0 / hhi, rel_tol=1e-4)
    # 5 positions, all sizeable ⇒ effective between 1 and 5
    assert 1.0 <= eff <= 5.0
    # manual HHI check
    weights = [p["value"] / 1967000 for p in REL_00042["positions"]]
    assert math.isclose(hhi, sum(w * w for w in weights), abs_tol=1e-5)


def test_breach_flag_over_and_under_threshold():
    # Controlled payload: weights 50 / 30 / 15 / 5 percent, threshold 10%.
    payload = {
        "relationship_id": "REL-TEST",
        "positions": [
            {"ticker": "BIG", "value": 50},
            {"ticker": "MID", "value": 30},
            {"ticker": "SML", "value": 15},
            {"ticker": "TINY", "value": 5},
        ],
    }
    a = compute_concentration(payload, DEFAULT_THRESHOLDS)
    breached = {f["entity"] for f in a["flags"] if f["type"] == "single_name"}
    assert "BIG" in breached     # 50% > 10%
    assert "MID" in breached     # 30% > 10%
    assert "SML" in breached     # 15% > 10%
    assert "TINY" not in breached  # 5% < 10% ⇒ no flag
    assert a["breach_count"] == 3
    # every flag is labelled firm policy, with the configured threshold
    for f in a["flags"]:
        assert f["policy"] == "firm-configured"
        assert math.isclose(f["threshold_pct"], 10.0, abs_tol=1e-6)


def test_asset_class_concentration_from_allocation():
    a = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    assert a["asset_class"] is not None
    assert a["asset_class"]["top"]["asset_class"] == "Equity"
    assert math.isclose(a["asset_class"]["top"]["weight_pct"], 68.0, abs_tol=1e-6)
    # Equity 68% > 40% firm asset-class limit ⇒ an asset_class breach flag
    ac_breaches = {f["entity"] for f in a["flags"] if f["type"] == "asset_class"}
    assert "Equity" in ac_breaches


def test_sector_omitted_note():
    a = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    assert a["sector"] is None
    assert any("sector" in n.lower() for n in a["notes"])


def test_empty_positions_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": []})


def test_malformed_position_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": [{"ticker": "X"}]})  # no value


def test_env_threshold_override(monkeypatch):
    monkeypatch.setenv("CONCENTRATION_SINGLE_NAME_THRESHOLD", "0.30")
    t = load_thresholds()
    assert math.isclose(t["single_name"], 0.30, abs_tol=1e-9)
    # percent-style value is tolerated (30 ⇒ 0.30)
    monkeypatch.setenv("CONCENTRATION_SINGLE_NAME_THRESHOLD", "30")
    assert math.isclose(load_thresholds()["single_name"], 0.30, abs_tol=1e-9)


def test_default_threshold_is_ten_percent():
    assert math.isclose(DEFAULT_SINGLE_NAME_THRESHOLD, 0.10, abs_tol=1e-9)


def test_risk_profile_annotation_optional():
    payload = dict(REL_00042)
    payload["risk_profile"] = {"risk_tolerance": "Moderate", "risk_score": 6}
    a = compute_concentration(payload, DEFAULT_THRESHOLDS)
    assert a["risk_context"]["risk_tolerance"] == "Moderate"
    # absence is fine too
    a2 = compute_concentration(REL_00042, DEFAULT_THRESHOLDS)
    assert a2["risk_context"] is None
