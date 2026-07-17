"""
Structured-clarification FORM submit — the eval-coverage guard.

A clarification renders on TWO planes: a plain-text twin on the chat SSE, and a structured FORM on the
out-of-band trace lane (a `structured_interaction` event carrying the nonce + entitled options + free-text
escape). A harness that only reads the SSE twin never exercises the form's resume + re-CHECK — eval
coverage silently collapses onto the text path (the same bug class as an HTTP-200-only load test).

This test closes that gap end to end through the REAL BFF:
  1. rm_jane asks a vague, in-domain question with NO client named → the pipeline abstains and offers a
     clarification. The FORM is emitted on the trace lane with her entitled in-book options.
  2. The harness SUBMITS the form (POST /api/clarify/resolve) by choosing one offered option value — not
     by re-typing text. The gateway consumes the single-use nonce and re-drives the whole pipeline
     (entitlement re-CHECKed), streaming the resumed answer back.
  3. The resumed answer is a real, grounded answer for the chosen client — not the clarification again,
     and not a denial.

Requires the running demo stack (BFF + gateway + agents + Redis + IAM), like the rest of this harness.
It never mutates gateway/agent config as a side effect of running.
"""
from __future__ import annotations

from lib import bff_client, config, trace_client
from lib.evidence import evidence

# A vague, in-domain ask with NO client named — the abstain-triage clarification trigger. The gateway
# offers the caller's in-book options rather than a bare no-service (see the A6 gateway behaviour).
_VAGUE_QUERY = "show me the holdings"


def test_form_submit_resumes_and_rechecks(jane_session):
    # 1. Drive the vague query → a clarification whose FORM rides the OOB trace lane.
    cid = bff_client.create_conversation(jane_session, "clarify form submit")
    ask = bff_client.send_message(jane_session, cid, _VAGUE_QUERY)
    evidence("vague ask (rm_jane) → clarification", {
        "conversation_id": cid,
        "http_status": ask.http_status,
        "answer_twin": ask.answer_text,
    })
    assert ask.http_status == 200, f"BFF turn failed: {ask.http_status}"

    request_id, events = trace_client.trace_for_conversation(cid)
    form = trace_client.structured_interaction(events)
    evidence("structured_interaction form on the trace lane", {"requestId": request_id, "form": form})
    assert form is not None, (
        "No structured_interaction FORM was emitted for a clarification — either "
        "conduit.clarify.structured-interaction.enabled is false, or the abstain-triage clarify "
        "regressed. Eval coverage would collapse to the text twin without it."
    )

    nonce = form.get("nonce")
    options = form.get("options") or []
    assert nonce, f"Form carries no nonce (not resumable): {form}"
    assert options, f"Form offered no options for an entitled RM — expected her in-book set: {form}"

    # The offered options are ONLY entitled candidates (the enumeration-oracle fix). rm_jane's book
    # includes Whitman (REL-00042); pick it if present, else the first offered option.
    values = [o.get("value") for o in options if o.get("value")]
    selection = config.WHITMAN_RELATIONSHIP_ID if config.WHITMAN_RELATIONSHIP_ID in values else values[0]

    # 2. SUBMIT the form by choosing an offered option (not re-typing text).
    resumed = bff_client.resolve_clarification(jane_session, cid, nonce, selection=selection)
    evidence("resumed answer after form submit", {
        "selection": selection,
        "http_status": resumed.http_status,
        "answer": resumed.answer_text,
    })

    # 3. The resume re-drove the full pipeline and returned a real answer — not the clarification again,
    #    and not a denial of the caller's OWN entitled client.
    assert resumed.http_status == 200, f"Resume failed: {resumed.http_status} / {resumed.raw_sse[:400]}"
    assert len(resumed.answer_text) > 20, f"Resumed answer suspiciously short: {resumed.answer_text!r}"
    lower = resumed.answer_text.lower()
    assert "not in your coverage" not in lower and "access denied" not in lower, (
        f"Resuming with her OWN entitled client was denied: {resumed.answer_text!r}"
    )
    assert "which client" not in lower, (
        f"Resume returned the clarification again instead of an answer: {resumed.answer_text!r}"
    )


def test_form_free_text_escape_is_untrusted(jane_session):
    """Submitting a free-text escape (not an offered option) still resumes; the free text is untrusted
    DATA — it re-drives the pipeline as a query and is never silently grounded to an entity."""
    cid = bff_client.create_conversation(jane_session, "clarify free-text escape")
    bff_client.send_message(jane_session, cid, _VAGUE_QUERY)
    _, events = trace_client.trace_for_conversation(cid)
    form = trace_client.structured_interaction(events)
    assert form is not None and form.get("nonce"), "No resumable form emitted for the vague ask."

    resumed = bff_client.resolve_clarification(
        jane_session, cid, form["nonce"], free_text=config.WHITMAN_NAME)
    evidence("resumed via free-text escape", {
        "free_text": config.WHITMAN_NAME,
        "http_status": resumed.http_status,
        "answer": resumed.answer_text,
    })
    # A named, entitled client typed as free text resolves + re-CHECKs like any reference → a real answer.
    assert resumed.http_status == 200, f"Free-text resume failed: {resumed.http_status}"
    assert len(resumed.answer_text) > 20, f"Resumed answer suspiciously short: {resumed.answer_text!r}"
