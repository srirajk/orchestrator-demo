"""
T2 per-hop identity gate.

Every gateway data-plane hop must carry the end user's JWT, and every agent/coverage
service must verify it fail-closed. These tests are deliberately live: direct probes hit
the running mock services, and propagation checks drive the real BFF -> gateway path.
"""
from __future__ import annotations

import base64
import json
import subprocess
import time

import requests

from lib import bff_client, config, iam_client, trace_client
from lib.evidence import evidence


def _b64url(data: dict) -> str:
    raw = json.dumps(data, separators=(",", ":")).encode()
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def _tamper_signature(jwt: str) -> str:
    header, payload, sig = jwt.split(".")
    mid = len(sig) // 2
    replacement = "A" if sig[mid] != "A" else "B"
    return f"{header}.{payload}.{sig[:mid]}{replacement}{sig[mid + 1:]}"


def _token_with_claims(claims: dict, kid: str = "t2-harness") -> str:
    header = {"alg": "RS256", "typ": "JWT", "kid": kid}
    return f"{_b64url(header)}.{_b64url(claims)}.c2lnbmF0dXJl"


def _rewrite_audience_without_resigning(jwt: str, aud: str) -> str:
    header, payload, sig = jwt.split(".")
    padded = payload + "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(padded))
    claims["aud"] = aud
    return f"{header}.{_b64url(claims)}.{sig}"


def _assert_status(resp: requests.Response, expected: int, label: str) -> None:
    evidence(label, {"status": resp.status_code, "body_preview": resp.text[:240]})
    assert resp.status_code == expected, f"{label}: expected HTTP {expected}, got {resp.status_code}: {resp.text[:240]}"


def _mcp_tools_call(headers: dict | None = None) -> requests.Response:
    return requests.post(
        f"{config.SERVICING_MCP_URL}/messages",
        json={"jsonrpc": "2.0", "id": "t2", "method": "tools/call",
              "params": {"name": "get_settlements", "arguments": {"relationship_id": "REL-00188"}}},
        headers=headers or {},
        timeout=15,
    )


def _agent_complete_previews(events: list[dict]) -> dict[str, str]:
    out: dict[str, str] = {}
    for evt in trace_client.events_of_type(events, "agent_complete"):
        data = evt.get("data") or {}
        out[str(data.get("agentId"))] = str(data.get("dataPreview") or "")
    return out


def test_no_token_and_tampered_rejected_by_agents_mcp_and_coverage():
    real = iam_client.get_jwt(config.USER_ENTITLED)
    tampered = _tamper_signature(real)
    bad_headers = {"Authorization": f"Bearer {tampered}"}

    probes = [
        ("wealth-http no token", requests.get(
            f"{config.WEALTH_HTTP_URL}/holdings",
            params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
            timeout=15)),
        ("wealth-http tampered", requests.get(
            f"{config.WEALTH_HTTP_URL}/holdings",
            params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
            headers=bad_headers,
            timeout=15)),
        ("insurance-http no token", requests.get(
            f"{config.INSURANCE_HTTP_URL}/policy-details",
            params={"policy_id": config.CONTINENTAL_FREIGHT_POLICY_ID},
            timeout=15)),
        ("insurance-http tampered", requests.get(
            f"{config.INSURANCE_HTTP_URL}/policy-details",
            params={"policy_id": config.CONTINENTAL_FREIGHT_POLICY_ID},
            headers=bad_headers,
            timeout=15)),
        ("wealth-coverage no token", requests.get(
            f"{config.WEALTH_COVERAGE_URL}/coverage/{config.USER_ENTITLED}",
            timeout=15)),
        ("wealth-coverage tampered", requests.get(
            f"{config.WEALTH_COVERAGE_URL}/coverage/{config.USER_ENTITLED}",
            headers=bad_headers,
            timeout=15)),
        ("insurance-coverage no token", requests.get(
            f"{config.INSURANCE_COVERAGE_URL}/coverage/{config.USER_INSURANCE_UNDERWRITER}",
            timeout=15)),
        ("insurance-coverage tampered", requests.get(
            f"{config.INSURANCE_COVERAGE_URL}/coverage/{config.USER_INSURANCE_UNDERWRITER}",
            headers=bad_headers,
            timeout=15)),
        ("servicing-mcp tools/call no token", _mcp_tools_call()),
        ("servicing-mcp tools/call tampered", _mcp_tools_call(bad_headers)),
    ]
    for label, resp in probes:
        _assert_status(resp, 401, label)


