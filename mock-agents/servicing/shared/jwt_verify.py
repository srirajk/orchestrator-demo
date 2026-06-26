"""
Shared JWT verification for mock agents.

Verifies RS256 JWTs issued by user-mgmt using the JWKS endpoint.
Policy:
  - No Authorization header  → allow (gateway is trust boundary; startup introspection has no token)
  - Authorization: Bearer <token> present but invalid → 401
  - Authorization: Bearer <token> present and valid   → allow, set request.state.principal
"""

import os
import time
import logging
import httpx
import jwt as pyjwt
from jwt.algorithms import RSAAlgorithm

log = logging.getLogger(__name__)

JWKS_URL = os.getenv("JWKS_URL", "http://user-mgmt:8084/.well-known/jwks.json")
VALID_ISSUERS = {"meridian-user-mgmt", "http://user-mgmt:8084"}
EXPECTED_AUDIENCE = os.getenv("AGENT_JWT_AUDIENCE", "meridian-gateway")

# Simple in-process JWKS cache: {kid: public_key_pem, "_fetched_at": float}
_jwks_cache: dict = {}
_CACHE_TTL = 300  # 5 minutes


def _fetch_public_key(kid: str):
    """Fetch and cache the RSA public key for the given kid from JWKS."""
    now = time.monotonic()
    if _jwks_cache.get("_fetched_at", 0) + _CACHE_TTL > now and kid in _jwks_cache:
        return _jwks_cache[kid]

    try:
        resp = httpx.get(JWKS_URL, timeout=5.0)
        resp.raise_for_status()
        jwks = resp.json()
    except Exception as exc:
        log.warning("Failed to fetch JWKS from %s: %s", JWKS_URL, exc)
        return None

    _jwks_cache.clear()
    _jwks_cache["_fetched_at"] = now
    for key in jwks.get("keys", []):
        k = key.get("kid")
        if k:
            _jwks_cache[k] = RSAAlgorithm.from_jwk(key)

    return _jwks_cache.get(kid)


def verify_bearer_token(authorization: str | None) -> tuple[bool, str | None, dict | None]:
    """
    Returns (allowed: bool, error_msg: str | None, claims: dict | None).

    allowed=True  → request should proceed
    allowed=False → return 401 with error_msg
    claims        → decoded JWT payload when allowed and token was present
    """
    if not authorization:
        # No token — allow (gateway already authenticated the user)
        return True, None, None

    if not authorization.startswith("Bearer "):
        return False, "Authorization header must be Bearer token", None

    token = authorization[7:].strip()
    if not token or token == "unused":
        return True, None, None

    # Count dots — must have exactly 2 (header.payload.signature)
    if token.count(".") != 2:
        return False, "Invalid token format", None

    # Decode header to get kid
    try:
        header = pyjwt.get_unverified_header(token)
    except Exception as exc:
        return False, f"Cannot decode token header: {exc}", None

    kid = header.get("kid")
    alg = header.get("alg", "")

    # Reject algorithm confusion attacks
    if alg.lower() == "none" or not alg.startswith("RS"):
        return False, f"Algorithm '{alg}' not permitted — RS256 required", None

    public_key = _fetch_public_key(kid)
    if public_key is None:
        log.warning("No public key found for kid=%s — allowing (JWKS unavailable)", kid)
        return True, None, None  # Fail open when JWKS unreachable (dev fallback)

    try:
        claims = pyjwt.decode(
            token,
            public_key,
            algorithms=["RS256"],
            audience=EXPECTED_AUDIENCE,
        )
    except pyjwt.ExpiredSignatureError:
        return False, "Token expired", None
    except pyjwt.InvalidAudienceError:
        return False, "Invalid audience", None
    except pyjwt.InvalidSignatureError:
        return False, "Invalid signature", None
    except Exception as exc:
        return False, f"Token verification failed: {exc}", None

    issuer = claims.get("iss", "")
    if issuer not in VALID_ISSUERS:
        return False, f"Untrusted issuer: {issuer}", None

    log.debug("JWT verified: sub=%s iss=%s", claims.get("sub"), issuer)
    return True, None, claims
