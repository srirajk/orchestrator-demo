"""
Positive path — the happy path a legitimate, entitled user should always get, end to end
through the real BFF: OIDC login -> real conversation -> real gateway fan-out -> grounded
answer. If these regress, everything downstream (entitlement, identity, grounding) is
moot — nothing else in this repo works if the basic path breaks.
"""
from __future__ import annotations
import re

from lib import bff_client, config, trace_client
from lib.evidence import evidence

_NUMBER_RE = re.compile(r"\d+(?:\.\d+)?\s*%|HHI\s*[:=]?\s*\d+(?:\.\d+)?", re.IGNORECASE)


def test_multistep_concentration(jane_session):
    """
    rm_jane asks a concentration question about her own client (Whitman, REL-00042).

    Asserts:
      1. The gateway resolved a MULTI-STEP plan (plan_graph event) with a concentration
         node that depends on a holdings node (holdings -> concentration).
      2. The streamed answer is grounded with actual concentration numbers (a single-name
         % or an HHI figure), not a generic non-answer.
    """
    turn = bff_client.send_message(
        jane_session,
        bff_client.create_conversation(jane_session, "Whitman concentration check"),
        f"Is the {config.WHITMAN_NAME} over-concentrated?",
    )
    evidence("chat turn (rm_jane, concentration question)", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    assert len(turn.answer_text) > 20, f"Answer suspiciously short: {turn.answer_text!r}"

    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence("trace events for this turn", {"requestId": request_id, "events": events})
    assert request_id is not None, (
        "No trace found for this conversation — either CONDUIT_ORCHESTRATION_DAG_ENABLED "
        "is off, or the trace store isn't reachable/populated in time."
    )

    plan_graphs = trace_client.events_of_type(events, "plan_graph")
    assert plan_graphs, (
        "No plan_graph event emitted for a concentration question — expected a multi-step "
        "DAG plan (holdings -> concentration). If CONDUIT_ORCHESTRATION_DAG_ENABLED is false "
        "this is expected (flat fan-out path never emits plan_graph); otherwise this is a "
        "routing/planning regression."
    )
    nodes = plan_graphs[-1]["data"]["nodes"]
    holdings_nodes = [n for n in nodes if "holdings" in n["agentId"].lower()]
    concentration_nodes = [n for n in nodes if "concentration" in n["agentId"].lower()]
    assert holdings_nodes, f"No holdings node in plan_graph: {nodes}"
    assert concentration_nodes, f"No concentration node in plan_graph: {nodes}"
    holdings_node_ids = {n["nodeId"] for n in holdings_nodes}
    depends_on_holdings = any(
        holdings_node_ids & set(n.get("dependsOn") or []) for n in concentration_nodes
    )
    assert depends_on_holdings, (
        f"concentration node(s) do not depend on the holdings node(s) — expected a "
        f"holdings -> concentration edge. nodes={nodes}"
    )

    assert _NUMBER_RE.search(turn.answer_text), (
        f"Answer contains no concentration figure (single-name % or HHI): {turn.answer_text!r}"
    )


def test_single_step_holdings(jane_session):
    """rm_jane asks a plain holdings question about her own client -> a grounded, single-step answer."""
    turn = bff_client.ask(jane_session, f"Give me the {config.WHITMAN_NAME} holdings")
    evidence("chat turn (rm_jane, holdings question)", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    assert len(turn.answer_text) > 20, f"Answer suspiciously short: {turn.answer_text!r}"
    lower = turn.answer_text.lower()
    assert "not in your coverage" not in lower and "denied" not in lower, (
        f"rm_jane was unexpectedly denied her own client's holdings: {turn.answer_text!r}"
    )
