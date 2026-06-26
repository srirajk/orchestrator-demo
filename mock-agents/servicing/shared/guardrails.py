"""
Runtime guardrails for Meridian servicing agents.

Same two-layer pattern as wealth:
  Input guardrails  — prompt injection + entity ID format
  Output guardrails — length + numeric grounding

For NAV tool the entity is fund_id (FND-NNNN), not relationship_id (REL-NNNNN).
The agent's tool function validates the actual lookup; guardrails block malformed input early.
"""
import re
import logging
from agents import (
    input_guardrail,
    output_guardrail,
    GuardrailFunctionOutput,
    RunContextWrapper,
    Agent,
)

log = logging.getLogger(__name__)

_INJECTION_PATTERNS = [
    "ignore previous",
    "ignore above",
    "disregard all",
    "forget your instructions",
    "you are now",
    "new instructions:",
    "system:",
    "act as",
    "override",
    "jailbreak",
]

_MIN_CHARS = 30
_MAX_SENTENCES = 8


# ── Input guardrail 1: prompt-injection detection ────────────────────────────

@input_guardrail(name="prompt-injection-check")
async def injection_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    input,
) -> GuardrailFunctionOutput:
    text = str(input).lower()
    for pattern in _INJECTION_PATTERNS:
        if pattern in text:
            log.warning("[guardrail] Injection detected: %r", pattern)
            return GuardrailFunctionOutput(
                output_info={"blocked_pattern": pattern, "reason": "prompt_injection"},
                tripwire_triggered=True,
            )
    return GuardrailFunctionOutput(output_info={"clean": True}, tripwire_triggered=False)


# ── Input guardrail 2a: relationship-ID format (settlements/corporate/custody/cash) ──

_REL_ID_RE = re.compile(r"\bREL-\d{5}\b")


@input_guardrail(name="relationship-id-format")
async def relationship_id_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    input,
) -> GuardrailFunctionOutput:
    text = str(input)
    match = _REL_ID_RE.search(text)
    if not match:
        log.warning("[guardrail] Missing valid REL-NNNNN in: %.80s", text)
    return GuardrailFunctionOutput(
        output_info={"has_valid_id": bool(match), "found": match.group() if match else None},
        tripwire_triggered=not bool(match),
    )


# ── Input guardrail 2b: fund-ID format (NAV tool) ────────────────────────────

_FUND_ID_RE = re.compile(r"\bFND-\d{4}\b")


@input_guardrail(name="fund-id-format")
async def fund_id_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    input,
) -> GuardrailFunctionOutput:
    text = str(input)
    match = _FUND_ID_RE.search(text)
    if not match:
        log.warning("[guardrail] Missing valid FND-NNNN in: %.80s", text)
    return GuardrailFunctionOutput(
        output_info={"has_valid_fund": bool(match), "found": match.group() if match else None},
        tripwire_triggered=not bool(match),
    )


# ── Output guardrail: response length ────────────────────────────────────────

@output_guardrail(name="response-length-check")
async def length_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    output,
) -> GuardrailFunctionOutput:
    text = str(output or "").strip()
    sentences = [s.strip() for s in re.split(r"[.!?]", text) if s.strip()]
    too_short = len(text) < _MIN_CHARS
    too_long = len(sentences) > _MAX_SENTENCES
    return GuardrailFunctionOutput(
        output_info={
            "char_count": len(text),
            "sentence_count": len(sentences),
            "too_short": too_short,
            "too_long": too_long,
        },
        tripwire_triggered=too_short or too_long,
    )


# ── Output guardrail factory: numeric grounding ───────────────────────────────

def _extract_numbers(text: str) -> set[str]:
    """Skip small integers (< 100) to avoid false positives on counts like '1 pending settlement'."""
    raw = re.findall(r"\b\d[\d,]*(?:\.\d+)?\b", text)
    result = set()
    for n in raw:
        normalized = n.replace(",", "")
        try:
            if "." in normalized or float(normalized) >= 100:
                result.add(normalized)
        except ValueError:
            pass
    return result


def make_grounding_guardrail(raw_data: dict):
    """Return an output guardrail bound to the current request's data."""
    raw_text = str(raw_data)
    allowed = _extract_numbers(raw_text)

    @output_guardrail(name="numeric-grounding-check")
    async def _guardrail(
        ctx: RunContextWrapper,
        agent: Agent,
        output,
    ) -> GuardrailFunctionOutput:
        narrative = str(output or "")
        hallucinated = _extract_numbers(narrative) - allowed
        if hallucinated:
            log.warning("[guardrail] Potential ungrounded numbers (advisory only): %s", hallucinated)
        return GuardrailFunctionOutput(
            output_info={
                "hallucinated_numbers": sorted(hallucinated),
                "grounded": not hallucinated,
            },
            tripwire_triggered=False,  # gateway synthesizer owns the real grounding check
        )

    return _guardrail
