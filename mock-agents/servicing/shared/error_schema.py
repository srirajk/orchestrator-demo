"""
Standard error schema for Meridian Asset Servicing MCP agents.

Every tool error uses mcp_error_json() to produce a consistent JSON-string envelope:
  {error, agent_id, trace_id, status_code}

For HTTP-level errors (e.g., JWT middleware 401 responses), call mcp_error_dict()
and pass the result directly to Starlette's JSONResponse.

This satisfies the Meridian Agent Compliance Contract (error_schema check),
matching the shape produced by the wealth and insurance HTTP services.
"""
import json

from opentelemetry import trace


def _get_trace_id() -> str:
    """Extract trace_id from the current OTel span, or return zeros if no active span."""
    span = trace.get_current_span()
    ctx = span.get_span_context() if span else None
    if ctx and ctx.is_valid:
        return format(ctx.trace_id, "032x")
    return "0" * 32


def mcp_error_dict(error_message: str, agent_id: str, status_code: int = 500) -> dict:
    """
    Return an error dict conforming to the Meridian standard error schema:
      {error, agent_id, trace_id, status_code}

    trace_id is extracted from the current OTel span (null-safe: returns zeros
    if no span is active, e.g., from HTTP middleware before a tool span starts).
    """
    return {
        "error": error_message,
        "agent_id": agent_id,
        "trace_id": _get_trace_id(),
        "status_code": status_code,
    }


def mcp_error_json(error_message: str, agent_id: str, status_code: int = 500) -> str:
    """
    Return a JSON string conforming to the Meridian standard error schema.

    Use this as the return value from MCP tool handlers — the shape is identical
    to what the wealth/insurance agents return on error, enabling uniform
    error-handling in the gateway.
    """
    return json.dumps(mcp_error_dict(error_message, agent_id, status_code))
