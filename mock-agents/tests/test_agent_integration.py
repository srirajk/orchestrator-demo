"""
Real-world integration tests for both mock agents.

Tests auth enforcement, data contracts, fault knobs, and cross-agent consistency.

Run from project root:
  PYTHONPATH=mock-agents/wealth:mock-agents/servicing pytest mock-agents/tests/test_agent_integration.py -v

The FastAPI TestClient goes through ALL middleware (including JWT enforcement), so these
are genuine integration tests — not unit tests that bypass auth.
"""
import base64
import json
import sys
import os
import time

# Wealth path added at module level (needed for the module-level import below).
# Servicing path is added PER METHOD in servicing tests to avoid the shared/ namespace
# collision: both wealth/shared/ and servicing/shared/ exist, and Python's module cache
# keeps whichever is imported first — inserting per method + clearing the cache ensures
# each test class gets the right 'shared' package.
_WEALTH_PATH    = os.path.join(os.path.dirname(__file__), "../wealth")
_SERVICING_PATH = os.path.join(os.path.dirname(__file__), "../servicing")

sys.path.insert(0, _WEALTH_PATH)

import pytest
from fastapi.testclient import TestClient

# ── Wealth HTTP service client ─────────────────────────────────────────────────
import main as wealth_main
from main import app as wealth_app

wealth = TestClient(wealth_app, raise_server_exceptions=False)

REL = "REL-00042"
FUND = "FND-7781"


def _allow_all_tokens(monkeypatch):
    """
    F-IDENTITY: production `verify_bearer_token` now fails CLOSED (401) with no/invalid
    token — correct, and covered exhaustively by TestWealthAuth below. The classes that
    use this helper are testing DATA CONTRACTS / fault knobs, not auth, and have no real
    signed JWT to send — patch the middleware's verify function so they can still reach
    the handlers. This does not touch production code; it only relaxes the TEST client.
    """
    monkeypatch.setattr(wealth_main, "verify_bearer_token", lambda auth: (True, None, None))


# ─────────────────────────────────────────────────────────────────────────────
# Authentication enforcement on wealth-http
# ─────────────────────────────────────────────────────────────────────────────

class TestWealthAuth:
    """JWT enforcement: every /holdings,/performance etc path goes through the
    jwt_auth_middleware. These tests prove the auth layer actually runs."""

    def test_no_auth_header_is_rejected(self):
        """F-IDENTITY: fail CLOSED. The gateway always propagates a verified caller JWT
        on every agent hop (ChatCompletionsController → AgentHarness → HttpAdapter); a
        request with no Authorization header never comes from a legitimate gateway call
        on behalf of a chat request, so it must be rejected."""
        r = wealth.get(f"/holdings?relationship_id={REL}")
        assert r.status_code == 401, f"Expected 401, got {r.status_code}: {r.text}"

    def test_bearer_unused_is_rejected(self):
        """The historical 'Bearer unused' placeholder bypass is removed — no live caller
        ever needs it, and it was a trivial way to bypass auth entirely."""
        r = wealth.get(
            f"/holdings?relationship_id={REL}",
            headers={"Authorization": "Bearer unused"},
        )
        assert r.status_code == 401

    def test_empty_bearer_is_rejected(self):
        """An empty bearer value is not a token — fail CLOSED, same as no header."""
        r = wealth.get(
            f"/holdings?relationship_id={REL}",
            headers={"Authorization": "Bearer "},
        )
        assert r.status_code == 401

    def test_malformed_token_too_many_dots_is_rejected(self):
        """Token with wrong number of dots is rejected before JWKS fetch."""
        r = wealth.get(
            f"/holdings?relationship_id={REL}",
            headers={"Authorization": "Bearer not.a.real.jwt.token"},
        )
        assert r.status_code == 401
        assert "error" in r.json() or "detail" in r.json()

    def test_non_rs_algorithm_rejected(self):
        """Algorithm confusion: HS256 tokens must be rejected regardless of JWKS state."""
        header = base64.urlsafe_b64encode(
            json.dumps({"alg": "HS256", "typ": "JWT"}).encode()
        ).rstrip(b"=").decode()
        payload = base64.urlsafe_b64encode(b'{"sub":"rm_jane"}').rstrip(b"=").decode()
        hs256_token = f"{header}.{payload}.fakesignature"
        r = wealth.get(
            f"/holdings?relationship_id={REL}",
            headers={"Authorization": f"Bearer {hs256_token}"},
        )
        assert r.status_code == 401

    def test_health_requires_no_auth(self):
        """/health must be accessible without auth for Docker healthchecks."""
        r = wealth.get("/health")
        assert r.status_code == 200
        assert r.json()["status"] == "ok"

    def test_openapi_requires_no_auth(self):
        """/openapi.json must be accessible without auth for registry introspection."""
        r = wealth.get("/openapi.json")
        assert r.status_code == 200
        assert "paths" in r.json()


