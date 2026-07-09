"""
Asset Servicing MCP server — Domain 2 mock agents.
Exposes 6 tools over SSE transport via FastMCP.

Agent layout (each agent in its own top-level subfolder):
  servicing/settlements/          → get_settlements
  servicing/corporate_actions/    → get_corporate_actions (RAG-augmented)
  servicing/custody/              → get_custody_positions
  servicing/nav/                  → get_nav  (keyed by fund_id, not relationship_id)
  servicing/cash/                 → get_cash
  servicing/settlement_risk/      → analyze_settlement_risk (fan-in analytics)
  servicing/settlement_penalty/   → calculate_trade_penalty (map item analytics)

Fault knobs (env vars, set in docker-compose for resilience tests):
  MCP_FAULT_TOOL=get_settlements   → that tool returns an error
  MCP_FAULT_ALL=true               → all tools fail
  MCP_FAULT_DELAY_MS=500           → inject latency (ms) to every call

The gateway's McpAdapter connects to: http://servicing:8082/sse

Distributed Tracing Note (MCP limitation):
  The MCP protocol (JSON-RPC over SSE) does not carry HTTP headers per-tool-call,
  so the W3C traceparent / tracestate headers that the gateway sends are not
  available inside individual tool handlers.  This means MCP tool spans are
  NOT automatically linked to the gateway's root OTel span.

  Future path: pass {"_traceId": "<trace-id>"} as a metadata key in tool
  arguments, then extract it here to manually create a linked child span.
  Until then, MCP spans appear as independent traces in Tempo (not as children
  of the gateway root).  A warning is emitted at startup to make this visible.
"""

import sys
import os
import logging

sys.path.insert(0, os.path.dirname(__file__))

_log = logging.getLogger(__name__)

# MCP does not propagate W3C traceparent headers to individual tool calls.
# Agent spans will not be linked to the gateway's OTel root span.
# To get linked spans, callers should include {"_traceId": "<id>"} in tool
# args metadata; individual tools should extract it and log it explicitly.
_log.warning(
    "servicing-mcp: MCP protocol does not support native trace propagation. "
    "Agent tool spans will NOT appear as children of the gateway root span in Tempo. "
    "Pass _traceId in tool metadata to correlate manually."
)

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest
from starlette.responses import JSONResponse, Response
from starlette.routing import Route, Mount

from settlements.tool import get_settlements as _get_settlements
from corporate_actions.tool import get_corporate_actions as _get_corporate_actions
from custody.tool import get_custody_positions as _get_custody_positions
from nav.tool import get_nav as _get_nav
from cash.tool import get_cash as _get_cash
from settlement_risk.tool import analyze_settlement_risk as _analyze_settlement_risk
from settlement_penalty.tool import calculate_trade_penalty as _calculate_trade_penalty

from shared.jwt_verify import verify_bearer_token
from shared.error_schema import mcp_error_dict

# Disable DNS-rebinding protection: this server runs inside Docker and is only
# reachable by the gateway service — cross-container Host headers (e.g.
# "servicing-mcp:8082") would otherwise be rejected with HTTP 421.
mcp = FastMCP(
    "Meridian Asset Servicing",
    instructions=(
        "Asset Servicing domain agents for the Meridian demo bank. "
        "Each agent lives in its own subfolder with schema, prompts, OTel spans, "
        "and (for corporate_actions) a RAG knowledge base. "
        "Fault knobs are controlled via env vars."
    ),
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)


@mcp.tool()
def get_custody_positions(relationship_id: str) -> str:
    """
    Get assets held at each custodian for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: holdings_by_custodian[], as_of_date
    """
    return _get_custody_positions(relationship_id)


@mcp.tool()
def get_settlements(relationship_id: str) -> str:
    """
    Get pending and failed settlement trades for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: pending[], failed[], as_of_date
    """
    return _get_settlements(relationship_id)


@mcp.tool()
def get_corporate_actions(relationship_id: str) -> str:
    """
    Get upcoming corporate actions (dividends, splits, rights) with regulatory context for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: upcoming_actions[], regulatory_context[], as_of_date
    """
    return _get_corporate_actions(relationship_id)


