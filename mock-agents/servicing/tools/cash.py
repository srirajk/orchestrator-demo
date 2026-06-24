import json
from data.canned import CASH
from .fault_knobs import maybe_fault


def get_cash(relationship_id: str) -> str:
    """Get cash balances and projected cash position for a relationship."""
    maybe_fault("get_cash")
    data = CASH.get(relationship_id)
    if data is None:
        return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
    return json.dumps(data)
