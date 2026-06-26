"""
Wealth Management HTTP service — Domain 1 mock agents.
FastAPI auto-generates the OpenAPI spec at /openapi.json,
which the gateway registry introspects in Phase 3.

Agent layout (each agent in its own top-level subfolder):
  wealth/holdings/          → GET /holdings
  wealth/performance/       → GET /performance
  wealth/goal_planning/     → GET /goal-planning
  wealth/risk_profile/      → GET /risk-profile  (RAG-augmented)

Fault knobs (all endpoints): ?_delay_ms=<n>  ?_fail=true
"""

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

import logging
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from shared.fault_knobs import fault_knob_middleware
from shared.jwt_verify import verify_bearer_token
from holdings.handler import router as holdings_router
from performance.handler import router as performance_router
from goal_planning.handler import router as goal_planning_router
from risk_profile.handler import router as risk_profile_router

log = logging.getLogger(__name__)

app = FastAPI(
    title="Meridian Wealth Management Service",
    description=(
        "Mock Wealth Management HTTP agents for the Meridian demo. "
        "Each agent lives in its own subfolder (holdings/, performance/, "
        "risk_profile/ [RAG-augmented], goal_planning/). "
        "All endpoints support ?_delay_ms and ?_fail fault knobs."
    ),
    version="0.3.0",
    contact={"name": "Meridian Platform Team"},
    license_info={"name": "Internal Demo"},
)

@app.middleware("http")
async def jwt_auth_middleware(request: Request, call_next):
    """Verify JWT on every request except /health and /openapi.json."""
    if request.url.path in ("/health", "/openapi.json", "/docs", "/redoc"):
        return await call_next(request)
    auth = request.headers.get("Authorization")
    allowed, error, claims = verify_bearer_token(auth)
    if not allowed:
        log.warning("wealth-http: rejected request — %s (path=%s)", error, request.url.path)
        return JSONResponse(status_code=401, content={"detail": error})
    if claims:
        request.state.principal = claims.get("sub")
    return await call_next(request)

app.middleware("http")(fault_knob_middleware)

app.include_router(holdings_router)
app.include_router(performance_router)
app.include_router(goal_planning_router)
app.include_router(risk_profile_router)


@app.get("/health", tags=["infra"], summary="Health check")
def health():
    return {
        "status": "ok",
        "service": "wealth-http",
        "version": "0.3.0",
        "agents": ["holdings", "performance", "risk_profile", "goal_planning"],
    }
