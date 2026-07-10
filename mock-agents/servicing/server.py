"""
Asset Servicing MCP server — Domain 2 mock agents.
Exposes the tools over MCP Streamable HTTP transport via FastMCP (single POST /mcp endpoint).

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

The gateway's McpAdapter connects to: http://servicing-mcp:8082/mcp

Distributed Tracing Note (Streamable HTTP):
  Over Streamable HTTP each JSON-RPC call is a real HTTP POST, so the W3C
  traceparent / tracestate headers the gateway injects DO now arrive at this
  server (unlike the legacy SSE transport, where per-tool-call headers were not
  available). FastMCP still does not automatically open a child span from that
  header inside individual tool handlers, so MCP tool spans are not yet linked
  to the gateway root span in Tempo.

  Future path: read the incoming traceparent (or an {"_traceId": ...} arg) in a
  handler and manually create a linked child span. A warning is emitted at
  startup to keep this visible.
"""

import sys
import os
import logging
logging.basicConfig(level=logging.INFO)

sys.path.insert(0, os.path.dirname(__file__))

_log = logging.getLogger(__name__)

# Over Streamable HTTP the gateway's W3C traceparent header DOES reach this server on
# each POST /mcp, but FastMCP does not auto-open a linked child span inside tool handlers.
# So agent tool spans still won't appear as children of the gateway root span in Tempo
# until a handler reads the header (or an {"_traceId": ...} arg) and links a span manually.
_log.warning(
    "servicing-mcp: Streamable HTTP delivers traceparent per call, but FastMCP does not "
    "auto-link tool spans. Agent tool spans will NOT appear as children of the gateway root "
    "span in Tempo until handlers read traceparent/_traceId and link a span manually."
)

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from starlette.applications import Starlette
from starlette.middleware import Middleware
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request as StarletteRequest
from starlette.responses import JSONResponse, Response
from starlette.routing import Route

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
    # Return each Streamable HTTP response as a single application/json body rather than an
    # SSE-upgraded stream. We wrap the app in a BaseHTTPMiddleware (JwtAuthMiddleware) to enforce
    # F-IDENTITY, and Starlette's BaseHTTPMiddleware is incompatible with streaming SSE response
    # bodies (it raises "Unexpected message received: http.request" and drops the body). A plain
    # JSON response is valid Streamable HTTP and is what the gateway's McpAdapter expects first;
    # the adapter still handles an SSE-upgraded body from any other server.
    json_response=True,
)


@mcp.tool()
async def get_custody_positions(relationship_id: str) -> str:
    """
    Get assets held at each custodian for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: holdings_by_custodian[], as_of_date
    """
    return await _get_custody_positions(relationship_id)


@mcp.tool()
async def get_settlements(relationship_id: str) -> str:
    """
    Get pending and failed settlement trades for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: pending[], failed[], as_of_date
    """
    return await _get_settlements(relationship_id)


@mcp.tool()
async def get_corporate_actions(relationship_id: str) -> str:
    """
    Get upcoming corporate actions (dividends, splits, rights) with regulatory context for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: upcoming_actions[], regulatory_context[], as_of_date
    """
    return await _get_corporate_actions(relationship_id)


@mcp.tool()
async def get_nav(fund_id: str) -> str:
    """
    Get the latest Net Asset Value (NAV) for a fund.
    Note: keyed by fund_id, NOT relationship_id — this is intentional.

    Args:
        fund_id: Fund identifier (e.g. FND-7781)

    Returns:
        JSON: nav, as_of_date, currency, aum
    """
    return await _get_nav(fund_id)


@mcp.tool()
async def get_cash(relationship_id: str) -> str:
    """
    Get cash balances and projected cash position for a relationship.

    Args:
        relationship_id: Unique relationship identifier (e.g. REL-00042)

    Returns:
        JSON: balances[], projected_cash_usd, as_of_date
    """
    return await _get_cash(relationship_id)


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

