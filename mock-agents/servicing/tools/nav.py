import json
from data.canned import NAV
from .fault_knobs import maybe_fault


def get_nav(fund_id: str) -> str:
    """Get the latest Net Asset Value for a fund. Keyed by fund_id, not relationship_id."""
    maybe_fault("get_nav")
    data = NAV.get(fund_id)
    if data is None:
        return json.dumps({"error": f"Fund '{fund_id}' not found. Known funds: {list(NAV)}"})
    return json.dumps(data)
