import json
from data.canned import CORPORATE_ACTIONS
from .fault_knobs import maybe_fault


def get_corporate_actions(relationship_id: str) -> str:
    """Get upcoming corporate actions (dividends, splits, rights) for a relationship."""
    maybe_fault("get_corporate_actions")
    data = CORPORATE_ACTIONS.get(relationship_id)
    if data is None:
        return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
    return json.dumps(data)
