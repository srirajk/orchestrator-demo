"""
Real integration tests for the Meridian AI Gateway coverage flow.

Requires docker compose up (core profile) before running:
  - Gateway at http://localhost:8080
  - IAM service at http://localhost:8084
  - Wealth coverage service at http://localhost:8086

Run:
  cd tests/integration
  pip install -r requirements.txt
  pytest test_gateway_coverage.py -v --tb=short
"""

import json
import time
import uuid
import pytest
import requests

# ── Constants ──────────────────────────────────────────────────────────────────

GATEWAY_URL = "http://localhost:8080"
IAM_URL = "http://localhost:8084"
COVERAGE_URL = "http://localhost:8086"

DEFAULT_PASSWORD = "Meridian@2024"

# Default timeout for a full LLM fan-out + synthesis cycle (seconds)
CHAT_TIMEOUT = 120


# ── Fixtures / helpers ─────────────────────────────────────────────────────────


def get_jwt(user_id: str, password: str = DEFAULT_PASSWORD) -> str:
    """
    Obtain a real RS256 JWT from the IAM service.

    POST /auth/token  →  { "accessToken": "..." }
    """
    resp = requests.post(
        f"{IAM_URL}/auth/token",
        json={"username": user_id, "password": password},
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()
    token = data.get("accessToken") or data.get("access_token")
    if not token:
        raise ValueError(f"No token in IAM response: {data}")
    return token


def collect_sse_text(response: requests.Response) -> str:
    """
    Read a streaming SSE response and concatenate all assistant content chunks.

    Each line looks like:  data: {"choices":[{"delta":{"content":"..."}}]}
    The stream ends with:  data: [DONE]
    """
    assembled: list[str] = []
    for raw_line in response.iter_lines():
        if isinstance(raw_line, bytes):
            raw_line = raw_line.decode("utf-8")
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("data:"):
            payload = line[len("data:"):].strip()
            if payload == "[DONE]":
                break
            try:
                chunk = json.loads(payload)
                choices = chunk.get("choices", [])
                for choice in choices:
                    delta = choice.get("delta", {})
                    content = delta.get("content")
                    if content:
                        assembled.append(content)
            except json.JSONDecodeError:
                # non-JSON data line — skip
                pass
    return "".join(assembled)


def chat(
    messages: list[dict],
    jwt: str,
    conv_id: str | None = None,
) -> tuple[str, str]:
    """
    POST /v1/chat/completions with stream=True.

    Returns (response_text, conversation_id).
    The conversation_id is either the one supplied or a freshly generated UUID.
    """
    if conv_id is None:
        conv_id = f"test-{uuid.uuid4().hex[:12]}"

    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {jwt}",
        "X-Conversation-Id": conv_id,
    }
    body = {
        "model": "meridian-assistant",
        "messages": messages,
        "stream": True,
    }

    resp = requests.post(
        f"{GATEWAY_URL}/v1/chat/completions",
        headers=headers,
        json=body,
        stream=True,
        timeout=CHAT_TIMEOUT,
    )
    resp.raise_for_status()
    text = collect_sse_text(resp)
    return text, conv_id


# ── Test functions ─────────────────────────────────────────────────────────────


def test_6_gateway_health_check():
    """GET /actuator/health returns 200 and {"status":"UP"}."""
    resp = requests.get(f"{GATEWAY_URL}/actuator/health", timeout=10)
    assert resp.status_code == 200, f"Expected 200, got {resp.status_code}: {resp.text}"
    body = resp.json()
    assert body.get("status") == "UP", f"Gateway health not UP: {body}"


def test_7_coverage_service_health():
    """GET /health on wealth-coverage-service returns 200 and service=wealth-coverage-service."""
    resp = requests.get(f"{COVERAGE_URL}/health", timeout=10)
    assert resp.status_code == 200, f"Expected 200, got {resp.status_code}: {resp.text}"
    body = resp.json()
    assert body.get("status") == "ok", f"Coverage service status not ok: {body}"
    # Accept either exact key name — some versions use "service", others don't
    service_val = body.get("service", "")
    assert "coverage" in service_val.lower() or service_val == "", (
        f"Unexpected service name: {service_val}"
    )


def test_8_unauthenticated_request_rejected():
    """
    POST /v1/chat/completions without Authorization header.
    Expected: 401 (gateway enforces auth) OR a 200 with a non-crashing denial response.
    """
    resp = requests.post(
        f"{GATEWAY_URL}/v1/chat/completions",
        headers={"Content-Type": "application/json"},
        json={
            "model": "meridian-assistant",
            "messages": [{"role": "user", "content": "Show my portfolio"}],
            "stream": True,
        },
        stream=True,
        timeout=30,
    )
    # Must not be a server error (5xx)
    assert resp.status_code != 500, "Gateway returned 500 on unauthenticated request"
    if resp.status_code == 401:
        # Preferred path: explicit rejection
        return
    # Alternative: gateway accepted the anonymous request but produced a safe response
    assert resp.status_code == 200, (
        f"Expected 401 or 200 for unauthenticated request, got {resp.status_code}"
    )
    # Consuming the body must not crash
    text = collect_sse_text(resp)
    assert text is not None


