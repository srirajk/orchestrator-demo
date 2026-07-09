"""
Entitlement (coverage) gate — RESOLVE is principal-agnostic, CHECK is the only gate
(CLAUDE.md §4f / world-b.md invariant 5). rm_carlos's book is REL-00099 ONLY
(scripts/seed-data/principals.json) — he is NOT entitled to Whitman (REL-00042), which
rm_jane covers. Asking about Whitman as rm_carlos must produce a clean denial, must never
invoke the holdings/concentration agents, and must never leak Whitman's data into the
response.
"""
from __future__ import annotations
import re

from lib import bff_client, config, trace_client
from lib.evidence import evidence

# Whitman-specific figures that must never appear in a denied response (from the live
# ground-truth holdings payload — see the harness README / test_grounding.py).
_LEAK_MARKERS = ("1,967,000", "1967000", "whitman family office")
_TICKER_RE = re.compile(r"\b(AAPL|MSFT|GOOGL|JPM|T-BILL-2026)\b")


def test_coverage_denial(carlos_session):
    """rm_carlos asks about Whitman (not his client) -> clean coverage denial, no data leak,
    and no holdings/concentration agent invocation in the trace."""
    turn = bff_client.ask(
        carlos_session, f"Is the {config.WHITMAN_NAME} over-concentrated?"
    )
    evidence("chat turn (rm_carlos, Whitman — NOT in his book)", {
        "conversation_id": turn.conversation_id,
        "http_status": turn.http_status,
        "answer": turn.answer_text,
    })
    assert turn.http_status == 200, f"BFF turn failed unexpectedly: {turn.http_status}"

    lower = turn.answer_text.lower()
    is_clean_denial = "not in your coverage" in lower or "access denied" in lower
    assert is_clean_denial, (
        f"Expected a clean coverage denial ('...not in your coverage...'), got: "
        f"{turn.answer_text!r}"
    )

    # No client data leaked into the denial response.
    for marker in _LEAK_MARKERS:
        assert marker not in lower, (
            f"Denial response leaked Whitman data ({marker!r} present): {turn.answer_text!r}"
        )
    leaked_tickers = _TICKER_RE.findall(turn.answer_text)
    assert not leaked_tickers, f"Denial response leaked position tickers: {leaked_tickers}"

    # No holdings/concentration agent ran — the entitlement CHECK must short-circuit
    # BEFORE any data-plane agent call, per CLAUDE.md §4f.
    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    evidence("trace events for the denied turn", {"requestId": request_id, "events": events})
    if request_id is not None:
        agent_starts = trace_client.events_of_type(events, "agent_start")
        data_agent_starts = [
            e for e in agent_starts
            if "holdings" in e["data"]["agentId"].lower()
            or "concentration" in e["data"]["agentId"].lower()
        ]
        assert not data_agent_starts, (
            f"A data-plane agent ran despite the coverage denial: {data_agent_starts}"
        )
        check_denied = trace_client.events_of_type(events, "check_denied")
        assert check_denied, (
            "No check_denied trace event recorded for a denied turn — the denial isn't "
            "showing up in the glass-box decision trace (evidence gap even if the user-"
            "facing copy was correct)."
        )
        assert check_denied[-1]["data"]["stage"] == "coverage", (
            f"Expected stage='coverage' on the deny event, got: {check_denied[-1]['data']}"
        )
