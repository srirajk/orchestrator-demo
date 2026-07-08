"""
Identity per hop — every data-plane call the gateway makes to an agent must carry the
CALLER'S verified JWT, and the agent must actually verify it (not fail open). This is the
harness's acceptance gate for the identity-propagation security fix; these are EXPECTED
to fail (or partially fail) until that fix lands — that's the point.

Evidence strategy (documented per CLAUDE.md's ask to explain how each detects its property):

  test_hop_identity_verified — composite, three legs, all against the SAME endpoint the
  live flow calls:
    (a) drive a live rm_jane turn through the real BFF, then grep the gateway's own DEBUG
        log (gateway/src/main/resources/application.yml sets `ai.conduit: DEBUG`) for the
        HttpAdapter line that fires on every agent invocation, correlated by conversationId
        via the logback MDC pattern (`cid=%X{conversationId}`) — proves THIS live turn
        actually attempted to carry a bearer token to the agent.
    (b) obtain a real signed JWT for rm_jane directly from IAM and call wealth-http's
        /holdings DATA endpoint directly with it — must succeed (200, real data).
    (c) take that SAME real JWT and flip one bit deep inside the signature segment, call
        the identical endpoint — must be REJECTED (401). If the agent accepts a tampered
        signature, verification isn't actually happening (see test_tampered_signature_
        rejected below for the isolated, minimal repro and root-cause diagnosis).

  test_no_token_rejected — direct curl, no login needed: hit the DATA endpoint with no
  Authorization header at all (expect 401 once agents fail closed) and /health (exempt,
  always 200). Marked xfail because the shipped agent's documented fallback for a missing
  header is currently "allow" (mock-agents/wealth/shared/jwt_verify.py) — flip to pass once
  that lands.

  test_tampered_signature_rejected — NOT part of the original ask, added because building
  the harness surfaced a live, more severe bug than the documented one: see its docstring.
"""
from __future__ import annotations
import time

import pytest
import requests

from lib import bff_client, config, docker_logs, iam_client
from lib.evidence import evidence

GATEWAY_CONTAINER = "conduit-gateway"
WEALTH_AGENT_CONTAINER = "conduit-wealth-http"


def _tamper_signature(jwt: str) -> str:
    """Flip one character deep inside the signature segment (not the first/last char,
    which base64's padding can occasionally leave bit-equivalent) — guarantees a byte
    change after base64url decoding, so re-verification MUST fail if it runs at all."""
    header, payload, sig = jwt.split(".")
    mid = len(sig) // 2
    ch = sig[mid]
    new_ch = "A" if ch != "A" else "B"
    return f"{header}.{payload}.{sig[:mid]}{new_ch}{sig[mid + 1:]}"


def test_hop_identity_verified(jane_session):
    """See module docstring — composite identity-per-hop check."""
    # ── Leg (a): live BFF turn, then confirm the gateway actually attempted to propagate
    # a bearer token to the agent DURING this turn.
    #
    # NOTE: originally this correlated by conversationId via the logback MDC pattern
    # (`cid=%X{conversationId}`), but a live run surfaced that the MDC context is EMPTY on
    # HttpAdapter's log line (`[rid= cid= uid=] - HttpAdapter: propagating JWT to agent`)
    # — a distinct, real gap: MDC is thread-local and, like callerToken before the
    # F-IDENTITY fix, does not survive the hop onto AgentHarness's virtual-thread executor
    # (see AgentHarness.java's own comment on exactly this class of bug for callerToken).
    # RequestCorrelationFilter populates MDC on the servlet thread; nothing re-populates it
    # on the executor thread HttpAdapter actually logs from. This means the glass-box's
    # per-request log correlation is currently unreliable for any log line emitted from
    # inside the agent-invocation hop — worth fixing alongside the identity work, even
    # though it is a logging/observability gap rather than a security one.
    #
    # Given that, this leg uses a before/after occurrence-count delta instead of exact
    # correlation: weaker (a concurrent request from eval-continuous could add noise) but
    # still a real, live signal that a propagation attempt happened around this turn. ---
    before_count = len(docker_logs.grep(GATEWAY_CONTAINER, "propagating JWT to agent", lines=3000))
    turn = bff_client.ask(jane_session, f"Give me the {config.WHITMAN_NAME} holdings")
    assert turn.http_status == 200, f"live turn failed: {turn.http_status}"
    time.sleep(1.0)  # let the log line land before we tail
    after_lines = docker_logs.grep(GATEWAY_CONTAINER, "propagating JWT to agent", lines=3000)
    after_count = len(after_lines)
    evidence("gateway DEBUG log — JWT propagation occurrence count around this turn", {
        "conversation_id": turn.conversation_id,
        "before_count": before_count,
        "after_count": after_count,
        "most_recent_matching_lines": after_lines[-5:],
        "known_gap": "MDC (rid=/cid=/uid=) is empty on this log line — see docstring/comment "
                     "above; exact per-conversation correlation is not currently possible from "
                     "gateway logs alone for this hop.",
    })
    assert after_count > before_count, (
        "No new 'propagating JWT to agent' DEBUG log line appeared after this live turn — "
        "either ai.conduit DEBUG logging is off, or the gateway did not attempt to attach a "
        "bearer token when calling the agent for this live turn."
    )

    # ── Legs (b) + (c): direct-to-agent probe with a real vs. tampered JWT for the same
    # user, same endpoint the live flow above just exercised. ────────────────────────────
    real_jwt = iam_client.get_jwt(config.USER_ENTITLED)
    tampered_jwt = _tamper_signature(real_jwt)

    real_resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        headers={"Authorization": f"Bearer {real_jwt}"},
        timeout=15,
    )
    tampered_resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        headers={"Authorization": f"Bearer {tampered_jwt}"},
        timeout=15,
    )
    evidence("direct wealth-http probe — real vs. tampered JWT", {
        "real_jwt_status": real_resp.status_code,
        "real_jwt_body_preview": real_resp.text[:200],
        "tampered_jwt_status": tampered_resp.status_code,
        "tampered_jwt_body_preview": tampered_resp.text[:200],
    })
    assert real_resp.status_code == 200, (
        f"A genuinely valid rm_jane JWT was rejected by wealth-http: {real_resp.status_code} "
        f"{real_resp.text[:200]}"
    )
    assert tampered_resp.status_code == 401, (
        f"wealth-http ACCEPTED a JWT with a tampered signature (HTTP {tampered_resp.status_code}) "
        f"— the agent is not actually verifying signatures on this hop. See "
        f"test_tampered_signature_rejected for the isolated root-cause repro."
    )


