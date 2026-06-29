"""
Tests for the Meridian User Management Service — RS256 JWT, JWKS, domain/membership model.

Run with:
    cd user-mgmt
    pip install -r requirements.txt
    pytest tests/ -v

These tests use SQLite in-memory (StaticPool) for the DB, and fakeredis for auth codes.
All tests are async (pytest-asyncio with asyncio_mode = auto).
"""

import base64

import pytest
from httpx import AsyncClient
from jose import jwt as jose_jwt

import main as app_module
from main import DEMO_PASSWORD


# ── JWKS tests ────────────────────────────────────────────────────────────────

class TestJWKS:
    async def test_jwks_has_correct_structure(self, app_client: AsyncClient):
        resp = await app_client.get("/.well-known/jwks.json")
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

    async def test_jwks_e_is_65537(self, app_client: AsyncClient):
        """Public exponent must be 65537 (the standard value)."""
        from jose.utils import base64url_decode
        resp = await app_client.get("/.well-known/jwks.json")
        key = resp.json()["keys"][0]
        e_bytes = base64url_decode(key["e"].encode())
        e = int.from_bytes(e_bytes, "big")
        assert e == 65537

    async def test_jwks_n_length(self, app_client: AsyncClient):
        """Modulus for a 2048-bit key should be 256 bytes when decoded."""
        from jose.utils import base64url_decode
        resp = await app_client.get("/.well-known/jwks.json")
        key = resp.json()["keys"][0]
        n_bytes = base64url_decode(key["n"].encode())
        assert len(n_bytes) == 256  # 2048 / 8


# ── Token issuance and RS256 verification ─────────────────────────────────────

