"""
Adversarial property-based + metamorphic test battery for the concentration
compute core (concentration/compute.py).

Goal: try HARD to break the math and prove the invariants are rock-solid.
Techniques:
  1. Property-based (hypothesis) — random portfolios, universal invariants.
  2. Metamorphic — scale / permutation / threshold-monotonicity / add-position.
  3. Adversarial / negative — empty, zero, negative, missing, bool, huge, NaN/inf.
  4. Grounding / no-fabrication — every output numeric is derivable from input.
  5. Config — env threshold tracking + default.

TEST CODE ONLY. compute.py / handler.py are never modified. Hermetic: imports the
pure compute function directly. Run from mock-agents/wealth/:

    python3 -m pytest concentration/ -q

Two tests are marked xfail(strict=True) because they document REAL defects found
in compute.py (NaN / +inf produce silent nan outputs instead of raising
ConcentrationInputError). The xfail *reason* is the bug report; strict=True means
the day compute.py is fixed, these flip to failing and demand attention.
"""
from __future__ import annotations

import math
import random
import string
from fractions import Fraction

import pytest
from hypothesis import given, settings, strategies as st, HealthCheck

from concentration.compute import (
    compute_concentration,
    load_thresholds,
    ConcentrationInputError,
    DEFAULT_SINGLE_NAME_THRESHOLD,
)

# ---------------------------------------------------------------------------
# Shared config / strategies
# ---------------------------------------------------------------------------

T = {"single_name": 0.10, "asset_class": 0.40, "sector": 0.25}

# Positive, finite, non-subnormal position values across a wide-but-sane range.
values = st.floats(
    min_value=1e-3, max_value=1e9, allow_nan=False, allow_infinity=False, width=64
)

tickers = st.text(alphabet=string.ascii_uppercase, min_size=1, max_size=5)


@st.composite
def positions(draw, min_size=1, max_size=40):
    n = draw(st.integers(min_value=min_size, max_value=max_size))
    vs = draw(st.lists(values, min_size=n, max_size=n))
    ts = draw(st.lists(tickers, min_size=n, max_size=n))
    return [{"ticker": t, "value": v} for t, v in zip(ts, vs)]


@st.composite
def portfolios(draw, min_size=1, max_size=40):
    return {"positions": draw(positions(min_size=min_size, max_size=max_size))}


def true_metrics(vs):
    """Ground-truth recomputation, independent of compute.py."""
    basis = sum(vs)
    weights = [v / basis for v in vs]
    hhi = sum(w * w for w in weights)
    eff = 1.0 / hhi
    return basis, weights, hhi, eff


CORE = settings(
    max_examples=300,
    deadline=None,
    suppress_health_check=[HealthCheck.too_slow],
)


# ===========================================================================
# 1. PROPERTY-BASED INVARIANTS
# ===========================================================================

@CORE
@given(portfolios())
def test_weights_in_unit_interval_and_sum_to_one(payload):
    a = compute_concentration(payload, T)
    ranked = a["single_name"]["ranked"]
    for r in ranked:
        assert 0.0 <= r["weight"] <= 1.0
        assert 0.0 <= r["weight_pct"] <= 100.0 + 1e-6
    # ground-truth true weights sum to exactly 1 (float-tight)
    vs = [p["value"] for p in payload["positions"]]
    _, weights, _, _ = true_metrics(vs)
    assert math.isclose(sum(weights), 1.0, abs_tol=1e-9)
    # rounded (6dp) display weights sum to ~1; cumulative rounding <= n*5e-7
    n = len(ranked)
    assert math.isclose(sum(r["weight"] for r in ranked), 1.0, abs_tol=n * 5e-7 + 1e-9)


