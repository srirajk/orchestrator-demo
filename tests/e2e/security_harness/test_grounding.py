"""
Grounding / no-fabrication — CLAUDE.md §4c: agent outputs are the only ground truth; the
model summarizes, it never computes, recalls, or invents numbers. This is the harness's
sharpest tool for catching the "15%-vs-10% wobble": the LLM restating the firm's
single-name concentration threshold (10.0% per registry/domains policy) as some other
number it half-remembers from training data or a nearby sentence, instead of the number
actually in the DATA.

test_grounding_no_fabrication is automated end to end: it drives a live concentration
question, independently re-derives ground truth by replaying the same holdings ->
concentration call chain the DAG made (see lib/ground_truth.py), and flags any percentage
or HHI figure in the answer that isn't within rounding tolerance of something the
concentration agent actually returned.

The other two adversarial experiments described in the task (2nd holdings producer ->
flat concentration; renamed output key -> wrong-number/422 path) require mutating the
registry manifest or the agent's response shape while the stack is running — deliberately
NOT automated here (this harness must not touch gateway/agent config as a side effect of
being run). See MANUAL_EXPERIMENTS.md-equivalent docstring at the bottom of this file for
the exact operator steps.
"""
from __future__ import annotations
import re

from lib import bff_client, config, ground_truth, trace_client
from lib.evidence import evidence

_PERCENT_RE = re.compile(r"(\d+(?:\.\d+)?)\s*%")
_HHI_RE = re.compile(r"HHI[^0-9]{0,20}(\d+(?:\.\d+)?)", re.IGNORECASE)

# Rounding tolerance: generous enough for "25.4194%" -> "25%"/"25.4%", tight enough that a
# threshold wobble (e.g. quoting 10% policy as 15%) still trips it.
_PCT_TOLERANCE = 0.75
_FALLBACK_PREFIX = "i could not safely validate the generated prose"


def _closest_distance(value: float, candidates: set[float]) -> float | None:
    if not candidates:
        return None
    return min(abs(value - c) for c in candidates)


def _turn_events(turn):
    request_id, events = trace_client.trace_for_conversation(turn.conversation_id)
    assert request_id is not None, "No trace found for this conversation."
    return request_id, events


def _grounded_figures(events, source_agent: str) -> list[dict]:
    figure_events = trace_client.events_of_type(events, "grounded_figures")
    assert figure_events, f"No grounded_figures trace event emitted: {events}"
    figures = []
    for evt in figure_events:
        figures.extend(evt.get("data", {}).get("figures") or [])
    source_figures = [f for f in figures if f.get("sourceAgent") == source_agent]
    assert source_figures, f"No grounded figures traced for {source_agent}: {figures}"
    return source_figures


def _figures_by_label(figures: list[dict]) -> dict[str, dict]:
    return {f["label"]: f for f in figures}


def _assert_answer_uses_figures(answer: str, figures: list[dict]) -> None:
    lower = answer.lower()
    assert _FALLBACK_PREFIX not in lower, f"Answer fell back to robotic figure dump: {answer!r}"
    for fig in figures:
        rendered = fig["renderedValue"]
        assert rendered in answer, (
            f"Answer did not include code-rendered figure {fig['label']!r}={rendered!r}. "
            f"Answer: {answer!r}"
        )


def test_grounding_no_fabrication(jane_session):
    turn = bff_client.ask(jane_session, f"Is the {config.WHITMAN_NAME} over-concentrated?")
    assert turn.http_status == 200, f"live turn failed: {turn.http_status}"
    assert len(turn.answer_text) > 20, f"Answer suspiciously short: {turn.answer_text!r}"

    truth = ground_truth.fetch_concentration_ground_truth()
    pct_truth = ground_truth.grounded_percentages(truth["concentration"])
    hhi_truth = ground_truth.grounded_hhi(truth["concentration"])

    found_pcts = [float(m) for m in _PERCENT_RE.findall(turn.answer_text)]
    found_hhis = [float(m) for m in _HHI_RE.findall(turn.answer_text)]

    ungrounded_pcts = []
    for v in found_pcts:
        d = _closest_distance(v, pct_truth)
        if d is None or d > _PCT_TOLERANCE:
            ungrounded_pcts.append({"value": v, "closest_ground_truth_distance": d})

    ungrounded_hhis = []
    for v in found_hhis:
        d = _closest_distance(v, hhi_truth)
        if d is None or d > _PCT_TOLERANCE:
            ungrounded_hhis.append({"value": v, "closest_ground_truth_distance": d})

    evidence("grounding check", {
        "answer": turn.answer_text,
        "answer_percentages_found": found_pcts,
        "answer_hhi_mentions_found": found_hhis,
        "ground_truth_percentages": sorted(pct_truth),
        "ground_truth_hhi_values": sorted(hhi_truth),
        "ungrounded_percentages": ungrounded_pcts,
        "ungrounded_hhi_mentions": ungrounded_hhis,
        "concentration_ground_truth_payload": truth["concentration"],
    })

    assert not ungrounded_pcts, (
        f"Answer states percentage(s) not present in the concentration agent's actual "
        f"output (within {_PCT_TOLERANCE}pp tolerance) — possible fabrication/threshold-"
        f"wobble: {ungrounded_pcts}. Ground truth percentages were: {sorted(pct_truth)}. "
        f"Full answer: {turn.answer_text!r}"
    )
    assert not ungrounded_hhis, (
        f"Answer states an HHI figure not present in the concentration agent's actual "
        f"output: {ungrounded_hhis}. Ground truth HHI values: {sorted(hhi_truth)}. "
        f"Full answer: {turn.answer_text!r}"
    )


