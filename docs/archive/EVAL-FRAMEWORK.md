# Conduit Eval Framework — an agent-agnostic evaluation worker

> **Status:** design / concept doc. Captures the framework we're converging on for turning the
> current Conduit-specific eval scripts (`eval/`) into a reusable, manifest-driven evaluation
> framework. No code committed against this yet — this is the architecture on paper.

---

## 1. The concept in one sentence

**Once traces are recorded, an eval worker connects to the trace store, runs the declared eval
suite (deterministic checks + LLM-as-a-judge), and writes the scores back — regardless of what
produced the traces** (the Conduit gateway, a LangGraph agent, CrewAI, a raw SDK app).

The worker connects to the **trace store** (Langfuse), *not* to the app framework directly. That
indirection is the whole point: anything that emits traces can be evaluated by the same worker.
It is **trace-source-agnostic.**

```
   Any app (gateway · LangGraph · CrewAI · SDK)
                 │  emits traces (OTel), tagged: domain, agent, session
                 ▼
        ┌──────────────────┐
        │   Trace store     │  (Langfuse)
        └────────┬─────────┘
                 │ 1. PULL eligible traces (filter by tag + sample)
                 ▼
   ┌───────────────────────────────────────────────┐
   │            EVAL WORKER (the framework)          │
   │  Source Adapter  → pluggable (Langfuse today)   │
   │  Suite Resolver  → which scorers for THIS trace │
   │                    (by domain/agent tag)        │
   │  Scorer Registry → deterministic + LLM-judge    │
   │                    + custom scorers             │
   │  Runner          → run the suite per trace      │
   │  Reporter        → 3. WRITE scores back         │
   └────────┬────────────────────────────────────────┘
            │ 2. score (LLM judge + deterministic)
            ▼  scores → back onto the trace
```

---

## 2. The key distinction: reference-free vs reference-based scorers

A **"reference"** = a known-correct answer (ground truth) authored ahead of time to grade against.

- **Reference-free scorer** — grades the output on its own; no answer key needed.
  *(Is the answer relevant? Safe? Grounded in the retrieved data?)*
- **Reference-based scorer** — *needs* the known-correct answer to grade.
  *(Did the router pick the **expected** agents? Is it faithful to a **reference** answer?)*

