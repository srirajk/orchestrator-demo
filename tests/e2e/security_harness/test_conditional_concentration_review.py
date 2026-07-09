"""
Live regression for the declared concentration_review condition.

The review node should fire only when concentration.breach_count > 0. Rivera is
the non-breaching demo relationship; Whitman is the breaching relationship.
"""
from __future__ import annotations

from lib import bff_client, config, trace_client
from lib.evidence import evidence


REVIEW_AGENT = "meridian.wealth.concentration_review"
CONCENTRATION_AGENT = "meridian.wealth.concentration"


def _ask_concentration(session, title: str, prompt: str) -> list[dict]:
    turn = bff_client.send_message(
        session,
        bff_client.create_conversation(session, title),
        prompt,
    )
    evidence(f"chat turn ({title})", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"

    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence(f"trace events ({title})", {"requestId": request_id, "events": events})
    assert request_id is not None, "No trace found for the conditional concentration turn"
    return events


def _final_plan_nodes(events: list[dict]) -> list[dict]:
    plan_graphs = trace_client.events_of_type(events, "plan_graph")
    assert plan_graphs, f"No plan_graph event emitted: {events}"
    return plan_graphs[-1]["data"]["nodes"]


def _node_status(nodes: list[dict], agent_id: str) -> str:
    matches = [n for n in nodes if n.get("agentId") == agent_id]
    assert matches, f"No {agent_id} node in final plan_graph: {nodes}"
    return matches[-1].get("status")


def _agent_complete_status(events: list[dict], agent_id: str) -> str:
    completes = [
        e for e in trace_client.events_of_type(events, "agent_complete")
        if e.get("data", {}).get("agentId") == agent_id
    ]
    assert completes, f"No agent_complete for {agent_id}: {events}"
    return completes[-1]["data"]["status"]


def test_concentration_review_condition_fires_for_breach_and_skips_for_diversified(jane_session):
    whitman_events = _ask_concentration(
        jane_session,
        "Whitman concentration review conditional",
        f"Does the {config.WHITMAN_NAME} need a firm-policy concentration review flag?",
    )
    whitman_nodes = _final_plan_nodes(whitman_events)
    assert _node_status(whitman_nodes, CONCENTRATION_AGENT) == "ok"
    assert _node_status(whitman_nodes, REVIEW_AGENT) == "ok"
    assert _agent_complete_status(whitman_events, REVIEW_AGENT) == "ok"

    rivera_events = _ask_concentration(
        jane_session,
        "Rivera concentration review conditional",
        f"Does the {config.RIVERA_NAME} need a firm-policy concentration review flag?",
    )
    rivera_nodes = _final_plan_nodes(rivera_events)
    assert _node_status(rivera_nodes, CONCENTRATION_AGENT) == "ok"
    assert _node_status(rivera_nodes, REVIEW_AGENT) == "skipped_condition_false"
    assert _agent_complete_status(rivera_events, REVIEW_AGENT) == "skipped"
