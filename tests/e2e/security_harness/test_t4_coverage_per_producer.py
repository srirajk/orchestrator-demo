from __future__ import annotations

import subprocess
import time

import pytest
import requests

from lib import bff_client, config, trace_client
from lib.evidence import evidence


DENY_WORDS = ("not in your coverage", "not in your book", "do not have access",
              "outside your access", "unable to verify your coverage", "cannot provide",
              "not authorized")
INTERESTING_EVENTS = {"agent_complete", "check_denied", "gate", "plan_graph", "request_complete"}


def _evidence_events(events):
    summary = []
    for event in events:
        if event.get("type") in INTERESTING_EVENTS:
            summary.append({"type": event.get("type"), "data": event.get("data")})
    return summary[-30:]


def _ask(session, prompt: str, title: str):
    turn = bff_client.ask(session, prompt, title)
    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence(title, {
        "conversation_id": turn.conversation_id,
        "request_id": request_id,
        "status": turn.http_status,
        "answer": turn.answer_text,
        "events": _evidence_events(events),
    })
    return turn, request_id, events


def _denied(turn):
    text = turn.answer_text.lower()
    return any(word in text for word in DENY_WORDS)


def _event(events, typ, pred=lambda e: True):
    return [e for e in trace_client.events_of_type(events, typ) if pred(e)]


def _restart_container(name: str):
    subprocess.run(["docker", "start", name], check=False)
    deadline = time.time() + 45
    while time.time() < deadline:
        try:
            if requests.get(config.WEALTH_COVERAGE_URL + "/health", timeout=2).status_code < 500:
                return
        except requests.RequestException:
            pass
        time.sleep(1)
    raise AssertionError(f"{name} did not become healthy after restart")


def test_s1_in_book_served(jane_session):
    turn, _, events = _ask(
        jane_session,
        "Show me the holdings for Whitman Family Office REL-00042.",
        "S1 in-book served",
    )
    assert turn.http_status == 200
    assert "Whitman" in turn.answer_text or "AAPL" in turn.answer_text
    assert _event(events, "agent_complete")


def test_s2_peer_isolation_denied(carlos_session):
    turn, _, events = _ask(
        carlos_session,
        "Show me the holdings for Whitman Family Office REL-00042.",
        "S2 peer isolation",
    )
    assert turn.http_status == 200
    assert _denied(turn), turn.answer_text
    assert _event(events, "check_denied", lambda e: e["data"].get("stage") == "coverage")


def test_s3_resolve_agnostic_check_gates(carlos_session):
    turn, _, events = _ask(
        carlos_session,
        "Show me the holdings for Whitman Family Office.",
        "S3 resolve agnostic check gates",
    )
    assert turn.http_status == 200
    assert _denied(turn), turn.answer_text
    denies = _event(events, "check_denied", lambda e: e["data"].get("stage") == "coverage")
    assert denies, events
    assert any(e["data"].get("entityId") == "REL-00042" for e in denies), denies


def test_s4_empty_book_denied():
    guest = bff_client.login("rm_guest")
    turn, _, events = _ask(
        guest,
        "Show me the holdings for Whitman Family Office REL-00042.",
        "S4 empty book",
    )
    assert turn.http_status == 200
    assert _denied(turn), turn.answer_text
    assert _event(events, "check_denied", lambda e: e["data"].get("stage") == "coverage")


def test_s5_single_relationship_scoping(ops_session):
    served, _, served_events = _ask(
        ops_session,
        "What is the settlement risk for REL-00188 (Okafor)?",
        "S5 single relationship served",
    )
    assert served.http_status == 200
    assert "settlement" in served.answer_text.lower()
    assert _event(served_events, "agent_complete")

    denied, _, denied_events = _ask(
        ops_session,
        "What is the settlement risk for REL-00042 (Whitman)?",
        "S5 single relationship denied",
    )
    assert denied.http_status == 200
    assert _denied(denied), denied.answer_text
    assert _event(denied_events, "check_denied", lambda e: e["data"].get("stage") == "coverage")


def test_s6_segment_gate_denies(carlos_session):
    turn, _, events = _ask(
        carlos_session,
        "Should we reprice the Continental Freight policy at renewal given open claims?",
        "S6 segment gate",
    )
    assert turn.http_status == 200
    assert "required services" in turn.answer_text.lower() or _denied(turn)
    assert _event(events, "check_denied", lambda e: e["data"].get("stage") == "structural")


