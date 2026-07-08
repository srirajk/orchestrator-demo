# Domain Knowledge — VERIFIED (D3)

> Rule (Sriraj): trust nothing until ≥2 independent authoritative sources AGREE. This file records
> what PASSED that bar (Pass 1, adversarial 3-vote), the design implication of each, and what is
> STILL PENDING a second targeted pass (`wf_aefe5dfb-274`). Anything under "PENDING" must NOT be
> asserted in the demo until confirmed. Pass 1 = 25 claims verified, 0 refuted.

## KEY REFRAME (turns a gap into a strength)
Verification found that the "thresholds" a naive demo would hardcode (a >10% concentration flag, a
loss-ratio cutoff, a drift band) mostly have **no universal regulatory standard — they are
firm-discretionary.** That is the honest truth *and* it is exactly right for World-B: **the
analytics agents COMPUTE the metric (pure math) and flag it against a FIRM-CONFIGURABLE policy
threshold — never a hardcoded "industry standard" number.** Saying "measured, flagged against your
firm's policy" is more credible to a bank than inventing a number, and it keeps thresholds in
config/manifest, not gateway code. Adopt this framing across all analytics agents.

---

## VERIFIED — safe to build on and to say out loud

### V1 — Suitability is principles-based; NO numeric concentration/allocation thresholds
FINRA Rule 2111: reasonable-basis + customer-specific + quantitative suitability over a 9-factor
investment profile (incl. risk tolerance). It contains **no numeric** concentration/allocation
limits — overconcentration is facts-and-circumstances. Any risk-tolerance→allocation mapping is
**firm-discretionary.** Since 30 Jun 2020, Reg BI governs *retail* recommendations (2111 carve-out;
meeting Reg BI satisfies suitability); 2111 still governs institutional/non-retail.
Sources: finra.org/rules-guidance/rulebooks/finra-rules/2111 · finra.org suitability FAQ · FINRA Notice 20-18 · SEC 34-88422.
**Design:** `concentration` / `suitability` agents flag against firm-configured policy, cite 2111 as
principles-based, never assert a universal % threshold.

### V2 — Wash-sale rule (IRC §1091) — CONFIRMED, assert confidently
Loss deduction (§165) disallowed if "substantially identical" securities acquired within **30 days
before to 30 days after** the sale (61-day window). "Substantially identical" is fact-specific (no
IRS bright line). §1091(d): disallowed loss preserved via **basis roll-forward** to the replacement
lot — EXCEPT a replacement bought inside an IRA extinguishes the loss permanently.
Sources: law.cornell.edu/uscode/text/26/1091 · uscode.house.gov (26 §1091) · investor.gov · Tax Notes.
**Design:** `tax_loss_harvesting` may state the §1091 rule as fact; flag wash-sale risk on proposed
harvest pairs; note the IRA caveat.

### V3 — EU CSDR settlement discipline — CONFIRMED + TIME-SENSITIVE
Cash penalties + fails-reporting **LIVE since 1 Feb 2022** (fail = non-delivery of securities OR cash
by ISD). **Mandatory buy-in is NOT activated** — suspended, and under the CSDR Refit (Reg (EU)
2023/2845, in force Jan 2024) reconfigured to a discretionary "last-resort" measure not triggered;
ESMA guides NCAs not to prioritise buy-in supervision. ESMA's Nov 2024 advice PROPOSES a moderate
penalty-rate increase — a proposal, **not enacted**. Holds through ESMA's Oct 2025 reports.
Sources: esma.europa.eu (buy-in suspension TS; penalty-mechanism advice) · ICMA CSDR settlement-discipline.
**Design:** `settlement_risk` computes CSDR **cash-penalty** exposure on fails and flags aging; it
must NOT claim mandatory buy-in is active. **Re-verify if demo dated after mid-2026.**

### V4 — Corporate-action taxonomy + election deadline (CAJWG) — CONFIRMED
Three classes: Mandatory Reorganisation · Mandatory-with-Options · Voluntary (offeror-driven, e.g.
tender). For elective events, non-elected securities take the **issuer-announced DEFAULT**; fully
voluntary events typically default to "take no action / retain." The Election Period **SHOULD** start
**at least ten business days** before the Market Deadline — a CAJWG market convention (a SHOULD, not
a binding mandate).
Sources: Clearstream CAJWG market-standards PDF · DTCC ISO 20022 CA guide · AFME/EBF CAJWG.
**Design:** `ca_impact` uses the three-class taxonomy + the announced default; present the
ten-business-day figure as a market convention, not a hard rule; highlight the default outcome of a
missed voluntary election.

### V5 — "5/25" rebalancing bands — CONFIRMED as a convention (medium confidence)
Absolute **5-pp** band for allocations ≥20%; relative **25%** band for sub-allocations <20% (a 10%
target acts at 7.5% / 12.5%). Attributed to Larry Swedroe; popularized by Bogleheads. A
practitioner **convention, NOT a regulatory standard** (secondary/blog sources → medium confidence).
Sources: bogleheads.org/wiki/Rebalancing · awealthofcommonsense.com (Swedroe 5/25) · thefinancebuff.com.
**Design:** `drift_rebalance` uses configurable bands, defaulting to 5/25 **labeled** as the
Swedroe/Bogleheads convention; never call it an industry standard.

