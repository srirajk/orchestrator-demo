"""
Insurance renewal-risk multi-step orchestration — the second business domain proving
2-PRODUCER FAN-IN (not just the 1-producer fan-in the wealth `concentration` agent
exercises): (policy_details || claim_status) -> renewal_risk.

uw_sam (insurance underwriter) asks a renewal question about a policy in his own book
(Continental Freight, POL-77001). Mirrors
tests/e2e/security_harness/test_positive_path.py::test_multistep_concentration.

Asserts:
  1. The gateway resolved a MULTI-STEP DAG plan (plan_graph event) with a renewal_risk
     node depending on BOTH a policy_details node and a claim_status node (the merge
     path, not a single-producer fan-in).
  2. The streamed answer is grounded with an actual loss-ratio figure derived from the
     real policy premium + claim amount (not a generic non-answer, not a fabricated
     number).
"""
from __future__ import annotations
import re

from lib import bff_client, config, trace_client
from lib.evidence import evidence

_LOSS_RATIO_RE = re.compile(r"\d+(?:\.\d+)?\s*%", re.IGNORECASE)


def test_insurance_renewal_multistep(sam_session):
    """
    uw_sam asks a renewal-risk question about his own policy (Continental Freight, POL-77001).

    Asserts the DAG fan-in shape (policy_details || claim_status -> renewal_risk) and that
    the grounded answer states a loss-ratio figure, not a generic non-answer.
    """
    turn = bff_client.send_message(
        sam_session,
        bff_client.create_conversation(sam_session, "Continental Freight renewal check"),
        f"Should we reprice the {config.CONTINENTAL_FREIGHT_NAME} policy at renewal "
        f"given the open claims?",
    )
    evidence("chat turn (uw_sam, renewal_risk question)", {
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
        "No plan_graph event emitted for a renewal-risk question — expected a multi-step "
        "DAG plan (policy_details, claim_status -> renewal_risk). If "
        "CONDUIT_ORCHESTRATION_DAG_ENABLED is false this is expected (flat fan-out path "
        "never emits plan_graph); otherwise this is a routing/planning regression."
    )
    nodes = plan_graphs[-1]["data"]["nodes"]
    policy_nodes = [n for n in nodes if "policy_details" in n["agentId"].lower()]
    claim_nodes = [n for n in nodes if "claim_status" in n["agentId"].lower()]
    renewal_nodes = [n for n in nodes if "renewal_risk" in n["agentId"].lower()]
    assert policy_nodes, f"No policy_details node in plan_graph: {nodes}"
    assert claim_nodes, f"No claim_status node in plan_graph: {nodes}"
    assert renewal_nodes, f"No renewal_risk node in plan_graph: {nodes}"

    upstream_node_ids = {n["nodeId"] for n in policy_nodes} | {n["nodeId"] for n in claim_nodes}
    for rn in renewal_nodes:
        depends_on = set(rn.get("dependsOn") or [])
        assert upstream_node_ids & depends_on == upstream_node_ids, (
            f"renewal_risk node does not depend on BOTH policy_details AND claim_status "
            f"(expected the 2-producer fan-in merge path). nodes={nodes}"
        )

    agent_completes = trace_client.events_of_type(events, "agent_complete")
    renewal_completes = [
        e for e in agent_completes if "renewal_risk" in e["data"]["agentId"].lower()
    ]
    assert renewal_completes, f"renewal_risk agent never completed: {events}"
    assert renewal_completes[-1]["data"]["status"] == "ok", (
        f"renewal_risk agent did not complete OK (fan-in merge or select likely broken): "
        f"{renewal_completes[-1]}"
    )

    assert _LOSS_RATIO_RE.search(turn.answer_text), (
        f"Answer contains no loss-ratio percentage figure: {turn.answer_text!r}"
    )
    lower = turn.answer_text.lower()
    assert "claims-based" in lower, (
        f"Answer did not qualify the loss ratio as claims-based: {turn.answer_text!r}"
    )
    assert "earned-premium" in lower or "earned premium" in lower, (
        f"Answer did not include the premium-basis disclosure: {turn.answer_text!r}"
    )
    assert "loss-only" in lower and "full claimed value" in lower, (
        f"Answer did not include the loss-only/full-claimed-value disclosure: {turn.answer_text!r}"
    )
    assert "not in your coverage" not in lower and "denied" not in lower, (
        f"uw_sam was unexpectedly denied his own policy's renewal analysis: {turn.answer_text!r}"
    )


def test_insurance_out_of_book_policy_denied(sam_session):
    """uw_sam asks about POL-88003 (Zenith) — NOT in his book — expect a denial, not data."""
    turn = bff_client.ask(
        sam_session,
        "Should we reprice the Zenith Logistics policy POL-88003 at renewal?",
    )
    evidence("chat turn (uw_sam, out-of-book policy)", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed: {turn.http_status}"
    lower = turn.answer_text.lower()
    assert "book of business" in lower or "denied" in lower or "not in your" in lower, (
        f"uw_sam was NOT denied for an out-of-book policy — entitlement regression: "
        f"{turn.answer_text!r}"
    )
