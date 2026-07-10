"""Live E2E observability proof against Tempo and Loki."""
from __future__ import annotations

import time
import base64
from typing import Any

import requests

from lib import bff_client, config, trace_client
from lib.evidence import evidence


def _wait_for_tempo_trace(trace_id: str, attempts: int = 20, delay_s: float = 2.0) -> dict:
    url = f"{config.TEMPO_URL}/api/traces/{trace_id}"
    last = None
    for _ in range(attempts):
        resp = requests.get(url, timeout=10)
        last = {"status": resp.status_code, "body": resp.text[:500]}
        if resp.status_code == 200:
            body = resp.json()
            if _spans(body):
                return body
        time.sleep(delay_s)
    raise AssertionError(f"Tempo trace {trace_id} not available: {last}")


def _wait_for_loki_trace_logs(trace_id: str, attempts: int = 20, delay_s: float = 2.0) -> list[str]:
    url = f"{config.LOKI_URL}/loki/api/v1/query_range"
    params = {
        "query": f'{{traceId="{trace_id}"}}',
        "limit": "50",
    }
    last = None
    for _ in range(attempts):
        resp = requests.get(url, params=params, timeout=10)
        last = {"status": resp.status_code, "body": resp.text[:500]}
        if resp.status_code == 200:
            streams = resp.json().get("data", {}).get("result", [])
            lines = [line for stream in streams for _, line in stream.get("values", [])]
            if lines:
                return lines
        time.sleep(delay_s)
    raise AssertionError(f"Loki had no logs labelled with traceId={trace_id}: {last}")


def _spans(trace: dict) -> list[dict[str, Any]]:
    out: list[dict[str, Any]] = []
    for batch in trace.get("batches", []):
        resource_attrs = _attrs(batch.get("resource", {}).get("attributes", []))
        for scope in batch.get("scopeSpans", []):
            for span in scope.get("spans", []):
                span["_resource"] = resource_attrs
                out.append(span)
    return out


def _attrs(items: list[dict]) -> dict[str, Any]:
    return {item.get("key"): _value(item.get("value", {})) for item in items}


def _value(value: dict) -> Any:
    if "stringValue" in value:
        return value["stringValue"]
    if "doubleValue" in value:
        return float(value["doubleValue"])
    if "intValue" in value:
        return int(value["intValue"])
    if "boolValue" in value:
        return bool(value["boolValue"])
    if "arrayValue" in value:
        return [_value(v) for v in value["arrayValue"].get("values", [])]
    return value


def _span_attrs(span: dict) -> dict[str, Any]:
    return _attrs(span.get("attributes", []))


def _span_trace_matches(span: dict, trace_id: str) -> bool:
    value = span.get("traceId")
    if value in (None, trace_id):
        return True
    try:
        return base64.b64decode(value).hex() == trace_id
    except Exception:
        return False


def _wealth_holdings_agent_spans(spans: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        s for s in spans
        if s.get("_resource", {}).get("service.name") == "conduit-wealth-http"
        and (
            _span_attrs(s).get("agent.id") == "meridian.wealth.holdings"
            or "holdings" in (s.get("name") or "").lower()
        )
    ]


def _decision_attributes_ready(spans: list[dict[str, Any]]) -> bool:
    all_attrs = [_span_attrs(s) for s in spans]
    return (
        any("conduit.routing.top_score" in a and "conduit.routing.margin" in a for a in all_attrs)
        and any(a.get("conduit.coverage.decision") == "allow" for a in all_attrs)
        and any(a.get("conduit.grounding.verdict") == "all-grounded" for a in all_attrs)
    )


def test_observability_e2e_trace_logs_and_decision_attributes(jane_session):
    turn = bff_client.ask(
        jane_session,
        f"Is the {config.WHITMAN_NAME} over-concentrated?",
    )
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    request_id, glass_events = trace_client.trace_for_conversation(turn.conversation_id)
    assert request_id, "No glass-box requestId found for observability turn"
    evidence("observability glass trace", {
        "requestId": request_id,
        "conversationId": turn.conversation_id,
        "answer": turn.answer_text,
        "event_count": len(glass_events),
    })

    tempo_trace = _wait_for_tempo_trace(request_id)
    spans = _spans(tempo_trace)
    evidence("tempo span names", [
        {
            "name": s.get("name"),
            "service.name": s.get("_resource", {}).get("service.name"),
            "parentSpanId": s.get("parentSpanId"),
        }
        for s in spans
    ])

    agent_spans = _wealth_holdings_agent_spans(spans)
    for _ in range(10):
        if agent_spans:
            break
        time.sleep(2.0)
        tempo_trace = _wait_for_tempo_trace(request_id, attempts=1, delay_s=0)
        spans = _spans(tempo_trace)
        agent_spans = _wealth_holdings_agent_spans(spans)
    assert agent_spans, "Tempo trace did not contain the wealth holdings agent span"
    assert all(_span_trace_matches(s, request_id) for s in agent_spans), (
        "Agent spans were not in the gateway trace"
    )
    assert any(s.get("parentSpanId") and s["parentSpanId"] != "0000000000000000" for s in agent_spans), (
        "Agent span appeared as a fresh root instead of a child span"
    )
    assert all(s.get("_resource", {}).get("service.name") != "unknown_service" for s in agent_spans)

    loki_lines = _wait_for_loki_trace_logs(request_id)
    evidence("loki traceId logs", loki_lines[:10])
    assert any("conduit-gateway" in line or "handleChat" in line for line in loki_lines), (
        "Loki returned traceId-labelled logs, but none looked like gateway request logs"
    )

    for _ in range(10):
        if _decision_attributes_ready(spans):
            break
        time.sleep(2.0)
        tempo_trace = _wait_for_tempo_trace(request_id, attempts=1, delay_s=0)
        spans = _spans(tempo_trace)

    all_attrs = [_span_attrs(s) for s in spans]
    assert any("conduit.routing.top_score" in a and "conduit.routing.margin" in a for a in all_attrs), (
        "Tempo trace missing routing score/margin attributes"
    )
    assert any(a.get("conduit.coverage.decision") == "allow" for a in all_attrs), (
        "Tempo trace missing coverage allow decision attribute"
    )
    assert any(a.get("conduit.grounding.verdict") == "all-grounded" for a in all_attrs), (
        "Tempo trace missing grounded-answer validator verdict"
    )
