"""Cash Management MCP tool."""
import json
from shared.canned_data import CASH
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "acme.servicing.cash"


def get_cash(relationship_id: str) -> str:
    """Get cash balances and projected cash position for a relationship."""
    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("get_cash")
        data = CASH.get(relationship_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Relationship '{relationship_id}' not found."})
        span.set_attribute("result.projected_cash_usd", data.get("projected_cash_usd", 0))
        span.set_attribute("result.currency_count", len(data.get("balances", [])))
        return json.dumps(data)
