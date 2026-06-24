import json
from data.canned import CUSTODY_POSITIONS
from .fault_knobs import maybe_fault


def get_custody_positions(relationship_id: str) -> str:
    """Get custody positions held at custodians for a relationship."""
    maybe_fault("get_custody_positions")
    data = CUSTODY_POSITIONS.get(relationship_id)
    if data is None:
        return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
    return json.dumps(data)
