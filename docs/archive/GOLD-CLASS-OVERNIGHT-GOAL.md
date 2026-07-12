# GOLD-CLASS OVERNIGHT GOAL — locked 2026-07-07 (deliver by morning 2026-07-08)

> Owner: Sriraj. Executor: agent (autonomous overnight). Status tracked here; nothing is
> "done" until every deliverable below has its acceptance box checked AND all five hats sign off.
> **No item may be silently dropped.** If something cannot be completed safely, it is STAGED and
> reported — never abandoned, never quietly skipped.

## Mission
Make all three domains — **Wealth Management, Insurance, Asset Servicing** — gold-class and
client-demo-ready: every agent production-grade, the manifest schema clean and coherent, the
analytics tier that makes multi-step orchestration real, the three domains brought together
through the DAG, and the whole thing measured (continuous eval + sharper dashboards).

## Non-negotiable rule on DOMAIN KNOWLEDGE (Sriraj's explicit instruction)
Do NOT say "yes" or trust any domain fact (metric definitions, formulas, regulatory rules,
what a real capability does) until it is confirmed by an **independent multi-source research
pass with ≥2 agreeing sources**. Fable's business roster is a DRAFT, not truth. Every domain
claim shipped in the demo must be traceable to agreeing sources; disagreements are flagged, not
asserted. Search multiple times, reconcile, then trust.

---

## DELIVERABLES (each with acceptance criteria — none may be forgotten)

- [ ] **D1 — Manifest schema hardened.** `io` dataflow contract finalized in
  `registry/agent-manifest.schema.json`; `market_research` audience fixed (segment→enterprise);
  registry manifests and any `gateway/src/main/resources/manifests` copies kept in sync.
  *Accept:* schema valid; all manifests validate; sync confirmed.
- [ ] **D2 — io contracts on ALL data agents, all 3 domains.** Wealth (done), Insurance,
  Asset Servicing. Truthful to each handler's real output. *Accept:* every data agent has a
  schema-valid `io` block; a summary table exists.
- [ ] **D3 — Domain knowledge VALIDATED (multi-source agreement).** For every analytics
  capability we design/claim: the industry-standard definition/formula, grounded in ≥2 agreeing
  sources, disagreements flagged. *Accept:* a `DOMAIN-KNOWLEDGE-VERIFIED.md` with per-claim
  sources + agreement status; nothing trusted on a single source.
- [ ] **D4 — Analytics tier designed + manifests, all 3 domains.** Fan-in capabilities with
  `io.consumes.from` referencing upstream produced types. Grounded in D3. *Accept:* manifests
  schema-valid; each analytics agent's DAG shape documented.
- [ ] **D5 — Flagship analytics agent(s) built production-grade.** `concentration` first
  (consumes `wealth.holdings`); more if time and confidence allow. *Accept:* `verify.py` 7/7;
  starts clean; standalone-tested (sample holdings → concentration metrics that match D3 defs).
- [ ] **D6 — DagResolver + unit tests (the deterministic brain).** Derives correct DAGs from
  `io` contracts; rejects cycles, missing producers, ambiguous producers; topo-sorts. *Accept:*
  pure unit tests pass, no LLM, no live code touched; proves derive + reject.
- [ ] **D7 — Gateway DAG executor + blackboard + `plan_graph` trace (RISK-GATED).** Additive,
  behind the existing flat path (branch only when `io` deps present); flat path stays default.
  *Accept:* end-to-end multi-step run works AND `world-b-check` CRITICAL 0 AND build green — OR
  it is cleanly STAGED on a branch, clearly marked WIP, with exact status reported. Never ship
  half-working as "done."
- [ ] **D8 — Continuous eval: multi-step + cross-domain scenarios + plan-correctness metric.**
  *Accept:* scenarios added to the eval set; a plan-correctness check (derived DAG == expected)
  exists; grounding/entitlement scores captured for the new flows.
- [ ] **D9 — Metric dashboards upgraded.** Sharper, demo-worthy panels (multi-step / plan
  correctness / grounding / entitlement across domains). *Accept:* dashboards render with real
  captured data; verified visually.
