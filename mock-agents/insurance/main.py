"""
Insurance HTTP service — Domain 2 (World B) mock agents.
FastAPI auto-generates the OpenAPI spec at /openapi.json,
which the gateway registry introspects (no gateway code change).

Agent layout (each agent in its own top-level subfolder):
  insurance/policy_details/   → GET /policy-details
  insurance/claim_status/     → GET /claim-status

Fault knobs (all endpoints): ?_delay_ms=<n>  ?_fail=true
Port: 8087
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
from policy_details.handler import router as policy_details_router
from claim_status.handler import router as claim_status_router

log = logging.getLogger(__name__)

app = FastAPI(
    title="Meridian Insurance Service",
    description=(
        "Mock Insurance HTTP agents for the Meridian World-B demo. "
        "Each agent lives in its own subfolder (policy_details/, claim_status/). "
        "All endpoints support ?_delay_ms and ?_fail fault knobs."
    ),
    version="0.1.0",
    contact={"name": "Meridian Platform Team"},
    license_info={"name": "Internal Demo"},
)

# Wire OTel distributed tracing — picks up W3C traceparent from the gateway
# and creates child spans, completing the gateway root → agent child trace.
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
            "insurance-http: rejected request — %s (path=%s) trace_id=%s convId=%s",
            error, request.url.path, trace_id,
            request.headers.get("x-conversation-id", "none"),
        )
        return JSONResponse(status_code=401, content={"detail": error})
    if claims:
        request.state.principal = claims.get("sub")
    return await call_next(request)


app.middleware("http")(fault_knob_middleware)

app.include_router(policy_details_router)
app.include_router(claim_status_router)


@app.get("/health", tags=["infra"], summary="Health check")
def health():
    return {
        "status": "ok",
        "service": "insurance-http",
        "version": "0.1.0",
        "agents": ["policy_details", "claim_status"],
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run("main:app", host="0.0.0.0", port=8087, reload=False)
