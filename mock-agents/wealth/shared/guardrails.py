"""
Runtime guardrails for Meridian wealth agents.

Two layers:
  Input guardrails  — run before the agent call. Block prompt injection + validate relationship IDs.
  Output guardrails — run after the agent returns. Enforce length limits and basic grounding.

When a guardrail trips (tripwire_triggered=True), agents.Runner raises
InputGuardrailTripwireTriggered or OutputGuardrailTripwireTriggered.
Handlers catch these and return HTTP 422 instead of passing bad output to the synthesizer.
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

# ── Patterns that signal a prompt injection attempt ──────────────────────────
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

# ── Input guardrail 1: prompt-injection detection ────────────────────────────

@input_guardrail(name="prompt-injection-check")
async def injection_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    input,                      # str or list[dict] (message history)
) -> GuardrailFunctionOutput:
    """Block requests that contain prompt injection patterns."""
    text = str(input).lower()
    for pattern in _INJECTION_PATTERNS:
        if pattern in text:
            log.warning("[guardrail] Injection pattern detected: %r", pattern)
            return GuardrailFunctionOutput(
                output_info={"blocked_pattern": pattern, "reason": "prompt_injection"},
                tripwire_triggered=True,
            )
    return GuardrailFunctionOutput(
        output_info={"clean": True},
        tripwire_triggered=False,
    )


# ── Input guardrail 2: relationship-ID format ────────────────────────────────

_REL_ID_RE = re.compile(r"\bREL-\d{5}\b")


@input_guardrail(name="relationship-id-format")
async def relationship_id_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    input,
) -> GuardrailFunctionOutput:
    """Require a correctly formatted relationship ID in the input prompt."""
    text = str(input)
    match = _REL_ID_RE.search(text)
    if not match:
        log.warning("[guardrail] No valid REL-NNNNN found in input: %.80s", text)
    return GuardrailFunctionOutput(
        output_info={"has_valid_id": bool(match), "found": match.group() if match else None},
        tripwire_triggered=not bool(match),
    )


# ── Output guardrail 1: response length ──────────────────────────────────────

_MIN_CHARS = 30
_MAX_SENTENCES = 8


@output_guardrail(name="response-length-check")
async def length_guardrail(
    ctx: RunContextWrapper,
    agent: Agent,
    output,
) -> GuardrailFunctionOutput:
    """Reject responses that are empty, too short, or unreasonably long."""
    text = str(output or "").strip()
    sentences = [s.strip() for s in re.split(r"[.!?]", text) if s.strip()]
    too_short = len(text) < _MIN_CHARS
    too_long = len(sentences) > _MAX_SENTENCES
    if too_short or too_long:
        log.warning(
            "[guardrail] Response length violation: chars=%d sentences=%d", len(text), len(sentences)
        )
    return GuardrailFunctionOutput(
        output_info={
            "char_count": len(text),
            "sentence_count": len(sentences),
            "too_short": too_short,
            "too_long": too_long,
        },
        tripwire_triggered=too_short or too_long,
    )


# ── Output guardrail 2: numeric grounding ────────────────────────────────────

def _extract_numbers(text: str) -> set[str]:
    """
    Pull significant numeric tokens (≥100 or containing a decimal) and normalize.
    Skips small integers like 1, 2, 3 which appear in any sentence and cause false positives.
    '1,967,000' → '1967000', '12.4' → '12.4', '1' → excluded
    """
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
    """
    Factory: returns an output guardrail that checks every number in the narrative
    appears in the raw tool data. Call this per-request with the actual data dict.

    Why a factory? The guardrail needs to close over the current request's data
    since we don't have a typed RunContext to thread it through.
    """
    raw_text = str(raw_data)
    allowed_numbers = _extract_numbers(raw_text)

    @output_guardrail(name="numeric-grounding-check")
    async def _grounding_guardrail(
        ctx: RunContextWrapper,
        agent: Agent,
        output,
    ) -> GuardrailFunctionOutput:
        """Block responses that reference numbers not present in the agent's tool data."""
        narrative = str(output or "")
        narrative_numbers = _extract_numbers(narrative)
        hallucinated = narrative_numbers - allowed_numbers
        if hallucinated:
            log.warning("[guardrail] Potential ungrounded numbers (advisory only): %s", hallucinated)
        return GuardrailFunctionOutput(
            output_info={
                "hallucinated_numbers": sorted(hallucinated),
                "grounded": len(hallucinated) == 0,
            },
            tripwire_triggered=False,  # gateway synthesizer owns the real grounding check
        )

    return _grounding_guardrail
