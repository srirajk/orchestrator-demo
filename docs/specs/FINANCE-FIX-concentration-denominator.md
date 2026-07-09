# Codex task — FINANCE-FIX: concentration denominator + keep loss-ratio disclosures visible

> Small, client-facing correctness fix from a sourced finance audit. Touches mock-agent compute + manifests
> (NOT gateway hot-path). Do NOT commit (reviewer commits). Repo `/Users/srirajkadimisetty/projects/
> orchestrator-chat`, branch `feat/conduit-chat`. World-B unaffected (mock agents are external), but run
> `scripts/world-b-check.sh` to confirm CRITICAL 0. If ambiguous, STOP and report.

## Item 1 (HIGH) — concentration % denominator vs its label
**Finding (sourced):** the standard way to express single-issuer concentration is as a % of **total
portfolio value INCLUDING cash** (UCITS measures each issuer vs NAV; RIC vs total assets; FINRA vs total
investment assets). `mock-agents/wealth/concentration/compute.py` computes every single-name weight and the
breach decision on `basis_total = sum(position market values)` — the **invested (ex-cash) base** — but the
flag message says **"{name} is {pct}% of the portfolio"** (~line 179). If the holdings payload carries cash
(as a `total_value` larger than the summed positions, or a cash balance not in `positions`), the
percentages — and the 10%-limit breach flags — are **overstated vs true AUM while labeled "% of the
portfolio."**

**Do this:**
1. **First confirm the data:** does the holdings payload (`mock-agents/wealth/.../canned_data` + the
   concentration input) carry a portfolio `total_value` that INCLUDES cash, and/or a cash balance separate
   from `positions`? Report what you find (this determines the fix).
2. **Fix (preferred): compute single-name % against the full portfolio value including cash.** Use the
   payload's `total_value` (the true portfolio total incl. cash) as the denominator when it is available and
   ≥ the summed positions; recompute weights, HHI-weights, and the breach flags on that basis. Then the label
   "% of the portfolio" is accurate.
   - **If `total_value` is NOT reliably cash-inclusive**, instead keep the invested base but **relabel
     honestly** everywhere the number surfaces — the `single_name` block, the flag `message`, and the T5
     declared `figures` label — to "% of invested holdings (ex-cash)", and reconcile `basis_total_value` vs
     `reported_total_value` in the `note`. Do NOT leave "% of the portfolio" on an ex-cash base.
3. **Keep it consistent end-to-end:** the T5 grounded `figures` for concentration reference these
   percentages — update the figure label/format to match whichever basis you use, so the grounded answer and
   the flag agree. Re-verify the breach flags recompute correctly on the new basis.

## Item 2 (MEDIUM) — keep the loss-ratio disclosures visible to the client
**Finding:** the insurance number is a *claims-based, full-claimed-value, loss-only* ratio (not the textbook
incurred loss ratio), which is **fine and honestly labeled** — the compute already emits `PREMIUM_NOTE`,
`LAE_NOTE`, `STATUS_NOTE`. The risk is synthesis collapsing it to a bare "loss ratio." **Ensure the
qualifier ("claims-based loss ratio") and those disclosure notes reach the client-facing answer** — e.g.
surface them as part of the renewal answer (declared figure label uses "claims loss ratio", and the notes
are included in synthesis rather than dropped). Do not change the math.

## Gate
- Report what the holdings data actually contains (cash or not) and which fix path you took and why.
- Concentration: the single-name %s and breach flags are computed on a basis whose LABEL matches (either
  cash-inclusive "% of portfolio", or ex-cash "% of invested holdings"); the T5 grounded figure agrees; the
  live wealth vertical answer reconciles (the %s sum sensibly against the stated basis).
- Loss ratio: the client-facing renewal answer carries the "claims-based" qualifier + the disclosure notes.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; the 3 verticals + grounding harness
  still pass live.

## Report
The holdings-data finding; the concentration fix path taken (cash-inclusive vs relabel) and the before/after
percentages for the demo portfolio; the figure-label/flag/notes consistency; the loss-ratio disclosure
surfacing; mvn / World-B / live results. Do NOT commit.