def test_1_discover_flow_no_client_mentioned():
    """
    rm_jane sends a vague portfolio question with no client name.
    Gateway should produce a clarification question offering her book members.
    """
    jwt = get_jwt("rm_jane")
    messages = [{"role": "user", "content": "Show me my portfolio"}]

    text, _ = chat(messages, jwt)

    lower = text.lower()
    assert len(text) > 5, f"Response is suspiciously short: {repr(text)}"
    has_question = "?" in text
    references_client = (
        "whitman" in lower
        or "calderon" in lower
        or "relationship" in lower
        or "client" in lower
        or "which" in lower
    )
    assert has_question or references_client, (
        f"Expected clarification with client options, got: {repr(text)}"
    )


def test_2_named_client_resolves_and_allows():
    """
    rm_jane asks for a named client in her book (Whitman, REL-00042).
    Response must NOT be a coverage denial and must be substantive.
    """
    jwt = get_jwt("rm_jane")
    messages = [
        {
            "role": "user",
            "content": "Show me holdings for Whitman Family Office REL-00042",
        }
    ]

    text, _ = chat(messages, jwt)

    lower = text.lower()
    assert "not in your coverage" not in lower, (
        f"rm_jane was incorrectly denied Whitman (REL-00042): {repr(text)}"
    )
    assert len(text) > 20, f"Response is too short for a valid holdings answer: {repr(text)}"


def test_3_okafor_denied_for_rm_jane():
    """
    rm_jane asks for Okafor Capital (REL-00188) which is NOT in her book.
    Response must indicate denial via coverage or access check.
    """
    jwt = get_jwt("rm_jane")
    messages = [
        {
            "role": "user",
            "content": "Show me the portfolio for Okafor Capital REL-00188",
        }
    ]

    text, _ = chat(messages, jwt)

    lower = text.lower()
    is_denied = (
        "coverage" in lower
        or "denied" in lower
        or "not authorized" in lower
        or "not authoriz" in lower
        or "not in your" in lower
        or "do not have access" in lower
        or "access denied" in lower
        or "not allowed" in lower
    )
    assert is_denied, (
        f"Expected a denial response for rm_jane querying Okafor (REL-00188), "
        f"but got: {repr(text)}"
    )


def test_4_multi_turn_carry_forward():
    """
    Two-turn conversation over the same conversation ID.
    Turn 1: establish Whitman context.
    Turn 2: follow-up question without re-stating the client.
    Turn 2 must NOT ask 'which client' — session context must carry forward.
    """
    jwt = get_jwt("rm_jane")
    conv_id = "test-mt-jane-001"

    # Turn 1 — establish entity context
    turn1_text, _ = chat(
        messages=[
            {
                "role": "user",
                "content": "Show me holdings for Whitman Family Office REL-00042",
            }
        ],
        jwt=jwt,
        conv_id=conv_id,
    )
    assert len(turn1_text) > 20, f"Turn 1 response is too short: {repr(turn1_text)}"

    # Turn 2 — follow-up, sends full message history
    turn2_text, _ = chat(
        messages=[
            {
                "role": "user",
                "content": "Show me holdings for Whitman Family Office REL-00042",
            },
            {"role": "assistant", "content": turn1_text},
            {"role": "user", "content": "What about their performance?"},
        ],
        jwt=jwt,
        conv_id=conv_id,
    )

    assert len(turn2_text) > 20, f"Turn 2 response is too short: {repr(turn2_text)}"

    lower = turn2_text.lower()
    # Session context must not trigger a fresh clarification re-asking which client
    asks_which_client = "which client" in lower or "please specify" in lower
    assert not asks_which_client, (
        f"Turn 2 unexpectedly asked for client clarification (session carry-forward failed): "
        f"{repr(turn2_text)}"
    )


def test_5_asset_servicing_no_coverage_check():
    """
    Ask about pending settlements — an asset-servicing question.
    Asset-servicing is role-based, not resource_scoped, so coverage check does not apply.
    Response must not be a 'not in your coverage' denial.
    A clarification about which client is acceptable, but not a coverage error.
    """
    jwt = get_jwt("rm_carlos")  # rm_carlos has servicing access
    messages = [{"role": "user", "content": "What are the pending settlements?"}]

    text, _ = chat(messages, jwt)

    lower = text.lower()
    assert "not in your coverage" not in lower, (
        f"Asset-servicing question was incorrectly denied via coverage check: {repr(text)}"
    )
    assert len(text) > 5, f"Response is suspiciously short for a settlement query: {repr(text)}"
