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

from fastapi import FastAPI
from shared.fault_knobs import fault_knob_middleware
from holdings.handler import router as holdings_router
from performance.handler import router as performance_router
from goal_planning.handler import router as goal_planning_router
from risk_profile.handler import router as risk_profile_router

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