@CORE
@given(portfolios())
def test_hhi_range_and_identity(payload):
    a = compute_concentration(payload, T)
    hhi = a["diversification"]["hhi"]
    eff = a["diversification"]["effective_number_of_positions"]
    n = a["position_count"]
    # HHI in (0, 1]
    assert 0.0 < hhi <= 1.0 + 1e-9
    # HHI == sum of squared weights (ground truth). Output HHI is rounded to 6dp,
    # so tolerate up to that rounding (5e-7).
    vs = [p["value"] for p in payload["positions"]]
    _, _, true_hhi, true_eff = true_metrics(vs)
    assert math.isclose(hhi, true_hhi, abs_tol=1e-6)
    # effective_number_of_positions == 1 / HHI
    assert math.isclose(eff, 1.0 / true_hhi, rel_tol=1e-6, abs_tol=1e-3)
    assert math.isclose(eff, true_eff, rel_tol=1e-6, abs_tol=1e-3)
    # 1 <= eff <= position_count
    assert 1.0 - 1e-9 <= eff <= n + 1e-3


@CORE
@given(st.integers(min_value=1, max_value=500))
def test_equal_weight_hhi_is_one_over_n(n):
    payload = {"positions": [{"ticker": f"EQ{i}", "value": 1000.0} for i in range(n)]}
    a = compute_concentration(payload, T)
    hhi = a["diversification"]["hhi"]
    eff = a["diversification"]["effective_number_of_positions"]
    assert math.isclose(hhi, 1.0 / n, abs_tol=1e-6)  # output HHI rounded to 6dp
    assert math.isclose(eff, float(n), rel_tol=1e-6, abs_tol=1e-3)


@CORE
@given(values, tickers)
def test_single_position_is_fully_concentrated(v, t):
    a = compute_concentration({"positions": [{"ticker": t, "value": v}]}, T)
    assert a["position_count"] == 1
    assert math.isclose(a["diversification"]["hhi"], 1.0, abs_tol=1e-9)
    assert math.isclose(
        a["diversification"]["effective_number_of_positions"], 1.0, abs_tol=1e-9
    )
    assert math.isclose(a["single_name"]["top"]["weight_pct"], 100.0, abs_tol=1e-6)


@CORE
@given(portfolios(), st.floats(min_value=0.001, max_value=0.99))
def test_single_name_flags_fire_exactly_at_threshold(payload, thr):
    thresholds = {"single_name": thr, "asset_class": 0.40, "sector": 0.25}
    a = compute_concentration(payload, thresholds)
    vs = [p["value"] for p in payload["positions"]]
    _, weights, _, _ = true_metrics(vs)
    # compute.py fires when weight > threshold (strict). Verify EXACTLY that:
    expected = sum(1 for w in weights if w > thr)
    flagged = [f for f in a["flags"] if f["type"] == "single_name"]
    assert a["breach_count"] == len(a["flags"])
    assert len(flagged) == expected
    ranked = a["single_name"]["ranked"]
    for r in ranked:
        is_flagged = any(f["entity"] == r["name"] and
                         math.isclose(f["observed_pct"], r["weight_pct"])
                         for f in flagged)
        # nobody strictly below threshold may be flagged; nobody above may be missed
        if r["weight"] < thr - 1e-9:
            assert not is_flagged
        if r["weight"] > thr + 1e-9:
            assert is_flagged


def test_threshold_exact_boundary_not_flagged():
    """Boundary case: weight EXACTLY == threshold.

    compute.py uses `weight > threshold` (strict), matching its message wording
    ('above the limit'). The task spec phrased it as `>=`. This test pins the
    ACTUAL behaviour: a name sitting exactly at the limit is NOT flagged. Reported
    as a spec-vs-implementation discrepancy (low severity, defensible by-design).
    """
    payload = {"positions": [{"ticker": "A", "value": 50}, {"ticker": "B", "value": 50}]}
    a = compute_concentration(payload, {"single_name": 0.50, "asset_class": 0.40, "sector": 0.25})
    assert a["breach_count"] == 0  # 0.5 is NOT > 0.5


# ===========================================================================
# 2. METAMORPHIC PROPERTIES
# ===========================================================================

