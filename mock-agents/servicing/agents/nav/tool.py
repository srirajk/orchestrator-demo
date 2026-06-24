import json
from shared.canned_data import NAV
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span

AGENT_ID = "acme.servicing.nav"


def get_nav(fund_id: str) -> str:
    """Get the latest Net Asset Value for a fund. Keyed by fund_id, not relationship_id."""
    with agent_span(AGENT_ID) as span:
        maybe_fault("get_nav")
        span.set_attribute("entity.fund_id", fund_id)
        data = NAV.get(fund_id)
        if data is None:
            span.set_attribute("error", True)
            return json.dumps({"error": f"Fund '{fund_id}' not found. Known funds: {list(NAV)}"})
        span.set_attribute("result.nav", data.get("nav", 0))
        return json.dumps(data)