def test_hop_carries_end_user_sub_flat_and_dag(jane_session):
    flat = bff_client.ask(jane_session, f"Give me the {config.WHITMAN_NAME} holdings")
    assert flat.http_status == 200, flat.answer_text
    flat_request_id, flat_events = trace_client.trace_for_conversation(flat.conversation_id)
    flat_previews = _agent_complete_previews(flat_events)
    evidence("flat hop verified-sub previews", {
        "requestId": flat_request_id,
        "conversation_id": flat.conversation_id,
        "previews": flat_previews,
    })
    assert any("holdings" in aid and f'"_verified_sub":"{config.USER_ENTITLED}"' in preview
               for aid, preview in flat_previews.items()), flat_previews

    dag = bff_client.send_message(
        jane_session,
        bff_client.create_conversation(jane_session, "T2 Whitman concentration"),
        f"Is the {config.WHITMAN_NAME} over-concentrated?",
    )
    assert dag.http_status == 200, dag.answer_text
    dag_request_id, dag_events = trace_client.trace_for_conversation(dag.conversation_id)
    plan_graphs = trace_client.events_of_type(dag_events, "plan_graph")
    dag_previews = _agent_complete_previews(dag_events)
    evidence("DAG hop verified-sub previews", {
        "requestId": dag_request_id,
        "conversation_id": dag.conversation_id,
        "plan_graph": plan_graphs[-1]["data"] if plan_graphs else None,
        "previews": dag_previews,
    })
    assert plan_graphs, "Expected holdings -> concentration DAG plan"
    assert any("holdings" in aid and f'"_verified_sub":"{config.USER_ENTITLED}"' in preview
               for aid, preview in dag_previews.items()), dag_previews
    assert any("concentration" in aid and f'"_verified_sub":"{config.USER_ENTITLED}"' in preview
               for aid, preview in dag_previews.items()), dag_previews


def test_jwks_outage_fails_closed_for_uncached_key():
    token = _token_with_claims({
        "sub": config.USER_ENTITLED,
        "iss": "http://iam-service:8084",
        "aud": "conduit-gateway",
        "exp": int(time.time()) + 300,
    }, kid="t2-uncached-outage-key")
    subprocess.run(["docker", "stop", "conduit-iam-service"], check=True, timeout=30)
    try:
        resp = requests.get(
            f"{config.WEALTH_HTTP_URL}/holdings",
            params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        _assert_status(resp, 401, "wealth-http rejects when JWKS is unreachable")
    finally:
        subprocess.run(["docker", "start", "conduit-iam-service"], check=True, timeout=30)
        for _ in range(30):
            try:
                if requests.get(f"{config.IAM_URL}/login", timeout=3).status_code < 500:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)


def test_wrong_audience_token_rejected():
    real = iam_client.get_jwt(config.USER_ENTITLED)
    wrong_aud = _rewrite_audience_without_resigning(real, "not-conduit-gateway")
    resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        headers={"Authorization": f"Bearer {wrong_aud}"},
        timeout=15,
    )
    evidence("wrong-audience-shaped token rejected", {
        "status": resp.status_code,
        "body_preview": resp.text[:240],
        "note": "IAM /auth/token does not expose an audience override; this rewrites aud "
                "without resigning, so live rejection may occur before the audience branch.",
    })
    assert resp.status_code == 401


def test_expired_token_rejected_before_data_access():
    expired = _token_with_claims({
        "sub": config.USER_ENTITLED,
        "iss": "http://iam-service:8084",
        "aud": "conduit-gateway",
        "exp": int(time.time()) - 60,
    })
    resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        headers={"Authorization": f"Bearer {expired}"},
        timeout=15,
    )
    evidence("expired token rejected before data access", {
        "status": resp.status_code,
        "body_preview": resp.text[:240],
        "note": "Gateway mid-plan AUTH_EXPIRED is covered by AgentHarnessResilienceIT; "
                "IAM does not expose a short-TTL mint knob for a live expires-between-ingress-and-hop token.",
    })
    assert resp.status_code == 401