- [ ] **D10 — Client-ready visual bringing the 3 domains together.** Local HTML: the three
  domains, their data + analytics agents, the live DAGs for real questions, the metrics story.
  *Accept:* self-contained file delivered locally (NOT an Artifact).
- [ ] **D11 — Bring-it-together demo path.** A cross-domain multi-step question runs (or is
  staged with a clear runbook). *Accept:* documented, reproducible.

---

## HAT SIGN-OFF GATES (all five must pass to say "done")
- **PO:** the three domains meet the gold-class business bar; the demo questions are real.
- **Domain expert:** every claimed metric/definition is D3-verified (≥2 agreeing sources).
- **Architect:** World-B clean (no domain literals in gateway Java), schema coherent, additive.
- **Dev lead:** builds green, tests pass, `verify.py` 7/7 on every agent.
- **QA:** all manifests schema-valid, `world-b-check` CRITICAL 0, eval green, demo path proven
  or cleanly staged.

## HARD CONSTRAINTS (carry every one — do not violate)
- Branches only: `feat/conduit-chat`, `conduit-platform`, `conduit-platform-container-reduction`.
  **NEVER main.** Commit messages end **"Approved by Sriraj."** — no AI attribution.
- `clearance` → `data classification` is DISPLAY TEXT ONLY. Field names stay (`clearance_min`,
  Cerbos policy). Do not rename identifiers.
- Do **not** touch `uac/backend` (separate project). Do **not** remove Redis principal-seeding.
- World-B: no domain/client/entity names, no ID patterns, no entity-type literals, no user-facing
  domain copy in gateway Java. `scripts/world-b-check.sh` CRITICAL must stay **0**.
- Deliver LOCAL files, never Claude Artifacts. Never commit `.wolf/memory.md`.
- Debate before non-trivial gateway code; D7 is additive + risk-gated per above.

## PARALLELISM PLAN
- Track A (running): Fable — analytics roster + demo questions + business narrative (DRAFT).
- Track B (running): io contracts on Insurance + Asset-Servicing manifests + sync check.
- Track C (launching): multi-source domain-knowledge research (D3) — the validation gate.
- Reconcile A∩C → trusted analytics definitions → D4/D5. Then D6 (resolver), D7 (gateway, gated),
  D8/D9 (eval/dashboards), D10/D11 (visual + bring-together). QA gate. Then, and only then, "done."

## FINDINGS LOG (tracked, none dropped)
- **F1 — Servicing entity mis-declaration (CONFIRMED).** The asset-servicing MCP handlers
  (settlement_status, custody_positions, cash_management, corporate_actions) are
  **relationship-scoped at runtime** — `SETTLEMENTS.get(relationship_id)`, canned data keyed by
  `relationship_id`, `relationship_id_guardrail` present. But their sub-domain manifests
  (`registry/domains/asset-servicing/*.json`) declare `fund_id` (FND-) as the entity type and are
  `resource_scoped: false`. So the declared entity vocabulary ≠ handler reality. `io.consumes`
  currently uses `fund_id` (matches the manifest, internally consistent) but NOT the handler truth.
  *Impact:* cross-domain wealth×servicing DAGs need a shared `relationship_id`; correct fix is to
  make servicing relationship-scoped in the sub-domain manifests. *Risk:* changing to
  `resource_scoped:true` + required `relationship_id` alters entitlement/coverage behavior — must
  first validate the servicing coverage-service config (`registry/domains/asset-servicing.json`).
  *Plan:* resolve deliberately in the architect pass — fix if coverage config supports it, else
  STAGE with a runbook. Flagship (wealth concentration) does not depend on this, so it is not
  critical-path for the primary demo. **Do not ship the servicing cross-domain path until F1 is
  resolved.**
- **F2 — Test-fixture manifests not synced.** `gateway/src/test/resources/manifests/` holds copies
  used by tests; the `io` additions were applied only to the runtime `registry/`. Mirror `io` into
  test fixtures if/when resolver or gateway tests load them, to avoid fixture drift. (Runtime path
  confirmed = `registry/` volume; no runtime duplicate exists.)