class TestTokenIssuance:
    def _pub_pem(self) -> str:
        from cryptography.hazmat.primitives import serialization
        return app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()

    async def test_issue_token_returns_rs256_jwt(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        assert resp.status_code == 200
        body = resp.json()
        assert "access_token" in body
        assert body["algorithm"] == "RS256"
        assert body["key_id"] == "meridian-key-1"
        assert "jwks_uri" in body

    async def test_token_is_valid_rs256(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]
        claims = jose_jwt.decode(
            token, self._pub_pem(), algorithms=["RS256"],
            audience="meridian-gateway", issuer="meridian-user-mgmt",
        )
        assert claims["sub"] == "rm_jane"
        assert claims["iss"] == "meridian-user-mgmt"
        assert claims["aud"] == "meridian-gateway"
        assert "exp" in claims
        assert "iat" in claims

    async def test_token_claims_match_user(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]
        claims = jose_jwt.decode(
            token, self._pub_pem(), algorithms=["RS256"],
            audience="meridian-gateway", issuer="meridian-user-mgmt",
            options={"verify_exp": False},
        )
        assert claims["name"] == "Jane Smith"
        assert claims["email"] == "jane.smith@meridianbank.com"
        assert "relationship_manager" in claims["roles"]

    async def test_token_header_has_kid(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]
        header = jose_jwt.get_unverified_header(token)
        assert header["alg"] == "RS256"
        assert header["kid"] == "meridian-key-1"

    async def test_token_unknown_user_returns_404(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "nonexistent"})
        assert resp.status_code == 404

    async def test_derived_book_in_response(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        assert resp.status_code == 200
        body = resp.json()
        assert "derived_book" in body
        # rm_jane has REL-00042, REL-00099 in her personal resources
        assert "REL-00042" in body["derived_book"]
        assert "REL-00099" in body["derived_book"]


# ── Book management (PersonalResource table) ──────────────────────────────────

class TestBookManagement:
    """
    In the new system, book = personal_resources rows with resource_type='relationship'.
    Domain membership does NOT auto-populate the book — they are separate concepts.
    """

    async def test_rm_jane_initial_book(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane/book")
        assert resp.status_code == 200
        rels = resp.json()["relationships"]
        assert "REL-00042" in rels
        assert "REL-00099" in rels

    async def test_rm_okafor_initial_book(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_okafor/book")
        assert resp.status_code == 200
        rels = resp.json()["relationships"]
        assert "REL-00188" in rels
        assert "REL-00200" in rels
        assert "REL-00042" not in rels

    async def test_jwt_book_claim_from_personal_resources(self, app_client: AsyncClient):
        """The 'book' claim in the JWT reflects the user's personal relationship resources."""
        from cryptography.hazmat.primitives import serialization
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        token = resp.json()["access_token"]
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()
        claims = jose_jwt.decode(
            token, pub_pem, algorithms=["RS256"],
            audience="meridian-gateway", issuer="meridian-user-mgmt",
            options={"verify_exp": False},
        )
        assert "REL-00042" in claims["book"]
        assert "REL-00099" in claims["book"]
        assert "domains" in claims
        assert "wealth-private-banking" in claims["domains"]

    async def test_adding_to_domain_does_not_change_book(self, app_client: AsyncClient):
        """
        Domain membership is decoupled from book in the new system.
        Adding rm_jane to intl-wealth does NOT add intl-wealth's relationships to her book.
        """
        book_before = (await app_client.get("/users/rm_jane/book")).json()["relationships"]
        assert "REL-00188" not in book_before

        # Add rm_jane to intl-wealth domain
        resp = await app_client.post(
            "/domains/intl-wealth/members",
            json={"user_id": "rm_jane"},
        )
        assert resp.status_code == 201

        # Book is unchanged — domain membership does NOT auto-populate book
        book_after = (await app_client.get("/users/rm_jane/book")).json()["relationships"]
        assert "REL-00188" not in book_after
        # Jane still has her own relationships
        assert "REL-00042" in book_after

    async def test_removing_from_domain_does_not_remove_book_relationships(self, app_client: AsyncClient):
        """
        Removing from a domain does not remove personal relationships.
        """
        resp = await app_client.delete("/domains/wealth-private-banking/members/rm_jane")
        assert resp.status_code == 200

        # Book is unchanged — domain membership and book are separate
        book = (await app_client.get("/users/rm_jane/book")).json()["relationships"]
        assert "REL-00042" in book
        assert "REL-00099" in book

    async def test_patch_book_add(self, app_client: AsyncClient):
        resp = await app_client.patch("/users/rm_jane/book", json={"add": ["REL-EXTRA"], "remove": []})
        assert resp.status_code == 200
        assert "REL-EXTRA" in resp.json()["book"]

    async def test_patch_book_remove(self, app_client: AsyncClient):
        resp = await app_client.patch("/users/rm_jane/book", json={"add": [], "remove": ["REL-00042"]})
        assert resp.status_code == 200
        assert "REL-00042" not in resp.json()["book"]
        assert "REL-00099" in resp.json()["book"]


# ── Domain CRUD ───────────────────────────────────────────────────────────────

class TestDomainCRUD:
    async def test_list_domains(self, app_client: AsyncClient):
        resp = await app_client.get("/domains")
        assert resp.status_code == 200
        domain_ids = [d["id"] for d in resp.json()]
        assert "wealth-private-banking" in domain_ids
        assert "intl-wealth" in domain_ids
        assert "admin-domain" in domain_ids

    async def test_get_domain(self, app_client: AsyncClient):
        resp = await app_client.get("/domains/wealth-private-banking")
        assert resp.status_code == 200
        d = resp.json()
        assert d["id"] == "wealth-private-banking"
        assert "REL-00042" in d["relationships"]

    async def test_create_domain(self, app_client: AsyncClient):
        resp = await app_client.post("/domains", json={
            "id": "new-domain",
            "name": "New Domain",
            "relationships": ["REL-99999"],
        })
        assert resp.status_code == 201
        assert resp.json()["id"] == "new-domain"

    async def test_create_domain_duplicate_returns_409(self, app_client: AsyncClient):
        resp = await app_client.post("/domains", json={
            "id": "wealth-private-banking",
            "name": "Duplicate",
            "relationships": [],
        })
        assert resp.status_code == 409

    async def test_update_domain_relationships(self, app_client: AsyncClient):
        resp = await app_client.put(
            "/domains/intl-wealth/relationships",
            json={"relationships": ["REL-00188", "REL-00200", "REL-00300"]},
        )
        assert resp.status_code == 200
        assert "REL-00300" in resp.json()["relationships"]

    async def test_list_domain_members(self, app_client: AsyncClient):
        resp = await app_client.get("/domains/wealth-private-banking/members")
        assert resp.status_code == 200
        body = resp.json()
        assert "rm_jane" in body["members"]
        assert "rm_chen" in body["members"]

    async def test_get_user_domains(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane/domains")
        assert resp.status_code == 200
        domain_ids = [d["id"] for d in resp.json()["domains"]]
        assert "wealth-private-banking" in domain_ids

    async def test_get_user_domains_unknown_user_returns_404(self, app_client: AsyncClient):
        resp = await app_client.get("/users/nobody/domains")
        assert resp.status_code == 404


# ── Health check ──────────────────────────────────────────────────────────────

class TestHealth:
    async def test_health_includes_algorithm_info(self, app_client: AsyncClient):
        resp = await app_client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["jwt_algorithm"] == "RS256"
        assert body["key_id"] == "meridian-key-1"

    async def test_health_postgres_connected(self, app_client: AsyncClient):
        resp = await app_client.get("/health")
        assert resp.json()["postgres"] == "connected"


# ── User CRUD ─────────────────────────────────────────────────────────────────

class TestUserCRUD:
    async def test_list_users(self, app_client: AsyncClient):
        resp = await app_client.get("/users")
        assert resp.status_code == 200
        ids = [u["id"] for u in resp.json()]
        assert "rm_jane" in ids
        assert "rm_okafor" in ids

    async def test_get_user(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane")
        assert resp.status_code == 200
        assert resp.json()["name"] == "Jane Smith"

    async def test_get_unknown_user_returns_404(self, app_client: AsyncClient):
        resp = await app_client.get("/users/ghost")
        assert resp.status_code == 404

    async def test_delete_user(self, app_client: AsyncClient):
        resp = await app_client.delete("/users/rm_chen")
        assert resp.status_code == 200
        assert (await app_client.get("/users/rm_chen")).status_code == 404

    async def test_create_user(self, app_client: AsyncClient):
        resp = await app_client.post("/users?user_id=new_user", json={
            "name": "New User",
            "email": "new@example.com",
            "roles": ["relationship_manager"],
            "book": ["REL-55555"],
            "segments": ["wealth"],
            "clearance": 2,
            "team": "",
        })
        assert resp.status_code == 201
        assert resp.json()["id"] == "new_user"
        assert "REL-55555" in resp.json()["book"]

    async def test_create_duplicate_user_returns_409(self, app_client: AsyncClient):
        resp = await app_client.post("/users?user_id=rm_jane", json={
            "name": "Jane Duplicate",
            "email": "jane@dup.com",
            "roles": ["relationship_manager"],
            "book": [],
            "segments": [],
            "clearance": 2,
            "team": "",
        })
        assert resp.status_code == 409


# ── OIDC tests ────────────────────────────────────────────────────────────────

class TestOIDCDiscovery:
    async def test_discovery_doc_has_required_fields(self, app_client: AsyncClient):
        resp = await app_client.get("/.well-known/openid-configuration")
        assert resp.status_code == 200
        doc = resp.json()
        assert "issuer" in doc
        assert "authorization_endpoint" in doc
        assert "token_endpoint" in doc
        assert "userinfo_endpoint" in doc
        assert "jwks_uri" in doc
        assert "code" in doc["response_types_supported"]

    async def test_discovery_issuer_matches_configured(self, app_client: AsyncClient):
        resp = await app_client.get("/.well-known/openid-configuration")
        doc = resp.json()
        assert doc["issuer"] == app_module.OIDC_ISSUER
        assert doc["issuer"].startswith("http")


class TestOIDCAuthorize:
    async def test_get_authorize_returns_html_form(self, app_client: AsyncClient):
        resp = await app_client.get("/oauth/authorize", params={
            "response_type": "code",
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "test-state-123",
        })
        assert resp.status_code == 200
        assert "text/html" in resp.headers["content-type"]
        assert "Sign In" in resp.text
        assert "Meridian" in resp.text

    async def test_post_authorize_valid_credentials_redirects_with_code(self, app_client: AsyncClient):
        resp = await app_client.post("/oauth/authorize", data={
            "username": "rm_jane",
            "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "state-abc",
        }, follow_redirects=False)
        assert resp.status_code == 302
        location = resp.headers["location"]
        assert "code=" in location
        assert "state=state-abc" in location
        assert "error" not in location

    async def test_post_authorize_wrong_password_redirects_with_error(self, app_client: AsyncClient):
        resp = await app_client.post("/oauth/authorize", data={
            "username": "rm_jane",
            "password": "wrong-password",
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "state-def",
        }, follow_redirects=False)
        assert resp.status_code == 302
        assert "error=" in resp.headers["location"]

    async def test_post_authorize_unknown_user_redirects_with_error(self, app_client: AsyncClient):
        resp = await app_client.post("/oauth/authorize", data={
            "username": "nobody",
            "password": "password",
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "state-ghi",
        }, follow_redirects=False)
        assert resp.status_code == 302
        assert "error=" in resp.headers["location"]


class TestOIDCTokenEndpoint:
    async def _get_auth_code(self, app_client: AsyncClient, user_id: str = "rm_jane") -> tuple[str, str]:
        """Helper: POST to /oauth/authorize and extract the auth code from redirect."""
        redirect_uri = "http://localhost:3080/oauth/openid/callback"
        resp = await app_client.post("/oauth/authorize", data={
            "username": user_id,
            "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat",
            "redirect_uri": redirect_uri,
            "state": "st",
        }, follow_redirects=False)
        assert resp.status_code == 302
        location = resp.headers["location"]
        code = None
        for param in location.split("?", 1)[-1].split("&"):
            if param.startswith("code="):
                code = param.split("=", 1)[1]
        assert code is not None
        return code, redirect_uri

    async def test_token_exchange_returns_access_and_id_token(self, app_client: AsyncClient):
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        })
        assert resp.status_code == 200
        body = resp.json()
        assert "access_token" in body
        assert "id_token" in body
        assert body["token_type"] == "Bearer"
        assert body["expires_in"] == 3600

    async def test_access_token_audience_is_gateway(self, app_client: AsyncClient):
        """access_token must be verifiable by the gateway (aud=meridian-gateway)."""
        from cryptography.hazmat.primitives import serialization
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        })
        access_token = resp.json()["access_token"]
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()
        claims = jose_jwt.decode(
            access_token, pub_pem, algorithms=["RS256"],
            audience="meridian-gateway",
            options={"verify_exp": False},
        )
        assert claims["sub"] == "rm_jane"
        assert claims["aud"] == "meridian-gateway"
        assert "roles" in claims
        assert "segments" in claims

    async def test_id_token_audience_is_client_id(self, app_client: AsyncClient):
        """id_token must be for LibreChat (aud=meridian-librechat, iss=OIDC_ISSUER)."""
        from cryptography.hazmat.primitives import serialization
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        })
        id_token = resp.json()["id_token"]
        pub_pem = app_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()
        claims = jose_jwt.decode(
            id_token, pub_pem, algorithms=["RS256"],
            audience="meridian-librechat",
            options={"verify_exp": False},
        )
        assert claims["sub"] == "rm_jane"
        assert claims["iss"] == app_module.OIDC_ISSUER
        assert claims["aud"] == "meridian-librechat"

    async def test_auth_code_is_single_use(self, app_client: AsyncClient):
        """Consuming a code once must prevent replay."""
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        data = {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        }
        resp1 = await app_client.post("/oauth/token", data=data)
        assert resp1.status_code == 200

        resp2 = await app_client.post("/oauth/token", data=data)
        assert resp2.status_code == 400
        assert "invalid_grant" in resp2.json()["detail"]

    async def test_wrong_client_secret_rejected(self, app_client: AsyncClient):
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "wrong-secret",
            "redirect_uri": redirect_uri,
        })
        assert resp.status_code == 401

    async def test_redirect_uri_mismatch_rejected(self, app_client: AsyncClient):
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": "http://evil.example.com/callback",
        })
        assert resp.status_code == 400

    async def test_token_exchange_basic_auth(self, app_client: AsyncClient):
        """client_secret_basic: credentials in Authorization header."""
        code, redirect_uri = await self._get_auth_code(app_client, "rm_jane")
        credentials = base64.b64encode(b"meridian-librechat:meridian-client-secret").decode()
        resp = await app_client.post("/oauth/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
            },
            headers={"Authorization": f"Basic {credentials}"},
        )
        assert resp.status_code == 200
        assert "access_token" in resp.json()


