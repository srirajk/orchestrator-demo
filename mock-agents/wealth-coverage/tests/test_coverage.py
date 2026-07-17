"""
Unit + integration tests for the wealth-coverage service.

Run from mock-agents/wealth-coverage/:
    python -m pytest tests/ -v
"""

import sys
import os

# Ensure the service package is importable when pytest is invoked from the
# service directory.
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import pytest
from fastapi.testclient import TestClient

from data import check, discover, resolve
from main import app
from conftest import auth_headers

client = TestClient(app)

# The service's JWT + A5 tenant-binding gates require every data call to carry a valid
# bearer token and a matching X-Tenant-Id. All demo principals live in the "default"
# tenant, so the honest header set for these HTTP tests is auth_headers("default").
DEFAULT_AUTH = auth_headers("default")


# ── unit tests: data layer ────────────────────────────────────────────────────


class TestDiscover:
    def test_rm_jane_sees_her_relationships(self):
        results = discover("rm_jane")
        ids = {r["id"] for r in results}
        assert ids == {"REL-00042", "REL-00099", "REL-00333"}

    def test_rm_ken_sees_okafor(self):
        results = discover("rm_ken")
        ids = {r["id"] for r in results}
        # rm_ken's book is exactly Okafor (REL-00188) per data.BOOKS. (The prior
        # {REL-00188, REL-00444, REL-00445} assertion was a copy-paste from the
        # ops_analyst_singh case and never matched the data — corrected here.)
        assert ids == {"REL-00188"}

    def test_ops_analyst_singh_sees_okafor(self):
        results = discover("ops_analyst_singh")
        ids = {r["id"] for r in results}
        assert ids == {"REL-00188", "REL-00444", "REL-00445"}

    def test_unknown_principal_gets_empty_list(self):
        assert discover("nobody") == []

    def test_admin_sees_all(self):
        results = discover("admin")
        ids = {r["id"] for r in results}
        assert {"REL-00042", "REL-00099", "REL-00188", "REL-00333", "REL-00444", "REL-00445"}.issubset(ids)


class TestCheck:
    def test_rm_jane_allowed_whitman(self):
        result = check("rm_jane", "REL-00042")
        assert result["allowed"] is True
        assert result["reason"] == "in-book"

    def test_rm_jane_denied_okafor(self):
        result = check("rm_jane", "REL-00188")
        assert result["allowed"] is False
        assert result["reason"] == "not-in-book"

    def test_ops_analyst_singh_allowed_okafor(self):
        result = check("ops_analyst_singh", "REL-00188")
        assert result["allowed"] is True
        assert result["reason"] == "in-book"

    def test_unknown_resource_denied(self):
        result = check("rm_jane", "REL-99999")
        assert result["allowed"] is False
        assert result["reason"] == "unknown-resource"

    def test_unknown_principal_denied(self):
        result = check("nobody", "REL-00042")
        assert result["allowed"] is False


class TestResolve:
    def test_resolve_whitman_by_alias(self):
        result = resolve("whitman family office", "relationship", "rm_jane")
        assert result["resolved"] is True
        assert result["id"] == "REL-00042"
        assert result["canonical_name"] == "Whitman Family Office"

    def test_resolve_exact_id(self):
        result = resolve("REL-00042", "relationship", "rm_jane")
        assert result["resolved"] is True
        assert result["id"] == "REL-00042"

    def test_resolve_not_found_returns_empty_candidates(self):
        result = resolve("nonexistent client", "relationship", "rm_jane")
        assert result["resolved"] is False
        assert result["id"] is None
        assert result["candidates"] == []

    def test_okafor_resolves_for_anyone_but_check_denies_rm_jane(self):
        # RESOLVE is principal-agnostic: it finds Okafor regardless of the caller's book.
        result = resolve("okafor", "relationship", "rm_jane")
        assert result["resolved"] is True
        assert result["id"] == "REL-00188"
        # CHECK is the gate: rm_jane is NOT entitled to Okafor (not in her book).
        assert check("rm_jane", "REL-00188")["allowed"] is False


# ── HTTP integration tests: FastAPI layer ─────────────────────────────────────


class TestDiscoverEndpoint:
    def test_discover_rm_jane(self):
        resp = client.get("/coverage/rm_jane", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        data = resp.json()
        ids = {r["id"] for r in data}
        assert ids == {"REL-00042", "REL-00099", "REL-00333"}
        # Verify shape
        for r in data:
            assert "id" in r
            assert "label" in r
            assert "sub_domain" in r

    def test_discover_unknown_principal_returns_empty_list(self):
        resp = client.get("/coverage/nobody", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        assert resp.json() == []


class TestCheckEndpoint:
    def test_check_allowed(self):
        resp = client.get("/coverage/rm_jane/resources/REL-00042", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        body = resp.json()
        assert body["allowed"] is True

    def test_check_denied(self):
        resp = client.get("/coverage/rm_jane/resources/REL-00188", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        body = resp.json()
        assert body["allowed"] is False
        assert "reason" in body


class TestResolveEndpoint:
    def test_resolve_whitman(self):
        resp = client.post(
            "/entities/resolve",
            json={"reference": "Whitman Family Office", "type": "relationship",
                  "principal_id": "rm_jane"},
            headers=DEFAULT_AUTH,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["resolved"] is True
        assert body["id"] == "REL-00042"
        assert body["canonical_name"] == "Whitman Family Office"

    def test_resolve_not_found(self):
        resp = client.post(
            "/entities/resolve",
            json={"reference": "Mystery Client", "type": "relationship",
                  "principal_id": "rm_jane"},
            headers=DEFAULT_AUTH,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["resolved"] is False
        assert body["candidates"] == []


class TestHealthEndpoint:
    def test_health(self):
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json()["status"] == "ok"
