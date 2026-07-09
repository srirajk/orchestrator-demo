# Codex task T5 — grounding: deterministic number rendering (the engine can never misstate a figure)

> GATEWAY code — the load-bearing numbers a bank sees must come from DATA, not from an LLM's prose.
> **World-B applies**: what counts as a load-bearing figure, its label, and its format are DECLARED in
> manifests (DATA); the renderer + validator are generic (no domain literals, no hardcoded figure names in
> Java). Run `scripts/world-b-check.sh` before/after; CRITICAL 0. Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull — T2/T3/T4/T6/map
> committed). Stack: docker compose `orchestrator-demo`; BFF :8099. **If ambiguous, STOP and report.**

## Why (the last correctness gap in the engine)
The orchestrator composes perfectly, but the final answer is written by the synthesis LLM — **including the
load-bearing numbers.** So the LLM can, in principle, type a figure that isn't in the agent output, or
garble one (494.8% → 49.48%). Today we only catch this *after the fact* (the eval-layer grounding check).
For a bank that is not enough: a composer that can still misstate a number is not trustworthy. This task
makes load-bearing figures **come from the data deterministically**, so the model can't produce a number
that isn't grounded — while the LLM still writes the fluent connective prose.

Keep the existing eval-layer grounding check as the after-the-fact MONITOR — this task adds the UPFRONT
guarantee; the two are defense-in-depth.

## Design — declare figures, render from data, LLM writes prose around them, validate
1. **Declare load-bearing figures (manifest, DATA).** The producing agent's manifest declares the figures
   its output carries — e.g. on `io.produces[].figures: [{ label, path, format }]` where `path` is a JMESPath
   into that agent's output and `format` is a generic formatter (`percent`, `percent1`, `currency_usd`,
   `count`, `plain`, `date`…). Example (renewal_risk): `{label:"Claims loss ratio", path:"loss_ratio_pct",
   format:"percent1"}`, `{label:"Firm renewal target", path:"target_loss_ratio_pct", format:"percent1"}`.
   Add to the schema (all 3 copies) + `AgentManifest`; **boot-validate** the `path` against the producer's
   introspected output schema (reuse the T3 SelectContractValidator machinery — a figure path referencing a
   field the agent doesn't emit fails at load).
2. **Render deterministically (code).** After a node completes, the gateway renders each declared figure from
   the real output into a **grounded figure**: `{ label, rendered_value (formatted string), raw_value,
   source_agent }`. The number is produced by CODE from DATA — never by the LLM.
3. **LLM writes prose around the grounded figures, never typing a load-bearing digit.** Feed the synthesizer
   the grounded-figures set and instruct it to reference them **verbatim** (recommended mechanism: the LLM
   writes with **placeholders** for figures — e.g. `{{claims_loss_ratio}}` — and the gateway substitutes the
   code-rendered values; the model literally cannot type a wrong digit). Whatever mechanism you choose, the
   contract is: **the model does not author load-bearing numbers.**
4. **Validate (the teeth) — fail closed on an ungrounded figure.** A deterministic post-synthesis check:
   every load-bearing numeral in the final answer must trace to a grounded figure by **ATTRIBUTION** — the
   value AND its label align with a grounded figure (not mere presence). An ungrounded or mismatched
   load-bearing number → the answer is corrected (re-render) or the request fails honestly — **never ship an
   ungrounded figure to the user.**

## The GATE contract — ATTRIBUTION, not byte-identity (do not get this wrong)
Per DECISION-LOG D15: presence-checking is the mistake. The validator must assert **value ↔ label ↔ source**,
using the DECLARED `format` transforms so `0.314` vs `31.4%` and `1967000` vs `$1,967,000` are the SAME
figure (not a false-fail), while `HHI is 10.0%` using the *threshold* value where the *breach %* was meant is
a FAIL (right number, wrong label). Do NOT implement it as naked byte-identity (too strict AND too weak), and
do NOT weaken the checker's tolerance to make an answer pass — if a real answer fails, the rendering/label is
wrong, fix that.

## HARNESS (first-class — must include a FAULT-INJECTION proof)
Unit (JUnit, hermetic):
- the generic renderer formats each `format` correctly (percent/currency/count/date), from a sample output;
- boot-validation rejects a figure `path` that references a non-emitted field;
- **★ FAULT INJECTION (the anti-gaming teeth):** feed the validator a synthesized answer that contains a
  load-bearing number NOT in the grounded figures (a fabricated/garbled figure) → the validator MUST flag it.
  And a right-number-wrong-label case → flagged. And a correct-but-differently-formatted case (0.314 vs
  31.4%) → PASSES (no false-fail). These three cases prove the attribution contract has teeth and isn't
  presence-checking.
Live BFF:
- drive the 3 verticals; assert every declared load-bearing figure in the answer matches the agent output by
  attribution — the renewal 494.8% loss ratio, the concentration issuer %s + breach count, the CSDR $185k /
  2-day aging — each traced to its source agent's raw value;
- a fault-injection live check if feasible (coax the synthesizer toward a wrong number via a stubbed/edge
  output) → the validation catches it and the user never sees an ungrounded figure.

## GATE
- All unit checks pass INCLUDING the three fault-injection cases (fabricated → flagged, wrong-label →
  flagged, reformatted → passes).
- Live: the 3 verticals' load-bearing figures are all grounded-by-attribution; the answers read naturally
  (prose intact, not a robotic figure dump).
- Regression: conditionals + map + identity + coverage all still pass live; `mvn test` green; World-B 0; the
  eval-layer grounding monitor still runs.

## Constraints / anti-gaming
- Load-bearing numbers are CODE-RENDERED from DATA; the LLM authors prose, not figures.
- Figures/labels/formats are manifest-DECLARED; the renderer + validator are generic (World-B — no figure
  names or domain copy in gateway Java).
- The validator asserts ATTRIBUTION and FAILS CLOSED on an ungrounded load-bearing number. Do NOT weaken it
  to pass; the fault-injection test must actually catch a fabricated number. Keep the eval-layer monitor.
- Do NOT commit.

## Report
Files changed; the figures declaration + boot validation; the rendering mechanism + how the LLM is kept from
authoring numbers (placeholders/substitution); the attribution validator (how it handles format transforms
and label-alignment, how it fails closed); the harness — ESPECIALLY the fault-injection cases with evidence
they catch a fabricated and a wrong-label number and pass a reformatted one; the live 3-vertical attribution
evidence; mvn / World-B / regression results. STOP and report anything the spec didn't anticipate.
