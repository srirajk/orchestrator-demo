"""Shared pytest fixtures for the wealth-coverage service.

Provides an in-test RS256 signing key and token minting so the service's JWT middleware
can be exercised WITHOUT a live Axiom/JWKS endpoint. The autouse fixture points
``jwt_verify._fetch_public_key`` at the in-test public key, so any token minted here
verifies exactly as if Axiom had signed it (same signature/issuer/expiry/audience path).

These helpers are local to the tests and never touch a running demo — pure in-process.
"""

import os
import sys
import time

# Make the service package (data, main, jwt_verify) importable from tests/.
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import pytest
import jwt as pyjwt
from cryptography.hazmat.primitives.asymmetric import rsa

import jwt_verify

# One RSA keypair for the whole test session — cheap, deterministic within a run.
_PRIVATE_KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUBLIC_KEY = _PRIVATE_KEY.public_key()

TEST_KID = "a5-test-key"
TEST_ISSUER = "http://localhost:8084"          # ∈ jwt_verify.VALID_ISSUERS
TEST_AUDIENCE = jwt_verify.EXPECTED_AUDIENCE    # what the service is configured to accept


@pytest.fixture(autouse=True)
def _trust_test_key(monkeypatch):
    """Route the service's signing-key lookup to the in-test public key (no network)."""
    def _fake_fetch(kid):
        return _PUBLIC_KEY if kid == TEST_KID else None
    monkeypatch.setattr(jwt_verify, "_fetch_public_key", _fake_fetch)


def mint_token(tenant_id, sub="rm_jane", audience=None, issuer=TEST_ISSUER,
               expires_in=3600, extra=None):
    """Mint an RS256 token signed by the in-test key. Carries the A5-relevant claims."""
    now = int(time.time())
    claims = {
        "sub": sub,
        "iss": issuer,
        "aud": TEST_AUDIENCE if audience is None else audience,
        "iat": now,
        "exp": now + expires_in,
        "tenant_id": tenant_id,
    }
    if extra:
        claims.update(extra)
    return pyjwt.encode(claims, _PRIVATE_KEY, algorithm="RS256", headers={"kid": TEST_KID})


def auth_headers(tenant_id, sub="rm_jane", x_tenant_id=None, **kw):
    """Build request headers with a valid bearer token and an X-Tenant-Id header.

    By default the header agrees with the token's tenant_id (the honest case). Pass
    ``x_tenant_id`` to deliberately forge a mismatch.
    """
    token = mint_token(tenant_id, sub=sub, **kw)
    return {
        "Authorization": f"Bearer {token}",
        "X-Tenant-Id": tenant_id if x_tenant_id is None else x_tenant_id,
    }
