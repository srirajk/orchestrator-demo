"""
Asset Servicing MCP server — Domain 2 mock agents.
Exposes 5 tools over SSE transport via FastMCP.

Agent layout (each agent in its own top-level subfolder):
  servicing/settlements/          → get_settlements
  servicing/corporate_actions/    → get_corporate_actions (RAG-augmented)
  servicing/custody/              → get_custody_positions
  servicing/nav/                  → get_nav  (keyed by fund_id, not relationship_id)
  servicing/cash/                 → get_cash

Fault knobs (env vars, set in docker-compose for resilience tests):
  MCP_FAULT_TOOL=get_settlements   → that tool returns an error
  MCP_FAULT_ALL=true               → all tools fail
  MCP_FAULT_DELAY_MS=500           → inject latency (ms) to every call

The gateway's McpAdapter connects to: http://servicing:8082/sse
"""

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from settlements.tool import get_settlements as _get_settlements
from corporate_actions.tool import get_corporate_actions as _get_corporate_actions
from custody.tool import get_custody_positions as _get_custody_positions
from nav.tool import get_nav as _get_nav
from cash.tool import get_cash as _get_cash

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


if __name__ == "__main__":
    import logging
    import uvicorn
    from starlette.applications import Starlette
    from starlette.middleware import Middleware
    from starlette.middleware.base import BaseHTTPMiddleware
    from starlette.requests import Request as StarletteRequest
    from starlette.responses import JSONResponse, Response
    from starlette.routing import Route, Mount

    from shared.jwt_verify import verify_bearer_token

    _log = logging.getLogger(__name__)

    class JwtAuthMiddleware(BaseHTTPMiddleware):
        """Verify JWT on all endpoints except /health."""
        async def dispatch(self, request: StarletteRequest, call_next):
            if request.url.path == "/health":
                return await call_next(request)
            auth = request.headers.get("Authorization")
            allowed, error, claims = verify_bearer_token(auth)
            if not allowed:
                _log.warning("servicing-mcp: rejected — %s (path=%s)", error, request.url.path)
                return Response(content=error, status_code=401, media_type="text/plain")
            return await call_next(request)

    async def health(request):
        return JSONResponse({
            "status": "ok",
            "service": "servicing-mcp",
            "version": "0.3.0",
            "agents": ["settlements", "corporate_actions", "custody", "nav", "cash"],
        })

    app = Starlette(
        routes=[
            Route("/health", health),
            Mount("/", app=mcp.sse_app()),
        ],
        middleware=[Middleware(JwtAuthMiddleware)],
    )

    uvicorn.run(app, host="0.0.0.0", port=8082)
