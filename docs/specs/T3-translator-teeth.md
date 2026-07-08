# Codex task T3 — the translator's TEETH: real output-schema introspection + boot-time select validation

> This is GATEWAY code (not a seed/config fix) — **World-B applies**: no domain names, client names,
> entity-type literals, or ID patterns in Java. Schemas and selects are DATA (manifests); the validator
> is generic. Run `scripts/world-b-check.sh` before AND after and report the CRITICAL count (must stay 0).
> Do NOT commit (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch
> `feat/conduit-chat` (pull latest — coverage fix is committed). Stack: docker compose `orchestrator-demo`.
> **This is the hardest task so far — read the whole spec before touching code. If any step is ambiguous,
> STOP and report rather than guess.**

## Why (the exact gap — verified by two architecture reviews)
The translator = each edge's declared JMESPath `select`, which reshapes a producer's output into the next
agent's input. It WORKS and fails safe at runtime. But its validation is **half-hollow**: today it can
only validate the **consumer's INPUT** schema, never the **producer's OUTPUT** schema — because output-schema
introspection is a **STUB for BOTH protocols**. Consequence: a `select` that references a field the producer
**never emits** is only caught at request time by the fail-safe (node silently not dispatched), never proven
wrong up front, and never at boot. This task gives the translator real teeth: **derive real output schemas
for both protocols, and validate every `select` against its producer's output schema at registry load, so a
broken mapping fails at BOOT with a precise error — not as a silent runtime degrade.**

NOTE ON SCOPE: this task does NOT build `io.produces[].entities` or per-producer coverage — that's T4
(deferred). T3 is purely the translator's output-schema validation. Stay in that lane.

## Files (confirm exact lines — they may have shifted since review)
- `gateway/src/main/java/ai/conduit/gateway/registry/introspection/AgentIntrospector.java`
  - HTTP output stub: `buildOutputSchemaFromResponse(...)` (~175-183) sets only `type`+`description` — a stub.
  - MCP output stub: (~209) `outputSchema = mapper.createObjectNode()` — empty "best-effort".
  - The INPUT side already resolves OpenAPI `requestBody` + `$ref` against `components/schemas` and merges
    query/path params — **mirror that resolution logic for the response body.**
- `gateway/src/main/java/ai/conduit/gateway/orchestration/executor/InputContractValidator.java`
  — the EXISTING generic validator (input-vs-schema). **Reuse it; do not fork it.**
- `gateway/src/main/java/ai/conduit/gateway/orchestration/executor/Blackboard.java`
  — `checkComposable(...)` (~210, runtime validation) and `isIncomplete(...)` (~218-223, hardcoded
  `_complete`/`truncated`). **Do NOT remove the runtime fail-safe** — it stays as the backstop.
- Registry bootstrap: `AgentRegistry` / `RegistryBootstrapLoader` (find where manifests are loaded, introspected,
  and stamped) — this is where boot-time validation hooks in.
- `registry/agent-manifest.schema.json` (+ the gateway resource copy under `gateway/src/main/resources/` +
  any root copy — keep all in sync) — for the optional `output_schema` fallback field.
- JMESPath lib is already a dependency: `io.burt:jmespath-jackson` (used by the Blackboard's `select`).

## Part 1 — Real HTTP output-schema introspection
In `buildOutputSchemaFromResponse`: for the agent's `operation_id`, read the OpenAPI operation's **2xx
response** `content."application/json".schema`, and **resolve `$ref`** against `components/schemas`
recursively (reuse the exact `$ref`-resolution helper the input/requestBody path already uses). Produce a
real JSON Schema (properties, required, nested objects/arrays) for the producer's output — not a stub.

## Part 2 — Real MCP output-schema introspection
FastMCP `tools/list` can return an `outputSchema` per tool (structured output). Read it from the tool
definition and use it as the producer's output schema. If a specific tool genuinely declares none, fall
back to a manifest-declared `output_schema` (Part 3). **Never leave it silently empty when a `select`
depends on it** — that empty object is the exact hole this task closes.

## Part 3 — Boot-time `select` validation (THE TEETH) + `output_schema` fallback
Add an OPTIONAL `output_schema` (a JSON-Schema object) to the agent-manifest schema — the documented escape
hatch used ONLY when neither protocol can introspect an output (validate the schema files stay consistent).

At **registry load / bootstrap**, for every agent `A` and every `A.io.consumes[]` entry that has BOTH a
`from` and a `select`, run this algorithm:

1. Resolve the producer `P` = the agent whose `io.produces[].type` equals this consume's `from`. (If none /
   ambiguous, that's a separate pre-existing io-graph error — report it, don't crash.)
2. Obtain `P`'s **output schema** (introspected via Part 1/2, else `P`'s manifest `output_schema`).
3. **Build a synthetic sample instance** from `P`'s output schema: recurse the schema — for an `object`, emit
   each declared property; for an `array`, emit exactly one element of the item schema; for a scalar, emit a
   type-appropriate placeholder (string→"x", number→1, boolean→true); honor `required` (always include
   required properties). This yields a representative JSON document shaped like `P`'s real output.
