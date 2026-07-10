"""
Standard error schema for Meridian Asset Servicing MCP agents.

    THE ERROR ENVELOPE IS NOT THE ERROR SIGNAL.

An MCP tool reports an execution failure by RAISING. The SDK converts any exception into
`CallToolResult(isError=True)`, which is the one thing an MCP client can actually branch on.
A tool that *returns* an error object has, by definition, returned a successful result: the
client sees `isError` absent, treats the payload as data, and hands it downstream as ground
truth. That is how a dead upstream becomes a confident-looking answer.

This module previously exposed `mcp_error_json()` for tools to RETURN. Over HTTP that shape is
fine — the wealth/insurance agents pair it with `JSONResponse(status_code=...)`, and the *status
code* carries the signal. MCP has no status code. Copying the body shape across protocols lost
the signal, and the gateway counted 29 failed tool calls as successful ones.

  Tool execution failure  ->  raise AgentToolError(...)   -> result.isError == True   (client branches)
  Protocol failure        ->  JSON-RPC "error" member                                 (SDK handles)
  HTTP-level failure      ->  mcp_error_dict() + JSONResponse(status_code=...)        (status carries it)

`mcp_error_dict()` remains for the HTTP middleware path (e.g. JWT 401 responses), where a real
status code is available. There is deliberately no `mcp_error_json()` any more.

This satisfies the Meridian Agent Compliance Contract (error_schema check): the envelope shape is
unchanged, it now travels as the *message* of a raised error rather than as a returned value.
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


class AgentToolError(Exception):
    """
    Raise this from an MCP tool when it cannot produce the requested data.

    FastMCP wraps any exception escaping a tool into `ToolError`, and the low-level server
    turns that into `CallToolResult(content=[TextContent(...)], isError=True)`. So raising is
    what makes the failure visible to the client; returning never is.

        # right — the client sees isError=True and counts the call as failed
        raise AgentToolError("llm_unavailable: TimeoutError", AGENT_ID, 503)

        # wrong — indistinguishable from a successful answer
        return json.dumps({"error": "llm_unavailable", "status_code": 503})

    `str(exc)` is the Meridian error envelope as JSON, so the structured fields (agent_id,
    trace_id, status_code) survive into the `isError` result text for debugging and audit.

    Note the message must not enumerate the corpus (no "known funds: [...]"): a tool error is
    returned to the caller, and the caller may not be entitled to the identifiers it lists.
    """

    def __init__(self, error_message: str, agent_id: str, status_code: int = 500):
        self.error_message = error_message
        self.agent_id = agent_id
        self.status_code = status_code
        super().__init__(json.dumps(mcp_error_dict(error_message, agent_id, status_code)))