---

## VERIFIED (Pass 2, `wf_aefe5dfb-274`) — the gaps, now closed

### V6 — Portfolio concentration + HHI — CONFIRMED
**HHI = Σ(wᵢ²)** over DECIMAL position weights (range 1/n … 1); **1/HHI = "effective number of
positions"** (Neff). Genuinely used for single-portfolio concentration (not only antitrust). Single-
issuer/sector exposure is expressed as **% of portfolio**; any hard flag threshold (e.g. >10%
single-name, sector caps) is **firm-discretionary — no industry standard.**
Sources: en.wikipedia.org HHI · CFA-L3 (AnalystPrep) · Bridgeway · Nasdaq · LSEG/FTSE Russell · sipametrics.
**Design (concentration agent):** report single-name % + HHI + Neff (pure math); flag vs a
**config threshold** (default 10%) labeled firm policy. ✔ matches the built flagship.

### V7 — Insurance loss ratio & combined ratio — CONFIRMED
**Loss ratio = (incurred losses + LAE) / earned premium** (plain loss-only form also exists; the
LAE-inclusive "loss & LAE ratio" is the common one). **Combined ratio = loss ratio + expense ratio**
(arithmetic sum); **100% = underwriting breakeven** (<100% underwriting profit; >100% relies on
investment income). Combined ratio measures UNDERWRITING profit only, not total profitability.
Sources: NAIC 2023 P&C report · AM Best QAR guide · IRMI · CFI · Verisk.
**Design (renewal_risk / claims_exposure):** compute loss ratio and combined ratio from premium +
incurred; state the 100% line; be explicit whether LAE is in the numerator.

### V8 — Aggregate limit erosion (ISO-CGL) — CONFIRMED
Per-occurrence limit caps one occurrence; **aggregate** caps the whole policy period. Paid **and
reserved** amounts **erode** the remaining aggregate (cumulative); once exhausted the insurer's
obligation ends. **Defense-within-limits** ("burning/wasting limits") means defence costs also erode
the limit. A full CGL has **TWO aggregates** (general + products-completed-operations) plus
sublimits — model each aggregate as a **separate eroding bucket.**
Sources: IRMI CGL-limits + per-occurrence + defense-within-limits · Insureon · InsureTutor · Rough Notes.
**Design (claims_exposure):** track remaining aggregate = aggregate − (paid + reserved), per bucket.

### V9 — Commercial renewal loss-ratio triggers — FIRM-DISCRETIONARY (confirmed)
**No universal numeric loss-ratio threshold** triggers reprice/non-renewal; target loss ratio is
carrier-specific (1 − expense − profit − LAE loads). "80%"/"40-60%" figures are vendor-blog only.
Repricing ≠ reunderwriting (rate action vs risk-selection remediation).
Sources: Carrier Management · Perr&Knight · McKinsey underwriting.
**Design (renewal_risk):** compute the loss ratio; flag vs a **carrier-configured** target; never
assert a universal trigger number.

### V10 — NAV oversight / plausibility — CONFIRMED (control), threshold firm-configured
Standard control = **NAV movement analysis with hierarchical tolerance/plausibility checks** before
striking (portfolio → asset class → instrument); tolerance breaches flag anomalies to remedy before
release. Tolerance VALUES are firm/mandate-configured (no universal number). **REFUTED:** the
specific 3-bucket decomposition taxonomy (Capital Flows / Asset Price / Other) — do NOT assert it.
Sources: Milestone · Funds-Axis · Linedata NavQuest · BBH · IVP (vendor pages → medium confidence).
**Design (nav_check):** day-over-day movement + tolerance flag vs config; reconcile positions/CAs;
don't claim a fixed decomposition taxonomy.

### V11 — Goals-based per-goal funding — CONFIRMED doctrine; funded-ratio arithmetic firm-dependent
CFA doctrine (2026 curriculum): goals-based allocation combines **sub-portfolios, each funding one
goal** with its own horizon + required probability of success. But a quantitative **"funded ratio =
assets / PV(goal)"** is **NOT** an established universal formula (the pension analog ASC 715/IAS 19
was refuted as uniform). Treat funded-ratio math as **firm-methodology-dependent.**
Sources: CFA Institute "Principles of Asset Allocation" (2026) · AnalystPrep L3 · Wiley.
**Design (goal_gap):** frame per-goal funding as the CFA sub-portfolio doctrine; label any
funded-ratio number as the firm's methodology, not a standard.

## STILL UNCONFIRMED (one item) — present as firm-methodology, no assertion
- **Cash projection / ladder (item 6)** — netting pending settlements vs expected income + funding/
  currency cutoffs did NOT reach the ≥2-source bar. `cash_projection` agent: compute the arithmetic
  (obligations − expected receipts) and label it firm-methodology; do NOT cite a standard.

## Currency / caveats
- CSDR is the most perishable finding — re-verify after mid-2026.
- 5/25 and funded-status rest on weaker sources; keep them firm-discretionary.
- Modal-verb precision the bank's experts will check: CAJWG ten-day is "SHOULD"; 2111 has no numbers;
  Reg BI is a carve-out from 2111 for retail, not a supersession.