def test_t5_grounded_figures_wealth_live(jane_session):
    turn = bff_client.ask(jane_session, f"Is the {config.WHITMAN_NAME} over-concentrated?")
    assert turn.http_status == 200, f"live turn failed: {turn.http_status}"
    _, events = _turn_events(turn)

    figures = _grounded_figures(events, "meridian.wealth.concentration")
    labels = _figures_by_label(figures)
    assert labels["Top single-name concentration"]["renderedValue"].endswith("%")
    assert labels["Concentration breach count"]["renderedValue"].isdigit()
    assert "Diversification HHI" in labels
    assert "Single-name threshold" in labels
    _assert_answer_uses_figures(turn.answer_text, figures)


def test_t5_grounded_figures_insurance_live(sam_session):
    turn = bff_client.ask(
        sam_session,
        f"Should we reprice the {config.CONTINENTAL_FREIGHT_NAME} policy at renewal given the open claims?",
    )
    assert turn.http_status == 200, f"live turn failed: {turn.http_status}"
    _, events = _turn_events(turn)

    figures = _grounded_figures(events, "meridian.insurance.renewal_risk")
    labels = _figures_by_label(figures)
    assert labels["Claims loss ratio"]["renderedValue"] == "494.8%"
    assert labels["Firm renewal target"]["renderedValue"].endswith("%")
    assert labels["Incurred losses"]["renderedValue"].startswith("$")
    assert labels["Renewal breach count"]["renderedValue"].isdigit()
    _assert_answer_uses_figures(turn.answer_text, figures)


def test_t5_grounded_figures_servicing_live(admin_session):
    turn = bff_client.ask(admin_session, "What is the settlement risk for REL-00188 (Okafor)?")
    assert turn.http_status == 200, f"live turn failed: {turn.http_status}"
    _, events = _turn_events(turn)

    figures = _grounded_figures(events, "meridian.servicing.settlement_risk")
    labels = _figures_by_label(figures)
    assert labels["CSDR penalty failed settlement amount"]["renderedValue"] == "$185,000.00"
    assert labels["Max failed age"]["renderedValue"] == "2"
    assert labels["Settlement breach count"]["renderedValue"].isdigit()
    assert labels["Failed exposure to settled cash"]["renderedValue"].endswith("%")
    assert labels["Failed exposure to custody market value"]["renderedValue"].endswith("%")
    _assert_answer_uses_figures(turn.answer_text, figures)


# ── Manual / scripted-toggle experiments (NOT run automatically) ──────────────────────
#
# These require mutating the registry (a manifest edit) or an agent's live response shape
# — out of scope for an automated harness that must not leave the stack in a different
# state than it found it. Documented here as the exact operator steps referenced by
# CLAUDE.md's world-b workflow (registry/domains + registry/manifests are config, not code).
#
# Experiment 1 — second wealth.holdings producer -> concentration query should go FLAT
#   (i.e. the gateway should refuse to silently pick one, per CLAUDE.md's zero-fabrication
#   posture, and the concentration step should not run against an ambiguous input):
#     1. Copy registry/manifests/wealth-management/meridian.wealth.holdings.json to
#        e.g. meridian.wealth.holdings-2.json, change "agent_id" to
#        "meridian.wealth.holdings-2" and point connection.openapi_url at a second wealth-http
#        instance (or the same one — the point is two producers of the same output key).
#     2. Restart the gateway (or trigger a registry reload if supported) so both manifests
#        are loaded.
#     3. Re-run: python3 -m pytest tests/e2e/security_harness/test_positive_path.py::test_multistep_concentration -v -s
#     4. Expected (per CLAUDE.md's zero-fabrication invariant): the plan_graph should NOT
#        silently wire concentration to one of the two ambiguous holdings producers, and/or
#        the turn should degrade to a flat single-step answer / clarification rather than
#        pick one arbitrarily. Revert the manifest copy afterward.
#
# Experiment 2 — holdings given a DIFFERENT output key -> wrong-number/422 path:
#     1. Temporarily edit mock-agents/wealth/holdings/handler.py (or the response model) so
#        the endpoint returns e.g. "total_portfolio_value" instead of "total_value" (the
#        key the concentration agent's HoldingsPayload / synthesis prompt expects).
#     2. Rebuild + restart wealth-http: `docker compose -p orchestrator-demo up -d --build wealth-http`
#     3. Re-run test_multistep_concentration.
#     4. Expected: either a clean 422 from the concentration agent (schema mismatch caught),
#        or — the failure mode to watch for — the synthesizer fabricates/misreads a number
#        because the key it expected is silently absent. A passing harness run here should
#        show a clean failure (agent_complete status="failed" in the trace, or a degraded-
#        but-honest answer), never a confidently wrong number.
#     5. Revert the handler change and rebuild wealth-http again before continuing other work.
