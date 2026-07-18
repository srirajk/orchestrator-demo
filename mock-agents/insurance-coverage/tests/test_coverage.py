"""
Unit + integration tests for the insurance-coverage service.

Run from mock-agents/insurance-coverage/:
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
    def test_uw_sam_sees_his_two_policies(self):
        results = discover("uw_sam")
        ids = {r["id"] for r in results}
        assert ids == {"POL-77001", "POL-77002"}

    def test_uw_dana_sees_zenith(self):
        results = discover("uw_dana")
        ids = {r["id"] for r in results}
        assert ids == {"POL-88003"}

    def test_unknown_principal_gets_empty_list(self):
        assert discover("nobody") == []

    def test_admin_sees_all(self):
        results = discover("admin")
        ids = {r["id"] for r in results}
        assert {"POL-77001", "POL-77002", "POL-88003"}.issubset(ids)


class TestCheck:
    def test_uw_sam_allowed_continental(self):
        result = check("uw_sam", "POL-77001")
        assert result["allowed"] is True
        assert result["reason"] == "in-book"

    def test_uw_sam_denied_zenith(self):
        result = check("uw_sam", "POL-88003")
        assert result["allowed"] is False
        assert result["reason"] == "not-covered"

    def test_unknown_resource_denied(self):
        result = check("uw_sam", "POL-99999")
        assert result["allowed"] is False
        assert result["reason"] == "unknown-resource"

    def test_unknown_principal_denied(self):
        result = check("nobody", "POL-77001")
        assert result["allowed"] is False


class TestResolve:
    def test_resolve_continental_by_alias(self):
        result = resolve("continental freight", "policy", "uw_sam")
        assert result["resolved"] is True
        assert result["id"] == "POL-77001"
        assert result["canonical_name"] == "Continental Freight Liability"

    def test_resolve_aurora_by_alias(self):
        result = resolve("aurora", "policy", "uw_sam")
        assert result["resolved"] is True
        assert result["id"] == "POL-77002"

    def test_resolve_exact_id(self):
        result = resolve("POL-77001", "policy", "uw_sam")
        assert result["resolved"] is True
        assert result["id"] == "POL-77001"

    def test_resolve_not_found_returns_empty_candidates(self):
        result = resolve("nonexistent policy", "policy", "uw_sam")
        assert result["resolved"] is False
        assert result["id"] is None
        assert result["candidates"] == []

    def test_zenith_resolves_for_anyone_but_check_denies_uw_sam(self):
        # RESOLVE is principal-agnostic: it finds Zenith regardless of the caller's book.
        result = resolve("zenith logistics", "policy", "uw_sam")
        assert result["resolved"] is True
        assert result["id"] == "POL-88003"
        # CHECK is the gate: uw_sam is NOT entitled to Zenith (not in his book).
        assert check("uw_sam", "POL-88003")["allowed"] is False


# ── HTTP integration tests: FastAPI layer ─────────────────────────────────────


class TestDiscoverEndpoint:
    def test_discover_uw_sam(self):
        resp = client.get("/coverage/uw_sam", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        data = resp.json()
        ids = {r["id"] for r in data}
        assert ids == {"POL-77001", "POL-77002"}
        for r in data:
            assert "id" in r
            assert "label" in r
            assert "sub_domain" in r

    def test_discover_unknown_principal_returns_empty_list(self):
        resp = client.get("/coverage/nobody", headers=auth_headers("default", sub="nobody"))
        assert resp.status_code == 200
        assert resp.json() == []


class TestCheckEndpoint:
    def test_check_allowed(self):
        resp = client.get("/coverage/uw_sam/resources/POL-77001", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        body = resp.json()
        assert body["allowed"] is True

    def test_check_denied(self):
        resp = client.get("/coverage/uw_sam/resources/POL-88003", headers=DEFAULT_AUTH)
        assert resp.status_code == 200
        body = resp.json()
        assert body["allowed"] is False
        assert body["reason"] == "not-covered"


class TestResolveEndpoint:
    def test_resolve_continental(self):
        resp = client.post(
            "/entities/resolve",
            json={"reference": "Continental Freight Liability", "type": "policy",
                  "principal_id": "uw_sam"},
            headers=DEFAULT_AUTH,
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["resolved"] is True
        assert body["id"] == "POL-77001"
        assert body["canonical_name"] == "Continental Freight Liability"

    def test_resolve_not_found(self):
        resp = client.post(
            "/entities/resolve",
            json={"reference": "Mystery Policy", "type": "policy",
                  "principal_id": "uw_sam"},
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
