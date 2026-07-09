"""
Reads the gateway's own glass-box trace store (GET /trace/history, GET /trace/{requestId})
— the same endpoints the glass-box panel's live SSE view is backed by (see
gateway/src/main/java/ai/conduit/gateway/api/v1/trace/TraceStreamController.java).

This is how the harness gets EVIDENCE for each check: the resolved plan_graph, every
entitlement_check / check_denied decision, and each agent_start/agent_complete (with a
truncated dataPreview) for the specific request a BFF turn produced.
"""
from __future__ import annotations
import time
from typing import Any

import requests

from . import config


def latest_request_id_for_conversation(conversation_id: str, attempts: int = 6,
                                        delay_s: float = 1.0) -> str | None:
    """The BFF forwards the Mongo conversation id as X-Conversation-Id, which the gateway
    uses as the trace grouping key. Trace persistence can lag a turn's SSE completion by a
    beat, so retry briefly rather than fail on a race."""
    for _ in range(attempts):
        resp = requests.get(
            f"{config.GATEWAY_URL}/trace/history",
            params={"conversationId": conversation_id, "limit": 1},
            timeout=10,
        )
        if resp.ok:
            ids = resp.json().get("requestIds", [])
            if ids:
                return ids[0]
        time.sleep(delay_s)
    return None


def events_for_request(request_id: str) -> list[dict[str, Any]]:
    resp = requests.get(f"{config.GATEWAY_URL}/trace/{request_id}", timeout=10)
    resp.raise_for_status()
    return resp.json()


def trace_for_conversation(conversation_id: str) -> tuple[str | None, list[dict[str, Any]]]:
    """Convenience: latest requestId + its full event list for a conversation."""
    rid = latest_request_id_for_conversation(conversation_id)
    if rid is None:
        return None, []
    return rid, events_for_request(rid)


def events_of_type(events: list[dict[str, Any]], event_type: str) -> list[dict[str, Any]]:
    return [e for e in events if e.get("type") == event_type]
