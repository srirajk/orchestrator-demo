"""
Pure concentration-analysis math for the Wealth Concentration agent.

This module has ZERO FastAPI / shared / OTel dependencies on purpose: it is the
hermetic compute core that both the FastAPI handler and the unit tests import
directly. It takes a holdings payload (the OUTPUT of the wealth.holdings agent)
and produces a concentration analysis. It never fetches anything.

DOMAIN TRUTH (docs/DOMAIN-KNOWLEDGE-VERIFIED.md):
There is NO universal regulatory concentration threshold. FINRA Rule 2111 is
principles-based; overconcentration is a facts-and-circumstances judgement. Any
percentage flag here is FIRM-DISCRETIONARY. We therefore COMPUTE the metrics
(pure math, always valid) and FLAG against a firm-configurable threshold read
from env/config, and we LABEL every flag as the firm's own policy — we never
assert that a given percentage is an industry or regulatory standard.
"""
from __future__ import annotations

import math
import os
from typing import Any, Optional


# --- Firm-configurable policy thresholds (env-driven; never a magic constant) ---
# Fractions in [0, 1]. Defaults are the firm's stated demo policy, NOT a rule.
DEFAULT_SINGLE_NAME_THRESHOLD = 0.10   # 10% single-name
DEFAULT_ASSET_CLASS_THRESHOLD = 0.40   # 40% single asset class
DEFAULT_SECTOR_THRESHOLD = 0.25        # 25% single sector (used only if data has sector)

POLICY_NOTE = (
    "Thresholds are firm-configured policy, not a regulatory or industry standard. "
    "FINRA Rule 2111 is principles-based and overconcentration is a "
    "facts-and-circumstances determination; any percentage flag is firm-discretionary."
)


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
    # Tolerate a percent-style value (e.g. 10 meaning 10%).
    return val / 100.0 if val > 1 else val


def load_thresholds() -> dict[str, float]:
    """Read firm-configured concentration thresholds from the environment."""
    return {
        "single_name": _env_fraction(
            "CONCENTRATION_SINGLE_NAME_THRESHOLD", DEFAULT_SINGLE_NAME_THRESHOLD
        ),
        "asset_class": _env_fraction(
            "CONCENTRATION_ASSET_CLASS_THRESHOLD", DEFAULT_ASSET_CLASS_THRESHOLD
        ),
        "sector": _env_fraction(
            "CONCENTRATION_SECTOR_THRESHOLD", DEFAULT_SECTOR_THRESHOLD
        ),
    }


class ConcentrationInputError(ValueError):
    """Raised when the holdings payload is empty or malformed (→ non-200)."""


def _name_of(position: dict[str, Any]) -> str:
    for key in ("ticker", "isin", "name", "security", "symbol"):
        v = position.get(key)
        if v:
            return str(v)
    return "UNKNOWN"


def _pct(fraction: float) -> float:
    return round(fraction * 100.0, 4)