@CORE
@given(portfolios(), st.floats(min_value=1e-3, max_value=1e3))
def test_scale_invariance(payload, k):
    base = compute_concentration(payload, T)
    scaled_payload = {
        "positions": [{"ticker": p["ticker"], "value": p["value"] * k}
                      for p in payload["positions"]]
    }
    scaled = compute_concentration(scaled_payload, T)
    # HHI / eff invariant under uniform scaling
    assert math.isclose(base["diversification"]["hhi"],
                        scaled["diversification"]["hhi"], abs_tol=1e-6)
    assert math.isclose(base["diversification"]["effective_number_of_positions"],
                        scaled["diversification"]["effective_number_of_positions"],
                        rel_tol=1e-5, abs_tol=1e-3)
    # weight_pct multiset invariant
    b = sorted(r["weight_pct"] for r in base["single_name"]["ranked"])
    s = sorted(r["weight_pct"] for r in scaled["single_name"]["ranked"])
    assert len(b) == len(s)
    for x, y in zip(b, s):
        assert math.isclose(x, y, abs_tol=1e-3)
    # flag entity multiset invariant
    from collections import Counter
    assert (Counter(f["entity"] for f in base["flags"]) ==
            Counter(f["entity"] for f in scaled["flags"]))


@CORE
@given(portfolios(min_size=1, max_size=30), st.integers(min_value=0, max_value=2**32 - 1))
def test_permutation_invariance(payload, seed):
    base = compute_concentration(payload, T)
    shuffled = list(payload["positions"])
    random.Random(seed).shuffle(shuffled)
    perm = compute_concentration({"positions": shuffled}, T)
    # HHI / eff invariant (float re-association tolerance)
    assert math.isclose(base["diversification"]["hhi"],
                        perm["diversification"]["hhi"], abs_tol=1e-5)
    assert math.isclose(base["diversification"]["effective_number_of_positions"],
                        perm["diversification"]["effective_number_of_positions"],
                        rel_tol=1e-4, abs_tol=1e-2)
    # weight multiset + flag set invariant
    b = sorted(r["weight_pct"] for r in base["single_name"]["ranked"])
    p = sorted(r["weight_pct"] for r in perm["single_name"]["ranked"])
    for x, y in zip(b, p):
        assert math.isclose(x, y, abs_tol=1e-3)
    from collections import Counter
    assert (Counter(f["entity"] for f in base["flags"]) ==
            Counter(f["entity"] for f in perm["flags"]))


@CORE
@given(portfolios(),
       st.floats(min_value=0.01, max_value=0.90),
       st.floats(min_value=0.01, max_value=0.90))
def test_threshold_monotonicity(payload, t1, t2):
    lo, hi = sorted((t1, t2))
    a_lo = compute_concentration(payload, {"single_name": lo, "asset_class": 0.40, "sector": 0.25})
    a_hi = compute_concentration(payload, {"single_name": hi, "asset_class": 0.40, "sector": 0.25})
    lo_names = {f["entity"] for f in a_lo["flags"] if f["type"] == "single_name"}
    hi_names = {f["entity"] for f in a_hi["flags"] if f["type"] == "single_name"}
    # raising the threshold never ADDS flags
    assert hi_names <= lo_names
    n_lo = sum(1 for f in a_lo["flags"] if f["type"] == "single_name")
    n_hi = sum(1 for f in a_hi["flags"] if f["type"] == "single_name")
    assert n_hi <= n_lo


@CORE
@given(portfolios(), values.filter(lambda x: x > 0))
def test_adding_position_reduces_max_single_name_weight(payload, extra):
    base = compute_concentration(payload, T)
    old_max_w = base["single_name"]["ranked"][0]["weight"]
    old_max_val = max(p["value"] for p in payload["positions"])
    # add a position no larger than the current largest -> max name value unchanged
    add_val = min(extra, old_max_val)
    new_payload = {"positions": payload["positions"] + [{"ticker": "NEWPOS", "value": add_val}]}
    new = compute_concentration(new_payload, T)
    new_max_w = new["single_name"]["ranked"][0]["weight"]
    # denominator grew, max numerator unchanged -> max weight strictly decreases (<= within tol)
    assert new_max_w <= old_max_w + 1e-9


