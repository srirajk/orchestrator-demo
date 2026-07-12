# Eval — Extraction Record (lift into its own project)

> **Purpose:** everything needed to lift the `eval/` suite out of this repo into its own
> standalone project/product. Pairs with the design (`EVAL-FRAMEWORK.md`) and product framing
> (`EVAL-PRODUCT-VISION.md`). Documentation only — no code changes here.

---

## TL;DR — how coupled is it?
**Barely.** `langfuse_continuous.py` imports only stdlib + `httpx` — **no Conduit code**. It talks
to Langfuse purely over the **public API**. So:
- **Generic (lift as-is):** all the Python (worker, experiment runner, dataset seeder, DeepEval
  gate, continuous loop).
- **Conduit-specific (genericize on extraction):** the **judge prompt contracts** (banking rubric)
  and **`golden-prompts.json`** / **`cerbos_golden_dataset.json`** (Conduit routing/policy cases).

The extraction is mostly: copy `eval/`, swap the prompts + goldens for the target domain, keep the
same env contract.

---

## File inventory (`eval/`)
| File | Lines | What it is | On extraction |
|---|---|---|---|
| `langfuse_continuous.py` | 634 | **Continuous online scorer** — pulls recent traces, runs deterministic + LLM-judge (grounding/honesty/relevance/safety), posts scores back | **generic** (rubric lives in the prompt) |
| `continuous_loop.py` | 53 | the poll loop driving the above (interval, sampling) | generic |
| `eval_deepeval.py` | 533 | **Release gate** — DeepEval over golden dataset (routing accuracy + faithfulness) → pass/fail | generic engine; metrics reference goldens |
| `langfuse_seed_datasets.py` | 328 | uploads `golden-prompts.json` → Langfuse datasets (`conduit-routing`, `conduit-synthesis`) | generic; input is the goldens |
| `langfuse_run_experiment.py` | 394 | runs a task over a dataset → a named **experiment run** for comparison | generic |
| `cerbos_policy_eval.py` | 142 | runs `cerbos_golden_dataset.json` against the live Cerbos PDP | **Conduit-specific** (authz demo) — optional |
| `golden-prompts.json` | 216 | 35 routing goldens (`prompt → expected_agents`) + 5 synthesis | **Conduit-specific** — replace per domain |
| `cerbos_golden_dataset.json` | 546 | Cerbos policy cases | **Conduit-specific** — optional |
| `Dockerfile` | 8 | container image for the workers | generic |
| `requirements.txt` | 7 | deps (langfuse SDK, httpx, deepeval, …) | generic |
| `prompts/llm_judge_continuous_contract.md` | 348 | continuous judge rubric | **genericize** (banking-specific) |
| `prompts/llm_judge_deepeval_contract.md` | 351 | gate judge rubric | **genericize** — *and collapse with the above (P1)* |
| `prompts/{intent_classifier,entity_extractor,answer_synthesizer}_contract.md` | — | gateway prompt contracts (not eval) | **leave** — these belong to the gateway, not the eval product |

---

## External contract (the only things it touches)
**Reads / writes Langfuse over the public API** (`$LANGFUSE_HOST/api/public/*`):
- `GET /api/public/traces` — pull recent traces (filter by tag/time, sample).
- `GET /api/public/traces/{id}` — full trace (input/output/observations/metadata/tags).
- `POST /api/public/scores` (SDK `create_score`) — write grounding/honesty/relevance/safety.
- `GET/POST /api/public/datasets*` — seed + read datasets, link experiment runs.

**Calls a judge LLM** (OpenAI-compatible) for the LLM-as-judge scorers.

That's the entire surface — no gateway, no DB, no Conduit internals.

---

## Config / env reference (the contract to reproduce anywhere)
| Env var | Purpose | Current default |
|---|---|---|
| `LANGFUSE_HOST` | trace store base URL | `http://langfuse:3000` |
| `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` | project keys (the isolation boundary) | `pk-lf-meridian-public` / `sk-lf-meridian-secret` |
| `JUDGE_BASE_URL` / `JUDGE_MODEL` / judge key | LLM-as-judge (OpenAI-compatible) | `https://api.openai.com/v1` / `gpt-4o-mini` |
| `ZAI_API_KEY` / `ZAI_BASE_URL` / `ZAI_EVAL_MODEL` | alt judge (GLM) | z.ai / `glm-4.5-flash` |
| `EVAL_POLL_INTERVAL_SECONDS` | continuous loop cadence | `300` |
| `EVAL_SAMPLE_RATE` (in code) | fraction of traces to LLM-judge | `1.0` |

Two containers today (both under the `eval` compose profile):
- `conduit-eval-continuous` → `langfuse_continuous.py` (online scorer).
- `conduit-langfuse-eval-worker` → the gate/experiment worker.

---

## Extraction steps (stand it alone)
1. **Copy** `eval/` (minus `cerbos_*` unless you want an authz-policy evaluator) into the new repo.
2. **Genericize the judge prompts** — replace the banking rubric in `llm_judge_*_contract.md` with
   the target domain's rubric (**and collapse the two into one shared scorer set — framework P1**).
3. **Replace the goldens** — `golden-prompts.json` with the new domain's `{prompt, expected_*}` cases.
4. **Set the env** (table above) to point at *any* Langfuse project + judge LLM.
5. **Run:** continuous worker (loop) + `langfuse_seed_datasets.py` + `langfuse_run_experiment.py` +
   `eval_deepeval.py` (gate). No other services required — it only needs a reachable Langfuse + a judge LLM.
6. **Framework path (optional, to make it a product):** follow `EVAL-FRAMEWORK.md` §7 — extract the
   scorer registry, add the manifest `eval` block + suite resolver, put a source-adapter interface
   behind Langfuse, and (later) the control-plane app in `EVAL-PRODUCT-VISION.md`.

---

## What "done" looks like for the standalone product
- Point it at a fresh Langfuse project → it scores that project's live traces with no Conduit
  present.
- Its judge rubric + goldens are the *new* domain's, declared as config (manifest), not hardcoded.
- The four seams (source adapter · scorer registry · suite resolver · scheduler) are the product's
  public interfaces. See `EVAL-FRAMEWORK.md`.
