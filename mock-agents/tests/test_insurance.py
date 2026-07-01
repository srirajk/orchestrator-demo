"""
Insurance HTTP service tests — guardrail + eval standard.

Tests: auth enforcement, entity-ID format validation, data contracts,
fault knobs, and OpenAPI schema integrity.

Run standalone:
  PYTHONPATH=mock-agents python -m pytest mock-agents/tests/test_insurance.py -v

Run as part of the full suite:
  PYTHONPATH=mock-agents python -m pytest mock-agents/tests/ -q \\
      --ignore=mock-agents/tests/test_concurrent_multiturn.py

Namespace isolation
-------------------
Both wealth/ and insurance/ expose a bare ``shared/`` package and a bare ``main``
module.  When pytest imports all test files in one process, whichever is imported
first wins the sys.modules cache.

This file saves the current sys.modules snapshot for the conflicting prefixes,
clears them, imports insurance's app, then restores the saved entries so that
test_wealth.py (collected later, alphabetically) still gets the correct module.
Insurance route handlers capture their data-references at import time (closures /
direct object references), so the app continues to work correctly even after
sys.modules is restored to the wealth view.
"""
import sys
import os
import base64
import json
import time

# ── Path / module-isolation setup ────────────────────────────────────────────

_INSURANCE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../insurance")

# Prefixes that conflict between wealth and insurance.
_CONFLICT_PREFIXES = ("main", "shared", "policy_details", "claim_status")


def _stash_conflicting() -> dict:
    """Remove and return all sys.modules entries under the conflict prefixes."""
    stash = {}
    for key in list(sys.modules.keys()):
        if any(key == p or key.startswith(p + ".") for p in _CONFLICT_PREFIXES):
            stash[key] = sys.modules.pop(key)
    return stash


def _restore_stash(stash: dict) -> None:
    """Clear any newly loaded entries under the prefixes and put back the stash."""
    for key in list(sys.modules.keys()):
        if any(key == p or key.startswith(p + ".") for p in _CONFLICT_PREFIXES):
            del sys.modules[key]
    sys.modules.update(stash)


# Save wealth (or empty) state, load insurance app, then restore.
_saved = _stash_conflicting()

sys.path.insert(0, _INSURANCE_PATH)

from main import app as insurance_app  # noqa: E402

_restore_stash(_saved)
del _saved

# ── Test client ───────────────────────────────────────────────────────────────

import pytest  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

insurance = TestClient(insurance_app, raise_server_exceptions=False)

# Seed IDs known to canned_data
POL = "POL-77001"
CLM = "CLM-5501"


# ─────────────────────────────────────────────────────────────────────────────
# Authentication enforcement
# ─────────────────────────────────────────────────────────────────────────────

class TestInsuranceAuth:
    """JWT middleware mirrors the wealth-http pattern: fail-open (no token = allowed),
    reject malformed tokens or wrong algorithms."""

    def test_no_auth_header_is_allowed(self):
        """No token — gateway is the auth boundary; startup introspection has no token."""
        r = insurance.get(f"/policy-details?policy_id={POL}")
        assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"

    def test_bearer_unused_is_allowed(self):
        """LibreChat's placeholder API key must not be rejected."""
        r = insurance.get(
            f"/policy-details?policy_id={POL}",
            headers={"Authorization": "Bearer unused"},
        )
        assert r.status_code == 200

    def test_empty_bearer_is_allowed(self):
        """Empty bearer is treated like no token."""
        r = insurance.get(
            f"/policy-details?policy_id={POL}",
            headers={"Authorization": "Bearer "},
        )
        assert r.status_code == 200

    def test_malformed_token_too_many_dots_is_rejected(self):
        """Token with wrong number of dots is rejected before JWKS fetch."""
        r = insurance.get(
            f"/policy-details?policy_id={POL}",
            headers={"Authorization": "Bearer not.a.real.jwt.token"},
        )
        assert r.status_code == 401
        assert "error" in r.json() or "detail" in r.json()

    def test_non_rs_algorithm_rejected(self):
        """Algorithm confusion: HS256 tokens must be rejected."""
        header = base64.urlsafe_b64encode(
            json.dumps({"alg": "HS256", "typ": "JWT"}).encode()
        ).rstrip(b"=").decode()
        payload = base64.urlsafe_b64encode(b'{"sub":"uw_sam"}').rstrip(b"=").decode()
        hs256_token = f"{header}.{payload}.fakesignature"
        r = insurance.get(
            f"/policy-details?policy_id={POL}",
            headers={"Authorization": f"Bearer {hs256_token}"},
        )
        assert r.status_code == 401

    def test_health_requires_no_auth(self):
        """/health must be accessible without auth for Docker healthchecks."""
        r = insurance.get("/health")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"

    def test_openapi_requires_no_auth(self):
        """/openapi.json must be accessible without auth for registry introspection."""
        r = insurance.get("/openapi.json")
        assert r.status_code == 200
        assert "paths" in r.json()


