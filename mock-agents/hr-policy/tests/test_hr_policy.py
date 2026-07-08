"""
HR Policy Q&A service tests.
Run from project root:
  PYTHONPATH=mock-agents/hr-policy pytest mock-agents/hr-policy/tests/

Or from the service directory:
  cd mock-agents/hr-policy && pytest tests/
"""
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import logging
import pytest
from fastapi.testclient import TestClient

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
        assert body["service"] == "hr-policy"
        assert body["agent_type"] == "knowledge"
        assert body["audience"] == "enterprise"

    def test_health_domain(self):
        r = client.get("/health")
        assert r.json()["domain"] == "hr"


class TestOpenApi:
    def test_openapi_json_served(self):
        r = client.get("/openapi.json")
        assert r.status_code == 200
        data = r.json()
        assert "paths" in data
        assert "/policy-qa" in data["paths"]

    def test_openapi_has_get_operation(self):
        r = client.get("/openapi.json")
        spec = r.json()
        assert "get" in spec["paths"]["/policy-qa"]

    def test_openapi_operation_id(self):
        r = client.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/policy-qa"]["get"]
        assert op.get("operationId") == "get_policy_qa"

    def test_openapi_topic_param_optional(self):
        r = client.get("/openapi.json")
        spec = r.json()
        op = spec["paths"]["/policy-qa"]["get"]
        params = {p["name"]: p for p in op.get("parameters", [])}
        assert "topic" in params
        assert not params["topic"].get("required", False)


class TestPolicyIndex:
    def test_no_topic_returns_index(self):
        r = client.get("/policy-qa")
        assert r.status_code == 200
        body = r.json()
        assert body["response_type"] == "topics_index"
        assert "available_topics" in body
        topics = body["available_topics"]
        assert "parental_leave" in topics
        assert "pto" in topics
        assert "benefits" in topics
        assert "conduct" in topics
        assert "performance" in topics
        assert "learning" in topics

    def test_index_has_agent_id(self):
        r = client.get("/policy-qa")
        assert r.json()["agent_id"] == "meridian.hr.policy_qa"


class TestPoliciesByTopic:
    def test_parental_leave(self):
        r = client.get("/policy-qa?topic=parental_leave")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "parental_leave"
        assert body["policy_id"] == "HR-POL-001"
        assert "sections" in body
        assert "key_facts" in body
        assert body["key_facts"]["maternity_leave_weeks_paid"] == 20
        assert body["key_facts"]["paternity_leave_weeks_paid"] == 10

    def test_pto(self):
        r = client.get("/policy-qa?topic=pto")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "pto"
        assert body["policy_id"] == "HR-POL-002"
        assert body["key_facts"]["pto_year1_3_days"] == 20

    def test_benefits(self):
        r = client.get("/policy-qa?topic=benefits")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "benefits"
        assert body["policy_id"] == "HR-POL-003"
        assert body["key_facts"]["k401_employer_match_pct"] == 6

    def test_conduct(self):
        r = client.get("/policy-qa?topic=conduct")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "conduct"
        assert body["policy_id"] == "HR-POL-004"
        assert "ethics_hotline" in body["key_facts"]

    def test_performance(self):
        r = client.get("/policy-qa?topic=performance")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "performance"
        assert body["policy_id"] == "HR-POL-005"

    def test_learning(self):
        r = client.get("/policy-qa?topic=learning")
        assert r.status_code == 200
        body = r.json()
        assert body["topic"] == "learning"
        assert body["policy_id"] == "HR-POL-006"
        assert body["key_facts"]["tuition_reimbursement_annual_usd"] == 10000

    def test_topic_case_insensitive(self):
        r = client.get("/policy-qa?topic=PARENTAL_LEAVE")
        assert r.status_code == 200
        assert r.json()["topic"] == "parental_leave"

    def test_unknown_topic_returns_400(self):
        r = client.get("/policy-qa?topic=vacation")
        assert r.status_code == 400
        body = r.json()
        assert "error" in body
        assert "vacation" in body["error"]
        assert "agent_id" in body
        assert "trace_id" in body

    def test_each_policy_has_multiple_sections(self):
        topics = ["parental_leave", "pto", "benefits", "conduct", "performance", "learning"]
        for topic in topics:
            r = client.get(f"/policy-qa?topic={topic}")
            assert r.status_code == 200, f"Failed for topic={topic}"
            body = r.json()
            assert len(body["sections"]) >= 3, f"Too few sections for topic={topic}"

    def test_sections_have_heading_and_body(self):
        r = client.get("/policy-qa?topic=benefits")
        for section in r.json()["sections"]:
            assert "heading" in section
            assert "body" in section
            assert len(section["body"]) > 40

    def test_response_has_available_topics(self):
        r = client.get("/policy-qa?topic=pto")
        assert "available_topics" in r.json()


class TestFaultKnobs:
    def test_fail_knob_returns_503(self):
        r = client.get("/policy-qa?_fail=true")
        assert r.status_code == 503
        body = r.json()
        assert "fault knob" in body["error"]
        assert body["agent_id"] == "meridian.hr.policy_qa"

    def test_delay_knob(self):
        import time
        start = time.time()
        r = client.get("/policy-qa?_delay_ms=100")
        elapsed = (time.time() - start) * 1000
        assert r.status_code == 200
        assert elapsed >= 100, f"Expected >=100ms delay, got {elapsed:.0f}ms"

    def test_fail_knob_with_topic(self):
        r = client.get("/policy-qa?topic=pto&_fail=true")
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
        r = client.get("/policy-qa", headers={"Authorization": "Bearer notavalidjwt"})
        assert r.status_code == 401

    def test_enterprise_audience_no_coverage(self):
        """
        Confirms this is a knowledge agent: the health response declares audience=enterprise.
        In the live stack the gateway skips the coverage check for enterprise agents.
        """
        r = client.get("/health")
        assert r.json()["audience"] == "enterprise"
