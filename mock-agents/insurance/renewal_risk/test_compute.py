"""
Hermetic unit tests for the renewal_risk compute core.

Imports the pure compute function directly — no FastAPI, no shared/, no
gateway, no docker. Run from mock-agents/insurance/:

    python3 -m pytest renewal_risk/test_compute.py -v
"""
import math

import pytest

from renewal_risk.compute import (
    compute_renewal_risk,
    load_target_loss_ratio,
    RenewalRiskInputError,
    DEFAULT_RENEWAL_TARGET_LOSS_RATIO,
)

# The real policy_details / claim_status shapes (mirrors
# shared/canned_data.py POLICIES["POL-77001"] / claims_for_policy("POL-77001")).
POLICY_77001 = {
    "policy_id": "POL-77001",
    "policy_name": "Continental Freight Liability",
    "line_of_business": "Commercial Auto Liability",
    "insured_name": "Continental Freight Inc.",
    "premium": 48500,
    "premium_currency": "USD",
    "coverage_limit": 5000000,
    "deductible": 25000,
    "status": "active",
    "effective_date": "2026-01-01",
    "expiry_date": "2026-12-31",
    "as_of_date": "2026-06-22",
}

# claim_status output when queried by policy_id — the "list" shape.
CLAIM_STATUS_LIST_77001 = {
    "policy_id": "POL-77001",
    "claim_count": 1,
    "claims": [
        {
            "claim_id": "CLM-5501",
            "policy_id": "POL-77001",
            "claimant": "Continental Freight Inc.",
            "amount": 240000,
            "amount_currency": "USD",
            "status": "under-review",
            "incident_date": "2026-05-14",
            "reported_date": "2026-05-16",
            "adjuster": "Dana Reyes",
            "as_of_date": "2026-06-22",
        }
    ],
}

# claim_status output when queried by claim_id directly — the "single" shape.
CLAIM_STATUS_SINGLE_5501 = CLAIM_STATUS_LIST_77001["claims"][0]

DEFAULT_TARGET = 0.60


def test_loss_ratio_from_real_shapes_list_form():
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    # 240000 / 48500 = 494.845...%
    assert math.isclose(a["loss_ratio_pct"], 494.8454, abs_tol=1e-3)
    assert a["incurred_losses"] == 240000.0
    assert a["premium"] == 48500.0
    assert a["policy_id"] == "POL-77001"
    assert a["claim_count"] == 1
    assert a["loss_ratio_label"] == "claims-based loss ratio"
    assert "claims-based loss ratio" in a["client_disclosure"].lower()


def test_loss_ratio_from_real_shapes_single_form():
    """The single-claim shape (claim_status queried by claim_id) normalises identically."""
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_SINGLE_5501}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert math.isclose(a["loss_ratio_pct"], 494.8454, abs_tol=1e-3)
    assert a["claim_count"] == 1


def test_flag_breached_above_target():
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert a["flag"]["breached"] is True
    assert a["breach_count"] == 1
    assert a["flag"]["policy"] == "firm-configured"
    assert "Claims-based loss ratio" in a["flag"]["message"]
    assert "firm policy" in a["flag"]["message"]
    assert "industry-standard" in a["flag"]["message"] or "standard" in a["flag"]["message"]


