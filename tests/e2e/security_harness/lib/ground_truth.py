"""
Independent ground-truth fetch for the grounding/no-fabrication check.

Rather than trying to pull the DAG's exact internal node input/output out of the trace
(agent_complete's dataPreview is truncated to 80 chars — nowhere near enough to grade a
whole concentration payload against), we independently replay the SAME two calls the
gateway's DAG would have made: GET .../holdings then POST that response straight into
.../concentration (per wealth-http's own OpenAPI doc: "The concentration request body IS
the holdings agent's output"). Same agent, same relationship, real JWT — an honest,
independently-obtained ground truth to grade the LLM's synthesized answer against.
"""
from __future__ import annotations
from typing import Any

import requests

from . import config, iam_client


def fetch_concentration_ground_truth(relationship_id: str = config.WHITMAN_RELATIONSHIP_ID,
                                      user: str = config.USER_ENTITLED) -> dict[str, Any]:
    jwt = iam_client.get_jwt(user)
    headers = {"Authorization": f"Bearer {jwt}"}
    holdings = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": relationship_id},
        headers=headers, timeout=15,
    )
    holdings.raise_for_status()
    concentration = requests.post(
        f"{config.WEALTH_HTTP_URL}/concentration",
        json=holdings.json(), headers=headers, timeout=15,
    )
    concentration.raise_for_status()
    return {"holdings": holdings.json(), "concentration": concentration.json()}


def grounded_percentages(concentration: dict[str, Any]) -> set[float]:
    """Every legitimate single-name / asset-class / threshold percentage the concentration
    agent actually produced, for the caller to grade extracted answer percentages against."""
    values: set[float] = set()
    sn = concentration.get("single_name") or {}
    for row in sn.get("ranked", []):
        if "weight_pct" in row:
            values.add(float(row["weight_pct"]))
    ac = concentration.get("asset_class") or {}
    for row in ac.get("ranked", []):
        if "weight_pct" in row:
            values.add(float(row["weight_pct"]))
    policy = concentration.get("policy") or {}
    for key in ("single_name_threshold_pct", "asset_class_threshold_pct", "sector_threshold_pct"):
        if policy.get(key) is not None:
            values.add(float(policy[key]))
    return values


def grounded_hhi(concentration: dict[str, Any]) -> set[float]:
    """HHI in both its native 0-1 scale and the *100 scale some prose reports it in."""
    div = concentration.get("diversification") or {}
    hhi = div.get("hhi")
    if hhi is None:
        return set()
    return {float(hhi), float(hhi) * 100}
