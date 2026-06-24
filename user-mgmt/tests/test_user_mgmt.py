"""
Tests for the Meridian User Management Service — RS256 JWT, JWKS, domain/membership model.

Run with:
    pip install pytest httpx fakeredis python-jose[cryptography] cryptography
    pytest tests/test_user_mgmt.py -v

These tests use an in-process TestClient (no real Redis required — fakeredis patches it).
"""

import base64
import json
import math

import fakeredis
import pytest
from fastapi.testclient import TestClient
from jose import jwt as jose_jwt
from jose.utils import base64url_decode

# Patch Redis before importing the app so the module-level singleton is replaced.
import redis as redis_lib

_fake_redis = fakeredis.FakeRedis(decode_responses=True)


def _fake_get_redis():
    return _fake_redis


import main as app_module

# Monkey-patch the Redis factory
app_module.get_redis = _fake_get_redis
app_module._redis = _fake_redis

from main import app

client = TestClient(app)


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def flush_redis():
    """Start every test with a clean Redis store and re-run startup seeding."""
    _fake_redis.flushall()
    # Re-run the startup seed so principals and domains exist
    app_module.seed()
    yield
    _fake_redis.flushall()


# ── JWKS tests ────────────────────────────────────────────────────────────────

class TestJWKS:
    def test_jwks_has_correct_structure(self):
        resp = client.get("/.well-known/jwks.json")
        assert resp.status_code == 200
        body = resp.json()
        assert "keys" in body
        assert len(body["keys"]) == 1
        key = body["keys"][0]
        assert key["kty"] == "RSA"
        assert key["use"] == "sig"
        assert key["alg"] == "RS256"
        assert key["kid"] == "meridian-key-1"
        assert "n" in key and len(key["n"]) > 0
        assert "e" in key and len(key["e"]) > 0

    def test_jwks_e_is_65537(self):
        """Public exponent must be 65537 (the standard value)."""
        resp = client.get("/.well-known/jwks.json")
        key = resp.json()["keys"][0]
        # Decode base64url → big-endian integer (jose.utils.base64url_decode needs bytes)
        e_bytes = base64url_decode(key["e"].encode())
        e = int.from_bytes(e_bytes, "big")
        assert e == 65537

    def test_jwks_n_length(self):
        """Modulus for a 2048-bit key should be 256 bytes when decoded."""
        resp = client.get("/.well-known/jwks.json")
        key = resp.json()["keys"][0]
        n_bytes = base64url_decode(key["n"].encode())
        assert len(n_bytes) == 256  # 2048 / 8


# ── Token issuance and RS256 verification ─────────────────────────────────────

class TestTokenIssuance:
    def _get_public_key_pem(self):
        """Fetch public key from JWKS and reconstruct PEM for jose verification."""
        return app_module._rsa_private_key.public_key()

    def test_issue_token_returns_rs256_jwt(self):
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        assert resp.status_code == 200
        body = resp.json()
        assert "access_token" in body
        assert body["algorithm"] == "RS256"
        assert body["key_id"] == "meridian-key-1"
        assert "jwks_uri" in body

    def test_token_is_valid_rs256(self):
        """Decode the issued token using the public key from JWKS."""
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]

        # Get public key PEM
        from cryptography.hazmat.primitives import serialization
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()

        claims = jose_jwt.decode(
            token,
            pub_pem,
            algorithms=["RS256"],
            audience="meridian-gateway",
            issuer="meridian-user-mgmt",
        )
        assert claims["sub"] == "rm_jane"
        assert claims["iss"] == "meridian-user-mgmt"
        assert claims["aud"] == "meridian-gateway"
        assert "exp" in claims
        assert "iat" in claims

    def test_token_claims_match_user(self):
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]

        from cryptography.hazmat.primitives import serialization
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()

        claims = jose_jwt.decode(
            token, pub_pem,
            algorithms=["RS256"],
            audience="meridian-gateway",
            issuer="meridian-user-mgmt",
            options={"verify_exp": False},
        )
        assert claims["name"] == "Jane Smith"
        assert claims["email"] == "jane.smith@meridianbank.com"
        assert "roles" in claims
        assert "relationship_manager" in claims["roles"]

    def test_token_header_has_kid(self):
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]
        header = jose_jwt.get_unverified_header(token)
        assert header["alg"] == "RS256"
        assert header["kid"] == "meridian-key-1"

    def test_token_unknown_user_returns_404(self):
        resp = client.post("/auth/token", json={"user_id": "nonexistent"})
        assert resp.status_code == 404

    def test_derived_book_in_response(self):
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        assert resp.status_code == 200
        body = resp.json()
        assert "derived_book" in body
        # rm_jane is in wealth-private-banking → REL-00042, REL-00099
        assert "REL-00042" in body["derived_book"]
        assert "REL-00099" in body["derived_book"]


# ── Domain membership → book derivation ──────────────────────────────────────

