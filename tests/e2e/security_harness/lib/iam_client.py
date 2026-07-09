"""
Direct IAM (Axiom) token issuance — used ONLY for direct agent-hop probes (hitting
wealth-http straight, bypassing the BFF and gateway) where we need a raw, real RS256
JWT to attach as a Bearer header ourselves.

Same pattern as tests/integration/test_gateway_coverage.py::get_jwt — reproduced here
(not imported) because that module is a pytest test file, not a library, and pulls in
its own test collection when imported. The endpoint contract (POST /auth/token ->
{"accessToken": "..."}) is the same real IAM endpoint either way.

This is NOT the path the production BFF uses (the BFF holds tokens server-side via
OAuth2 authorization_code + refresh_token, see apps/chat/bff/.../AccessTokenService.java).
It is a direct password-grant-shaped convenience endpoint IAM exposes for testing/tools.
"""
from __future__ import annotations
import requests

from . import config


def get_jwt(user_id: str, password: str = config.DEMO_PASSWORD, timeout: int = 15) -> str:
    """Obtain a real RS256 JWT for `user_id` directly from IAM. Raises on failure."""
    resp = requests.post(
        f"{config.IAM_URL}/auth/token",
        json={"username": user_id, "password": password},
        timeout=timeout,
    )
    resp.raise_for_status()
    data = resp.json()
    token = data.get("accessToken") or data.get("access_token")
    if not token:
        raise ValueError(f"No token in IAM response for {user_id}: {data}")
    return token