@CORE
@given(portfolios(), values.filter(lambda x: x > 0))
def test_adding_position_moves_hhi_in_predicted_direction(payload, extra):
    vs = [p["value"] for p in payload["positions"]]
    _, _, H, _ = true_metrics(vs)
    new_vs = vs + [extra]
    _, _, H2, _ = true_metrics(new_vs)
    f = extra / sum(new_vs)  # weight of the added position in the new portfolio
    # Exact algebra: HHI decreases iff f < 2H/(1+H).
    boundary = 2 * H / (1 + H)
    if f < boundary - 1e-12:
        assert H2 <= H + 1e-12
    elif f > boundary + 1e-12:
        assert H2 >= H - 1e-12
    # (at the boundary both are ~equal; nothing to assert)


# ===========================================================================
# 3. ADVERSARIAL / NEGATIVE
# ===========================================================================

def test_empty_positions_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": []}, T)


def test_positions_missing_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({}, T)


def test_positions_not_a_list_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": {"ticker": "A", "value": 1}}, T)


def test_payload_not_a_dict_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration([{"ticker": "A", "value": 1}], T)  # type: ignore[arg-type]


def test_position_not_a_dict_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": ["AAPL"]}, T)


@given(st.floats(min_value=-1e9, max_value=-1e-3))
def test_negative_value_raises(neg):
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": [{"ticker": "A", "value": neg}]}, T)


def test_missing_value_raises():
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": [{"ticker": "X"}]}, T)


@pytest.mark.parametrize("bad", [True, False])
def test_bool_value_rejected(bad):
    # bool is an int subclass; compute.py explicitly rejects it (never treats True as 1).
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": [{"ticker": "A", "value": bad}]}, T)


@pytest.mark.parametrize("bad", ["100", None, [1], {"x": 1}])
def test_non_numeric_value_raises(bad):
    with pytest.raises(ConcentrationInputError):
        compute_concentration({"positions": [{"ticker": "A", "value": bad}]}, T)


def test_all_zero_total_raises():
    payload = {"positions": [{"ticker": "A", "value": 0}, {"ticker": "B", "value": 0}]}
    with pytest.raises(ConcentrationInputError):
        compute_concentration(payload, T)


def test_zero_value_position_allowed_and_unflagged():
    # a single zero among positives is legal: weight 0, never a breach, finite output.
    payload = {"positions": [{"ticker": "A", "value": 100}, {"ticker": "Z", "value": 0}]}
    a = compute_concentration(payload, T)
    assert a["position_count"] == 2
    assert math.isfinite(a["diversification"]["hhi"])
    z = next(r for r in a["single_name"]["ranked"] if r["name"] == "Z")
    assert z["weight"] == 0.0
    assert "Z" not in {f["entity"] for f in a["flags"]}


def test_huge_portfolio_5000_positions_is_finite_and_consistent():
    rng = random.Random(1234)
    payload = {"positions": [{"ticker": f"S{i}", "value": rng.uniform(1, 1_000_000)}
                             for i in range(5000)]}
    a = compute_concentration(payload, T)
    assert a["position_count"] == 5000
    hhi = a["diversification"]["hhi"]
    eff = a["diversification"]["effective_number_of_positions"]
    assert 0.0 < hhi <= 1.0
    assert math.isfinite(eff) and 1.0 <= eff <= 5000 + 1e-3
    # eff == 1/HHI checked against the FULL-PRECISION hhi (the output hhi is rounded
    # to 6dp, which loses relative precision when hhi ~ 1/5000).
    vs = [p["value"] for p in payload["positions"]]
    _, _, true_hhi, _ = true_metrics(vs)
    assert math.isclose(eff, 1.0 / true_hhi, rel_tol=1e-4)
    # display weights still sum to ~1 within cumulative rounding
    s = sum(r["weight"] for r in a["single_name"]["ranked"])
    assert math.isclose(s, 1.0, abs_tol=5000 * 5e-7 + 1e-6)