| Scorer | Reference-free? | Runs on live traffic | Runs on golden dataset |
|---|---|---|---|
| grounding (answer traces to agent outputs in the trace) | ✅ | ✔ | ✔ |
| partial-honesty (admits what's missing) | ✅ | ✔ | ✔ |
| relevance | ✅ | ✔ | ✔ |
| safety (no injection / unsafe advice) | ✅ | ✔ | ✔ |
| **routing accuracy** (picked the *expected* agents) | ❌ needs expected | — | ✔ |
| **faithfulness vs reference** | ❌ needs reference | — | ✔ |

**Why the two modes differ:** live production questions have **no answer key** (nobody wrote the
correct answer for an ad-hoc user question), so you can only run **reference-free** scorers on
them. A golden dataset *has* the answer key, so it can run **all** scorers.

---

## 3. Why run reference-free scorers on live traffic (the value)

The release gate scores ~35 curated cases. **Production is thousands of real, unanticipated
questions.** Reference-free scoring is the only way to know the system is behaving on *that*
traffic. Without it you're blind in prod until a user complains.

- **Grounding is the star** — reference-free (the "reference" is the agent data already in the
  trace) yet it's the **hallucination detector on live traffic** ("did it invent a number?"). For a
  bank that's the #1 trust promise, measured on every real answer.
- **Safety** — catches injection / unsafe advice in the wild.
- **Drift detection** — swap a model / a provider silently updates theirs / a prompt drifts → the
  grounding/safety score trend dips **before** anyone notices.
- **Long-tail coverage** — catches what the goldens never anticipated.
- **The flywheel** — a live trace scoring low on grounding is a real failure → add it to the golden
  dataset (now with an expected answer) → the **release gate protects against it forever.**
  Reference-free (find in the wild) **feeds** reference-based (lock down in the gate).

---

## 4. Two modes, one engine

| | **Continuous** (online) | **Batch** (offline) |
|---|---|---|
| Input | live traces (Langfuse) | golden dataset (`{input, expected}`) |
| Scorers | reference-free only | **all** (+ reference-based) |
| Output | scores → back onto each trace | pass/fail **gate** (+ scores) |
| Today | `eval/langfuse_continuous.py` (in the `eval-worker` container) | `eval/eval_deepeval.py` + `eval/langfuse_run_experiment.py` |

**Both drivers share ONE scorer registry.** DeepEval becomes just the batch driver (wrapping the
shared scorers as DeepEval metrics), or is replaced by an own batch driver — either way the
scoring logic is written **once**.

> **Current debt:** the judge rubric is defined **twice** — `eval/prompts/llm_judge_continuous_contract.md`
> and `eval/prompts/llm_judge_deepeval_contract.md`. The refactor collapses these into one shared
> scorer set, each scorer tagged reference-free / reference-based.

---

## 5. What each evaluated unit declares (the manifest)

**Granularity — "per evaluated unit," keyed by trace tags:**
- **Single-agent app** → per **agent**.
- **Orchestrated system (Conduit)** → **end-to-end** evals per **domain** (the final answer) **±**
  **component** evals per **agent** (one agent's contribution).

**Two config layers:**

**A. Worker-level (global) — one place, not per agent:**
```yaml
worker:
  trace_store: langfuse           # source adapter
  langfuse: { host: ..., public_key_env: ..., secret_key_env: ... }
  schedule: continuous            # continuous | cron(...)
  default_sample_rate: 0.3
  judge: { base_url_env: JUDGE_BASE_URL, model_env: JUDGE_MODEL, api_key_env: ... }
```

**B. Per-unit (in the domain/agent manifest) — World-B declaration:**
```json
"eval": {
  "unit": "domain",                         // "domain" (end-to-end) | "agent" (component)
  "match": { "domain": "wealth" },          // trace filter — which traces are mine (by tag)
  "sample_rate": 0.3,
  "suite": [
    { "scorer": "grounding",        "kind": "reference_free",  "judge": "llm",           "prompt": "contracts/grounding.md", "threshold": 0.7 },
    { "scorer": "partial_honesty",  "kind": "reference_free",  "judge": "deterministic" },
    { "scorer": "relevance",        "kind": "reference_free",  "judge": "llm",           "prompt": "contracts/relevance.md" },
    { "scorer": "safety",           "kind": "reference_free",  "judge": "llm",           "prompt": "contracts/safety.md" },
    { "scorer": "routing_accuracy", "kind": "reference_based", "dataset": "conduit-routing" }  // batch-only
  ]
}
```

The worker **resolves the suite per trace by its tag** (`domain` / `agent`). Onboarding a domain
brings its **evals** with it — **no worker code changed.** That is World B extended to the eval
layer.

---

## 6. The four pluggable seams (what makes it a framework, not a script)

1. **Source Adapter** — where traces come from (Langfuse now; could add others). One interface:
   `pull(filter, since) -> [trace]`, `write_score(trace_id, name, value, comment)`.
2. **Scorer Registry** — named scorers, each declaring `kind` (reference_free | reference_based),
   `judge` (llm | deterministic | custom), and its inputs. One interface: `score(trace|item) -> Score`.
3. **Suite Resolver** — maps a trace (by tag) → the ordered list of scorers to run (from the
   manifest). This is the World-B glue.
4. **Scheduler / Driver** — continuous loop, cron, or on-demand batch; owns sampling + rate limits.

---

## 7. What we have vs. what to build

**Have (Conduit-shaped, hardcoded):** the worker container, LLM + deterministic judges, the
dataset runner, golden prompts.

**To make it a framework:**
- [ ] Extract the **scorer registry** (one place; each scorer tagged reference-free/based).
- [ ] Collapse the two judge contracts into the shared registry.
- [ ] Add a **source-adapter** interface (Langfuse impl behind it).
- [ ] Add the **manifest `eval` block** + a **suite resolver** that maps trace-tag → suite.
- [x] Ensure traces carry the **`domain`/`agent` tags** — DONE (2026-07-01): `ChatService` sets
      first-class `langfuse.trace.tags` = `domain:<d>` per distinct domain + `agent:<id>` per invoked
      agent (values from the manifest; World-B clean). Verified live in Langfuse.
- [ ] Two thin **drivers** (continuous + batch) over the shared registry.
- [ ] Optional: auto-seed datasets on bring-up (fold `langfuse_seed_datasets.py` into the boot provisioner).

---

## 8. Related decisions
- Observability model: **one Langfuse project + domain tags** (not project-per-domain). Traces
  must carry a `domain` tag — which is also what the suite resolver keys on.
- Eval ownership: **keep the external worker** (control, portability, versioned prompts) rather than
  Langfuse-native evaluators; the **release gate stays external / in CI** regardless.
