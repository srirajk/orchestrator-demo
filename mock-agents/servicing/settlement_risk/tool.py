"""Settlement Risk MCP tool."""
import json
import logging
from typing import Any

from shared.error_schema import mcp_error_json
from shared.fault_knobs import maybe_fault
from shared.telemetry import agent_span
from settlement_risk.compute import compute_settlement_risk, SettlementRiskInputError


AGENT_ID = "meridian.servicing.settlement_risk"
log = logging.getLogger(__name__)


def analyze_settlement_risk(
    settlement_status: dict[str, Any] = {},
    custody_positions: dict[str, Any] = {},
    cash_position: dict[str, Any] = {},
) -> str:
    """Analyse settlement fails aging and CSDR cash-penalty exposure from fan-in data."""
    relationship_id = None
    if isinstance(settlement_status, dict):
        relationship_id = settlement_status.get("relationship_id")

    with agent_span(AGENT_ID, relationship_id) as span:
        maybe_fault("analyze_settlement_risk")
        payload = {
            "settlement_status": settlement_status,
            "custody_positions": custody_positions,
            "cash_position": cash_position,
        }
        try:
            analysis = compute_settlement_risk(payload)
        except SettlementRiskInputError as exc:
            span.set_attribute("error", True)
            span.set_attribute("error.type", "invalid_settlement_risk_payload")
            return mcp_error_json(str(exc), AGENT_ID, 422)
        except Exception as exc:
            log.error("Settlement risk compute failed: %s", exc)
            span.set_attribute("error", True)
            span.set_attribute("error.type", type(exc).__name__)
            return mcp_error_json(
                f"Settlement risk analysis failed: {type(exc).__name__}",
                AGENT_ID,
                500,
            )

        span.set_attribute("result.pending_count", analysis["pending_count"])
        span.set_attribute("result.failed_count", analysis["failed_count"])
        span.set_attribute("result.failed_amount_usd", analysis["failed_amount_usd"])
        span.set_attribute("result.breach_count", analysis["breach_count"])
        return json.dumps(analysis)
