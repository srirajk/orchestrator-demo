"""
Wealth Market Research HTTP service — knowledge agent.
FastAPI auto-generates the OpenAPI spec at /openapi.json,
which the gateway registry introspects at startup.

Agent layout:
  wealth_market_research/market_research/  → GET /market-research

This is a KNOWLEDGE agent: returns house-view commentary and sector outlooks.
No client entity is required. Accessed by Wealth segment users (internal classification).

Fault knobs (all endpoints): ?_delay_ms=<n>  ?_fail=true
Port: 8089
"""

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

import logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from opentelemetry import trace as otel_trace
from shared.fault_knobs import fault_knob_middleware
from shared.jwt_verify import verify_bearer_token
from shared.telemetry import setup_telemetry
from market_research.handler import router as market_research_router

log = logging.getLogger(__name__)

app = FastAPI(
    title="Meridian Wealth Market Research Service",
    description=(
        "Mock Wealth Market Research HTTP agent for the Meridian demo. "
        "Serves house-view / market commentary / sector outlooks. "
        "Knowledge agent — no client entity required; audience: segment (wealth). "
        "Supports ?_delay_ms and ?_fail fault knobs."
    ),
    version="1.0.0",
    contact={"name": "Meridian Platform Team"},
    license_info={"name": "Internal Demo"},
)

# Wire OTel distributed tracing — picks up W3C traceparent from the gateway
# and creates child spans, completing the gateway root → agent child trace in Tempo.
setup_telemetry(app)


@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    """Verify JWT on every request except /health and /openapi.json."""
    if request.url.path in ("/health", "/openapi.json", "/docs", "/redoc"):
        return await call_next(request)
    auth = request.headers.get("Authorization")
    allowed, error, claims = verify_bearer_token(auth)
    if not allowed:
        ctx = otel_trace.get_current_span().get_span_context()
        trace_id = format(ctx.trace_id, "032x") if ctx and ctx.is_valid else "none"
        log.warning(
            "wealth-market-research: rejected request — %s (path=%s) trace_id=%s convId=%s",
            error, request.url.path, trace_id,
            request.headers.get("x-conversation-id", "none"),
        )
        return JSONResponse(status_code=401, content={"detail": error})
    if claims:
        request.state.principal = claims.get("sub")
    response = await call_next(request)
    if claims and claims.get("sub"):
        response.headers["X-Conduit-Verified-Sub"] = claims["sub"]
    return response


app.middleware("http")(fault_knob_middleware)

app.include_router(market_research_router)


@app.get("/health", tags=["infra"], summary="Health check")
def health():
    return {
        "status": "ok",
        "service": "wealth-market-research",
        "version": "1.0.0",
        "agents": ["market_research"],
        "agent_type": "knowledge",
        "audience": "segment",
        "domain": "wealth-management",
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8089, reload=False)
