"""Live BFF proof for multi-turn DAG entity carry/backstop."""
from __future__ import annotations

import time

import requests

from lib import bff_client, config, trace_client
from lib.evidence import evidence


CALDERON_RELATIONSHIP_ID = "REL-00099"
CALDERON_NAME = "Calderon Trust"


def _history_ids(conversation_id: str) -> list[str]:
    resp = requests.get(
        f"{config.GATEWAY_URL}/trace/history",
        params={"conversationId": conversation_id, "limit": 10},
        timeout=10,
    )
    resp.raise_for_status()
    return resp.json().get("requestIds", [])


def _send_and_trace(session, conversation_id: str, prompt: str, title: str,
                    previous_count: int = 0):
    turn = bff_client.send_message(session, conversation_id, prompt)
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"

    deadline = time.time() + 20
    request_id = None
    events = []
    while time.time() < deadline:
        ids = _history_ids(conversation_id)
        if len(ids) > previous_count:
            request_id = ids[0]
            events = trace_client.events_for_request(request_id)
            if trace_client.events_of_type(events, "request_complete"):
                break
        time.sleep(0.5)

    evidence(title, {
        "conversation_id": conversation_id,
        "request_id": request_id,
        "status": turn.http_status,
        "answer": turn.answer_text,
        "events": events,
    })
    assert request_id is not None, "No trace found for this turn."
    return turn, request_id, events


def _latest_plan_nodes(events):
    plan_graphs = trace_client.events_of_type(events, "plan_graph")
    assert plan_graphs, "No plan_graph event emitted."
    return plan_graphs[-1]["data"]["nodes"]


def _assert_settlement_risk_fanin(events):
    nodes = _latest_plan_nodes(events)
    settlement = [n for n in nodes if "settlement_status" in n["agentId"].lower()]
    custody = [n for n in nodes if "custody_positions" in n["agentId"].lower()]
    cash = [n for n in nodes if "cash_management" in n["agentId"].lower()]
    risk = [n for n in nodes if "settlement_risk" in n["agentId"].lower()]
    assert settlement and custody and cash and risk, nodes
    upstream = {n["nodeId"] for n in settlement + custody + cash}
    assert upstream <= set(risk[-1].get("dependsOn") or []), nodes


def _assert_holding_concentration_fanin(events):
    nodes = _latest_plan_nodes(events)
    holdings = [n for n in nodes if "holdings" in n["agentId"].lower()]
    concentration = [n for n in nodes if "concentration" in n["agentId"].lower()]
    assert holdings and concentration, nodes
    assert {n["nodeId"] for n in holdings} <= set(concentration[-1].get("dependsOn") or []), nodes


def _entity_ids_from_events(events) -> set[str]:
    ids: set[str] = set()
    for event in events:
        data = event.get("data") or {}
        for key in ("entityId", "relationshipId", "resourceId"):
            value = data.get(key)
            if isinstance(value, str):
                ids.add(value)
    return ids


def test_multiturn_dag_backstop_fires_and_new_entity_overrides(admin_session):
    cid = bff_client.create_conversation(admin_session, "multi-turn DAG backstop")

    first, _, first_events = _send_and_trace(
        admin_session,
        cid,
        f"What is the concentration for {config.WHITMAN_NAME} ({config.WHITMAN_RELATIONSHIP_ID})?",
        "turn 1 seeds focal relationship",
        previous_count=0,
    )
    assert len(first.answer_text) > 20, first.answer_text
    _assert_holding_concentration_fanin(first_events)
    assert config.WHITMAN_RELATIONSHIP_ID in _entity_ids_from_events(first_events)

    second, _, second_events = _send_and_trace(
        admin_session,
        cid,
        "And their settlement risk?",
        "turn 2 carried entity fires settlement DAG",
        previous_count=1,
    )
    assert len(second.answer_text) > 20, second.answer_text
    _assert_settlement_risk_fanin(second_events)
    entity_ids = _entity_ids_from_events(second_events)
    assert config.WHITMAN_RELATIONSHIP_ID in entity_ids, second_events
    assert CALDERON_RELATIONSHIP_ID not in entity_ids, second_events

    third, _, third_events = _send_and_trace(
        admin_session,
        cid,
        f"What about {CALDERON_NAME} ({CALDERON_RELATIONSHIP_ID})?",
        "turn 3 explicit entity overrides carried relationship",
        previous_count=2,
    )
    assert len(third.answer_text) > 20, third.answer_text
    _assert_settlement_risk_fanin(third_events)
    entity_ids = _entity_ids_from_events(third_events)
    assert CALDERON_RELATIONSHIP_ID in entity_ids, third_events
    assert config.WHITMAN_RELATIONSHIP_ID not in entity_ids, third_events


def test_multiturn_carried_entity_rechecks_entitlement(carlos_session):
    cid = bff_client.create_conversation(carlos_session, "multi-turn recheck deny")
    denied_words = ("access denied", "not authorized", "not in your coverage", "not in your book")

    first, _, first_events = _send_and_trace(
        carlos_session,
        cid,
        f"Show me the holdings for {config.WHITMAN_NAME} ({config.WHITMAN_RELATIONSHIP_ID}).",
        "turn 1 denied relationship is still in transcript",
        previous_count=0,
    )
    assert any(word in first.answer_text.lower() for word in denied_words), first.answer_text
    assert trace_client.events_of_type(first_events, "check_denied"), first_events

    second, _, second_events = _send_and_trace(
        carlos_session,
        cid,
        "And their settlement risk?",
        "turn 2 carried denied relationship is rechecked",
        previous_count=1,
    )
    assert any(word in second.answer_text.lower() for word in denied_words), second.answer_text
    denies = trace_client.events_of_type(second_events, "check_denied")
    assert denies, second_events
    assert config.WHITMAN_RELATIONSHIP_ID in _entity_ids_from_events(second_events)
    assert not trace_client.events_of_type(second_events, "agent_complete"), second_events


def test_multiturn_honest_clarify_without_current_or_carried_entity(admin_session):
    cid = bff_client.create_conversation(admin_session, "multi-turn honest clarify")
    turn, _, events = _send_and_trace(
        admin_session,
        cid,
        "And their settlement risk?",
        "no focal entity clarifies honestly",
        previous_count=0,
    )
    lower = turn.answer_text.lower()
    assert "which" in lower or "specify" in lower or "provide" in lower or "relationship" in lower, (
        turn.answer_text
    )
    assert not trace_client.events_of_type(events, "agent_complete"), events
    assert not trace_client.events_of_type(events, "plan_graph"), events
