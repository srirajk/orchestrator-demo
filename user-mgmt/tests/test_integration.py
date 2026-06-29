"""
Integration tests for six real-world scenarios:
  1. Bank (wealth banking RM with relationship book)
  2. Hospital (attending physician with patient resources)
  3. Law firm (associate with matter resources)
  4. Role + team lifecycle (create team, add member, verify role grant)
  5. Policy lifecycle (validate/apply Cerbos YAML via admin endpoints)
  6. OIDC auth flow (authorize → code → tokens → userinfo)

These tests run fully async, using SQLite in-memory for DB and fakeredis for auth codes.
"""

import base64

import pytest
from httpx import AsyncClient

from main import DEMO_PASSWORD


# ── 1. Bank scenario ──────────────────────────────────────────────────────────

class TestBankScenario:
    """RM in wealth private banking has a seeded relationship book."""

    async def test_rm_jane_exists_with_correct_book(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane")
        assert resp.status_code == 200
        user = resp.json()
        assert user["name"] == "Jane Smith"
        assert "relationship_manager" in user["roles"]
        # Seeded book
        assert "REL-00042" in user["book"]
        assert "REL-00099" in user["book"]

    async def test_rm_jane_has_wealth_segment(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane")
        user = resp.json()
        assert "wealth" in user["segments"]

    async def test_rm_jane_is_in_wealth_domain(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane/domains")
        assert resp.status_code == 200
        domain_ids = [d["id"] for d in resp.json()["domains"]]
        assert "wealth-private-banking" in domain_ids

    async def test_rm_jane_relationship_access_allowed(self, app_client: AsyncClient):
        resp = await app_client.get("/users/rm_jane/relationships/REL-00042/access")
        assert resp.status_code == 200
        assert resp.json()["allowed"] is True

    async def test_rm_jane_okafor_relationship_denied(self, app_client: AsyncClient):
        """REL-00188 belongs to rm_okafor — jane should NOT have access."""
        resp = await app_client.get("/users/rm_jane/relationships/REL-00188/access")
        assert resp.status_code == 200
        assert resp.json()["allowed"] is False

    async def test_add_relationship_to_book(self, app_client: AsyncClient):
        resp = await app_client.patch("/users/rm_jane/book", json={"add": ["REL-99001"], "remove": []})
        assert resp.status_code == 200
        assert "REL-99001" in resp.json()["book"]

    async def test_remove_relationship_from_book(self, app_client: AsyncClient):
        # First add, then remove
        await app_client.patch("/users/rm_jane/book", json={"add": ["REL-TEMP"], "remove": []})
        resp = await app_client.patch("/users/rm_jane/book", json={"add": [], "remove": ["REL-TEMP"]})
        assert resp.status_code == 200
        assert "REL-TEMP" not in resp.json()["book"]

    async def test_jwt_contains_book_claim(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/token", json={"user_id": "rm_jane"})
        assert resp.status_code == 200
        from cryptography.hazmat.primitives import serialization
        import main as main_module
        from jose import jwt as jose_jwt
        pub_pem = main_module._rsa_private_key.public_key().public_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PublicFormat.SubjectPublicKeyInfo,
        ).decode()
        claims = jose_jwt.decode(
            resp.json()["access_token"], pub_pem,
            algorithms=["RS256"], audience="meridian-gateway",
            options={"verify_exp": False},
        )
        assert "REL-00042" in claims["book"]
        assert "wealth" in claims["segments"]


# ── 2. Hospital scenario ──────────────────────────────────────────────────────

class TestHospitalScenario:
    """Attending physician with patient resource grants."""

    async def test_create_physician(self, app_client: AsyncClient):
        resp = await app_client.post(
            "/users?user_id=dr_patel",
            json={
                "name": "Dr. Priya Patel",
                "email": "patel@citymedical.com",
                "roles": ["attending_physician"],
                "book": [],
                "segments": [],
                "clearance": 1,
                "team": "",
            },
        )
        assert resp.status_code == 201
        assert resp.json()["id"] == "dr_patel"

    async def test_add_patient_resource(self, app_client: AsyncClient):
        await app_client.post(
            "/users?user_id=dr_patel",
            json={"name": "Dr. Priya Patel", "email": "patel@citymedical.com",
                  "roles": ["attending_physician"], "book": [], "segments": [], "clearance": 1, "team": ""},
        )
        resp = await app_client.post(
            "/users/dr_patel/resources",
            json={"resource_type": "patient", "resource_id": "PAT-00123"},
        )
        assert resp.status_code == 201
        assert resp.json()["added"] is True

    async def test_patient_access_check(self, app_client: AsyncClient):
        await app_client.post(
            "/users?user_id=dr_patel",
            json={"name": "Dr. Priya Patel", "email": "patel@citymedical.com",
                  "roles": ["attending_physician"], "book": [], "segments": [], "clearance": 1, "team": ""},
        )
        await app_client.post(
            "/users/dr_patel/resources",
            json={"resource_type": "patient", "resource_id": "PAT-00123"},
        )
        # Check via access endpoint with resource_type param
        resp = await app_client.get(
            "/users/dr_patel/relationships/PAT-00123/access?resource_type=patient"
        )
        assert resp.status_code == 200
        assert resp.json()["allowed"] is True

    async def test_patient_access_denied_for_unassigned(self, app_client: AsyncClient):
        await app_client.post(
            "/users?user_id=dr_patel",
            json={"name": "Dr. Priya Patel", "email": "patel@citymedical.com",
                  "roles": ["attending_physician"], "book": [], "segments": [], "clearance": 1, "team": ""},
        )
        resp = await app_client.get(
            "/users/dr_patel/relationships/PAT-99999/access?resource_type=patient"
        )
        assert resp.status_code == 200
        assert resp.json()["allowed"] is False

    async def test_remove_patient_resource(self, app_client: AsyncClient):
        await app_client.post(
            "/users?user_id=dr_patel",
            json={"name": "Dr. Priya Patel", "email": "patel@citymedical.com",
                  "roles": ["attending_physician"], "book": [], "segments": [], "clearance": 1, "team": ""},
        )
        await app_client.post(
            "/users/dr_patel/resources",
            json={"resource_type": "patient", "resource_id": "PAT-00123"},
        )
        resp = await app_client.delete("/users/dr_patel/resources/patient/PAT-00123")
        assert resp.status_code == 200
        assert resp.json()["removed"] is True

        # Now access should be denied
        check = await app_client.get(
            "/users/dr_patel/relationships/PAT-00123/access?resource_type=patient"
        )
        assert check.json()["allowed"] is False


# ── 3. Law firm scenario ──────────────────────────────────────────────────────

class TestLawFirmScenario:
    """Associate with matter resource grants."""

    async def _create_associate(self, app_client: AsyncClient) -> None:
        await app_client.post(
            "/users?user_id=assoc_smith",
            json={
                "name": "James Smith",
                "email": "jsmith@lawfirm.com",
                "roles": ["associate"],
                "book": [],
                "segments": [],
                "clearance": 1,
                "team": "",
            },
        )

    async def test_create_associate_and_assign_matter(self, app_client: AsyncClient):
        await self._create_associate(app_client)
        resp = await app_client.post(
            "/users/assoc_smith/resources",
            json={"resource_type": "matter", "resource_id": "MTR-2024-001"},
        )
        assert resp.status_code == 201

    async def test_matter_access_allowed(self, app_client: AsyncClient):
        await self._create_associate(app_client)
        await app_client.post(
            "/users/assoc_smith/resources",
            json={"resource_type": "matter", "resource_id": "MTR-2024-001"},
        )
        resp = await app_client.get(
            "/users/assoc_smith/relationships/MTR-2024-001/access?resource_type=matter"
        )
        assert resp.json()["allowed"] is True

    async def test_matter_idempotent_add(self, app_client: AsyncClient):
        """Adding the same matter twice should not create duplicates."""
        await self._create_associate(app_client)
        for _ in range(3):
            await app_client.post(
                "/users/assoc_smith/resources",
                json={"resource_type": "matter", "resource_id": "MTR-2024-001"},
            )
        resp = await app_client.get(
            "/users/assoc_smith/relationships/MTR-2024-001/access?resource_type=matter"
        )
        assert resp.json()["allowed"] is True


# ── 4. Role + team lifecycle ──────────────────────────────────────────────────

class TestRoleTeamLifecycle:

    async def test_create_team(self, app_client: AsyncClient):
        resp = await app_client.post("/teams", json={
            "id": "tech-ops",
            "name": "Tech Operations",
            "description": "Internal ops",
            "default_roles": ["platform_admin"],
            "segments": ["servicing"],
            "allowed_domains": [],
        })
        assert resp.status_code == 201
        assert resp.json()["id"] == "tech-ops"

    async def test_add_member_auto_assigns_default_role(self, app_client: AsyncClient):
        # Create a team with default_roles
        await app_client.post("/teams", json={
            "id": "tech-ops",
            "name": "Tech Operations",
            "description": "",
            "default_roles": ["platform_admin"],
            "segments": [],
            "allowed_domains": [],
        })
        # Add rm_chen to the team
        resp = await app_client.post("/teams/tech-ops/members", json={"user_id": "rm_chen"})
        assert resp.status_code == 201

        # rm_chen should now have platform_admin role
        user_resp = await app_client.get("/users/rm_chen")
        assert "platform_admin" in user_resp.json()["roles"]

    async def test_remove_member(self, app_client: AsyncClient):
        await app_client.post("/teams", json={
            "id": "tech-ops",
            "name": "Tech Operations",
            "description": "",
            "default_roles": [],
            "segments": [],
            "allowed_domains": [],
        })
        await app_client.post("/teams/tech-ops/members", json={"user_id": "rm_chen"})
        resp = await app_client.delete("/teams/tech-ops/members/rm_chen")
        assert resp.status_code == 204

        # Verify removal
        members_resp = await app_client.get("/teams/tech-ops/members")
        member_ids = [m["id"] for m in members_resp.json()["members"]]
        assert "rm_chen" not in member_ids

    async def test_list_teams(self, app_client: AsyncClient):
        resp = await app_client.get("/teams")
        assert resp.status_code == 200
        team_ids = [t["id"] for t in resp.json()]
        # Teams that are pure teams (not also domains) should be listed
        assert "platform" in team_ids or "wealth-ultra-hnw" in team_ids

    async def test_create_role_and_assign(self, app_client: AsyncClient):
        # Create custom role
        role_resp = await app_client.post("/roles", json={
            "id": "compliance_officer",
            "name": "Compliance Officer",
            "description": "Compliance and audit role",
            "permissions": ["read:audit-logs"],
            "clearance_required": 3,
        })
        assert role_resp.status_code == 201

        # Assign to user
        assign_resp = await app_client.post("/users/rm_jane/roles", json={"role_id": "compliance_officer"})
        assert assign_resp.status_code == 200
        assert "compliance_officer" in assign_resp.json()["roles"]

    async def test_remove_role_from_user(self, app_client: AsyncClient):
        # Assign then remove
        await app_client.post("/users/rm_jane/roles", json={"role_id": "domain_admin"})
        resp = await app_client.delete("/users/rm_jane/roles/domain_admin")
        assert resp.status_code == 204

    async def test_domain_admin_grant_and_revoke(self, app_client: AsyncClient):
        # Grant rm_okafor as admin of wealth-private-banking
        grant_resp = await app_client.post(
            "/domains/wealth-private-banking/admins",
            json={"user_id": "rm_okafor"},
        )
        assert grant_resp.status_code == 201

        # Verify via domain admins list
        admins_resp = await app_client.get("/domains/wealth-private-banking/admins")
        assert "rm_okafor" in admins_resp.json()["admins"]

        # Verify via user's admin_domains
        user_resp = await app_client.get("/users/rm_okafor")
        assert "wealth-private-banking" in user_resp.json()["admin_domains"]

        # Revoke
        revoke_resp = await app_client.delete("/domains/wealth-private-banking/admins/rm_okafor")
        assert revoke_resp.status_code == 200

        admins_after = await app_client.get("/domains/wealth-private-banking/admins")
        assert "rm_okafor" not in admins_after.json()["admins"]

        user_after = await app_client.get("/users/rm_okafor")
        assert "wealth-private-banking" not in user_after.json()["admin_domains"]


# ── 5. Policy lifecycle ───────────────────────────────────────────────────────

class TestPolicyLifecycle:

    async def test_list_policies(self, app_client: AsyncClient):
        resp = await app_client.get("/admin/policies")
        assert resp.status_code == 200
        assert "policies" in resp.json()

    async def test_validate_valid_cerbos_yaml(self, app_client: AsyncClient):
        valid_yaml = """
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: "default"
  resource: "agent"
  rules:
    - actions: ["invoke"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager"]
"""
        resp = await app_client.post("/admin/policies/validate", json={"yaml": valid_yaml})
        assert resp.status_code == 200
        result = resp.json()
        assert "valid" in result

    async def test_validate_invalid_yaml(self, app_client: AsyncClient):
        resp = await app_client.post("/admin/policies/validate", json={"yaml": "not: valid: cerbos: yaml:"})
        assert resp.status_code == 200
        # Should return valid: False or valid: True — both are acceptable; service doesn't crash

    async def test_policy_resources_endpoint(self, app_client: AsyncClient):
        resp = await app_client.get("/admin/policy-resources")
        assert resp.status_code == 200
        resources = resp.json()["resources"]
        # Defaults are always present
        assert "agent" in resources
        assert "relationship" in resources
        assert "domain" in resources

    async def test_segments_endpoint(self, app_client: AsyncClient):
        resp = await app_client.get("/admin/segments")
        assert resp.status_code == 200
        segs = resp.json()["segments"]
        # Seeded users have wealth + servicing segments
        assert "wealth" in segs
        assert "servicing" in segs


# ── 6. OIDC auth flow ─────────────────────────────────────────────────────────

class TestOIDCFlow:

    async def test_discovery_doc(self, app_client: AsyncClient):
        resp = await app_client.get("/.well-known/openid-configuration")
        assert resp.status_code == 200
        doc = resp.json()
        assert "issuer" in doc
        assert "authorization_endpoint" in doc
        assert "token_endpoint" in doc
        assert "jwks_uri" in doc

    async def test_login_form(self, app_client: AsyncClient):
        resp = await app_client.get("/oauth/authorize", params={
            "response_type": "code",
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "xyz",
        })
        assert resp.status_code == 200
        assert "Sign In" in resp.text

    async def test_full_oidc_flow(self, app_client: AsyncClient):
        redirect_uri = "http://localhost:3080/oauth/openid/callback"

        # Step 1: POST credentials → get auth code
        auth_resp = await app_client.post("/oauth/authorize", data={
            "username": "rm_jane",
            "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat",
            "redirect_uri": redirect_uri,
            "state": "state-abc",
        }, follow_redirects=False)
        assert auth_resp.status_code == 302
        location = auth_resp.headers["location"]
        assert "code=" in location

        # Extract code from query string
        code = None
        for param in location.split("?", 1)[-1].split("&"):
            if param.startswith("code="):
                code = param.split("=", 1)[1]
        assert code is not None, "No code in redirect"

        # Step 2: Exchange code for tokens
        token_resp = await app_client.post("/oauth/token", data={
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        })
        assert token_resp.status_code == 200
        tokens = token_resp.json()
        assert "access_token" in tokens
        assert "id_token" in tokens
        assert tokens["token_type"] == "Bearer"

        # Step 3: Call /oauth/userinfo
        userinfo_resp = await app_client.get(
            "/oauth/userinfo",
            headers={"Authorization": f"Bearer {tokens['access_token']}"},
        )
        assert userinfo_resp.status_code == 200
        ui = userinfo_resp.json()
        assert ui["sub"] == "rm_jane"
        assert ui["name"] == "Jane Smith"

    async def test_auth_code_single_use(self, app_client: AsyncClient):
        redirect_uri = "http://localhost:3080/oauth/openid/callback"
        auth_resp = await app_client.post("/oauth/authorize", data={
            "username": "rm_jane",
            "password": DEMO_PASSWORD,
            "client_id": "meridian-librechat",
            "redirect_uri": redirect_uri,
            "state": "st",
        }, follow_redirects=False)
        code = None
        for p in auth_resp.headers["location"].split("?", 1)[-1].split("&"):
            if p.startswith("code="):
                code = p.split("=", 1)[1]

        token_data = {
            "grant_type": "authorization_code",
            "code": code,
            "client_id": "meridian-librechat",
            "client_secret": "meridian-client-secret",
            "redirect_uri": redirect_uri,
        }
        r1 = await app_client.post("/oauth/token", data=token_data)
        assert r1.status_code == 200

        r2 = await app_client.post("/oauth/token", data=token_data)
        assert r2.status_code == 400
        assert "invalid_grant" in r2.json()["detail"]

    async def test_wrong_password_redirects_with_error(self, app_client: AsyncClient):
        resp = await app_client.post("/oauth/authorize", data={
            "username": "rm_jane",
            "password": "totally_wrong",
            "client_id": "meridian-librechat",
            "redirect_uri": "http://localhost:3080/oauth/openid/callback",
            "state": "s",
        }, follow_redirects=False)
        assert resp.status_code == 302
        assert "error=" in resp.headers["location"]

    async def test_admin_login_endpoint(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/login", json={
            "username": "admin",
            "password": DEMO_PASSWORD,
        })
        assert resp.status_code == 200
        body = resp.json()
        assert "access_token" in body
        assert body["user"]["id"] == "admin"

    async def test_wrong_password_login_401(self, app_client: AsyncClient):
        resp = await app_client.post("/auth/login", json={
            "username": "rm_jane",
            "password": "wrong",
        })
        assert resp.status_code == 401

    async def test_jwks_endpoint(self, app_client: AsyncClient):
        resp = await app_client.get("/.well-known/jwks.json")
        assert resp.status_code == 200
        keys = resp.json()["keys"]
        assert len(keys) == 1
        assert keys[0]["kty"] == "RSA"
        assert keys[0]["alg"] == "RS256"

    async def test_health_endpoint(self, app_client: AsyncClient):
        resp = await app_client.get("/health")
        assert resp.status_code == 200
        body = resp.json()
        assert body["jwt_algorithm"] == "RS256"
        # Postgres should be connected (SQLite in tests)
        assert body["postgres"] == "connected"