class TestDomainMembership:
    def test_book_derived_from_domains_rm_jane(self):
        """rm_jane is in wealth-private-banking → book should include REL-00042, REL-00099."""
        book = app_module.get_book_from_domains("rm_jane")
        assert "REL-00042" in book
        assert "REL-00099" in book
        # Should NOT include rm_okafor's relationships
        assert "REL-00188" not in book

    def test_book_derived_from_domains_rm_okafor(self):
        """rm_okafor is in intl-wealth → REL-00188, REL-00200."""
        book = app_module.get_book_from_domains("rm_okafor")
        assert "REL-00188" in book
        assert "REL-00200" in book
        assert "REL-00042" not in book

    def test_admin_book_is_empty(self):
        """admin is in admin-domain which has no relationships."""
        book = app_module.get_book_from_domains("admin")
        assert book == []

    def test_jwt_book_claim_derived_from_domains(self):
        """The 'book' claim in the JWT must match the derived (domain-based) book."""
        resp = client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]

        from cryptography.hazmat.primitives import serialization
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()

        claims = jose_jwt.decode(
            token, pub_pem,
            algorithms=["RS256"],
            audience="meridian-gateway",
            issuer="meridian-user-mgmt",
            options={"verify_exp": False},
        )
        assert "REL-00042" in claims["book"]
        assert "REL-00099" in claims["book"]
        assert "domains" in claims
        assert "wealth-private-banking" in claims["domains"]

    def test_add_member_to_domain_updates_book(self):
        """Adding rm_jane to intl-wealth means her token now includes REL-00188."""
        # Initially rm_jane should NOT have REL-00188
        book_before = app_module.get_book_from_domains("rm_jane")
        assert "REL-00188" not in book_before

        # Add rm_jane to intl-wealth
        resp = client.post(
            "/domains/intl-wealth/members",
            json={"user_id": "rm_jane"},
        )
        assert resp.status_code == 201

        # Now the derived book should include REL-00188
        book_after = app_module.get_book_from_domains("rm_jane")
        assert "REL-00188" in book_after
        assert "REL-00042" in book_after  # still has original domain

    def test_remove_member_from_domain_removes_relationships(self):
        """Removing rm_jane from wealth-private-banking removes those relationships."""
        resp = client.delete("/domains/wealth-private-banking/members/rm_jane")
        assert resp.status_code == 200

        book = app_module.get_book_from_domains("rm_jane")
        # rm_chen is still in wealth-private-banking but rm_jane's book should be empty now
        assert "REL-00042" not in book
        assert "REL-00099" not in book


# ── Domain CRUD ───────────────────────────────────────────────────────────────

class TestDomainCRUD:
    def test_list_domains(self):
        resp = client.get("/domains")
        assert resp.status_code == 200
        domains = resp.json()
        domain_ids = [d["id"] for d in domains]
        assert "wealth-private-banking" in domain_ids
        assert "intl-wealth" in domain_ids
        assert "admin-domain" in domain_ids

    def test_get_domain(self):
        resp = client.get("/domains/wealth-private-banking")
        assert resp.status_code == 200
        d = resp.json()
        assert d["id"] == "wealth-private-banking"
        assert "REL-00042" in d["relationships"]

    def test_create_domain(self):
        resp = client.post("/domains", json={
            "id": "new-domain",
            "name": "New Domain",
            "relationships": ["REL-99999"],
        })
        assert resp.status_code == 201
        assert resp.json()["id"] == "new-domain"

    def test_create_domain_duplicate_returns_409(self):
        resp = client.post("/domains", json={
            "id": "wealth-private-banking",
            "name": "Duplicate",
            "relationships": [],
        })
        assert resp.status_code == 409

    def test_update_domain_relationships(self):
        resp = client.put(
            "/domains/intl-wealth/relationships",
            json={"relationships": ["REL-00188", "REL-00200", "REL-00300"]},
        )
        assert resp.status_code == 200
        assert "REL-00300" in resp.json()["relationships"]

    def test_list_domain_members(self):
        resp = client.get("/domains/wealth-private-banking/members")
        assert resp.status_code == 200
        body = resp.json()
        assert "rm_jane" in body["members"]
        assert "rm_chen" in body["members"]

    def test_get_user_domains(self):
        resp = client.get("/users/rm_jane/domains")
        assert resp.status_code == 200
        body = resp.json()
        domain_ids = [d["id"] for d in body["domains"]]
        assert "wealth-private-banking" in domain_ids

    def test_get_user_domains_unknown_user_returns_404(self):
        resp = client.get("/users/nobody/domains")
        assert resp.status_code == 404


# ── Health check ──────────────────────────────────────────────────────────────

class TestHealth:
    def test_health_includes_algorithm_info(self):
        resp = client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["jwt_algorithm"] == "RS256"
        assert body["key_id"] == "meridian-key-1"


# ── Existing user CRUD (regression) ──────────────────────────────────────────

class TestUserCRUD:
    def test_list_users(self):
        resp = client.get("/users")
        assert resp.status_code == 200
        ids = [u["id"] for u in resp.json()]
        assert "rm_jane" in ids
        assert "rm_okafor" in ids

    def test_get_user(self):
        resp = client.get("/users/rm_jane")
        assert resp.status_code == 200
        assert resp.json()["name"] == "Jane Smith"

    def test_get_unknown_user_returns_404(self):
        resp = client.get("/users/ghost")
        assert resp.status_code == 404

    def test_patch_book(self):
        resp = client.patch("/users/rm_jane/book", json={"add": ["REL-EXTRA"], "remove": []})
        assert resp.status_code == 200
        assert "REL-EXTRA" in resp.json()["book"]

    def test_delete_user(self):
        resp = client.delete("/users/rm_chen")
        assert resp.status_code == 200
        assert client.get("/users/rm_chen").status_code == 404