def compute_concentration(
    payload: dict[str, Any],
    thresholds: Optional[dict[str, float]] = None,
) -> dict[str, Any]:
    """
    Compute a concentration analysis from a holdings payload.

    Args:
        payload: the holdings agent output. Expected fields (from the real
            holdings shape): ``positions`` (list of {ticker, value, ...}),
            optional ``total_value``, ``allocation_by_class`` (list of
            {asset_class, pct}), ``relationship_id``, ``currency``,
            ``as_of_date``. An optional ``risk_profile`` object is used only to
            annotate context; it is never required.
        thresholds: firm-configured fractions; defaults to ``load_thresholds()``.

    Returns:
        A JSON-serialisable analysis dict.

    Raises:
        ConcentrationInputError: if positions are missing/empty/malformed.
    """
    if thresholds is None:
        thresholds = load_thresholds()

    if not isinstance(payload, dict):
        raise ConcentrationInputError("holdings payload must be a JSON object")

    positions = payload.get("positions")
    if not isinstance(positions, list) or len(positions) == 0:
        raise ConcentrationInputError(
            "holdings payload has no positions to analyse"
        )

    # Extract (name, value) pairs; value must be a non-negative number.
    parsed: list[tuple[str, float]] = []
    for p in positions:
        if not isinstance(p, dict):
            raise ConcentrationInputError("each position must be a JSON object")
        val = p.get("value")
        if val is None or isinstance(val, bool) or not isinstance(val, (int, float)):
            raise ConcentrationInputError(
                f"position {_name_of(p)!r} has a missing or non-numeric 'value'"
            )
        if not math.isfinite(val):
            raise ConcentrationInputError(
                f"position {_name_of(p)!r} has a non-finite 'value' (NaN/inf)"
            )
        if val < 0:
            raise ConcentrationInputError(
                f"position {_name_of(p)!r} has a negative 'value'"
            )
        parsed.append((_name_of(p), float(val)))

    basis_total = sum(v for _, v in parsed)
    if basis_total <= 0:
        raise ConcentrationInputError(
            "sum of position values is zero — cannot compute weights"
        )

    # --- Per-position weights (denominator = invested base = sum of positions) ---
    weighted = [
        {"name": name, "value": value, "weight": value / basis_total}
        for name, value in parsed
    ]
    weighted.sort(key=lambda r: r["weight"], reverse=True)

    ranked = [
        {
            "name": r["name"],
            "value": round(r["value"], 2),
            "weight": round(r["weight"], 6),
            "weight_pct": _pct(r["weight"]),
        }
        for r in weighted
    ]
    top = ranked[0]

    # --- HHI + effective number of positions (pure math, always valid) ---
    hhi = sum(r["weight"] ** 2 for r in weighted)
    effective_positions = 1.0 / hhi  # hhi in (0, 1] ⇒ effective in [1, N]

    sn_threshold = thresholds["single_name"]

    flags: list[dict[str, Any]] = []
    for r in weighted:
        if r["weight"] > sn_threshold:
            flags.append(
                {
                    "type": "single_name",
                    "entity": r["name"],
                    "observed_pct": _pct(r["weight"]),
                    "threshold_pct": _pct(sn_threshold),
                    "breached": True,
                    "policy": "firm-configured",
                    "message": (
                        f"{r['name']} is {_pct(r['weight'])}% of the portfolio, "
                        f"above the firm-configured single-name limit of "
                        f"{_pct(sn_threshold)}% (firm policy, not a regulatory standard)."
                    ),
                }
            )

    notes: list[str] = []

    # --- Asset-class concentration: only from allocation_by_class (positions carry
    #     no per-position asset_class in the real holdings shape). ---
    asset_class_block: Optional[dict[str, Any]] = None
    alloc = payload.get("allocation_by_class")
    if isinstance(alloc, list) and alloc:
        ac_threshold = thresholds["asset_class"]
        ac_ranked = []
        for a in alloc:
            if not isinstance(a, dict):
                continue
            cls = a.get("asset_class") or a.get("class") or "UNKNOWN"
            pct = a.get("pct")
            if pct is None or isinstance(pct, bool) or not isinstance(pct, (int, float)):
                continue
            if not math.isfinite(pct):
                continue
            frac = float(pct) / 100.0 if pct > 1 else float(pct)
            ac_ranked.append({"asset_class": cls, "fraction": frac})
        ac_ranked.sort(key=lambda r: r["fraction"], reverse=True)
        if ac_ranked:
            asset_class_block = {
                "source": "allocation_by_class",
                "ranked": [
                    {"asset_class": r["asset_class"], "weight_pct": _pct(r["fraction"])}
                    for r in ac_ranked
                ],
                "top": {
                    "asset_class": ac_ranked[0]["asset_class"],
                    "weight_pct": _pct(ac_ranked[0]["fraction"]),
                },
            }
            for r in ac_ranked:
                if r["fraction"] > ac_threshold:
                    flags.append(
                        {
                            "type": "asset_class",
                            "entity": r["asset_class"],
                            "observed_pct": _pct(r["fraction"]),
                            "threshold_pct": _pct(ac_threshold),
                            "breached": True,
                            "policy": "firm-configured",
                            "message": (
                                f"{r['asset_class']} is {_pct(r['fraction'])}% of the "
                                f"portfolio, above the firm-configured asset-class limit "
                                f"of {_pct(ac_threshold)}% (firm policy)."
                            ),
                        }
                    )
    else:
        notes.append(
            "asset-class concentration omitted — holdings payload carries no "
            "'allocation_by_class' array."
        )

    # --- Sector concentration: the real holdings shape has no per-position sector,
    #     so sector concentration is not computable and is intentionally omitted. ---
    has_sector = any(
        isinstance(p, dict) and p.get("sector") for p in positions
    )
    sector_block = None
    if not has_sector:
        notes.append(
            "sector concentration omitted — holdings positions carry no 'sector' field."
        )

    # Optional risk-profile context (annotation only; never required / never a gate).
    risk_context = None
    rp = payload.get("risk_profile")
    if isinstance(rp, dict):
        risk_context = {
            "risk_tolerance": rp.get("risk_tolerance"),
            "risk_score": rp.get("risk_score"),
            "max_drawdown_tolerance_pct": rp.get("max_drawdown_tolerance_pct"),
        }

    return {
        "analysis": "concentration",
        "relationship_id": payload.get("relationship_id"),
        "relationship_name": payload.get("relationship_name"),
        "as_of_date": payload.get("as_of_date"),
        "currency": payload.get("currency"),
        "position_count": len(parsed),
        "basis_total_value": round(basis_total, 2),
        "reported_total_value": payload.get("total_value"),
        "policy": {
            "source": "firm-configured",
            "single_name_threshold_pct": _pct(sn_threshold),
            "asset_class_threshold_pct": _pct(thresholds["asset_class"]),
            "sector_threshold_pct": _pct(thresholds["sector"]),
            "note": POLICY_NOTE,
        },
        "single_name": {
            "top": {
                "entity": top["name"],
                "value": top["value"],
                "weight_pct": top["weight_pct"],
            },
            "ranked": ranked,
        },
        "asset_class": asset_class_block,
        "sector": sector_block,
        "diversification": {
            "hhi": round(hhi, 6),
            "effective_number_of_positions": round(effective_positions, 4),
        },
        "flags": flags,
        "breach_count": len(flags),
        "risk_context": risk_context,
        "notes": notes,
    }
