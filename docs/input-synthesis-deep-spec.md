# Input Synthesis — Deep Spec (the one unproven piece)

*Everything else in the gateway is a known pattern. This is the part that turns a natural-
language prompt into each agent's exact input — and the part that, if shaky, makes the
whole demo look shaky. Build and test it in isolation before wiring the fan-out.*

---

## 1. What it has to do

After the resolver selects N agents, for each one it must produce a JSON object that
conforms to that agent's `input_schema`, populated from:
- the user's prompt,
- the conversation context (so "now show me *their* performance" works), and
- resolved entities (so "Whitman Family Office" becomes a real `relationship_id`).

That JSON is what the protocol adapter sends. Get it wrong and the right agent is called
with the wrong (or fabricated) inputs.

---

## 2. The naive approach — and why it's dangerous

The obvious design: hand the LLM the prompt + the agent's `input_schema` and say "fill it
in." Two failure modes make this unacceptable for a bank:

1. **Identifier fabrication.** Asked to produce `relationship_id` for a client it can't
   know, an LLM will *invent a plausible-looking value*. Calling a custody agent with a
   fabricated account ID is a data-integrity incident, not a glitch.
2. **Silent wrong-field binding.** Free-text "fill the schema" gives you no guarantee the
   model didn't put the period in the wrong field or coerce a type.

So the rule: **the LLM may extract human references; it may never produce system
identifiers.**

---

## 3. The reliable design: Extract → Resolve → Bind

Three stages, with the dangerous capability (turning a reference into an ID) taken away
from the LLM entirely.

### Step 1 — Extract (LLM, structured, once per request)
One LLM call, using **tool-calling / structured output** (never free-text parsing),
extracts a typed **entity bag** from prompt + context. The extraction tool's schema is the
**union** of fields any selected agent needs. The model extracts only what's present;
anything absent is `null`. It extracts *references verbatim* — it is explicitly instructed
**not** to guess or normalize identifiers.

```jsonc
// Extracted entity bag for the hero prompt:
{
  "relationship_reference": "Whitman Family Office",   // verbatim, NOT an id
  "period": "QTD",                                      // inferred default if absent
  "ticker_references": [],
  "fund_reference": null
}
```

One batched call (not one per agent) keeps the hot-path latency to a single LLM round-trip
regardless of fan-out width — important for time-to-first-token.

### Step 2 — Resolve (deterministic, NO LLM)
A directory/resolver service maps human references to system identifiers:
`"Whitman Family Office"` → `relationship_id: "REL-00042"`. For the PoC this is a small
lookup table; in production it's a real entity-resolution service.

This is the safety keystone: **if a reference doesn't resolve, the pipeline does not
fabricate — it treats the field as unresolved and asks** (Step 3). The LLM's invented value
can never reach an agent because the LLM never produced the ID in the first place.

### Step 3 — Bind & validate (deterministic)
For each selected agent, build its input object by pulling the fields it needs from the
resolved bag, then **validate against that agent's `input_schema`**. Outcomes per agent:
- All required fields present and valid → **invoke**.
- A required field unresolved/missing → **drop that agent** or **raise a clarification**
  (see §5), depending on whether the field is essential to the user's intent.

```jsonc
// Bound, validated inputs ready for the adapters:
{
  "meridian.wealth.holdings":            { "relationship_id": "REL-00042" },
  "meridian.wealth.performance":         { "relationship_id": "REL-00042", "period": "QTD" },
  "meridian.servicing.settlement_status":{ "relationship_id": "REL-00042" }
  // ...
}
```

---

## 4. Interface and where it sits

Lives in resolver **Stage C**, between agent selection and adapter invocation.

```java
interface InputSynthesizer {
  SynthesisResult synthesize(String prompt,
                             ConversationContext ctx,
                             List<AgentDefinition> selected);
}

record SynthesisResult(
  Map<String, JsonNode> inputs,          // agent_id -> validated input
  List<String> droppedAgents,            // selected but un-resolvable
  Optional<ClarificationRequest> clarify // if essential info is missing
) {}
```

---

## 5. Reliability mechanisms

- **Structured output / tool-calling** — force schema-valid JSON; never regex free text.
- **Validate + one bounded retry** — if extraction output fails its schema, retry once with
  the validation error fed back; then give up gracefully.
- **Never-fabricate identifiers** — enforced structurally by the Extract/Resolve split, not
  by trusting a prompt instruction.
- **Missing-field policy** — sensible defaults where safe (`period` → "QTD"); otherwise
  clarify. Defaults are declared, not improvised.
- **Context reuse** — resolve "Whitman Family Office" once, cache `REL-00042` against the
  `conversation_id`, so follow-ups ("now their risk profile") reuse it without re-asking.
  *This is the one place conversation context genuinely feeds synthesis.*
- **Clarify, don't guess** — unresolved or ambiguous reference → the resolver's fallback
  path asks ("Which Smith account — the trust or the foundation?") rather than picking.

---

## 6. Failure modes — and the isolation test harness

Build a standalone fixture suite (`prompt → expected entity bag → expected per-agent
inputs`) and run it against the synthesizer **before** it's wired into the fan-out. Cover:

| Case | Prompt | Expected behaviour |
|---|---|---|
| Happy path | "...Whitman Family Office..." | resolves → all agents bound |
| No reference, no context | "Show me holdings" | clarify ("Which relationship?") |
| Ambiguous reference | "the Smith account" (3 matches) | disambiguate |
| Reference from prior turn | "now their risk profile" | reuse cached id from context |
| Missing optional field | no period given | default to QTD |
| Fabrication attempt | unknown client name | unresolved → clarify; **never** an invented id reaches an agent |
| Paraphrase robustness | varied phrasings | same extraction |

The pass bar: high extraction accuracy **and zero fabricated identifiers ever emitted**.
The second metric is non-negotiable for a bank.

---

## 7. What this buys the demo

For this demo specifically, synthesis is: extract "Whitman Family Office" → resolve to a
`relationship_id` → bind it to every selected agent's input. Clean, and it's a *visible*
win: the **glass-box panel should show the extracted entity and the resolved ID**, so the
"no glue code — the gateway figured out the fields itself" claim isn't asserted, it's on
screen. That on-screen moment is a quiet but powerful proof point for a technical buyer.

---

## 8. Sequencing

This is **M4-critical and the first thing to build in M4**, ahead of the fan-out wiring.
It is the highest-leverage de-risking move in the whole project: prove synthesis in
isolation against the fixture suite, *then* connect the adapters. If synthesis is solid,
the rest of M4 is plumbing.

---

## Still un-dug (the other remaining pieces, in priority order)

1. **Harness & Telemetry spec** — your two headline selling points, deserving their own
   deep spec (pipeline stages + Resilience4j config; OTel span model + signal list +
   glass-box event schema + the "observability plane" framing).
2. **SSE contract** — the exact OpenAI chunk format the gateway must emit, with the
   title-call short-circuit, so LibreChat renders cleanly on the first try.
3. **Routing confidence** — the Stage-A confidence floor, multi-agent threshold, and the
   fallback/clarify posture (couples tightly to synthesis §5).