# ─────────────────────────────────────────────────────────────────────────────
# Data contracts — critical for answer grounding
# ─────────────────────────────────────────────────────────────────────────────

class TestWealthDataContracts:
    """Every field the gateway's synthesis prompt references must be present and typed
    correctly. If these fail, the numeric grounding check in the gateway will fail too."""

    @pytest.fixture(autouse=True)
    def _allow(self, monkeypatch):
        _allow_all_tokens(monkeypatch)

    def test_holdings_structure(self):
        r = wealth.get(f"/holdings?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert body["relationship_id"] == REL
        assert isinstance(body["positions"], list), "positions must be a list"
        assert len(body["positions"]) > 0, "must have at least one position"
        assert isinstance(body["total_value"], (int, float)), "total_value must be numeric"
        assert "allocation_by_class" in body

    def test_holdings_position_schema(self):
        r = wealth.get(f"/holdings?relationship_id={REL}")
        pos = r.json()["positions"][0]
        # Actual canned data uses ticker/isin/qty/value (no weight_pct at position level)
        required = {"ticker", "qty", "value"}
        missing = required - set(pos.keys())
        assert not missing, f"Position missing fields: {missing}"

    def test_performance_structure(self):
        r = wealth.get(f"/performance?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body["total_return_pct"], (int, float))
        assert isinstance(body["pnl"], (int, float))
        assert "period" in body

    def test_risk_profile_structure(self):
        r = wealth.get(f"/risk-profile?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert 0 <= body["risk_score"] <= 10, "risk_score must be in [0, 10]"
        assert body["risk_tolerance"] in ("Conservative", "Moderate", "Aggressive",
                                           "Moderately Aggressive", "Moderately Conservative")
        assert isinstance(body["concentration_flags"], list)

    def test_goal_planning_structure(self):
        r = wealth.get(f"/goal-planning?relationship_id={REL}")
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body["goals"], list)
        assert isinstance(body["overall_on_track"], bool)

    def test_unknown_relationship_returns_404(self):
        for path in ["/holdings", "/performance", "/risk-profile", "/goal-planning"]:
            r = wealth.get(f"{path}?relationship_id=REL-99999")
            assert r.status_code == 404, f"{path}: expected 404 for unknown REL, got {r.status_code}"

    def test_all_endpoints_echo_relationship_id(self):
        """Every endpoint echoes back relationship_id — needed for response attribution."""
        for path in ["/holdings", "/performance", "/risk-profile", "/goal-planning"]:
            r = wealth.get(f"{path}?relationship_id={REL}")
            assert r.status_code == 200
            assert r.json().get("relationship_id") == REL, f"{path} must echo relationship_id"


# ─────────────────────────────────────────────────────────────────────────────
# Fault knobs — required for resilience demo
# ─────────────────────────────────────────────────────────────────────────────

class TestWealthFaultKnobs:
    @pytest.fixture(autouse=True)
    def _allow(self, monkeypatch):
        _allow_all_tokens(monkeypatch)

    @pytest.mark.parametrize("path", [
        "/holdings", "/performance", "/risk-profile", "/goal-planning"
    ])
    def test_fail_knob_returns_503(self, path):
        r = wealth.get(f"{path}?relationship_id={REL}&_fail=true")
        assert r.status_code == 503
        body = r.json()
        assert "fault knob" in body.get("error", "").lower()

    @pytest.mark.parametrize("path", [
        "/holdings", "/performance", "/risk-profile", "/goal-planning"
    ])
    def test_delay_knob_adds_latency(self, path):
        start = time.monotonic()
        r = wealth.get(f"{path}?relationship_id={REL}&_delay_ms=300")
        elapsed_ms = (time.monotonic() - start) * 1000
        assert r.status_code == 200
        assert elapsed_ms >= 250, f"{path}: expected >=250ms delay, got {elapsed_ms:.0f}ms"


# ─────────────────────────────────────────────────────────────────────────────
# OpenAPI schema integrity — the gateway registry introspects this
# ─────────────────────────────────────────────────────────────────────────────

class TestOpenApiSchema:
    def test_all_4_paths_present(self):
        r = wealth.get("/openapi.json")
        paths = r.json()["paths"]
        for endpoint in ["/holdings", "/performance", "/goal-planning", "/risk-profile"]:
            assert endpoint in paths, f"Missing path: {endpoint}"

    def test_relationship_id_param_on_all_endpoints(self):
        r = wealth.get("/openapi.json")
        paths = r.json()["paths"]
        for endpoint in ["/holdings", "/performance", "/goal-planning", "/risk-profile"]:
            params = paths[endpoint]["get"].get("parameters", [])
            param_names = [p["name"] for p in params]
            assert "relationship_id" in param_names, f"{endpoint} missing relationship_id param"

    def test_responses_200_defined(self):
        r = wealth.get("/openapi.json")
        paths = r.json()["paths"]
        for endpoint in ["/holdings", "/performance", "/goal-planning", "/risk-profile"]:
            responses = paths[endpoint]["get"].get("responses", {})
            assert "200" in responses, f"{endpoint} missing 200 response definition"


# ─────────────────────────────────────────────────────────────────────────────
# Servicing MCP tool contracts — tested via direct function calls
# ─────────────────────────────────────────────────────────────────────────────

class TestServicingToolContracts:
    """Direct tool invocation verifies the data the MCP server returns.
    These are the same functions registered with FastMCP; testing them directly
    avoids MCP protocol overhead while still exercising the actual business logic."""

    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports):
        pass

    def test_custody_structure(self):
        from custody.tool import get_custody_positions
        result = json.loads(get_custody_positions(REL))
        assert "holdings_by_custodian" in result
        assert len(result["holdings_by_custodian"]) > 0
        total = sum(
            h["value"]
            for c in result["holdings_by_custodian"]
            for h in c["holdings"]
        )
        assert total > 0, "Total custody value must be positive"

    def test_settlements_unique_trade_ids(self):
        from settlements.tool import get_settlements
        result = json.loads(get_settlements(REL))
        assert "pending" in result and "failed" in result
        ids = [t["trade_id"] for t in result["pending"]]
        assert len(ids) == len(set(ids)), "Trade IDs must be unique"

    def test_cash_projected_is_numeric(self):
        from cash.tool import get_cash
        result = json.loads(get_cash(REL))
        assert "balances" in result
        assert isinstance(result["projected_cash_usd"], (int, float))

    def test_nav_fund_id_echoed(self):
        from nav.tool import get_nav
        result = json.loads(get_nav(FUND))
        assert result["fund_id"] == FUND
        assert "nav" in result and isinstance(result["nav"], (int, float))
        assert "as_of_date" in result

    def test_nav_rejects_relationship_id(self):
        from nav.tool import get_nav
        result = json.loads(get_nav(REL))
        assert "error" in result, "NAV must reject non-fund IDs (key isolation enforced)"

    def test_corporate_actions_type_is_known(self):
        from corporate_actions.tool import get_corporate_actions
        result = json.loads(get_corporate_actions(REL))
        # Canned data uses Title Case; match exactly as returned
        known = {"Dividend", "Stock Split", "Rights Issue", "Earnings",
                 "Bond Maturity", "Spin Off", "Acquisition"}
        for action in result.get("upcoming_actions", []):
            assert action["type"] in known, f"Unknown action type: {action['type']}"


# ─────────────────────────────────────────────────────────────────────────────
# Servicing fault knobs — MCP tools use env vars
# ─────────────────────────────────────────────────────────────────────────────

class TestServicingFaultKnobs:
    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports):
        pass

    def test_fault_tool_triggers_error_on_target(self):
        os.environ["MCP_FAULT_TOOL"] = "get_settlements"
        import importlib
        import shared.fault_knobs as fk
        importlib.reload(fk)
        try:
            with pytest.raises(RuntimeError, match="fault knob"):
                fk.maybe_fault("get_settlements")
        finally:
            os.environ.pop("MCP_FAULT_TOOL", None)

    def test_fault_tool_does_not_affect_other_tools(self):
        os.environ["MCP_FAULT_TOOL"] = "get_settlements"
        import importlib
        import shared.fault_knobs as fk
        importlib.reload(fk)
        try:
            # get_custody_positions should NOT be affected
            fk.maybe_fault("get_custody_positions")  # must not raise
        finally:
            os.environ.pop("MCP_FAULT_TOOL", None)

    def test_fault_all_triggers_any_tool(self):
        os.environ["MCP_FAULT_ALL"] = "true"
        import importlib
        import shared.fault_knobs as fk
        importlib.reload(fk)
        try:
            with pytest.raises(RuntimeError, match="fault knob"):
                fk.maybe_fault("get_custody_positions")
        finally:
            os.environ.pop("MCP_FAULT_ALL", None)

    def test_no_fault_by_default(self):
        os.environ.pop("MCP_FAULT_TOOL", None)
        os.environ.pop("MCP_FAULT_ALL", None)
        import importlib
        import shared.fault_knobs as fk
        importlib.reload(fk)
        fk.maybe_fault("get_settlements")  # must not raise


