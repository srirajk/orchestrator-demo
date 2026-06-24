"""
Asset Servicing MCP server tests — Phase 2 acceptance.

Smoke-tests the MCP server by importing the tool functions directly
(unit-test level) plus a tools/list check via the MCP client.

Run from project root:
  PYTHONPATH=mock-agents/servicing pytest mock-agents/tests/test_servicing.py
"""
import json
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../servicing"))

import pytest


class TestCannedData:
    """Validate canned data shape for each tool — no HTTP/MCP overhead."""

    def test_custody_positions_schema(self):
        from tools.custody import get_custody_positions
        result = json.loads(get_custody_positions("REL-00042"))
        assert "holdings_by_custodian" in result
        assert len(result["holdings_by_custodian"]) > 0
        custodian = result["holdings_by_custodian"][0]
        assert "custodian" in custodian
        assert "holdings" in custodian

    def test_settlements_schema(self):
        from tools.settlements import get_settlements
        result = json.loads(get_settlements("REL-00042"))
        assert "pending" in result
        assert "failed" in result
        # The MSFT settlement should be present
        if result["pending"]:
            trade = result["pending"][0]
            assert "trade_id" in trade
            assert "amount" in trade

    def test_corporate_actions_schema(self):
        from tools.corporate_actions import get_corporate_actions
        result = json.loads(get_corporate_actions("REL-00042"))
        assert "upcoming_actions" in result
        if result["upcoming_actions"]:
            action = result["upcoming_actions"][0]
            assert "action_id" in action
            assert "type" in action

    def test_nav_schema(self):
        from tools.nav import get_nav
        result = json.loads(get_nav("FND-7781"))
        assert "fund_id" in result
        assert "nav" in result
        assert "as_of_date" in result
        assert result["fund_id"] == "FND-7781"

    def test_nav_unknown_fund(self):
        from tools.nav import get_nav
        result = json.loads(get_nav("FND-9999"))
        assert "error" in result

    def test_cash_schema(self):
        from tools.cash import get_cash
        result = json.loads(get_cash("REL-00042"))
        assert "balances" in result
        assert "projected_cash_usd" in result

    def test_nav_not_keyed_by_relationship(self):
        """
        NAV uses fund_id not relationship_id — this is what prevents hero from selecting it.
        """
        from tools.nav import get_nav
        result = json.loads(get_nav("REL-00042"))  # wrong key type
        assert "error" in result


class TestFaultKnob:
    """Verify MCP fault knobs trigger correctly."""

    def test_fault_tool_triggers_error(self):
        os.environ["MCP_FAULT_TOOL"] = "get_settlements"
        try:
            from tools.settlements import get_settlements
            from tools.fault_knobs import maybe_fault
            # Reload to pick up env change
            import importlib
            import tools.fault_knobs as fk_module
            importlib.reload(fk_module)
            import tools.settlements as s_module
            importlib.reload(s_module)
            with pytest.raises(RuntimeError, match="fault knob"):
                fk_module.maybe_fault("get_settlements")
        finally:
            del os.environ["MCP_FAULT_TOOL"]

    def test_no_fault_by_default(self):
        os.environ.pop("MCP_FAULT_TOOL", None)
        os.environ.pop("MCP_FAULT_ALL", None)
        from tools.fault_knobs import maybe_fault
        import importlib
        import tools.fault_knobs as fk_module
        importlib.reload(fk_module)
        # Should not raise
        fk_module.maybe_fault("get_settlements")


class TestCrossDataConsistency:
    """Numbers must cross-reference between agents for Phase 4 grounding."""

    def test_msft_value_matches_settlement(self):
        from tools.custody import get_custody_positions
        from tools.settlements import get_settlements
        custody = json.loads(get_custody_positions("REL-00042"))
        settlement = json.loads(get_settlements("REL-00042"))

        # Find MSFT value in custody
        msft_value = None
        for custodian in custody["holdings_by_custodian"]:
            for h in custodian["holdings"]:
                if h["security"] == "MSFT":
                    msft_value = h["value"]

        # The pending T-9912 MSFT buy should match
        if settlement["pending"]:
            t9912 = next(
                (t for t in settlement["pending"] if t["trade_id"] == "T-9912"), None
            )
            if t9912 and msft_value is not None:
                assert t9912["amount"] == msft_value, (
                    f"MSFT custody value {msft_value} must match settlement amount {t9912['amount']}"
                )
