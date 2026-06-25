"""
Asset Servicing MCP server tests — Phase 2 acceptance.

Smoke-tests the MCP server by importing the tool functions directly
plus a cross-data consistency check.

Run from project root:
  pytest mock-agents/tests/test_servicing.py -v

Or alongside all tests:
  PYTHONPATH=mock-agents/wealth pytest mock-agents/tests/ -v
"""
import json
import os
import sys

import pytest

# NOTE: sys.path management is handled entirely by the `servicing_imports` fixture
# in conftest.py to avoid the shared/ namespace collision when running alongside
# test_agent_integration.py (which adds wealth to sys.path at module level).


class TestCannedData:
    """Validate canned data shape for each tool — no HTTP/MCP overhead."""

    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports):
        pass

    def test_custody_positions_schema(self):
        from custody.tool import get_custody_positions
        result = json.loads(get_custody_positions("REL-00042"))
        assert "holdings_by_custodian" in result
        assert len(result["holdings_by_custodian"]) > 0
        custodian = result["holdings_by_custodian"][0]
        assert "custodian" in custodian
        assert "holdings" in custodian

    def test_settlements_schema(self):
        from settlements.tool import get_settlements
        result = json.loads(get_settlements("REL-00042"))
        assert "pending" in result
        assert "failed" in result
        if result["pending"]:
            trade = result["pending"][0]
            assert "trade_id" in trade
            assert "amount" in trade

    def test_corporate_actions_schema(self):
        from corporate_actions.tool import get_corporate_actions
        result = json.loads(get_corporate_actions("REL-00042"))
        assert "upcoming_actions" in result
        if result["upcoming_actions"]:
            action = result["upcoming_actions"][0]
            assert "action_id" in action
            assert "type" in action

    def test_nav_schema(self):
        from nav.tool import get_nav
        result = json.loads(get_nav("FND-7781"))
        assert "fund_id" in result
        assert "nav" in result
        assert "as_of_date" in result
        assert result["fund_id"] == "FND-7781"

    def test_nav_unknown_fund(self):
        from nav.tool import get_nav
        result = json.loads(get_nav("FND-9999"))
        assert "error" in result

    def test_cash_schema(self):
        from cash.tool import get_cash
        result = json.loads(get_cash("REL-00042"))
        assert "balances" in result
        assert "projected_cash_usd" in result

    def test_nav_not_keyed_by_relationship(self):
        """NAV uses fund_id not relationship_id — this is what prevents hero from selecting it."""
        from nav.tool import get_nav
        result = json.loads(get_nav("REL-00042"))
        assert "error" in result


class TestFaultKnob:
    """Verify MCP fault knobs trigger correctly."""

    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports):
        pass

    def test_fault_tool_triggers_error(self):
        os.environ["MCP_FAULT_TOOL"] = "get_settlements"
        try:
            import importlib
            import shared.fault_knobs as fk_module
            importlib.reload(fk_module)
            with pytest.raises(RuntimeError, match="fault knob"):
                fk_module.maybe_fault("get_settlements")
        finally:
            os.environ.pop("MCP_FAULT_TOOL", None)

    def test_no_fault_by_default(self):
        os.environ.pop("MCP_FAULT_TOOL", None)
        os.environ.pop("MCP_FAULT_ALL", None)
        import importlib
        import shared.fault_knobs as fk_module
        importlib.reload(fk_module)
        fk_module.maybe_fault("get_settlements")  # must not raise


class TestCrossDataConsistency:
    """Numbers must cross-reference between agents for answer grounding."""

    @pytest.fixture(autouse=True)
    def _fix(self, servicing_imports):
        pass

    def test_msft_value_matches_settlement(self):
        from custody.tool import get_custody_positions
        from settlements.tool import get_settlements
        custody = json.loads(get_custody_positions("REL-00042"))
        settlement = json.loads(get_settlements("REL-00042"))

        msft_value = None
        for custodian in custody["holdings_by_custodian"]:
            for h in custodian["holdings"]:
                if h["security"] == "MSFT":
                    msft_value = h["value"]

        if settlement["pending"]:
            t9912 = next(
                (t for t in settlement["pending"] if t["trade_id"] == "T-9912"), None
            )
            if t9912 and msft_value is not None:
                assert t9912["amount"] == msft_value, (
                    f"MSFT custody value {msft_value} must match settlement amount {t9912['amount']}"
                )