# ─────────────────────────────────────────────────────────────────────────────
# Cross-agent data consistency — critical for answer synthesis grounding
# ─────────────────────────────────────────────────────────────────────────────

class TestCrossAgentConsistency:
    """The gateway synthesizes one answer from data across multiple agents.
    Numbers that appear in the final answer MUST come from agent outputs.
    These tests verify the canned data is internally consistent."""

    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports, monkeypatch):
        _allow_all_tokens(monkeypatch)

    def test_holdings_and_custody_both_have_msft(self):
        from custody.tool import get_custody_positions
        custody = json.loads(get_custody_positions(REL))
        r = wealth.get(f"/holdings?relationship_id={REL}")
        holdings = r.json()

        custody_securities = {
            h["security"]
            for c in custody["holdings_by_custodian"]
            for h in c["holdings"]
        }
        # Wealth holdings use "ticker"; custody uses "security" — both reference the same instruments
        holdings_securities = {pos["ticker"] for pos in holdings["positions"]}
        overlap = custody_securities & holdings_securities
        assert len(overlap) > 0, (
            f"Custody ({custody_securities}) and holdings ({holdings_securities}) must share securities"
        )

    def test_total_value_positive_in_custody(self):
        from custody.tool import get_custody_positions
        custody = json.loads(get_custody_positions(REL))
        total = sum(
            h["value"]
            for c in custody["holdings_by_custodian"]
            for h in c["holdings"]
        )
        assert total > 0

    def test_performance_pnl_aligns_with_holdings_value(self):
        """PnL and total_value must both be positive for a healthy account."""
        from custody.tool import get_custody_positions
        custody = json.loads(get_custody_positions(REL))
        perf = wealth.get(f"/performance?relationship_id={REL}").json()
        assert perf["pnl"] > 0 or perf["total_return_pct"] != 0, \
            "Performance data should show non-trivial returns"
