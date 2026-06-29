"""
Standard error schema for Meridian insurance agents.

Every 4xx/5xx response uses error_response() to produce a consistent envelope:
  {error, agent_id, trace_id, status_code}

This satisfies the Meridian Agent Compliance Contract (error_schema check).
"""
from pydantic import BaseModel
from fastapi.responses import JSONResponse
from opentelemetry import trace


class ErrorResponse(BaseModel):
    error: str
    agent_id: str
    trace_id: str
    status_code: int


def error_response(status_code: int, error: str, agent_id: str) -> JSONResponse:
    """Return a JSONResponse conforming to the Meridian standard error schema."""
    span = trace.get_current_span()
    ctx = span.get_span_context() if span else None
    tid = format(ctx.trace_id, "032x") if ctx and ctx.is_valid else "0" * 32
    return JSONResponse(
        status_code=status_code,
        content={
            "error": error,
            "agent_id": agent_id,
            "trace_id": tid,
            "status_code": status_code,
        },
    )
