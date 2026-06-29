"""
Canned responses for the Insurance HTTP agents (policy_details, claim_status).

Numbers are internally consistent so the gateway's synthesis grounding check
passes (e.g. a claim amount is always <= the policy's coverage_limit, and
each claim's policy_id refers to a policy that exists here).

Books / entitlements (who may see which policy) live in the SEPARATE
insurance-coverage service — these canned records are entitlement-agnostic
(parallel to wealth: the Okafor relationship has holdings data too; coverage
is what gates it).
"""

# policy_id → policy record
POLICIES: dict = {
    "POL-77001": {
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
    },
    "POL-77002": {
        "policy_id": "POL-77002",
        "policy_name": "Aurora Mfg Property",
        "line_of_business": "Commercial Property",
        "insured_name": "Aurora Manufacturing LLC",
        "premium": 72000,
        "premium_currency": "USD",
        "coverage_limit": 12000000,
        "deductible": 100000,
        "status": "active",
        "effective_date": "2025-07-01",
        "expiry_date": "2026-06-30",
        "as_of_date": "2026-06-22",
    },
    # Exists in the catalogue but is OUT OF uw_sam's book (the denial case,
    # parallel to the Okafor relationship in wealth). Data is present so the
    # denial is purely a coverage decision, never "data missing".
    "POL-88003": {
        "policy_id": "POL-88003",
        "policy_name": "Zenith Logistics",
        "line_of_business": "Marine Cargo & Logistics",
        "insured_name": "Zenith Logistics Group",
        "premium": 95000,
        "premium_currency": "USD",
        "coverage_limit": 20000000,
        "deductible": 150000,
        "status": "active",
        "effective_date": "2026-02-15",
        "expiry_date": "2027-02-14",
        "as_of_date": "2026-06-22",
    },
}

# claim_id → claim record (each claim references a policy_id above)
CLAIMS: dict = {
    "CLM-5501": {
        "claim_id": "CLM-5501",
        "policy_id": "POL-77001",
        "claimant": "Continental Freight Inc.",
        "amount": 240000,
        "amount_currency": "USD",
        "status": "under-review",
        "incident_date": "2026-05-14",
        "reported_date": "2026-05-16",
        "adjuster": "Dana Reyes",
        "description": "Multi-vehicle collision involving two insured freight units on I-95.",
        "as_of_date": "2026-06-22",
    },
    "CLM-5502": {
        "claim_id": "CLM-5502",
        "policy_id": "POL-77002",
        "claimant": "Aurora Manufacturing LLC",
        "amount": 880000,
        "amount_currency": "USD",
        "status": "approved",
        "incident_date": "2026-03-02",
        "reported_date": "2026-03-04",
        "adjuster": "Marcus Webb",
        "description": "Roof collapse from winter storm at the Aurora plant; property damage claim.",
        "as_of_date": "2026-06-22",
    },
}


def claims_for_policy(policy_id: str) -> list[dict]:
    """All claims filed against a given policy."""
    return [c for c in CLAIMS.values() if c.get("policy_id") == policy_id]