def test_s7_classification_gate_denies(jane_session):
    turn, _, events = _ask(
        jane_session,
        "What is the settlement risk for REL-00042 (Whitman)?",
        "S7 classification gate",
    )
    assert turn.http_status == 200
    assert "required services" in turn.answer_text.lower() or _denied(turn)
    gates = _event(events, "gate", lambda e: e["data"].get("gate") == "classification")
    assert any(e["data"].get("effect") == "deny" for e in gates), gates


def test_s8_all_three_pass(ops_session):
    turn, _, events = _ask(
        ops_session,
        "What is the settlement risk for REL-00188 (Okafor)?",
        "S8 all three pass",
    )
    assert turn.http_status == 200
    assert "cash" in turn.answer_text.lower() and "settlement" in turn.answer_text.lower()
    gates = _event(events, "gate")
    assert any(e["data"].get("gate") == "segment" and e["data"].get("effect") == "allow" for e in gates)
    assert any(e["data"].get("gate") == "classification" and e["data"].get("effect") == "allow" for e in gates)
    assert any(e["data"].get("gate") == "coverage" and e["data"].get("effect") == "allow" for e in gates)


def test_s9_unresolved_id_denies(jane_session):
    turn, _, events = _ask(
        jane_session,
        "Show me the holdings for REL-99999.",
        "S9 unresolved id deny",
    )
    assert turn.http_status == 200
    assert _denied(turn) or "could not find" not in turn.answer_text.lower(), turn.answer_text
    assert _event(events, "check_denied", lambda e: e["data"].get("reason") == "unresolved-entity")


def test_s10_coverage_outage_denies(jane_session):
    container = "wealth-coverage"
    subprocess.run(["docker", "stop", container], check=True)
    try:
        turn, _, events = _ask(
            jane_session,
            "Show me the holdings for Whitman Family Office REL-00042.",
            "S10 coverage outage",
        )
        assert turn.http_status == 200
        assert "unable to verify your coverage" in turn.answer_text.lower() or _denied(turn)
        assert _event(events, "check_denied", lambda e: e["data"].get("reason") == "coverage-unavailable")
    finally:
        _restart_container(container)


def test_s11_ambiguous_reference_clarifies(jane_session):
    turn, _, events = _ask(
        jane_session,
        "Show me the holdings for the trust.",
        "S11 ambiguous reference",
    )
    assert turn.http_status == 200
    lower = turn.answer_text.lower()
    assert "which" in lower or "clarify" in lower or "could you" in lower, turn.answer_text
    assert not _event(events, "agent_complete")


def _run_t4_junit(method: str):
    result = subprocess.run(
        ["mvn", f"-Dtest=DagPlanExecutorTest#{method}", "test"],
        cwd="gateway",
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=120,
    )
    evidence(f"JUnit {method}", result.stdout[-4000:])
    assert result.returncode == 0


def test_s12_discovered_entity_filter_unit():
    _run_t4_junit("discoveredEntitiesFilteredBeforeDownstream")


def test_s13_per_producer_uncovered_degrades_unit():
    _run_t4_junit("uncoveredProducerInFanInLeavesSurvivorAndSkipsConsumer")


def test_s14_condition_and_map_over_filtered_data_unit():
    _run_t4_junit("conditionSeesOnlyFilteredData")
    _run_t4_junit("mapSeesOnlyFilteredData")


def test_s15_valid_token_uncovered_entity_denied(carlos_session):
    turn, _, events = _ask(
        carlos_session,
        "Show me the holdings for Whitman Family Office REL-00042.",
        "S15 valid token uncovered",
    )
    assert turn.http_status == 200
    assert _denied(turn), turn.answer_text
    assert _event(events, "check_denied", lambda e: e["data"].get("stage") == "coverage")


def test_s16_multiturn_rechecks_coverage(jane_session):
    cid = bff_client.create_conversation(jane_session, "S16 multiturn recheck")
    first = bff_client.send_message(jane_session, cid, "Show me the holdings for Whitman Family Office REL-00042.")
    assert first.http_status == 200
    assert "Whitman" in first.answer_text or "AAPL" in first.answer_text

    second = bff_client.send_message(jane_session, cid, "Now show me the Okafor account REL-00188.")
    request_id, events = trace_client.trace_for_conversation(second.conversation_id)
    evidence("S16 multiturn recheck", {
        "conversation_id": second.conversation_id,
        "request_id": request_id,
        "answer": second.answer_text,
        "events": _evidence_events(events),
    })
    assert second.http_status == 200
    assert _denied(second), second.answer_text
    assert _event(events, "check_denied", lambda e: e["data"].get("stage") == "coverage")
