# Analytics Tier — Business DRAFT (PO / domain, via Fable)

> STATUS: **DRAFT — NOT YET TRUSTED.** Every factual/domain claim here (metric definitions,
> formulas, thresholds, regulatory rules) must be confirmed by the D3 multi-source research pass
> (≥2 agreeing independent sources) before it ships in the demo. See
> `DOMAIN-KNOWLEDGE-VERIFIED.md` (produced by D3) for the trusted subset. This file is the design
> input for the analytics-tier manifests (D4) and agents (D5), not ground truth.

## Analytics roster (fan-in agents; consume DATA-agent outputs only)

### Wealth Management
- **concentration** — consumes `holdings` + `risk_profile` → single-issuer / sector / asset-class
  concentration scored vs stated risk tolerance; breach flags w/ severity.
- **drift_rebalance** — consumes `holdings` + `risk_profile` + `market_research` → target-vs-actual
  drift + PROPOSED rebalancing trades, framed against house view.
- **goal_gap** — consumes `goal_planning` + `performance` + `holdings` → funded-status trajectory +
  gap-closure proposal (contribution / return / timeline).
- **review_brief** — consumes `performance` + `holdings` + `risk_profile` + `goal_planning` +
  `market_research` → one-page review (widest fan-in; the demo peak).

### Insurance
- **coverage_adequacy** — consumes `policy_details` (all lines) → gap/overlap analysis: limit
  adequacy, deductible stacking, missing umbrella/excess, lapsed exposure.
- **claims_exposure** — consumes `claim_status` + `policy_details` → open-claim exposure vs
  remaining per-occurrence/aggregate limits; erosion; deductible burn.
- **renewal_risk** — consumes `policy_details` + `claim_status` → incurred loss ratio + trend +
  PROPOSED action (reprice / restructure / refer / non-renew).

### Asset Servicing
- **settlement_risk** — consumes `settlement_status` + `custody_positions` + `cash_management` →
  fails triage: aging, buy-in / CSDR-penalty exposure, root-cause split.
- **cash_projection** — consumes `cash_management` + `settlement_status` + `corporate_actions` →
  projected cash ladder per account/ccy; overdraft/funding-cutoff warnings.
- **nav_check** — consumes `nav` + `custody_positions` + `corporate_actions` → NAV plausibility:
  day-over-day decomposition, position/CA inconsistency flags.
- **ca_impact** — consumes `corporate_actions` + `custody_positions` + `cash_management` →
  election impact pack: affected positions, deadlines, per-option cash/stock outcomes.

## Demo questions (require fan-in; abbreviated — full set in transcript)
- Wealth: concentration vs risk tolerance; review prep; goal gap; house-view rebalance.
- Insurance: renewal loss-ratio triage; coverage-gap walk; aggregate erosion on open claim.
- Servicing: aging fails/buy-in; next-day cash cover; NAV-move break; voluntary elections due.
- Cross-domain: (Wealth×Insurance) founder total exposure vs coverage; (Servicing×Wealth) which
  clients hold a broken-NAV fund; (Insurance×Wealth) claim payout vs retirement goal.

## Gold-class business bar (12 — the PO acceptance list)
1. Every number traceable on-screen to a data-agent output (no orphan numbers).
2. Analytics never fill gaps — missing input → report without the verdict, say why.
3. Entitlement asymmetry demonstrable live (same question, entitled vs denied).
4. Cross-domain answers pass two gates visibly; failing one degrades explicitly.
5. Proposals labeled "for your review" — never reads as executed/regulated advice.
6. Vocabulary survives a practitioner (loss ratio, erosion, buy-in, funded status, drift...).
7. Data coherent as a world (IDs, ccy, dates, reserves, premiums, NAVs realistic + consistent).
8. Ambiguity clarifies with entitled candidates — never guesses, never fabricates an ID.
9. Partial failure is a stage feature — kill an agent, answer still arrives stating what's missing.
10. Glass box matches the narration exactly (same agents, order, entitlement decisions).
11. Same question, same session → same numbers.
12. Every demo question genuinely earns its orchestration (no multi-agent hammer on 1-agent nail).

## Claims requiring D3 verification (the must-be-correct list)
- Wealth: concentration measures (single-issuer %, sector %, HHI); suitability/risk-tolerance
  breach thresholds; rebalancing drift tolerance bands; goal funded-status & gap math;
  tax-loss-harvesting wash-sale rule.
- Insurance: loss ratio definition (incurred + LAE / earned premium); aggregate-limit erosion;
  coverage adequacy (umbrella/excess, deductible stacking); commercial renewal metrics.
- Servicing: settlement fails + buy-in; CSDR mandatory buy-in / cash penalties; NAV oversight /
  plausibility; cash-ladder projection; corporate-action voluntary elections & deadlines.