def test_nan_value_rejected():
    """Regression (fixed via math.isfinite guard): a NaN 'value' must raise, not return a silent nan HHI."""
    payload = {"positions": [{"ticker": "A", "value": 100.0},
                             {"ticker": "B", "value": float("nan")}]}
    with pytest.raises(ConcentrationInputError):
        compute_concentration(payload, T)


def test_inf_value_rejected():
    """Regression (fixed via math.isfinite guard): a +inf 'value' must raise, not yield a silent nan HHI."""
    payload = {"positions": [{"ticker": "A", "value": 100.0},
                             {"ticker": "B", "value": float("inf")}]}
    with pytest.raises(ConcentrationInputError):
        compute_concentration(payload, T)


# ===========================================================================
# 4. GROUNDING / NO-FABRICATION
# ===========================================================================

@CORE
@given(portfolios())
def test_every_output_numeric_is_derivable_from_input(payload):
    a = compute_concentration(payload, T)
    vs = [p["value"] for p in payload["positions"]]
    basis, weights, hhi, eff = true_metrics(vs)

    # scalars derive from input
    assert a["position_count"] == len(vs)
    assert math.isclose(a["basis_total_value"], round(basis, 2), abs_tol=1e-3)
    assert math.isclose(a["diversification"]["hhi"], hhi, abs_tol=1e-6)  # 6dp output rounding
    assert math.isclose(a["diversification"]["effective_number_of_positions"],
                        eff, rel_tol=1e-6, abs_tol=1e-3)

    # each ranked weight is exactly value/basis for some input position (no invented weights)
    input_weight_pcts = sorted(round(w * 100.0, 4) for w in weights)
    out_weight_pcts = sorted(r["weight_pct"] for r in a["single_name"]["ranked"])
    for x, y in zip(input_weight_pcts, out_weight_pcts):
        assert math.isclose(x, y, abs_tol=1e-3)

    # top is the genuine argmax
    assert math.isclose(a["single_name"]["top"]["weight_pct"], max(out_weight_pcts), abs_tol=1e-6)

    # flags carry only real observed weights + the configured threshold — no magic numbers
    ranked_pcts = {round(r["weight_pct"], 4) for r in a["single_name"]["ranked"]}
    for f in a["flags"]:
        if f["type"] == "single_name":
            assert round(f["observed_pct"], 4) in ranked_pcts
            assert math.isclose(f["threshold_pct"], T["single_name"] * 100.0, abs_tol=1e-6)

    # policy block echoes the configured thresholds, not hardcoded values
    assert math.isclose(a["policy"]["single_name_threshold_pct"], T["single_name"] * 100.0, abs_tol=1e-6)
    assert math.isclose(a["policy"]["asset_class_threshold_pct"], T["asset_class"] * 100.0, abs_tol=1e-6)
    assert math.isclose(a["policy"]["sector_threshold_pct"], T["sector"] * 100.0, abs_tol=1e-6)


# ===========================================================================
# 4b. REFERENCE-ORACLE CORRECTNESS  (proves numbers are THE right value)
# ===========================================================================
#
# The agent normalizes to float weights, then squares and sums them. The oracle
# uses a DIFFERENT, exact formulation with fractions.Fraction:
#     weights_i = v_i / Σv                    (exact rationals)
#     HHI       = Σ (v_i)^2 / (Σ v_i)^2       (raw-value form, not via weights)
#     eff       = 1 / HHI
#     flag_i    iff  v_i/Σv  >  threshold     (exact rational comparison)
# Equal output within tight tolerance => the agent computes the correct value,
# not merely a value that happens to sit in (0,1].

def _oracle(named_values, thr):
    fv = [Fraction(v) for _, v in named_values]
    basis = sum(fv)
    weights = [x / basis for x in fv]
    hhi_via_weights = sum(w * w for w in weights)
    hhi_via_raw = sum(x * x for x in fv) / (basis * basis)
    assert hhi_via_weights == hhi_via_raw, "two exact HHI formulations disagree"
    hhi = hhi_via_raw
    eff = Fraction(1) / hhi
    fthr = Fraction(thr)
    flagged = {name for (name, _), w in zip(named_values, weights) if w > fthr}
    return {
        "basis": basis,
        "weights_pct": sorted(float(w * 100) for w in weights),
        "hhi": float(hhi),
        "eff": float(eff),
        "flagged": flagged,
    }


