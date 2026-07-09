RELATIONSHIPS = {
    "REL-00042": {
        "entity_id": "REL-00042",
        "name": "Whitman Family Office",
        "aliases": ["whitman", "whitman family", "whitman family office"],
        "type": "relationship"
    },
    "REL-00099": {
        "entity_id": "REL-00099",
        "name": "Calderon Trust",
        "aliases": ["calderon", "calderon trust"],
        "type": "relationship"
    },
    "REL-00333": {
        "entity_id": "REL-00333",
        "name": "Rivera Diversified Trust",
        "aliases": ["rivera", "rivera diversified", "rivera diversified trust", "diversified trust"],
        "type": "relationship"
    },
    "REL-00188": {
        "entity_id": "REL-00188",
        "name": "Okafor Capital",
        "aliases": ["okafor", "okafor capital"],
        "type": "relationship"
    },
    "REL-00200": {
        "entity_id": "REL-00200",
        "name": "Andersen Trust",
        "aliases": ["andersen", "andersen trust"],
        "type": "relationship"
    },
}

BOOKS = {
    "rm_jane": ["REL-00042", "REL-00099", "REL-00333"],
    "rm_carlos": ["REL-00042", "REL-00188"],
    "rm_guest": [],
}

def resolve_entity(query: str, entity_type: str = "relationship"):
    query_lower = query.strip().lower()
    if entity_type == "relationship":
        # Already a canonical ID
        if query.upper() in RELATIONSHIPS:
            return {"resolved": True, "entity_id": query.upper(), "name": RELATIONSHIPS[query.upper()]["name"], "type": "relationship", "candidates": []}
        # Alias search
        matches = []
        for rel_id, rel in RELATIONSHIPS.items():
            if any(alias in query_lower or query_lower in alias for alias in rel["aliases"]):
                matches.append({"entity_id": rel_id, "name": rel["name"]})
        if len(matches) == 1:
            return {"resolved": True, "entity_id": matches[0]["entity_id"], "name": matches[0]["name"], "type": "relationship", "candidates": []}
        elif len(matches) > 1:
            return {"resolved": False, "entity_id": None, "name": None, "type": "relationship", "candidates": matches}
        else:
            return {"resolved": False, "entity_id": None, "name": None, "type": "relationship", "candidates": []}
    return {"resolved": False, "entity_id": None, "name": None, "type": entity_type, "candidates": []}

def check_access(principal_id: str, relationship_id: str):
    book = BOOKS.get(principal_id, [])
    allowed = relationship_id in book
    return {"allowed": allowed, "reason": "in-book" if allowed else "not-in-book", "principal_id": principal_id, "relationship_id": relationship_id}