def test_flag_not_breached_below_target():
    policy = dict(POLICY_77001, premium=1_000_000)  # loss ratio 24% < 60% target
    payload = {"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert a["flag"]["breached"] is False
    assert a["breach_count"] == 0
    assert a["flags"] == []


def test_no_claims_is_valid_zero_loss_ratio():
    payload = {
        "policy_record": POLICY_77001,
        "claim_status": {"policy_id": "POL-77001", "claim_count": 0, "claims": []},
    }
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert a["incurred_losses"] == 0.0
    assert a["loss_ratio_pct"] == 0.0
    assert a["claim_count"] == 0
    assert any("no open claims" in n.lower() for n in a["notes"])


def test_missing_claim_status_fails_safe():
    """claim_status absent entirely (claims feed down / edge unbound) MUST fail safe —
    never reported as a favorable 0% loss ratio (Opus review S1). Present-but-empty is a
    genuine zero (test_no_claims_is_valid_zero_loss_ratio); ABSENT is an error."""
    payload = {"policy_record": POLICY_77001}
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk(payload, DEFAULT_TARGET)


def test_combined_ratio_omitted_with_note():
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert a["combined_ratio_pct"] is None
    assert a["expense_ratio_pct"] is None
    assert any("combined ratio omitted" in n.lower() for n in a["notes"])


def test_lae_note_present():
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    assert any("lae" in n.lower() for n in a["notes"])


def test_client_disclosure_includes_premium_lae_and_status_notes():
    payload = {"policy_record": POLICY_77001, "claim_status": CLAIM_STATUS_LIST_77001}
    a = compute_renewal_risk(payload, DEFAULT_TARGET)
    disclosure = a["client_disclosure"].lower()
    assert "claims-based loss ratio" in disclosure
    assert "earned-premium basis" in disclosure
    assert "loss-only" in disclosure
    assert "full claimed value" in disclosure
    assert "combined ratio omitted" in disclosure


def test_missing_policy_record_raises():
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"claim_status": CLAIM_STATUS_LIST_77001})


def test_missing_premium_raises():
    policy = {k: v for k, v in POLICY_77001.items() if k != "premium"}
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001})


def test_zero_premium_raises():
    policy = dict(POLICY_77001, premium=0)
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001})


def test_negative_premium_raises():
    policy = dict(POLICY_77001, premium=-100)
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001})


def test_non_numeric_premium_raises():
    policy = dict(POLICY_77001, premium="a lot")
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001})


def test_non_finite_premium_raises():
    policy = dict(POLICY_77001, premium=float("nan"))
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy, "claim_status": CLAIM_STATUS_LIST_77001})
    policy_inf = dict(POLICY_77001, premium=float("inf"))
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": policy_inf, "claim_status": CLAIM_STATUS_LIST_77001})


def test_malformed_claim_amount_raises():
    bad = {
        "policy_id": "POL-77001",
        "claim_count": 1,
        "claims": [{"claim_id": "CLM-9999", "status": "open"}],  # no amount
    }
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": POLICY_77001, "claim_status": bad})


def test_negative_claim_amount_raises():
    bad = {
        "policy_id": "POL-77001",
        "claim_count": 1,
        "claims": [{"claim_id": "CLM-9999", "amount": -500, "status": "open"}],
    }
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk({"policy_record": POLICY_77001, "claim_status": bad})


def test_multiple_claims_sum_incurred_losses():
    two_claims = {
        "policy_id": "POL-77002",
        "claim_count": 2,
        "claims": [
            {"claim_id": "CLM-A", "amount": 100000, "status": "approved"},
            {"claim_id": "CLM-B", "amount": 50000, "status": "under-review"},
        ],
    }
    policy = dict(POLICY_77001, policy_id="POL-77002", premium=72000)
    a = compute_renewal_risk({"policy_record": policy, "claim_status": two_claims}, DEFAULT_TARGET)
    assert a["incurred_losses"] == 150000.0
    assert a["claim_count"] == 2
    # 150000 / 72000 = 208.333...%
    assert math.isclose(a["loss_ratio_pct"], 208.3333, abs_tol=1e-3)


def test_env_threshold_override(monkeypatch):
    monkeypatch.setenv("RENEWAL_TARGET_LOSS_RATIO", "0.75")
    assert math.isclose(load_target_loss_ratio(), 0.75, abs_tol=1e-9)
    # percent-style value is tolerated (75 -> 0.75)
    monkeypatch.setenv("RENEWAL_TARGET_LOSS_RATIO", "75")
    assert math.isclose(load_target_loss_ratio(), 0.75, abs_tol=1e-9)


def test_default_target_is_sixty_percent():
    assert math.isclose(DEFAULT_RENEWAL_TARGET_LOSS_RATIO, 0.60, abs_tol=1e-9)


def test_non_dict_payload_raises():
    with pytest.raises(RenewalRiskInputError):
        compute_renewal_risk("not a dict")  # type: ignore[arg-type]
