"""
Fault-knob middleware for the Wealth HTTP service.
Supports ?_delay_ms=<n> (inject latency) and ?_fail=true (force 503).
"""
import asyncio

from fastapi import Request
from fastapi.responses import JSONResponse


async def fault_knob_middleware(request: Request, call_next):
    params = request.query_params
    fail = params.get("_fail", "").lower() in ("true", "1", "yes")
    try:
        delay_ms = int(params.get("_delay_ms", 0))
    except (ValueError, TypeError):
        delay_ms = 0

    if fail:
        return JSONResponse(
            status_code=503,
            content={
                "error": "fault knob triggered",
                "fault_knob": "_fail=true",
                "detail": "This 503 is intentional — used to test resilience in the gateway.",
            },
        )

    if delay_ms > 0:
        await asyncio.sleep(delay_ms / 1000)

    return await call_next(request)