class TestOIDCUserInfo:
    async def _get_access_token(self, app_client: AsyncClient, user_id: str = "rm_jane") -> str:
        redirect_uri = "http://localhost:3080/oauth/openid/callback"
        resp1 = await app_client.post("/oauth/authorize", data={
            "username": user_id, "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat", "redirect_uri": redirect_uri, "state": "s",
        }, follow_redirects=False)
        code = None
        for p in resp1.headers["location"].split("?", 1)[-1].split("&"):
            if p.startswith("code="):
                code = p.split("=", 1)[1]
        resp2 = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code", "code": code,
            "client_id": "meridian-librechat", "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        })
        return resp2.json()["access_token"]

    async def test_userinfo_returns_profile(self, app_client: AsyncClient):
        token = await self._get_access_token(app_client, "rm_jane")
        resp = await app_client.get("/oauth/userinfo", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 200
        body = resp.json()
        assert body["sub"] == "rm_jane"
        assert body["name"] == "Jane Smith"
        assert "email" in body

    async def test_userinfo_without_token_is_401(self, app_client: AsyncClient):
        resp = await app_client.get("/oauth/userinfo")
        assert resp.status_code == 401

    async def test_userinfo_with_bad_token_is_401(self, app_client: AsyncClient):
        resp = await app_client.get("/oauth/userinfo", headers={"Authorization": "Bearer not.a.jwt"})
        assert resp.status_code == 401


class TestPasswordHashing:
    async def test_default_password_accepted(self, app_client: AsyncClient):
        """Seeded users must authenticate with DEMO_PASSWORD."""
        resp = await app_client.post("/oauth/authorize", data={
            "username": "admin", "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "s",
        }, follow_redirects=False)
        assert resp.status_code == 302
        assert "code=" in resp.headers["location"]

    async def test_wrong_password_rejected_for_all_roles(self, app_client: AsyncClient):
        for uid in ["rm_jane", "admin", "rm_diaz"]:
            resp = await app_client.post("/oauth/authorize", data={
                "username": uid, "password": "totally_wrong",
                "client_id": "meridian-librechat",
                "redirect_uri": "http://localhost:3080/oauth/openid/callback",
                "state": "s",
            }, follow_redirects=False)
            assert resp.status_code == 302, f"Expected redirect for {uid}"
            assert "error=" in resp.headers["location"], f"Expected error for {uid}"
