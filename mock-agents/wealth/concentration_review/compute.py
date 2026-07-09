from __future__ import annotations

from typing import Any


def build_review_flag(analysis: dict[str, Any]) -> dict[str, Any]:
    flags = analysis.get("flags")
    if not isinstance(flags, list):
        flags = []

    breach_count = analysis.get("breach_count")
    if not isinstance(breach_count, int):
        breach_count = len(flags)

    policy = analysis.get("policy") if isinstance(analysis.get("policy"), dict) else {}
    return {
        "review_flag": True,
        "flag_type": "firm_policy_concentration_review",
        "relationship_id": analysis.get("relationship_id"),
        "relationship_name": analysis.get("relationship_name"),
        "breach_count": breach_count,
        "flags": flags,
        "policy": {
            "source": policy.get("source", "firm-configured"),
            "note": policy.get("note"),
        },
        "agent_narrative": (
            "Conditional concentration review flag: concentration analysis found "
            f"{breach_count} firm-policy breach(es). This is a review flag only, not investment advice."
        ),
    }
