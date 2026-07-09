"""
Fault-knob middleware for the Wealth HTTP service.
Supports ?_delay_ms=<n> (inject latency) and ?_fail=true (force 503).

Standard error schema (Meridian agent contract):
  {error, agent_id, trace_id, status_code, detail}
"""
import asyncio

from fastapi import Request
from shared.error_schema import error_response


AGENT_ID = "meridian.wealth.http"


async def fault_knob_middleware(request: Request, call_next):
    params = request.query_params
    fail = params.get("_fail", "").lower() in ("true", "1", "yes")
    try:
        delay_ms = int(params.get("_delay_ms", 0))
    except (ValueError, TypeError):
        delay_ms = 0

    if fail:
        return error_response(
            503,
            "fault knob triggered — This 503 is intentional, used to test resilience in the gateway.",
            AGENT_ID,
        )

    if delay_ms > 0:
        await asyncio.sleep(delay_ms / 1000)

    return await call_next(request)
