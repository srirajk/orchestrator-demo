import json
from data.canned import SETTLEMENTS
from .fault_knobs import maybe_fault


def get_settlements(relationship_id: str) -> str:
    """Get pending and failed settlement trades for a relationship."""
    maybe_fault("get_settlements")
    data = SETTLEMENTS.get(relationship_id)
    if data is None:
        return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
    return json.dumps(data)
