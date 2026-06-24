"""Settlements MCP tool."""
import json
from shared.canned_data import SETTLEMENTS
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "acme.servicing.settlement_status"


def get_settlements(relationship_id: str) -> str:
    """Get pending and failed settlement trades for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_settlements")
        data = SETTLEMENTS.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
        pending = len(data.get("pending", []))
        failed = len(data.get("failed", []))
        span.set_attribute("result.pending_count", pending)
        span.set_attribute("result.failed_count", failed)
        if failed > 0:
            span.set_attribute("alert.failed_settlements", True)
        return json.dumps(data)
