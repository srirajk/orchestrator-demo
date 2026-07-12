# Codex task T1.6 — routing hardening + honest goal-pick measurement

> Follows T1.5, which proved the reported 46% was a MISCALIBRATED metric (true routing is ~85% domain-
> level). This task fixes the measurement AND hardens routing to a genuine ~90%. Do NOT commit (reviewer
> commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull
> latest — T1 + T1.5 are committed). Stack: docker compose `orchestrator-demo`. World-B CRITICAL 0.
> **ANTI-GAMING is the whole point of this spec — read the guardrails in each part.**

## Context (what T1.5 found, verified)
On 26 "clear" queries: router top-scored agent == expected 17/26 (65%); right-DOMAIN 22/26 (85%). Only
4 genuine wrong-domain, 2 of them low-score (0.33/0.43 — should have abstained). Out-of-scope abstain
7/8 (a haiku routed to `cash_management`). The 46% headline came from a harness that forces `picked_goal`
∈ {the 3 analytics goals}, so data-agent-labeled queries (e.g. `wealth.holdings`) can NEVER score right.

## Part A — Fix the harness metric to measure REAL correctness (not relabel to flatter numbers)
Each labeled query has a true intended outcome of one of three shapes; measure each on its OWN terms:
1. **Flat query** (intended answer = a single data/leaf agent, e.g. "show top holdings" → `wealth.holdings`):
   correct ⇔ the router's **top-scored agent == expected agent**.
2. **Analytics query** (intended answer = a fan-in goal, e.g. "issuer concentration" → `wealth.concentration`):
   correct ⇔ the **resolved DAG goal == expected goal** (the tryDag goal-selection: highest-ranked agent
   whose io declares a fan-in — NOT merely the top embedding score).
3. **Out-of-scope** (intended = `abstain`): correct ⇔ the router **declines / clarifies** (see Part B).
Report THREE numbers — flat top-agent accuracy, analytics goal accuracy, and out-of-scope abstain rate —
PLUS an overall domain-level accuracy, PLUS the full confusion list (query, expected, actual, score).
**GUARDRAIL:** do NOT "fix" the metric by relabeling flat queries as analytics goals, by widening a match
to "any agent in the domain," or by dropping the hard queries. Each query keeps the label of what it
SHOULD route to; the metric just has to compare against the right thing per shape. Fewer, honest greens
beat inflated ones.

## Part B — Add an abstain floor + margin to the router (config-driven, World-B clean)
Today the router always returns a pick — a haiku routed to `cash_management`, and 0.33/0.43 misroutes
were served instead of declined. Add: if the top candidate's score is below a configured **floor**, OR the
top-vs-second **margin** is below a configured minimum, the router ABSTAINS → triggers the existing
clarify/decline path (no goal forced). Thresholds come from `application.yml` / env (e.g.
`conduit.routing.min-score`, `conduit.routing.min-margin`) — **NOT** a hardcoded magic constant, and NOT a
domain literal. **GUARDRAIL:** tune the floor on the abstain behavior generally, do not curve-fit it to the
exact 8 out-of-scope test strings; verify it does not start abstaining on the legitimate clear queries
(regression: clear-query accuracy must not drop because good queries now abstain).

## Part C — Tune skill examples for the genuine confusers (manifest data, World-B clean)
The flagship miss: *"which positions are creating issuer concentration for Whitman"* → `servicing.settlement_status`
(0.643) instead of `wealth.concentration`. Add/adjust `skills[].examples` in the affected manifests
(concentration + any others in the confusion list) so the RIGHT agent out-scores the confuser. Re-embed at
boot. **GUARDRAIL — no overfitting:** do NOT paste the literal test queries in as examples (that inflates the
score without real generalization). Add realistic, varied phrasings of the intent; then prove generalization
by keeping/adding HELD-OUT paraphrases in the labeled set that are NOT used as examples, and those must pass
too. If an example edit helps one query but hurts another domain's routing, that is a real regression — the
overall table must improve, not just the one query.

## Gate (re-measure, honest)
- Re-run the fixed harness: **domain-level accuracy ≥ ~90%** on clear queries; **out-of-scope abstain ≥ ~90%**;
  the flagship "issuer concentration" query now resolves to `wealth.concentration`; no clear-query regression
  from the abstain floor.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; the 3 live verticals still answer
  (abstain floor didn't break them).
- If you CANNOT reach ~90% honestly (without relabeling / overfitting / dropping queries), STOP and report the
  real number + why — a truthful 85% beats a gamed 95%.

## Report
Before/after three-number table (flat / analytics / abstain) + overall domain-level, the confusion list before
and after, the exact config thresholds chosen and why, the example edits made (and the held-out paraphrases
proving generalization), and confirmation the live verticals + mvn + World-B are green.
