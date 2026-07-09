"""
Shared JWT verification for mock agents.

Verifies RS256 JWTs issued by Axiom (iam-service) using the JWKS endpoint.

Policy (F-IDENTITY fix — fails CLOSED, not open):
  - No Authorization header                         → 401 (DATA endpoints always require a
    verified caller identity; the gateway propagates one on every agent hop). Callers that
    legitimately have no caller identity — health checks, OpenAPI/docs introspection, and the
    MCP initialize/tools/list handshake the gateway runs at boot — are exempted BEFORE this
    function is ever called, in the per-service middleware (see each service's main.py /
    server.py). This function itself never grants an anonymous allow.
  - Authorization: Bearer <token> present but invalid → 401 (this includes a JWKS-fetch
    failure — an agent that cannot verify a signature must reject, not allow; see the
    JWKSUnreachable handling below)
  - Authorization: Bearer <token> present and valid   → allow, set request.state.principal
"""

import os
import time
import logging
import httpx
import jwt as pyjwt
from jwt.algorithms import RSAAlgorithm

log = logging.getLogger(__name__)

JWKS_URL = os.getenv("JWKS_URL", "http://iam-service:8084/oauth2/jwks")
VALID_ISSUERS = {"http://host.docker.internal:8084", "http://iam-service:8084", "http://localhost:8084"}
EXPECTED_AUDIENCE = os.getenv("AGENT_JWT_AUDIENCE", "conduit-gateway")

# Simple in-process JWKS cache: {kid: public_key_pem, "_fetched_at": float}
_jwks_cache: dict = {}
_CACHE_TTL = 300  # 5 minutes


class JWKSUnreachable(Exception):
    """Raised when the JWKS endpoint could not be fetched (network/HTTP error).

    Distinct from 'JWKS fetched successfully but the requested kid is absent':
    the former is an infrastructure fault (dev fallback may apply), the latter is a
    token signed by an unknown/untrusted key and MUST be rejected (fail closed).
    """


def _fetch_public_key(kid: str):
    """Return the RSA public key for the given kid from JWKS.

    Returns the key on success, or None if the JWKS was fetched but does not
    contain this kid (unknown key → caller must reject).
    Raises JWKSUnreachable if the JWKS endpoint itself could not be reached.
    """
    now = time.monotonic()
    if _jwks_cache.get("_fetched_at", 0) + _CACHE_TTL > now and kid in _jwks_cache:
        return _jwks_cache[kid]

    try:
        resp = httpx.get(JWKS_URL, timeout=5.0)
        resp.raise_for_status()
        jwks = resp.json()
    except Exception as exc:
        log.warning("Failed to fetch JWKS from %s: %s", JWKS_URL, exc)
        raise JWKSUnreachable(str(exc)) from exc

    _jwks_cache.clear()
    _jwks_cache["_fetched_at"] = now
    for key in jwks.get("keys", []):
        k = key.get("kid")
        if k:
            _jwks_cache[k] = RSAAlgorithm.from_jwk(key)

    # JWKS fetched OK. If the kid is present, return it; otherwise None signals
    # "known-good JWKS, unknown kid" → the caller fails closed.
    return _jwks_cache.get(kid)


def verify_bearer_token(authorization: str | None) -> tuple[bool, str | None, dict | None]:
    """
    Returns (allowed: bool, error_msg: str | None, claims: dict | None).

    allowed=True  → request should proceed
    allowed=False → return 401 with error_msg
    claims        → decoded JWT payload when allowed and token was present
    """
    if not authorization:
        # F-IDENTITY: fail CLOSED. A missing token here means the caller was never
        # authenticated, or the gateway's identity-propagation seam broke upstream —
        # never fall through to an anonymous allow on a data endpoint. Legitimate
        # no-token callers (health/openapi/docs introspection, MCP initialize/tools/list)
        # are carved out by the middleware before verify_bearer_token is invoked.
        return False, "Missing Authorization header", None

    if not authorization.startswith("Bearer "):
        return False, "Authorization header must be Bearer token", None

    token = authorization[7:].strip()
    if not token:
        # F-IDENTITY: an empty bearer value is not a token — fail CLOSED, same as a
        # missing header. (The historical "unused"/empty placeholder bypass — a leftover
        # accommodation with no live caller — is removed for the same reason: any string
        # here must be a real, verifiable JWT or the request is rejected.)
        return False, "Empty bearer token", None

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

    try:
        public_key = _fetch_public_key(kid)
    except JWKSUnreachable:
        # F-IDENTITY: fail CLOSED, not open. A JWKS fetch failure means this agent CANNOT
        # verify the signature — that is indistinguishable, from the caller's side, from an
        # attacker holding a forged token, so it must be rejected exactly like an unknown
        # kid or a bad signature. (The previous "allow so local dev keeps working" fallback
        # was live-fire masking real signature verification: with the wrong JWKS_URL this
        # branch fired on EVERY request, so tampered-signature tokens were accepted too —
        # see bug-250 / test_tampered_signature_rejected.) If iam-service is genuinely
        # down, agents correctly reject data calls until it recovers — the gateway would
        # itself refuse to serve a request without a working JWKS in the same way.
        log.warning("JWKS unreachable — rejecting kid=%s (fail closed)", kid)
        return False, "JWKS endpoint unreachable — cannot verify token", None

    if public_key is None:
        # JWKS fetched successfully but has no key for this kid → the token was signed
        # by an unknown/untrusted key. Fail CLOSED (reject), never fall through to allow.
        log.warning("Unknown kid=%s — JWKS reachable but key absent; rejecting", kid)
        return False, f"Unknown signing key (kid={kid})", None

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
