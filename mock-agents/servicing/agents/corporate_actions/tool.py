import json
from shared.canned_data import CORPORATE_ACTIONS
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "acme.servicing.corporate_actions"


def get_corporate_actions(relationship_id: str) -> str:
    """Get upcoming corporate actions (dividends, splits, rights) for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_corporate_actions")
        data = CORPORATE_ACTIONS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
        span.set_attribute("result.action_count", len(data.get("upcoming_actions", [])))
        return json.dumps(data)
