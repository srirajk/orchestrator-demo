"""Axiom Story A5 — the coverage service enforces tenant binding at the DATA layer.

Spec harness (doc 07 A5) → pytest mapping:
  CoverageTenantEnforcementTest.forgedHeaderRejectedByCoverage
      → TestCoverageTenantEnforcement.test_forged_header_rejected_by_coverage
  CoverageTenantEnforcementTest.crossTenantBookRequestRejected
      → TestCoverageTenantEnforcement.test_cross_tenant_book_request_rejected

The service does NOT trust the X-Tenant-Id header: it re-derives the tenant from the
verified JWT and from the requested book's owner and requires all three to agree, so a
compromised/buggy gateway is stopped HERE, not merely by gateway discipline.
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from fastapi.testclient import TestClient

from main import app
from conftest import auth_headers, mint_token

client = TestClient(app)


class TestCoverageTenantEnforcement:
    def test_forged_header_rejected_by_coverage(self):
        """X-Tenant-Id disagrees with the verified JWT tenant_id ⇒ 403 from coverage."""
        # A genuine, fully-valid token for tenant "default" (rm_jane's tenant) — but the
        # X-Tenant-Id header is forged to a different tenant.
        token = mint_token("default", sub="rm_jane")
        resp = client.get(
            "/coverage/rm_jane",
            headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": "tenant-evil"},
        )
        assert resp.status_code == 403, resp.text
        assert "does not match token tenant" in resp.json()["detail"]

    def test_cross_tenant_book_request_rejected(self):
        """Valid tenant-A token (token==header) requesting a tenant-B book ⇒ 403."""
        # Internally-consistent token for "tenant-a" (tenant_id == X-Tenant-Id), asking for
        # rm_jane's book — which is owned by the "default" tenant. Cross-tenant ⇒ deny.
        resp = client.get("/coverage/rm_jane", headers=auth_headers("tenant-a", sub="user_a"))
        assert resp.status_code == 403, resp.text
        assert "Cross-tenant book request" in resp.json()["detail"]

    # ── guards: the gate must not over-deny the honest, tenant-consistent path ──────

    def test_consistent_tenant_allowed(self):
        """Token tenant == header == book-owner tenant ⇒ 200 (no over-blocking)."""
        resp = client.get("/coverage/rm_jane", headers=auth_headers("default"))
        assert resp.status_code == 200, resp.text
        ids = {r["id"] for r in resp.json()}
        assert ids == {"REL-00042", "REL-00099", "REL-00333"}

    def test_check_forged_header_rejected(self):
        """The forged-header gate applies to CHECK too, not just DISCOVER."""
        token = mint_token("default", sub="rm_jane")
        resp = client.get(
            "/coverage/rm_jane/resources/REL-00042",
            headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": "tenant-evil"},
        )
        assert resp.status_code == 403, resp.text

    def test_resolve_forged_header_rejected(self):
        """RESOLVE is principal-agnostic but still binds token⇔header tenant."""
        token = mint_token("default", sub="rm_jane")
        resp = client.post(
            "/entities/resolve",
            json={"reference": "whitman", "type": "relationship"},
            headers={"Authorization": f"Bearer {token}", "X-Tenant-Id": "tenant-evil"},
        )
        assert resp.status_code == 403, resp.text

    def test_missing_tenant_header_rejected(self):
        """A data request with a valid token but no X-Tenant-Id cannot be bound ⇒ 403."""
        token = mint_token("default", sub="rm_jane")
        resp = client.get("/coverage/rm_jane", headers={"Authorization": f"Bearer {token}"})
        assert resp.status_code == 403, resp.text
