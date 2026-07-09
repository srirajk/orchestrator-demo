"""Live BFF proof for declared io.map dynamic iteration."""
from __future__ import annotations

from lib import bff_client, trace_client
from lib.evidence import evidence


def _turn_with_trace(session, title: str, prompt: str):
    turn = bff_client.ask(session, prompt, title)
    evidence(f"chat turn ({title})", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence(f"trace events ({title})", {"requestId": request_id, "events": events})
    assert request_id is not None, "No trace found for this conversation."
    return turn, events


def _latest_plan_nodes(events):
    plan_graphs = trace_client.events_of_type(events, "plan_graph")
    assert plan_graphs, "No plan_graph event emitted for map-iteration question."
    return plan_graphs[-1]["data"]["nodes"]


def _map_event(events):
    events = trace_client.events_of_type(events, "map_iteration")
    assert events, "No map_iteration trace event emitted."
    return events[-1]["data"]


def _trade_penalty_node(nodes):
    matches = [n for n in nodes if "trade_penalty" in n["agentId"].lower()]
    assert matches, f"No trade_penalty node in plan_graph: {nodes}"
    return matches[-1]


def test_map_iteration(ops_session):
    """ops_analyst_singh proves normal, empty, capped+failed map paths live via BFF."""
    turn, events = _turn_with_trace(
        ops_session,
        "map iteration normal",
        "For asset servicing relationship REL-00188 Okafor, itemize failed trade CSDR "
        "cash penalty aging by failed trade.",
    )
    nodes = _latest_plan_nodes(events)
    penalty_node = _trade_penalty_node(nodes)
    assert any("settlement_status" in dep for dep in penalty_node.get("dependsOn", [])), nodes
    assert penalty_node["status"].startswith("map:"), nodes
    data = _map_event(events)
    assert data["total"] == 1 and data["ran"] == 1 and data["ok"] == 1, data
    assert data["failed"] == 0 and data["truncated"] is False, data
    assert "mandatory buy-in is active" not in turn.answer_text.lower(), turn.answer_text

    _, empty_events = _turn_with_trace(
        ops_session,
        "map iteration empty",
        "For asset servicing relationship REL-00445 Map Empty, itemize failed trade CSDR "
        "cash penalty aging by failed trade.",
    )
    empty_nodes = _latest_plan_nodes(empty_events)
    empty_penalty_node = _trade_penalty_node(empty_nodes)
    assert empty_penalty_node["status"].startswith("map: 0/0 ok"), empty_nodes
    empty_data = _map_event(empty_events)
    assert empty_data["total"] == 0 and empty_data["ran"] == 0, empty_data
    assert empty_data["ok"] == 0 and empty_data["failed"] == 0, empty_data

    capped_turn, capped_events = _turn_with_trace(
        ops_session,
        "map iteration capped",
        "For asset servicing relationship REL-00444 Map Stress, run failed settlement "
        "CSDR penalty map iteration and tell me what was skipped.",
    )
    capped_nodes = _latest_plan_nodes(capped_events)
    capped_penalty_node = _trade_penalty_node(capped_nodes)
    assert "capped" in capped_penalty_node["status"], capped_nodes
    capped_data = _map_event(capped_events)
    assert capped_data["total"] == 3, capped_data
    assert capped_data["ran"] == 2, capped_data
    assert capped_data["ok"] >= 1 and capped_data["failed"] >= 1, capped_data
    assert capped_data["truncated"] is True, capped_data
    lower = capped_turn.answer_text.lower()
    assert "skip" in lower or "cap" in lower or "truncat" in lower, capped_turn.answer_text

    denied, denied_events = _turn_with_trace(
        ops_session,
        "map iteration out of book",
        "For asset servicing relationship REL-00042 Whitman, itemize failed trade CSDR "
        "cash penalty aging by failed trade.",
    )
    lower = denied.answer_text.lower()
    assert (
        "access denied" in lower
        or "not authorized" in lower
        or "not in your coverage" in lower
    ), denied.answer_text
    assert trace_client.events_of_type(denied_events, "check_denied"), denied_events
