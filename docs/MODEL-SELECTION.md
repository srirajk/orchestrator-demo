# Model Selection Guide — Meridian AI Gateway

> **Status:** Recommended configuration, 2026-06-29.
> **Principle:** No single model for everything. Each LLM call site is matched to a **tier**
> by its real constraints — latency budget, request volume, whether it blocks, and whether a
> human reads the output. Every call site is **independently model- and provider-swappable
> via config** (no code change), so a customer can dial cost/latency/quality per task.

---

## The tiers

| Tier | Use when | Optimize for | Default (GLM family) |
|---|---|---|---|
| **Fast** | High volume, on the latency-critical path, bounded task (classify/extract), or async non-blocking monitoring | Latency + cost | `glm-4.5-flash` |
| **Quality** | User-facing output, or rare-but-quality-critical drafting | Faithfulness + fluency | `glm-4.6` |
| **Max** | A **blocking gate** where a wrong call is expensive and volume is tiny | Reasoning rigor (cost irrelevant) | strongest available (today `glm-4.6`) |

---

## Per-call-site recommendation (and what's configured today)

| Call site | Runs | Volume | Latency-critical | Blocks? | User-facing | **Tier** | Model | Config key |
|---|---|---|---|---|---|---|---|---|
| **Intent classification + entity extraction** | every turn | High | **Yes** (p99) | — | No | **Fast** | `glm-4.5-flash` | `MERIDIAN_LLM_INTENT_CLASSIFIER_MODEL`, `MERIDIAN_LLM_ENTITY_EXTRACTOR_MODEL` |
| **Answer synthesis** | every answered turn | Medium | TTFT (streamed) | — | **Yes** | **Quality** | `glm-4.6` | `MERIDIAN_LLM_SYNTHESIZER_MODEL` |
| **Domain agents (mock)** | per fan-out | High | Yes | — | No | **Fast** | `glm-4.5-flash` | `WEALTH_AGENT_LLM_MODEL`, `SERVICING_AGENT_LLM_MODEL` |
| **DeepEval faithfulness judge** (release gate) | offline, on a change | Low | No | **Yes — blocks deploy** | No | **Max** | `glm-4.6` | `DEEPEVAL_JUDGE_MODEL` |
| **Langfuse continuous judge** (drift monitor) | async, off live traces | High (sampled) | No | No | No | **Fast/Mid** | `glm-4.6` | `JUDGE_MODEL` / `ZAI_EVAL_MODEL` |
| **IAM policy generation** | admin drafts a policy | Very low | No | No (human-approved) | No (internal) | **Quality** | `glm-4.6` | `IAM_POLICY_GENERATION_MODEL` |
| **Embeddings** (routing) | every turn | High | **Yes** | — | No | (non-LLM) | `all-MiniLM-L6-v2` (384d) | `MERIDIAN_EMBEDDING_*` |

---

## Why each tier — the reasoning

1. **Latency-critical + high-volume + bounded → Fast.** Intent classification and entity
   extraction run on **every** turn and sit on the p99 budget. A flash model handles a
   bounded, structured (tool-call / JSON) task reliably at a fraction of the latency and
   cost. Putting a heavy model here blows the latency budget for zero quality gain.
2. **User-facing → Quality.** Synthesis is what the relationship manager actually reads;
   grounding adherence and fluency justify a stronger model. Because it streams, **time-to-
   first-token** matters more than raw throughput.
3. **Blocking gate → Max, cost-be-damned.** The DeepEval faithfulness judge runs **offline**
   and **blocks deploys**. A weak judge passes bad releases. Use the strongest reasoning
   model — volume is tiny, so cost is irrelevant. (It is also validated against 15
   human-labeled cases, so the judge itself is certified before it gates anything.)
4. **Async + non-blocking + high-volume → Fast/Mid.** The continuous judge scores live
   traffic for drift; it never blocks a request or a release. Cost dominates, so a cheaper
   model is defensible — we keep it at `glm-4.6` because **safety** misjudgments are costly
   and the volume is sampled (lookback window + trace cap), not every request.
5. **Rare + human-in-the-loop → Quality is enough.** Policy generation is *drafted* then
   human-approved; correctness matters but volume is negligible. A strong (not max) model.

---

## The anti-pattern we deliberately avoid

> **Using one heavy model (`gpt-5.x`, `glm-5.x`) for everything.**

It would (a) blow the p99 latency budget on routing — which runs on every turn — and
(b) cost ~10× on bounded tasks for no quality gain. The gateway tiers instead: flash for
routing/extraction, a strong model for synthesis, the strongest only where it blocks a gate.

> *Fixed 2026-06-29:* IAM policy generation defaulted to an unverified `glm-5.2` that
> disagreed with its own Java default — reconciled to `glm-4.6` (the documented strong
> model). The DeepEval judge model was hardcoded — now `DEEPEVAL_JUDGE_MODEL`-overridable.

---

## The GTM headline: model selection is a config knob, not a rebuild

Every call site reads `*_BASE_URL` + `*_MODEL` + `*_API_KEY` independently. That means a
customer can run, with **zero code change**:

- **All-GLM** (default): flash for routing, `glm-4.6` for synthesis + judging.
- **Mixed provider**: GLM-flash for routing (cheap, fast), GPT or Claude for synthesis
  (quality), per task.
- **All-local / air-gapped**: point every `*_BASE_URL` at an in-house OpenAI-compatible
  endpoint.

Cost, latency, and quality are **dialed per task** — the platform is never bet on one model
or one vendor. That is the deliberate, auditable model-economics story an enterprise
procurement/risk committee wants to see.

---

## Quick reference — environment overrides

```bash
# Fast tier (routing + extraction) — every turn, latency-critical
MERIDIAN_LLM_INTENT_CLASSIFIER_MODEL=glm-4.5-flash
MERIDIAN_LLM_ENTITY_EXTRACTOR_MODEL=glm-4.5-flash
# Quality tier (synthesis) — user-facing
MERIDIAN_LLM_SYNTHESIZER_MODEL=glm-4.6
# Max tier (release-gate judge) — blocks deploys, strongest model
DEEPEVAL_JUDGE_MODEL=glm-4.6
# Fast/Mid (async drift judge)
ZAI_EVAL_MODEL=glm-4.6
# Quality (rare, human-approved policy drafting)
IAM_POLICY_GENERATION_MODEL=glm-4.6
```