4. **Apply the consume's JMESPath `select`** to that synthetic sample (via `io.burt:jmespath-jackson`).
5. **Validate the projected result against `A`'s introspected INPUT schema** using the EXISTING
   `InputContractValidator` (the same code path runtime uses — you are just running it at boot against a
   schema-derived sample instead of live data).
6. **Decision:**
   - If validation passes → the edge's `select` is provably shape-compatible. OK.
   - If it FAILS (a required consumer field is missing/null because the `select` referenced a field `P`'s
     schema does not contain) → **REJECT at boot** with a precise, actionable error: `agentId`, the offending
     `from`/`select`, and the specific missing field. Fail that manifest's load (mark it failed / abort
     bootstrap per the loader's existing failure convention) — it must NOT boot silently.
   - **HONEST DEGRADATION (no silent pass):** if `P`'s output schema is genuinely unavailable (no
     introspection AND no manifest `output_schema`), do NOT pass silently and do NOT fail hard — log a
     clear WARNING naming the unvalidated edge, and emit a boot summary line: `select validation: X validated,
     Y UNVALIDATED (no output schema)`. After Parts 1-2, Y should be ~0; any residual must be visible, not hidden.

Runtime `checkComposable` **stays** as the backstop. Boot-time is the new upfront gate; runtime is defense in depth.

## Part 4 (secondary — only if clean) — completeness as a declared contract
Promote the hardcoded `_complete`/`truncated` keys out of `Blackboard.isIncomplete` into the documented output
contract (a convention in the schema/docs, read generically). Lower priority; skip if it risks Parts 1-3.

## GATE — prove it, and it must ACTUALLY reject (anti-gaming)
1. **BOOT-REJECT TEST (the core proof):** introduce a manifest/fixture whose `select` references a field the
   producer's output schema does NOT contain → registry load **rejects it** with the precise error. This is
   THE proof the teeth work. **Do NOT weaken/disable the validator to make anything pass** — if a *real*
   vertical's `select` turns out invalid, fix the SELECT, not the validator, and report that you found a real
   bug.
2. **GOOD PATH:** the three real verticals' selects (concentration, renewal, settlement) all PASS boot
   validation; boot log shows `16 loaded, 0 failed`.
3. **INTROSPECTION IS REAL:** log/dump the derived output schema for ONE HTTP producer and ONE MCP producer —
   both must be real, populated schemas (paste them in the report), not empty objects.
4. **RUNTIME BACKSTOP INTACT:** a deliberately-broken runtime input is still not dispatched (checkComposable
   unchanged in behavior).
5. `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0 (before AND after); the three
   verticals still answer live via the BFF.
6. **HONEST COUNT:** report edges validated at boot vs unvalidated — zero silent passes.

## Constraints
- World-B: the validator and introspection are GENERIC — no domain/client/entity/ID literals in Java. Keep
  the schema copies in sync. Run world-b-check before/after; report the CRITICAL count.
- Do NOT commit. Do NOT remove the runtime fail-safe. Do NOT weaken validation to pass. Do NOT build the
  `io.produces[].entities`/coverage mechanism (that's T4).

## Report (be specific)
Files changed; exactly how HTTP and MCP output schemas are now derived (paste one real example of each); the
boot-time validation algorithm as implemented (esp. the synthetic-instance builder and that it reuses
`InputContractValidator`); the BOOT-REJECT test with the actual rejection error; the validated-vs-unvalidated
edge count; and mvn / world-b / live-vertical results. If you hit anything the spec didn't anticipate, STOP
and report it rather than improvising.
