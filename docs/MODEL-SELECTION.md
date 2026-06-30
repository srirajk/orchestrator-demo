# Model Selection Guide — Conduit AI Gateway

> **Status:** Recommended configuration, 2026-06-29.
> **Principle:** No single model for everything — because **each task is bound on a different
> performance axis, and one model cannot be optimal on all of them.** Routing is *latency*-bound,
> synthesis is *quality/TTFT*-bound, the release gate is *accuracy*-bound, drift monitoring is
> *throughput*-bound. We match each call site to the model whose performance profile fits its
> binding constraint. Every call site is **independently model- and provider-swappable via
> config** (no code change), so the profile is a deployment decision, not a rebuild.

---

## The performance axes (why we tier — not economics)

| Task | **Bound on** | Consequence of the wrong model |
|---|---|---|
| Routing + entity extraction | **Latency** — runs every turn, before any data moves; bounded task | A frontier model adds seconds to TTFT for *zero* accuracy gain (flash already saturates a bounded task), and bottlenecks p99 under concurrency |
| Answer synthesis | **Quality + TTFT** — open generative, user-facing, streamed | A weak model caps grounding-adherence + fluency on the answer the user judges you by |
| Release-gate judge | **Accuracy** — offline, *blocking* a deploy | A weak judge passes bad releases; latency is irrelevant, so use the strongest |
| Drift monitoring | **Throughput** — async stream of live traces | Must keep pace with traffic *without* adding latency to any request |

You physically cannot win latency, quality, accuracy, and throughput with one model. Tiering
puts each task on the model that fits the axis it's bound on.

## The tiers

| Tier | Use when | **Optimize for** | Default (GLM family) |
|---|---|---|---|
| **Fast** | High volume, on the latency-critical path, bounded task (classify/extract), or async non-blocking monitoring | **Latency / throughput** | `glm-4.5-flash` |
| **Quality** | User-facing generative output, or rare quality-critical drafting | **Grounding-adherence + TTFT** | `glm-4.6` |
| **Max** | A **blocking gate** where a wrong call is expensive and volume is tiny | **Judgment accuracy** (latency irrelevant) | strongest available (today `glm-4.6`) |

---

## Per-call-site recommendation (and what's configured today)

| Call site | Runs | Volume | Latency-critical | Blocks? | User-facing | **Tier** | Model | Config key |
|---|---|---|---|---|---|---|---|---|
| **Intent classification + entity extraction** | every turn | High | **Yes** (p99) | — | No | **Fast** | `glm-4.5-flash` | `CONDUIT_LLM_INTENT_CLASSIFIER_MODEL`, `CONDUIT_LLM_ENTITY_EXTRACTOR_MODEL` |
| **Answer synthesis** | every answered turn | Medium | TTFT (streamed) | — | **Yes** | **Quality** | `glm-4.6` | `CONDUIT_LLM_SYNTHESIZER_MODEL` |
| **Domain agents (mock)** | per fan-out | High | Yes | — | No | **Fast** | `glm-4.5-flash` | `WEALTH_AGENT_LLM_MODEL`, `SERVICING_AGENT_LLM_MODEL` |
| **DeepEval faithfulness judge** (release gate) | offline, on a change | Low | No | **Yes — blocks deploy** | No | **Max** | `glm-4.6` | `DEEPEVAL_JUDGE_MODEL` |
| **Langfuse continuous judge** (drift monitor) | async, off live traces | High (sampled) | No | No | No | **Fast/Mid** | `glm-4.6` | `JUDGE_MODEL` / `ZAI_EVAL_MODEL` |
| **IAM policy generation** | admin drafts a policy | Very low | No | No (human-approved) | No (internal) | **Quality** | `glm-4.6` | `IAM_POLICY_GENERATION_MODEL` |
| **Embeddings** (routing) | every turn | High | **Yes** | — | No | (non-LLM) | `all-MiniLM-L6-v2` (384d) | `CONDUIT_EMBEDDING_*` |

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

It would **blow the p99 latency budget on routing** — which runs on every turn, on the
critical path, for a bounded task that a flash model already answers at the accuracy ceiling.
Adding frontier-model latency there raises time-to-first-token for *every* request and
bottlenecks concurrency under load — pure latency tax, no quality return. The gateway tiers
instead: flash for routing/extraction (latency-bound), a strong model for synthesis
(quality-bound), the strongest only where it blocks a gate (accuracy-bound).

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

## Provider allocation — reference deployment profiles

Tier (Fast/Quality/Max) is *what kind* of model; **provider** is *whose*. Both are per-call-site
config. Two recommended profiles:

### Profile A — "Brand + SLA on the critical path" (recommended default)
Isolate providers by component so one vendor's outage degrades, not kills.

| Component | Provider | Models |
|---|---|---|
| **Gateway** (routing + synthesis) | **OpenAI** | `gpt-4o-mini` (routing/extraction) · `gpt-4o`/`gpt-4.1` (synthesis) |
| **Domain agents** | **GLM (Z.AI)** | `glm-4.5-flash` (in prod: domain team's choice) |
| **Axiom / IAM policy gen** | **GLM (Z.AI)** | `glm-4.6` |
| **Eval (DeepEval / Langfuse)** | **GLM (Z.AI)** | `glm-4.6` / flash |

Why: the gateway is the user-facing critical path — OpenAI's instruction-following + grounding
on the answer the RM reads, plus a single SLA / key / failure mode on the path that must always
work. Cost-tiering stays *within* OpenAI (mini for routing, full for synthesis). GLM economy
lives downstream where a provider blip degrades but doesn't black out the request.

**Caveat:** OpenAI on every turn's routing costs more than GLM-flash. If synthesis is on OpenAI,
add a **provider fallback** (OpenAI primary → `glm-4.6` on failure) so an OpenAI outage degrades
gracefully. The per-call-site `*_BASE_URL`/`*_API_KEY` already exists; the fallback is a small
try-primary-then-secondary seam (roadmap).

### Profile B — "Cost-first / single vendor"
Whole stack on GLM (Z.AI): gateway routing `glm-4.5-flash`, synthesis `glm-4.6`, agents flash,
Axiom `glm-4.6`. Cheapest, one vendor, one key — at the cost of the OpenAI brand/quality signal
on the user-facing answer. This is the current default in `application.yml`.

> Both profiles are **zero code change** — only `*_BASE_URL` + `*_MODEL` + `*_API_KEY` per call
> site. That swappability is the platform property; the profile is a deployment decision.

---

## Quick reference — environment overrides

```bash
# Fast tier (routing + extraction) — every turn, latency-critical
CONDUIT_LLM_INTENT_CLASSIFIER_MODEL=glm-4.5-flash
CONDUIT_LLM_ENTITY_EXTRACTOR_MODEL=glm-4.5-flash
# Quality tier (synthesis) — user-facing
CONDUIT_LLM_SYNTHESIZER_MODEL=glm-4.6
# Max tier (release-gate judge) — blocks deploys, strongest model
DEEPEVAL_JUDGE_MODEL=glm-4.6
# Fast/Mid (async drift judge)
ZAI_EVAL_MODEL=glm-4.6
# Quality (rare, human-approved policy drafting)
IAM_POLICY_GENERATION_MODEL=glm-4.6
```
