import json
from shared.canned_data import CUSTODY_POSITIONS
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "acme.servicing.custody_positions"


def get_custody_positions(relationship_id: str) -> str:
    """Get custody positions held at custodians for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_custody_positions")
        data = CUSTODY_POSITIONS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
        custodian_count = len(data.get("holdings_by_custodian", []))
        span.set_attribute("result.custodian_count", custodian_count)
        return json.dumps(data)