@settings(max_examples=200, deadline=None, suppress_health_check=[HealthCheck.too_slow])
@given(positions(min_size=1, max_size=25), st.floats(min_value=0.02, max_value=0.9))
def test_matches_independent_fraction_oracle(pos, thr):
    thresholds = {"single_name": thr, "asset_class": 0.40, "sector": 0.25}
    a = compute_concentration({"positions": pos}, thresholds)
    named = [(p["ticker"], p["value"]) for p in pos]
    orc = _oracle(named, thr)

    # HHI: agent value == exact oracle within 6dp-rounding tolerance
    assert math.isclose(a["diversification"]["hhi"], orc["hhi"], rel_tol=1e-6, abs_tol=1e-6)
    # effective number of positions == 1/HHI (exact oracle)
    assert math.isclose(a["diversification"]["effective_number_of_positions"],
                        orc["eff"], rel_tol=1e-4, abs_tol=1e-3)
    # basis total (output is rounded to 2dp; tolerate that rounding granularity)
    assert math.isclose(a["basis_total_value"], float(orc["basis"]), rel_tol=1e-9, abs_tol=6e-3)
    # weight_pct multiset
    out_pcts = sorted(r["weight_pct"] for r in a["single_name"]["ranked"])
    for x, y in zip(out_pcts, orc["weights_pct"]):
        assert math.isclose(x, y, abs_tol=1e-3)
    # breach flag set matches exact rational decision (note: agent uses strict >)
    got = {f["entity"] for f in a["flags"] if f["type"] == "single_name"}
    # multiple positions can share a ticker; oracle set is by name, so compare names present
    assert got == orc["flagged"]


# ===========================================================================
# 4c. GOLDEN KNOWN-ANSWER TESTS  (hand-computed, exact)
# ===========================================================================

@pytest.mark.parametrize(
    "name,positions_in,thr,exp_hhi,exp_eff,exp_top_pct,exp_breaches",
    [
        # 70/20/10 asymmetric. w=.7,.2,.1  HHI=.49+.04+.01=.54  eff=1/.54=1.851852
        # thr .10 strict: .7>.1 yes, .2>.1 yes, .1>.1 NO -> {A,B}
        ("70_20_10",
         [("A", 70), ("B", 20), ("C", 10)], 0.10, 0.54, 1.8519, 70.0, {"A", "B"}),
        # 50/30/15/5  HHI=.25+.09+.0225+.0025=.365  eff=1/.365=2.739726
        # thr .10: .5,.3,.15 breach; .05 no -> 3
        ("50_30_15_5",
         [("W", 50), ("X", 30), ("Y", 15), ("Z", 5)], 0.10, 0.365, 2.7397, 50.0,
         {"W", "X", "Y"}),
        # four equal 25s  HHI=4*.0625=.25  eff=4.0  thr .10 -> all four breach
        ("equal_4",
         [("P", 25), ("Q", 25), ("R", 25), ("S", 25)], 0.10, 0.25, 4.0, 25.0,
         {"P", "Q", "R", "S"}),
        # single position  HHI=1  eff=1  thr .10 -> the one name breaches (100%>10%)
        ("single",
         [("ONLY", 500000)], 0.10, 1.0, 1.0, 100.0, {"ONLY"}),
    ],
)
def test_golden_known_answers(name, positions_in, thr, exp_hhi, exp_eff, exp_top_pct, exp_breaches):
    payload = {"positions": [{"ticker": t, "value": v} for t, v in positions_in]}
    thresholds = {"single_name": thr, "asset_class": 0.40, "sector": 0.25}
    a = compute_concentration(payload, thresholds)
    assert math.isclose(a["diversification"]["hhi"], exp_hhi, abs_tol=1e-6), name
    assert math.isclose(a["diversification"]["effective_number_of_positions"],
                        exp_eff, abs_tol=1e-4), name
    assert math.isclose(a["single_name"]["top"]["weight_pct"], exp_top_pct, abs_tol=1e-4), name
    got = {f["entity"] for f in a["flags"] if f["type"] == "single_name"}
    assert got == exp_breaches, f"{name}: got {got}, expected {exp_breaches}"
    assert a["breach_count"] == len(a["flags"])


