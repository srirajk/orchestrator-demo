"""
Wealth Market Research service tests.
Run from project root:
  PYTHONPATH=mock-agents/wealth-market-research pytest mock-agents/wealth-market-research/tests/

Or from the service directory:
  cd mock-agents/wealth-market-research && pytest tests/
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import logging
import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch

# Silence OTel connection errors in test runs
logging.getLogger("opentelemetry").setLevel(logging.CRITICAL)
logging.getLogger("openinference").setLevel(logging.CRITICAL)

from main import app

client = TestClient(app)


class TestHealth:
    def test_health_ok(self):
        r = client.get("/health")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "ok"
        assert body["service"] == "wealth-market-research"
        assert body["agent_type"] == "knowledge"
        assert body["audience"] == "segment"

    def test_health_domain(self):
        r = client.get("/health")
        assert r.json()["domain"] == "wealth-management"


class TestOpenApi:
    def test_openapi_json_served(self):
        r = client.get("/openapi.json")
        assert r.status_code == 200
        data = r.json()
        assert "paths" in data
        assert "/market-research" in data["paths"]

    def test_openapi_has_get_operation(self):
        r = client.get("/openapi.json")
        spec = r.json()
        ops = spec["paths"]["/market-research"]
        assert "get" in ops

    def test_openapi_operation_id(self):
        r = client.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/market-research"]["get"]
        assert op.get("operationId") == "get_market_research"

    def test_openapi_topic_param_optional(self):
        r = client.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/market-research"]["get"]
        params = {p["name"]: p for p in op.get("parameters", [])}
        # topic must be present but NOT required (knowledge agent, no entity needed)
        assert "topic" in params
        assert not params["topic"].get("required", False)


class TestMarketResearchBroadOverview:
    def test_no_topic_returns_broad_overview(self):
        r = client.get("/market-research")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "broad_overview"
        assert "sections" in body
        assert len(body["sections"]) > 0
        assert "asset_allocation_model" in body

    def test_broad_overview_has_agent_id(self):
        r = client.get("/market-research")
        assert r.json()["agent_id"] == "acme.wealth.market_research"

    def test_broad_overview_has_available_topics(self):
        r = client.get("/market-research")
        body = r.json()
        assert "available_topics" in body
        topics = body["available_topics"]
        assert "equities" in topics
        assert "fixed_income" in topics
        assert "alternatives" in topics
        assert "macro" in topics


class TestMarketResearchByTopic:
    def test_equities_topic(self):
        r = client.get("/market-research?topic=equities")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "equities"
        assert "sector_ratings" in body
        assert len(body["sections"]) > 0

    def test_fixed_income_topic(self):
        r = client.get("/market-research?topic=fixed_income")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "fixed_income"
        assert "rate_forecasts" in body

    def test_alternatives_topic(self):
        r = client.get("/market-research?topic=alternatives")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "alternatives"
        assert "target_allocations" in body

    def test_macro_topic(self):
        r = client.get("/market-research?topic=macro")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "macro"
        assert "gdp_forecasts" in body

    def test_topic_case_insensitive(self):
        r = client.get("/market-research?topic=EQUITIES")
        assert r.status_code == 200
        assert r.json()["topic"] == "equities"

    def test_unknown_topic_returns_400(self):
        r = client.get("/market-research?topic=crypto")
        assert r.status_code == 400
        body = r.json()
        assert "error" in body
        assert "crypto" in body["error"]
        assert "agent_id" in body
        assert "trace_id" in body

    def test_each_topic_has_sections(self):
        for topic in ("equities", "fixed_income", "alternatives", "macro"):
            r = client.get(f"/market-research?topic={topic}")
            assert r.status_code == 200, f"Failed for topic={topic}"
            body = r.json()
            assert len(body["sections"]) >= 3, f"Too few sections for topic={topic}"

    def test_each_section_has_heading_and_body(self):
        r = client.get("/market-research?topic=equities")
        for section in r.json()["sections"]:
            assert "heading" in section
            assert "body" in section
            assert len(section["body"]) > 50


class TestFaultKnobs:
    def test_fail_knob_returns_503(self):
        r = client.get("/market-research?_fail=true")
        assert r.status_code == 503
        body = r.json()
        assert "fault knob" in body["error"]
        assert body["agent_id"] == "acme.wealth.market_research"

    def test_delay_knob(self):
        import time
        start = time.time()
        r = client.get("/market-research?_delay_ms=100")
        elapsed = (time.time() - start) * 1000
        assert r.status_code == 200
        assert elapsed >= 100, f"Expected >=100ms delay, got {elapsed:.0f}ms"

    def test_fail_takes_precedence_over_topic(self):
        r = client.get("/market-research?topic=equities&_fail=true")
        assert r.status_code == 503


class TestJwtBypass:
    def test_health_no_auth_required(self):
        """Health endpoint must be accessible without a token."""
        r = client.get("/health", headers={})
        assert r.status_code == 200

    def test_openapi_no_auth_required(self):
        """OpenAPI spec must be accessible without a token (gateway introspection)."""
        r = client.get("/openapi.json", headers={})
        assert r.status_code == 200

    def test_invalid_bearer_returns_401(self):
        """A malformed Bearer token must return 401."""
        r = client.get("/market-research", headers={"Authorization": "Bearer notavalidjwt"})
        assert r.status_code == 401
