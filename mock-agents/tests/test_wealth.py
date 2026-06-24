"""
Wealth HTTP service tests — Phase 2 acceptance.
Run from mock-agents/wealth/: pytest ../../tests/test_wealth.py
Or from project root:  PYTHONPATH=mock-agents/wealth pytest mock-agents/tests/test_wealth.py
"""
import sys
import os

# Make the wealth package importable from this test file
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../wealth"))

import pytest
from fastapi.testclient import TestClient
from main import app

client = TestClient(app)

REL = "REL-00042"


class TestHealth:
    def test_health_ok(self):
        r = client.get("/health")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"


class TestOpenApi:
    def test_openapi_json_served(self):
        r = client.get("/openapi.json")
        assert r.status_code == 200
        data = r.json()
        assert "paths" in data
        # All 4 Wealth endpoints must be present
        paths = data["paths"]
        assert "/holdings" in paths
        assert "/performance" in paths
        assert "/goal-planning" in paths
        assert "/risk-profile" in paths

    def test_openapi_has_required_params(self):
        r = client.get("/openapi.json")
        spec = r.json()
        holdings_get = spec["paths"]["/holdings"]["get"]
        param_names = [p["name"] for p in holdings_get.get("parameters", [])]
        assert "relationship_id" in param_names


class TestHoldings:
    def test_known_relationship(self):
        r = client.get(f"/holdings?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert body["relationship_id"] == REL
        assert len(body["positions"]) > 0
        assert "allocation_by_class" in body
        assert "total_value" in body

    def test_unknown_relationship(self):
        r = client.get("/holdings?relationship_id=REL-99999")
        assert r.status_code == 404

    def test_fault_knob_fail(self):
        r = client.get(f"/holdings?relationship_id={REL}&_fail=true")
        assert r.status_code == 503
        assert "fault knob" in r.json()["error"]

    def test_fault_knob_delay(self):
        import time
        start = time.time()
        r = client.get(f"/holdings?relationship_id={REL}&_delay_ms=100")
        elapsed = (time.time() - start) * 1000
        assert r.status_code == 200
        assert elapsed >= 100, f"Expected >=100ms delay, got {elapsed:.0f}ms"


class TestPerformance:
    def test_known_relationship(self):
        r = client.get(f"/performance?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert body["relationship_id"] == REL
        assert "total_return_pct" in body
        assert "pnl" in body

    def test_custom_period(self):
        r = client.get(f"/performance?relationship_id={REL}&period=1Y")
        assert r.status_code == 200
        assert r.json()["period"] == "1Y"

    def test_fault_knob_fail(self):
        r = client.get(f"/performance?relationship_id={REL}&_fail=true")
        assert r.status_code == 503


class TestGoalPlanning:
    def test_known_relationship(self):
        r = client.get(f"/goal-planning?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert "goals" in body
        assert isinstance(body["goals"], list)
        assert "overall_on_track" in body

    def test_fault_knob_fail(self):
        r = client.get(f"/goal-planning?relationship_id={REL}&_fail=true")
        assert r.status_code == 503


class TestRiskProfile:
    def test_known_relationship(self):
        r = client.get(f"/risk-profile?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert "risk_tolerance" in body
        assert "risk_score" in body
        assert "concentration_flags" in body

    def test_concentration_flag_schema(self):
        r = client.get(f"/risk-profile?relationship_id={REL}")
        flags = r.json()["concentration_flags"]
        if flags:
            flag = flags[0]
            assert "security" in flag
            assert "flagged" in flag

    def test_fault_knob_fail(self):
        r = client.get(f"/risk-profile?relationship_id={REL}&_fail=true")
        assert r.status_code == 503
