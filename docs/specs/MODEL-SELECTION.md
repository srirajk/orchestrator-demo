# Gateway LLM model selection (locked 2026-07-11)

Per-call-site model choice for the gateway's OpenAI-compatible call sites. All config via
`CONDUIT_LLM_*` / `JUDGE_*` env (provider/model swappable per call site — no code). Pricing/latency as of
July 2026; verify live and tune per call site off Langfuse cost/latency once real traffic flows.

## Locked map
| Call site | Model | In/Out ($/1M) | Selection criteria |
|---|---|---|---|
| IntentClassifier | **gpt-4.1-nano** | 0.10 / 0.40 | fires every request (hot path); trivial classify; cheapest + fastest TTFT; structured output |
| EntityExtractor | **gpt-4.1-mini** | 0.40 / 1.60 | every request; extraction ACCURACY matters (grounding/masking depend on it) → mini not nano |
| Routing re-ranker | **gpt-5-mini** | 0.25 / 2.00 | fires on near-ties; needs capability-vs-entity discrimination reasoning; still fast |
| AnswerSynthesizer | **gpt-5-mini** | 0.25 / 2.00 | every answer, streaming; grounding faithfulness + low hallucination + good TTFT (→ gpt-4.1 if max quality wanted) |
| Clarification | **gpt-4.1-nano** | 0.10 / 0.40 | short generated question; trivial (glm-4.5-flash also acceptable) |
| LLM-as-judge (eval / World-B) — OFFLINE | **o4-mini** | 1.10 / 4.40 | reasoning quality; latency-insensitive; NEVER on the request path |

## Selection principles
- **Off the 4o family.** gpt-4o-mini is superseded — gpt-4.1-mini is ~½ latency / ~83% cheaper and more
  reliable at structured output. gpt-4o is expensive+old.
- **Cost is a MIX, not one model.** Cheapest is 4.1-nano ($0.10/$0.40); gpt-5-mini ($0.25/$2) is NOT cheaper
  than 4o-mini per token — put nano on the high-VOLUME hot path (intent), 5-mini only on the QUALITY driver
  (synthesizer). Volume drivers = intent+extract (every request); token driver = synthesizer.
- **Prompt caching is the biggest lever.** System prompts are compiled-from-manifest and largely static →
  gpt-4.1 cached input is 75% off ($0.50 vs $2.00). Applies on the repeating hot path.
- **Reasoning (o-series) OFF the request path** — only for the offline LLM-as-judge.
- **Load testing does NOT use these.** Load runs against the stub-LLM (#31) — real OpenAI is never hit for
  load (it burned quota before). So this map optimizes REAL/demo traffic latency+cost+quality only; it has
  zero bearing on throughput/load tests.
- **Tune empirically.** Langfuse tracks per-call-site cost/latency — A/B candidates on real traffic and adjust.