async def _rehydrate_body(request: StarletteRequest, body: bytes) -> None:
    """
    Replay an already-consumed request body so the downstream ASGI app (FastMCP's
    sse_app) can still read it. Standard Starlette workaround for peeking a POST body
    inside BaseHTTPMiddleware: overriding `_receive` makes `request.receive()` — which
    `call_next` feeds straight through to the mounted app — replay the cached bytes
    instead of trying to read an already-drained stream.
    """
    async def receive():
        return {"type": "http.request", "body": body, "more_body": False}
    request._receive = receive


def _jsonrpc_method(body: bytes) -> str | None:
    """Best-effort extraction of the JSON-RPC `method` field from an MCP POST body."""
    try:
        import json
        return json.loads(body).get("method")
    except Exception:
        return None


class JwtAuthMiddleware(BaseHTTPMiddleware):
    """
    Verify JWT on every DATA call (F-IDENTITY: fail CLOSED — see shared/jwt_verify.py).

    Exemptions (legitimately have no caller identity):
      - GET /health
      - An MCP channel-open GET (legacy SSE stream open, or an optional Streamable HTTP
        server-to-client GET /mcp) — opening a stream discloses no data by itself; caller
        identity is enforced on the JSON-RPC call that follows.
      - JSON-RPC 'initialize' / 'tools/list' over POST (POST /mcp for Streamable HTTP; POST
        /messages for legacy SSE) — this is exactly the gateway's boot-time
        McpToolIntrospector handshake (see
        gateway/.../registry/introspection/McpToolIntrospector.java), which runs before any
        user request exists and so never carries a caller token.

    Every other call — in particular JSON-RPC 'tools/call', the actual data-plane
    invocation the gateway's McpAdapter makes on behalf of a chat request — requires a
    valid `Authorization: Bearer <token>` and is rejected (401) without one.
    """
    async def dispatch(self, request: StarletteRequest, call_next):
        if request.url.path == "/health":
            return await call_next(request)

        auth = request.headers.get("Authorization")

        if not auth and request.method == "GET":
            # SSE channel-open — no JSON-RPC method exists yet at this step, and no data
            # is disclosed by merely opening the stream. The tools/call gate below is
            # what actually protects data.
            return await call_next(request)

        if not auth and request.method == "POST":
            body = await request.body()
            method = _jsonrpc_method(body)
            if method in ("initialize", "tools/list"):
                await _rehydrate_body(request, body)
                return await call_next(request)
            _log.warning(
                "servicing-mcp: rejected unauthenticated JSON-RPC '%s' (path=%s)",
                method, request.url.path,
            )
            return JSONResponse(
                status_code=401,
                content=mcp_error_dict("Missing Authorization header", "meridian.servicing.gateway", 401),
            )

        allowed, error, claims = verify_bearer_token(auth)
        if not allowed:
            _log.warning("servicing-mcp: rejected — %s (path=%s)", error, request.url.path)
            return JSONResponse(
                status_code=401,
                content=mcp_error_dict(error, "meridian.servicing.gateway", 401),
            )
        if claims:
            request.state.principal = claims.get("sub")
        response = await call_next(request)
        if claims and claims.get("sub"):
            response.headers["X-Conduit-Verified-Sub"] = claims["sub"]
        return response


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

    Streamable HTTP transport: the whole exchange (initialize → tools/call) happens on a single
    endpoint, ``POST /mcp``. We build ON FastMCP's ``streamable_http_app()`` rather than mounting
    it under a parent Starlette, because that app carries a REQUIRED lifespan
    (``self.session_manager.run()``) and Starlette does NOT run a *mounted* sub-app's lifespan —
    mounting it would leave the StreamableHTTP session manager un-started and every request would
    fail. Building on it keeps that lifespan intact.

    We then:
      * insert ``GET /health`` ahead of the ``/mcp`` route (health is JWT-exempt), and
      * wrap the whole app in JwtAuthMiddleware (F-IDENTITY: fail CLOSED) via ``add_middleware`` —
        applied whether the server is started via __main__ (uvicorn) or imported by a test
        harness / ASGI runner.
    """
    app = mcp.streamable_http_app()          # Starlette with lifespan = session_manager.run()
    app.router.routes.insert(0, Route("/health", _health))
    app.add_middleware(JwtAuthMiddleware)    # outermost — runs before the /mcp handler
    return app


# Module-scope app — importable by tests, ASGI runners, and __main__.
app = create_app()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8082)