# ─────────────────────────────────────────────────────────────────────────────
# Data contracts — critical for answer grounding
# ─────────────────────────────────────────────────────────────────────────────

class TestInsuranceDataContracts:
    """Every field the gateway's synthesis prompt references must be present,
    typed correctly, and returned with the right status code."""

    # ── policy_details ────────────────────────────────────────────────────────

    def test_policy_details_structure(self):
        r = insurance.get(f"/policy-details?policy_id={POL}")
        assert r.status_code == 200
        body = r.json()
        required = {"policy_id", "line_of_business", "premium", "coverage_limit", "status"}
        missing = required - set(body.keys())
        assert not missing, f"policy_details missing fields: {missing}"
        assert body["policy_id"] == POL
        assert isinstance(body["premium"], (int, float))
        assert isinstance(body["coverage_limit"], (int, float))

    def test_unknown_policy_returns_404(self):
        r = insurance.get("/policy-details?policy_id=POL-99999")
        assert r.status_code == 404
        body = r.json()
        assert "error" in body

    def test_invalid_policy_id_format_returns_422(self):
        """Format check fires BEFORE canned-data lookup — wrong prefix → 422, not 404."""
        for bad_id in ("BADID", "POL-", "pol-77001", "CLM-5501", "POLICY-001"):
            r = insurance.get(f"/policy-details?policy_id={bad_id}")
            assert r.status_code == 422, (
                f"policy_id={bad_id!r}: expected 422, got {r.status_code} — {r.text}"
            )
            body = r.json()
            assert "error" in body
            assert "invalid format" in body["error"].lower(), (
                f"422 body should mention 'invalid format': {body['error']}"
            )

    # ── claim_status ──────────────────────────────────────────────────────────

    def test_claim_status_structure(self):
        r = insurance.get(f"/claim-status?claim_id={CLM}")
        assert r.status_code == 200
        body = r.json()
        required = {"claim_id", "policy_id", "amount", "status", "incident_date", "adjuster"}
        missing = required - set(body.keys())
        assert not missing, f"claim_status missing fields: {missing}"
        assert body["claim_id"] == CLM
        assert isinstance(body["amount"], (int, float))

    def test_unknown_claim_returns_404(self):
        r = insurance.get("/claim-status?claim_id=CLM-99999")
        assert r.status_code == 404
        body = r.json()
        assert "error" in body

    def test_invalid_claim_id_format_returns_422(self):
        for bad_id in ("BADCLM", "CLM-", "clm-5501", "POL-77001", "CLAIM-001"):
            r = insurance.get(f"/claim-status?claim_id={bad_id}")
            assert r.status_code == 422, (
                f"claim_id={bad_id!r}: expected 422, got {r.status_code} — {r.text}"
            )
            body = r.json()
            assert "error" in body
            assert "invalid format" in body["error"].lower()

    def test_invalid_policy_id_in_claim_lookup_returns_422(self):
        """When policy_id (not claim_id) is malformed, claim-status also returns 422."""
        r = insurance.get("/claim-status?policy_id=NOTAPOL")
        assert r.status_code == 422
        body = r.json()
        assert "error" in body
        assert "invalid format" in body["error"].lower()

    def test_claim_status_neither_id_returns_400(self):
        """Neither claim_id nor policy_id provided → 400, not 404 or 422."""
        r = insurance.get("/claim-status")
        assert r.status_code == 400
        body = r.json()
        assert "error" in body

    def test_claim_status_by_policy_id(self):
        """Using policy_id returns a list of claims for that policy."""
        r = insurance.get(f"/claim-status?policy_id={POL}")
        assert r.status_code == 200
        body = r.json()
        assert body["policy_id"] == POL
        assert "claim_count" in body
        assert isinstance(body["claims"], list)
        assert body["claim_count"] == len(body["claims"])


