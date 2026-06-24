"""
Wealth Management HTTP service — Domain 1 mock agent.
FastAPI auto-generates the OpenAPI spec at /openapi.json,
which the gateway registry introspects in Phase 3.

Agent IDs:
  acme.wealth.holdings        → GET /holdings
  acme.wealth.performance     → GET /performance
  acme.wealth.goal_planning   → GET /goal-planning
  acme.wealth.risk_profile    → GET /risk-profile

Fault knobs (all endpoints): ?_delay_ms=<n>  ?_fail=true
"""

from fastapi import FastAPI
from middleware.fault_knobs import fault_knob_middleware
from routers import holdings, performance, goal_planning, risk_profile

app = FastAPI(
    title="Meridian Wealth Management Service",
    description=(
        "Mock Wealth Management HTTP agent for the Meridian demo. "
        "Returns canned portfolio data for three demo relationships. "
        "Supports ?_delay_ms and ?_fail fault knobs on every endpoint."
    ),
    version="0.2.0",
    contact={"name": "Meridian Platform Team"},
    license_info={"name": "Internal Demo"},
)

app.middleware("http")(fault_knob_middleware)

app.include_router(holdings.router)
app.include_router(performance.router)
app.include_router(goal_planning.router)
app.include_router(risk_profile.router)


@app.get("/health", tags=["infra"], summary="Health check")
def health():
    return {"status": "ok", "service": "wealth-http", "version": "0.2.0"}