@mcp.tool()
def get_nav(fund_id: str) -> str:
    """
    Get the latest Net Asset Value (NAV) for a fund.
    Note: keyed by fund_id, NOT relationship_id — this is intentional.

    Args:
        fund_id: Fund identifier (e.g. FND-7781)

    Returns:
        JSON: nav, as_of_date, currency, aum
    """
    return _get_nav(fund_id)


@mcp.tool()
def get_cash(relationship_id: str) -> str:
    """
    Get cash balances and projected cash position for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: balances[], projected_cash_usd, as_of_date
    """
    return _get_cash(relationship_id)


@mcp.tool()
def analyze_settlement_risk(
    settlement_status: dict = {},
    custody_positions: dict = {},
    cash_position: dict = {},
) -> str:
    """
    Analyse settlement-risk from merged fan-in producer outputs.

    Args:
        settlement_status: get_settlements output projection.
        custody_positions: get_custody_positions output projection.
        cash_position: get_cash output projection.

    Returns:
        JSON: fails aging, CSDR cash-penalty exposure basis, and firm-policy flags
    """
    return _analyze_settlement_risk(settlement_status, custody_positions, cash_position)


@mcp.tool()
def calculate_trade_penalty(
    trade_id: str,
    security: str,
    settle_date: str,
    amount: float,
    side: str,
    as_of_date: str,
    isin: str = "",
    reason: str = "",
    fail_item: bool | None = False,
) -> str:
    """
    Calculate CSDR cash-penalty exposure for a single failed settlement.

    Args:
        trade_id: Failed trade identifier.
        security: Security symbol/name.
        settle_date: Contractual settlement date in ISO format.
        amount: Failed settlement cash amount.
        side: Trade side.
        as_of_date: Evaluation date in ISO format.
        isin: Optional instrument identifier.
        reason: Optional failure reason.
        fail_item: Test-only injected item failure flag.

    Returns:
        JSON: one failed-trade penalty calculation and policy flags.
    """
    return _calculate_trade_penalty(
        trade_id=trade_id,
        security=security,
        settle_date=settle_date,
        amount=amount,
        side=side,
        as_of_date=as_of_date,
        isin=isin,
        reason=reason,
        fail_item=fail_item,
    )


# ── HTTP middleware — lives at module scope so it applies whenever the app is
#    imported or run (not only when started via __main__). ────────────────────

class JwtAuthMiddleware(BaseHTTPMiddleware):
    """Verify JWT on all endpoints except /health."""
    async def dispatch(self, request: StarletteRequest, call_next):
        if request.url.path == "/health":
            return await call_next(request)
        auth = request.headers.get("Authorization")
        allowed, error, claims = verify_bearer_token(auth)
        if not allowed:
            _log.warning("servicing-mcp: rejected — %s (path=%s)", error, request.url.path)
            return JSONResponse(
                status_code=401,
                content=mcp_error_dict(error, "meridian.servicing.gateway", 401),
            )
        return await call_next(request)


async def _health(request: StarletteRequest) -> JSONResponse:
    return JSONResponse({
        "status": "ok",
        "service": "servicing-mcp",
        "version": "0.3.0",
        "agents": [
            "settlements",
            "corporate_actions",
            "custody",
            "nav",
            "cash",
            "settlement_risk",
            "settlement_penalty",
        ],
    })


def create_app() -> Starlette:
    """
    Create and return the ASGI application (importable for testing and ASGI runners).

    The JWT middleware is always applied — regardless of whether the server is
    started via __main__ (uvicorn directly) or imported by a test harness / ASGI
    server like gunicorn.
    """
    return Starlette(
        routes=[
            Route("/health", _health),
            Mount("/", app=mcp.sse_app()),
        ],
        middleware=[Middleware(JwtAuthMiddleware)],
    )


# Module-scope app — importable by tests, ASGI runners, and __main__.
app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8082)
