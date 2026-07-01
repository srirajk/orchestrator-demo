# Eval — Product Vision (framework + app)

> **Status:** vision / scoping doc. Separates the two things: the **framework** (declarative
> eval-as-code engine — buildable now, see `EVAL-FRAMEWORK.md`) and the **product app** (a control
> plane on top — a future, separate app). Capturing so it can be handed to a builder (incl. Codex).

---

## The one-line thesis
**Declarative, config-as-code evaluation** — your evals live in the manifest next to your agent,
versioned in git, and run against any trace store / any agent stack. *Eval-as-code + portable +
trace-source-agnostic* — not clicked into a vendor UI, not locked to one observability platform.

## Two layers, kept separate

### 1. The framework (the engine) — buildable now
Headless, runs anywhere. See `docs/EVAL-FRAMEWORK.md` for the architecture. In short:
- **Scorer registry** (one shared set; each scorer tagged reference-free / reference-based),
- **Manifest `eval` block** declaring each unit's suite (World-B style),
- **Suite resolver** (picks a domain/agent's suite by trace tag),
- **Source adapter** (Langfuse now; pluggable),
- **Two drivers** — continuous (live traces) + batch (datasets / release gate).
This is the OSS/library seed of the product.

### 2. The product app (the control plane) — a separate app, later
The engine is headless; the **app** is the UI + management layer on top. **What it does:**
- **Results & drift dashboard** — score trends per domain / agent / suite over time; spot
  regressions and model/prompt drift at a glance.
- **Run explorer** — compare experiment (dataset) runs side-by-side; drill from a run into failures.
- **Score → trace drill-down** — click a low grounding/safety score → the trace → the scorer's
  reasoning → the exact span that failed.
- **Suite view (config-as-code preserved)** — browse the manifest-declared suites (git is the
  source of truth); optionally *propose* edits as a PR rather than mutating in a UI.
- **Connectors** — configure trace stores (Langfuse, others) + agent stacks (gateway, LangGraph,
  CrewAI…).
- **Alerting** — notify on drift / score drops / release-gate failures.
- **Governance** — who owns which suite; per-domain / per-tenant access.
- **The flywheel, one click** — promote a low-scoring *live* trace into the *golden dataset*
  (→ opens a PR), so a production miss becomes a permanent gate case.

### UX direction
Reuse the **Axiom UX aesthetic** already in the repo (the identity platform's modern, dark,
gradient, high-contrast look) so the eval app feels part of the same product family — modern, not
a stock admin panel.

---

## Honest positioning (don't skip)
This is a **crowded, well-funded space**: Langfuse (has evals), Braintrust, LangSmith, Arize
Phoenix, Galileo, Humanloop, Confident AI/DeepEval, Patronus, Ragas. The bar isn't "does it work"
— it's **"why this over those."** The differentiator has to be the **declarative / config-as-code
/ portable DX**, and it must be *noticeably* better, not just different.

**Realistic staging:** (1) framework + (2) a killer World-B reference demo + (3) DX (spec, CLI,
docs, examples) = an achievable **OSS MVP**. The full SaaS app (multi-tenant, hosting, scale, GTM)
is a company-sized, mostly-non-code step — go there only after the MVP proves interest.

---

## Good candidate to build with Codex?
**Yes — this is a strong test for an agentic coder like Codex:**
- Greenfield-ish, **well-specified** (this doc + `EVAL-FRAMEWORK.md` are the spec).
- Clean module boundaries (registry / resolver / adapter / drivers) — easy to parallelize + verify.
- Deterministic acceptance (scorers produce scores; the resolver maps tag→suite; unit-testable).
- The repo already has `AGENTS.md` + `.codex/` wired for Codex.

**Hand Codex:** `docs/EVAL-FRAMEWORK.md` (architecture) + this doc (product framing) + the existing
`eval/` scripts (the pre-framework code to refactor). Start it on **P1** (the shared scorer
registry, as a **standalone, neutrally-named module** — not bolted into Conduit internals).
