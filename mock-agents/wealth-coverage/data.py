"""
Seed data for the wealth-coverage service.

RELATIONSHIPS maps relationship_id → metadata.
BOOKS maps principal_id → set of relationship_ids they own / may access.
"""

RELATIONSHIPS: dict[str, dict] = {
    "REL-00042": {
        "id": "REL-00042",
        "label": "Whitman Family Office",
        "canonical_name": "Whitman Family Office",
        "sub_domain": "private-banking",
        "owning_rm": "rm_jane",
        "aliases": ["whitman", "whitman family", "whitman family office", "the whitman relationship",
                    "whitman account", "whitman portfolio"],
    },
    "REL-00099": {
        "id": "REL-00099",
        "label": "Calderon Trust",
        "canonical_name": "Calderon Trust",
        "sub_domain": "private-banking",
        "owning_rm": "rm_jane",
        "aliases": ["calderon", "calderon trust", "the calderon trust", "calderon account"],
    },
    "REL-00333": {
        "id": "REL-00333",
        "label": "Rivera Diversified Trust",
        "canonical_name": "Rivera Diversified Trust",
        "sub_domain": "private-banking",
        "owning_rm": "rm_jane",
        "aliases": ["rivera", "rivera diversified", "rivera diversified trust",
                    "the rivera relationship", "rivera account", "diversified trust"],
    },
    "REL-00188": {
        "id": "REL-00188",
        "label": "Okafor Family Trust",
        "canonical_name": "Okafor Family Trust",
        "sub_domain": "private-banking",
        "owning_rm": "rm_ken",
        "aliases": ["okafor", "okafor family", "okafor family trust", "the okafor account",
                    "okafor account"],
    },
    "REL-00201": {
        "id": "REL-00201",
        "label": "Sterling Capital Partners",
        "canonical_name": "Sterling Capital Partners",
        "sub_domain": "private-banking",
        "owning_rm": "rm_carlos",
        "aliases": ["sterling", "sterling capital", "sterling capital partners",
                    "the sterling relationship", "sterling account", "sterling partners"],
    },
    "REL-00444": {
        "id": "REL-00444",
        "label": "Map Stress Settlement Account",
        "canonical_name": "Map Stress Settlement Account",
        "sub_domain": "private-banking",
        "owning_rm": "ops_analyst_singh",
        "aliases": ["map stress", "map stress settlement", "map stress account"],
    },
    "REL-00445": {
        "id": "REL-00445",
        "label": "Map Empty Settlement Account",
        "canonical_name": "Map Empty Settlement Account",
        "sub_domain": "private-banking",
        "owning_rm": "ops_analyst_singh",
        "aliases": ["map empty", "map empty settlement", "map empty account"],
    },
}

# Book = the set of relationship_ids a principal may access.
# The coverage service is the SOLE source of principal→entities. Whatever a
# principal's book returns here IS the ground truth for entitlement CHECK.
BOOKS: dict[str, set[str]] = {
    "rm_jane":   {"REL-00042", "REL-00099", "REL-00333"},
    # rm_carlos owns his own distinct client (Sterling). Whitman/Calderon/Okafor are
    # NOT in his book → denied, proving book-of-business isolation between RMs.
    "rm_carlos": {"REL-00201"},
    # Asset-servicing operations persona. This mirrors IAM personal_resources seed V9
    # so both demo book stores agree until T4 removes the IAM copy entirely.
    "ops_analyst_singh": {"REL-00188", "REL-00444", "REL-00445"},
    "rm_ken":    {"REL-00188"},
    "admin":     set(RELATIONSHIPS.keys()),   # platform admin sees all
}


def discover(principal_id: str) -> list[dict]:
    """Return all resources in principal's book."""
    ids = BOOKS.get(principal_id, set())
    result = []
    for rel_id in ids:
        rel = RELATIONSHIPS.get(rel_id)
        if rel:
            result.append({
                "id": rel["id"],
                "label": rel["label"],
                "sub_domain": rel["sub_domain"],
            })
    return result


def check(principal_id: str, resource_id: str) -> dict:
    """Return allowed/denied for a specific resource."""
    book = BOOKS.get(principal_id, set())
    if resource_id not in RELATIONSHIPS:
        return {"allowed": False, "reason": "unknown-resource"}
    if resource_id in book:
        return {"allowed": True, "reason": "in-book"}
    return {"allowed": False, "reason": "not-in-book"}


def resolve(reference: str, entity_type: str, principal_id: str) -> dict:
    """
    Resolve a free-text reference to a canonical relationship ID.

    RESOLVE is principal-agnostic: it searches ALL entities regardless of the
    caller's book.  principal_id is accepted for audit/logging only — it must
    NOT be used to filter candidates.  CHECK is the sole authorization gate.

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
    for rel_id, rel in RELATIONSHIPS.items():
        # Exact canonical-ID match
        if rel_id.lower() == ref_lower:
            matches.append(rel)
            continue
        # Alias match (substring in either direction)
        for alias in rel.get("aliases", []):
            if alias in ref_lower or ref_lower in alias:
                matches.append(rel)
                break

    if len(matches) == 1:
        rel = matches[0]
        return {
            "resolved": True,
            "id": rel["id"],
            "canonical_name": rel["canonical_name"],
            "candidates": None,
        }
    if len(matches) > 1:
        return {
            "resolved": False,
            "id": None,
            "canonical_name": None,
            "candidates": [{"id": r["id"], "name": r["canonical_name"]} for r in matches],
        }
    return {
        "resolved": False,
        "id": None,
        "canonical_name": None,
        "candidates": [],
    }
