"""
Seed data for the insurance-coverage service.

POLICIES maps policy_id → metadata (the catalogue of underwritable policies).
BOOKS maps principal_id → set of policy_ids that underwriter owns / may access.

This is the insurance parallel of the wealth-coverage book model:
  uw_sam   → {POL-77001, POL-77002}   (his book)
  POL-88003 (Zenith Logistics)        → owned by uw_dana, NOT in uw_sam's book
                                         (the denial case, parallel to Okafor)
"""

POLICIES: dict[str, dict] = {
    "POL-77001": {
        "id": "POL-77001",
        "label": "Continental Freight Liability",
        "canonical_name": "Continental Freight Liability",
        "sub_domain": "claims-servicing",
        "owning_uw": "uw_sam",
        "aliases": [
            "continental", "continental freight", "continental freight liability",
            "freight liability", "the continental policy", "continental policy",
        ],
    },
    "POL-77002": {
        "id": "POL-77002",
        "label": "Aurora Mfg Property",
        "canonical_name": "Aurora Mfg Property",
        "sub_domain": "claims-servicing",
        "owning_uw": "uw_sam",
        "aliases": [
            "aurora", "aurora mfg", "aurora manufacturing", "aurora property",
            "aurora mfg property", "the aurora policy", "aurora policy",
        ],
    },
    "POL-88003": {
        "id": "POL-88003",
        "label": "Zenith Logistics",
        "canonical_name": "Zenith Logistics",
        "sub_domain": "claims-servicing",
        "owning_uw": "uw_dana",
        "aliases": [
            "zenith", "zenith logistics", "the zenith policy", "zenith policy",
            "zenith logistics group",
        ],
    },
}

# Book = the set of policy_ids a principal may access.
BOOKS: dict[str, set[str]] = {
    "uw_sam":  {"POL-77001", "POL-77002"},
    "uw_dana": {"POL-88003"},
    "admin":   set(POLICIES.keys()),   # platform admin sees all
}


# Tenant that OWNS each principal's book (Axiom Story A5). Every current demo principal
# lives in the single pre-Axiom "default" tenant; the coverage service uses this map to
# reject a request whose (token-and-header) tenant does not match the book's owning tenant
# — a genuine cross-tenant data boundary enforced at the data layer, not just the gateway.
DEFAULT_TENANT = "default"
PRINCIPAL_TENANTS: dict[str, str] = {principal_id: DEFAULT_TENANT for principal_id in BOOKS}


def owner_tenant(principal_id: str) -> str:
    """Return the tenant that owns principal_id's book.

    Unknown principals fall back to the default tenant: their book is empty, but the
    request is still tenant-scoped so a mismatched tenant is rejected before any lookup.
    """
    return PRINCIPAL_TENANTS.get(principal_id, DEFAULT_TENANT)


def discover(principal_id: str) -> list[dict]:
    """Return all resources in principal's book."""
    ids = BOOKS.get(principal_id, set())
    result = []
    for pol_id in ids:
        pol = POLICIES.get(pol_id)
        if pol:
            result.append({
                "id": pol["id"],
                "label": pol["label"],
                "sub_domain": pol["sub_domain"],
            })
    return result


def check(principal_id: str, resource_id: str) -> dict:
    """Return allowed/denied for a specific resource."""
    book = BOOKS.get(principal_id, set())
    if resource_id not in POLICIES:
        return {"allowed": False, "reason": "unknown-resource"}
    if resource_id in book:
        return {"allowed": True, "reason": "in-book"}
    return {"allowed": False, "reason": "not-covered"}


def resolve(reference: str, entity_type: str, principal_id: str) -> dict:
    """
    Resolve a free-text reference to a canonical policy ID.

    RESOLVE is principal-agnostic: it searches ALL entities regardless of the
    caller's book. principal_id is accepted for audit/logging only — it must
    NOT be used to filter candidates. CHECK is the sole authorization gate.

    Returns one of:
      {"resolved": True,  "id": "...", "canonical_name": "...", "candidates": None}
      {"resolved": False, "id": None,  "canonical_name": None,  "candidates": [...]}  (ambiguous)
      {"resolved": False, "id": None,  "canonical_name": None,  "candidates": []}    (not found)
    """
    ref_lower = reference.lower().strip()
    if not ref_lower:
        return {"resolved": False, "id": None, "canonical_name": None, "candidates": []}

    # Search ALL entities — principal_id is for audit only, not a filter.
    matches = []
    for pol_id, pol in POLICIES.items():
        # Exact canonical-ID match
        if pol_id.lower() == ref_lower:
            matches.append(pol)
            continue
        # Alias match (substring in either direction)
        for alias in pol.get("aliases", []):
            if alias in ref_lower or ref_lower in alias:
                matches.append(pol)
                break

    if len(matches) == 1:
        pol = matches[0]
        return {
            "resolved": True,
            "id": pol["id"],
            "canonical_name": pol["canonical_name"],
            "candidates": None,
        }
    if len(matches) > 1:
        return {
            "resolved": False,
            "id": None,
            "canonical_name": None,
            "candidates": [{"id": p["id"], "name": p["canonical_name"]} for p in matches],
        }
    return {
        "resolved": False,
        "id": None,
        "canonical_name": None,
        "candidates": [],
    }