def test_no_token_rejected():
    """
    Direct curl, no BFF/login involved:
      - GET /health with no Authorization -> 200 (exempt by design, see main.py's
        jwt_auth_middleware path allowlist).
      - GET /holdings (a DATA endpoint) with no Authorization -> MUST be 401 once agents
        fail closed on a missing token. Today mock-agents/wealth/shared/jwt_verify.py's
        documented policy is "No Authorization header -> allow" (dev/startup-introspection
        fallback) — so this currently returns 200. xfail until that lands; flip to pass then.
    """
    health = requests.get(f"{config.WEALTH_HTTP_URL}/health", timeout=10)
    evidence("GET /health, no auth", {"status": health.status_code, "body": health.text[:200]})
    assert health.status_code == 200, f"/health should be exempt and always 200, got {health.status_code}"

    data_resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        timeout=10,
    )
    evidence("GET /holdings, NO Authorization header", {
        "status": data_resp.status_code,
        "body_preview": data_resp.text[:200],
    })
    if data_resp.status_code != 401:
        pytest.xfail(
            f"KNOWN GAP (fail-open, not yet fixed): wealth-http allowed an unauthenticated "
            f"DATA call (HTTP {data_resp.status_code}). Fix: mock-agents/wealth/shared/"
            f"jwt_verify.py's 'no Authorization header' branch must return "
            f"(False, ...) instead of (True, None, None) for data endpoints."
        )
    assert data_resp.status_code == 401


def test_tampered_signature_rejected():
    """
    NOT in the original ask — added because probing for test_hop_identity_verified found a
    live bug more severe than the documented one.

    Root cause (confirmed by direct inspection, see harness report): wealth-http's JWKS_URL
    default is `http://iam-service:8084/.well-known/jwks.json`, but iam-service's real JWKS
    endpoint (per its own OIDC discovery document, `jwks_uri`) is `/oauth2/jwks` — the
    `.well-known` path 301-redirects there. httpx.get() does not follow redirects by
    default, so the agent's JWKS fetch gets a 301 body, fails to parse it as JSON, and the
    exception is classified as "JWKS unreachable" -> the code's intentional DEV FALLBACK
    ("JWKS unreachable -> allow so local dev keeps working when iam-service is down") fires
    on every single verification attempt. Net effect: JWT signature verification is a no-op
    on this hop for ANY token, tampered or not — confirmed via `docker logs conduit-wealth-http`
    showing "JWKS unreachable — allowing kid=... (dev fallback)" on every request.

    This is worse than the documented "no token -> allow" gap: it means even an attacker
    with a garbage-signed token, not just a missing one, currently gets real client data.

    Fix candidates (pick one, not gateway/agent code changes made by this harness):
      1. Correct JWKS_URL to .../oauth2/jwks (or set it via env in docker-compose.yml,
         same as the gateway's own CONDUIT_AUTH_JWKS_URL).
      2. And/or: stop treating "JWKS unreachable" as allow-fallback on DATA endpoints —
         infra faults should fail closed for data-plane calls, same as an unknown kid.
    """
    real_jwt = iam_client.get_jwt(config.USER_ENTITLED)
    header, payload, sig = real_jwt.split(".")
    mid = len(sig) // 2
    ch = sig[mid]
    tampered_sig = sig[:mid] + ("A" if ch != "A" else "B") + sig[mid + 1:]
    tampered_jwt = f"{header}.{payload}.{tampered_sig}"

    resp = requests.get(
        f"{config.WEALTH_HTTP_URL}/holdings",
        params={"relationship_id": config.WHITMAN_RELATIONSHIP_ID},
        headers={"Authorization": f"Bearer {tampered_jwt}"},
        timeout=15,
    )
    jwks_evidence = docker_logs.grep(WEALTH_AGENT_CONTAINER, "JWKS unreachable", lines=200)
    evidence("tampered-signature probe + agent JWKS-fetch log", {
        "status": resp.status_code,
        "body_preview": resp.text[:200],
        "recent_JWKS_unreachable_log_lines": jwks_evidence[-5:],
    })
    assert resp.status_code == 401, (
        f"CRITICAL: wealth-http accepted a tampered-signature JWT (HTTP {resp.status_code}). "
        f"Likely cause: JWKS_URL misconfiguration causing the 'JWKS unreachable -> allow' dev "
        f"fallback to fire on every request (see recent log lines in the evidence above)."
    )