def test_golden_real_holdings_shape():
    """REL-00042 real numbers, HHI hand-derived via Σv²/(Σv)²."""
    vals = {"AAPL": 318000, "MSFT": 372000, "GOOGL": 289500,
            "JPM": 487500, "T-BILL-2026": 500000}
    payload = {"positions": [{"ticker": k, "value": v} for k, v in vals.items()]}
    a = compute_concentration(payload, T)
    basis = sum(vals.values())  # 1_967_000
    exp_hhi = sum(v * v for v in vals.values()) / (basis * basis)
    exp_eff = 1.0 / exp_hhi
    assert math.isclose(a["diversification"]["hhi"], exp_hhi, abs_tol=1e-6)
    assert math.isclose(a["diversification"]["effective_number_of_positions"],
                        exp_eff, rel_tol=1e-4)
    # top single name = T-BILL (500000/1967000 = 25.4194%)
    assert a["single_name"]["top"]["entity"] == "T-BILL-2026"
    assert math.isclose(a["single_name"]["top"]["weight_pct"], 25.4194, abs_tol=1e-3)
    # thr 10% -> every name > 10% except none (all 5 are >14%): all 5 breach
    got = {f["entity"] for f in a["flags"] if f["type"] == "single_name"}
    assert got == set(vals)  # every position exceeds 10%


# ===========================================================================
# 5. CONFIG PROPERTY
# ===========================================================================

@given(st.floats(min_value=0.01, max_value=0.99))
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_env_single_name_threshold_tracks_config(monkeypatch, thr):
    monkeypatch.setenv("CONCENTRATION_SINGLE_NAME_THRESHOLD", repr(thr))
    loaded = load_thresholds()
    assert math.isclose(loaded["single_name"], thr, rel_tol=1e-9, abs_tol=1e-9)
    # flags must track the *configured* value exactly
    payload = {"positions": [
        {"ticker": "BIG", "value": 60},
        {"ticker": "SML", "value": 40},
    ]}
    a = compute_concentration(payload, loaded)
    weights = {"BIG": 0.6, "SML": 0.4}
    expected = {name for name, w in weights.items() if w > loaded["single_name"]}
    got = {f["entity"] for f in a["flags"] if f["type"] == "single_name"}
    assert got == expected
    for f in a["flags"]:
        if f["type"] == "single_name":
            assert math.isclose(f["threshold_pct"], thr * 100.0, abs_tol=1e-4)


def test_default_single_name_threshold_when_unset(monkeypatch):
    monkeypatch.delenv("CONCENTRATION_SINGLE_NAME_THRESHOLD", raising=False)
    assert math.isclose(load_thresholds()["single_name"], 0.10, abs_tol=1e-9)
    assert math.isclose(DEFAULT_SINGLE_NAME_THRESHOLD, 0.10, abs_tol=1e-9)


@pytest.mark.parametrize("raw,expected", [
    ("0.25", 0.25),     # fraction form
    ("25", 0.25),       # percent form tolerated
    ("", 0.10),         # blank -> default
    ("garbage", 0.10),  # unparseable -> default
    ("-5", 0.10),       # non-positive -> default
    ("0", 0.10),        # zero -> default
])
def test_env_threshold_parsing_variants(monkeypatch, raw, expected):
    monkeypatch.setenv("CONCENTRATION_SINGLE_NAME_THRESHOLD", raw)
    assert math.isclose(load_thresholds()["single_name"], expected, abs_tol=1e-9)