# ─────────────────────────────────────────────────────────────────────────────
# Fault knobs — required for resilience demo
# ─────────────────────────────────────────────────────────────────────────────

class TestInsuranceFaultKnobs:
    """?_fail=true → 503; ?_delay_ms=300 → elapsed ≥ ~250 ms."""

    @pytest.mark.parametrize("path,params", [
        ("/policy-details", f"policy_id={POL}&_fail=true"),
        ("/claim-status",   f"claim_id={CLM}&_fail=true"),
    ])
    def test_fail_knob_returns_503(self, path, params):
        r = insurance.get(f"{path}?{params}")
        assert r.status_code == 503
        body = r.json()
        assert "fault knob" in body.get("error", "").lower(), (
            f"503 body should mention 'fault knob': {body}"
        )

    @pytest.mark.parametrize("path,params", [
        ("/policy-details", f"policy_id={POL}&_delay_ms=300"),
        ("/claim-status",   f"claim_id={CLM}&_delay_ms=300"),
    ])
    def test_delay_knob_adds_latency(self, path, params):
        start = time.monotonic()
        r = insurance.get(f"{path}?{params}")
        elapsed_ms = (time.monotonic() - start) * 1000
        assert r.status_code == 200
        assert elapsed_ms >= 250, (
            f"{path}: expected >=250 ms delay, got {elapsed_ms:.0f} ms"
        )


# ─────────────────────────────────────────────────────────────────────────────
# OpenAPI schema integrity — the gateway registry introspects this
# ─────────────────────────────────────────────────────────────────────────────

class TestInsuranceOpenApiSchema:
    """The gateway registry introspects /openapi.json to derive agent I/O schemas.
    The two required operationIds must be present and the paths must be discoverable."""

    def test_openapi_json_served(self):
        r = insurance.get("/openapi.json")
        assert r.status_code == 200
        spec = r.json()
        assert "paths" in spec

    def test_both_paths_present(self):
        r = insurance.get("/openapi.json")
        paths = r.json()["paths"]
        assert "/policy-details" in paths, "Missing /policy-details path"
        assert "/claim-status" in paths, "Missing /claim-status path"

    def test_operation_id_get_policy_details(self):
        r = insurance.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/policy-details"]["get"]
        assert op.get("operationId") == "get_policy_details", (
            f"Expected operationId 'get_policy_details', got {op.get('operationId')!r}"
        )

    def test_operation_id_get_claim_status(self):
        r = insurance.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/claim-status"]["get"]
        assert op.get("operationId") == "get_claim_status", (
            f"Expected operationId 'get_claim_status', got {op.get('operationId')!r}"
        )

    def test_policy_id_param_on_policy_details(self):
        r = insurance.get("/openapi.json")
        params = r.json()["paths"]["/policy-details"]["get"].get("parameters", [])
        names = [p["name"] for p in params]
        assert "policy_id" in names, f"policy_details missing policy_id param; got {names}"

    def test_claim_id_param_on_claim_status(self):
        r = insurance.get("/openapi.json")
        params = r.json()["paths"]["/claim-status"]["get"].get("parameters", [])
        names = [p["name"] for p in params]
        assert "claim_id" in names, f"claim_status missing claim_id param; got {names}"
