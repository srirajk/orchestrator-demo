"""
Asset-servicing settlement-risk multi-step orchestration.

Proves 3-PRODUCER FAN-IN for the third business domain:
settlement_status || custody_positions || cash_management -> settlement_risk.
"""
from __future__ import annotations
import re

from lib import bff_client, trace_client
from lib.evidence import evidence

_MONEY_OR_AGE_RE = re.compile(r"\$?\d[\d,]*(?:\.\d+)?|day", re.IGNORECASE)


def test_servicing_settlement_multistep(admin_session):
    """admin asks a settlement-risk question through the real BFF."""
    turn = bff_client.send_message(
        admin_session,
        bff_client.create_conversation(admin_session, "Okafor settlement risk"),
        "What is the settlement risk for REL-00188 (Okafor)?",
    )
    evidence("chat turn (admin, settlement_risk question)", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    assert len(turn.answer_text) > 20, f"Answer suspiciously short: {turn.answer_text!r}"

    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence("trace events for this turn", {"requestId": request_id, "events": events})
    assert request_id is not None, "No trace found for this conversation."

    plan_graphs = trace_client.events_of_type(events, "plan_graph")
    assert plan_graphs, (
        "No plan_graph event emitted for a settlement-risk question — expected a "
        "multi-step DAG plan (settlement_status, custody_positions, cash -> settlement_risk)."
    )
    nodes = plan_graphs[-1]["data"]["nodes"]
    settlement_nodes = [n for n in nodes if "settlement_status" in n["agentId"].lower()]
    custody_nodes = [n for n in nodes if "custody_positions" in n["agentId"].lower()]
    cash_nodes = [n for n in nodes if "cash_management" in n["agentId"].lower()]
    risk_nodes = [n for n in nodes if "settlement_risk" in n["agentId"].lower()]
    assert settlement_nodes, f"No settlement_status node in plan_graph: {nodes}"
    assert custody_nodes, f"No custody_positions node in plan_graph: {nodes}"
    assert cash_nodes, f"No cash_management node in plan_graph: {nodes}"
    assert risk_nodes, f"No settlement_risk node in plan_graph: {nodes}"

    upstream_node_ids = (
        {n["nodeId"] for n in settlement_nodes}
        | {n["nodeId"] for n in custody_nodes}
        | {n["nodeId"] for n in cash_nodes}
    )
    for rn in risk_nodes:
        depends_on = set(rn.get("dependsOn") or [])
        assert upstream_node_ids & depends_on == upstream_node_ids, (
            "settlement_risk node does not depend on settlement_status, "
            f"custody_positions, and cash_management. nodes={nodes}"
        )

    agent_completes = trace_client.events_of_type(events, "agent_complete")
    risk_completes = [
        e for e in agent_completes if "settlement_risk" in e["data"]["agentId"].lower()
    ]
    assert risk_completes, f"settlement_risk agent never completed: {events}"
    assert risk_completes[-1]["data"]["status"] == "ok", (
        f"settlement_risk agent did not complete OK: {risk_completes[-1]}"
    )

    lower = turn.answer_text.lower()
    assert "csdr" in lower and "cash" in lower and "penalt" in lower, turn.answer_text
    assert "mandatory buy-in is active" not in lower, turn.answer_text
    assert _MONEY_OR_AGE_RE.search(turn.answer_text), (
        f"Answer contains no settlement-risk number: {turn.answer_text!r}"
    )
