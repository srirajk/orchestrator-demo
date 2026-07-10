"""Live metrics pillar gate: RED metrics, saturation, dashboard, pivots, alerts."""
from __future__ import annotations

import concurrent.futures
import re
import time
from pathlib import Path
from typing import Any

import pytest
import requests

from lib import config, iam_client
from lib.evidence import evidence


ROOT = Path(__file__).resolve().parents[3]
GAUGE_RE = re.compile(r"^conduit_gateway_inflight_requests(?:\{[^}]*\})?\s+([0-9.]+)$", re.MULTILINE)


def _gateway_sse(prompt: str, token: str) -> tuple[int, str]:
    resp = requests.post(
        f"{config.GATEWAY_URL}/v1/chat/completions",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        json={
            "model": "conduit-assistant",
            "stream": True,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=config.CHAT_TIMEOUT_S,
    )
    return resp.status_code, resp.text


def _prometheus_text() -> str:
    resp = requests.get(f"{config.GATEWAY_URL}/actuator/prometheus", timeout=10)
    resp.raise_for_status()
    return resp.text


def _inflight_value() -> float:
    text = _prometheus_text()
    match = GAUGE_RE.search(text)
    assert match, "conduit_gateway_inflight_requests missing from /actuator/prometheus"
    return float(match.group(1))


def _wait_for_idle(timeout_s: float = 30.0) -> float:
    deadline = time.time() + timeout_s
    last = None
    while time.time() < deadline:
        last = _inflight_value()
        if last == 0:
            return last
        time.sleep(0.5)
    raise AssertionError(f"in-flight gauge did not return to zero; last={last}")


def _prom_query(query: str) -> list[dict[str, Any]]:
    resp = requests.get(
        f"{config.PROMETHEUS_URL}/api/v1/query",
        params={"query": query},
        timeout=10,
    )
    resp.raise_for_status()
    body = resp.json()
    assert body.get("status") == "success", body
    return body.get("data", {}).get("result", [])


def _wait_for_prom_series(query: str, timeout_s: float = 45.0) -> list[dict[str, Any]]:
    deadline = time.time() + timeout_s
    last: list[dict[str, Any]] = []
    while time.time() < deadline:
        last = _prom_query(query)
        if last:
            return last
        time.sleep(3.0)
    raise AssertionError(f"Prometheus query had no series: {query}; last={last}")


def _grafana_get(path: str) -> dict[str, Any]:
    resp = requests.get(
        f"{config.GRAFANA_URL}{path}",
        auth=(config.GRAFANA_USER, config.GRAFANA_PASSWORD),
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json()


def _dashboard_exprs(dashboard: dict[str, Any]) -> set[str]:
    exprs: set[str] = set()
    for panel in dashboard.get("dashboard", {}).get("panels", []):
        for target in panel.get("targets", []):
            expr = target.get("expr")
            if expr:
                exprs.add(expr)
    return exprs


def test_gateway_metrics_saturation_dashboard_pivots_and_alerts():
    token = iam_client.get_jwt(config.USER_ENTITLED)
    assert _wait_for_idle() == 0

    prompts = [
        f"Is the {config.WHITMAN_NAME} over-concentrated?",
        f"What is the concentration risk in the {config.WHITMAN_NAME} holdings?",
        f"Give me a full overview of the {config.WHITMAN_NAME} relationship {config.WHITMAN_RELATIONSHIP_ID}.",
    ]
    peak = 0.0
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(prompts)) as pool:
        futures = [pool.submit(_gateway_sse, prompt, token) for prompt in prompts]
        while any(not f.done() for f in futures):
            peak = max(peak, _inflight_value())
            time.sleep(0.2)
        results = [f.result() for f in futures]

    assert all(status == 200 for status, _ in results), results
    assert peak >= 1, f"in-flight gauge never moved under concurrent load; peak={peak}"
    idle = _wait_for_idle()

    prometheus = _prometheus_text()
    required_metrics = [
        "conduit_gateway_requests_total",
        "conduit_gateway_request_duration_seconds_bucket",
        "conduit_gateway_stage_duration_seconds_bucket",
        "conduit_gateway_inflight_requests",
        "conduit_agent_latency_seconds_bucket",
        "conduit_fanout_duration_seconds_bucket",
        "conduit_authz_decisions_total",
        "jvm_memory_used_bytes",
        "jvm_threads_live_threads",
        "jvm_gc_pause_seconds",
    ]
    missing = [metric for metric in required_metrics if metric not in prometheus]
    assert not missing, f"/actuator/prometheus missing metrics: {missing}"
    assert re.search(r'conduit_gateway_requests_total\{[^}]*path="dag"', prometheus), (
        "request RED metric missing path=dag tag after DAG traffic"
    )
    assert re.search(r'conduit_gateway_requests_total\{[^}]*outcome="ANSWERED"', prometheus), (
        "request RED metric missing outcome=ANSWERED tag"
    )

    dashboard = _grafana_get("/api/dashboards/uid/gateway-health-slo")
    assert dashboard.get("dashboard", {}).get("title") == "Gateway Health / SLO"
    exprs = _dashboard_exprs(dashboard)
    for fragment in [
        "conduit_gateway_requests_total",
        "conduit_gateway_request_duration_seconds_bucket",
        "conduit_gateway_inflight_requests",
        "conduit_gateway_stage_duration_seconds_bucket",
        "conduit_agent_calls_total",
        "conduit_dag_fallback_total",
        "conduit_coverage_unavailable_total",
        "conduit_jwks_refresh_failures_total",
        "jvm_memory_used_bytes",
    ]:
        assert any(fragment in expr for expr in exprs), f"dashboard target missing {fragment}"

    _wait_for_prom_series("conduit_gateway_inflight_requests")
    _wait_for_prom_series("conduit_gateway_requests_total")
    _wait_for_prom_series("conduit_gateway_request_duration_seconds_count")

    tempo = _grafana_get("/api/datasources/uid/tempo")
    loki = _grafana_get("/api/datasources/uid/loki")
    traces_to_logs = tempo.get("jsonData", {}).get("tracesToLogsV2", {})
    assert traces_to_logs.get("datasourceUid") == "loki"
    assert traces_to_logs.get("filterByTraceID") is True
    assert "traceId" in traces_to_logs.get("query", "")
    derived = loki.get("jsonData", {}).get("derivedFields", [])
    assert any(field.get("datasourceUid") == "tempo" for field in derived)
    assert any("traceId" in field.get("matcherRegex", "") for field in derived)

    rules = requests.get(f"{config.PROMETHEUS_URL}/api/v1/rules", timeout=10)
    rules.raise_for_status()
    alert_names = {
        rule.get("name")
        for group in rules.json().get("data", {}).get("groups", [])
        for rule in group.get("rules", [])
        if rule.get("type") == "alerting"
    }
    expected_alerts = {
        "GatewaySaturationApproachingLivelock",
        "GatewayErrorRateSpike",
        "GatewayLatencySLOBreach",
        "DagFallbackRateSpike",
        "CoverageServiceUnavailable",
        "JwksRefreshFailure",
        "GatewayDown",
    }
    assert expected_alerts <= alert_names

    rules_text = (ROOT / "infra" / "alerting-rules.yml").read_text()
    assert "conduit_gateway_inflight_requests" in rules_text
    assert ">= 20" in rules_text
    evidence("observability metrics gate", {
        "peak_inflight": peak,
        "idle_inflight": idle,
        "dashboard": dashboard.get("dashboard", {}).get("uid"),
        "alerts": sorted(expected_alerts),
    })
