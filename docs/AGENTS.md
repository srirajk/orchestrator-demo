# Conduit AI — Agent Guide

## What is Conduit?

Conduit is an AI assistant built for relationship managers at a private bank. A relationship manager (RM) looks after a book of high-net-worth clients — family offices, trusts, and individuals — and needs a unified view of each client's financial picture every day.

That picture lives across two separate back-office systems that do not talk to each other. Conduit bridges them. An RM types one plain-English question. Conduit figures out which systems to ask, calls them in parallel, and returns one coherent answer — grounded entirely in real data, never invented.

---

## Who uses it

**Relationship Manager (RM)** — the primary user. Sits with clients, manages their portfolio, handles their banking needs. Needs fast answers without toggling between six systems.

**Example user: rm_jane** — a senior RM at Meridian Bank. Her book includes the Whitman Family Office and the Calderon Trust. She does not have access to the Okafor account (that belongs to a different RM). Conduit enforces this automatically.

---

## The two business domains

### Domain 1 — Wealth Management
Everything about how a client's money is invested. Holdings, performance, goals, risk. These are the forward-looking, advisory questions an RM asks when preparing for a client meeting or reviewing a portfolio.

### Domain 2 — Asset Servicing
Everything about how that money is operationally managed day-to-day. Custody, settlements, corporate actions, cash. These are the operational questions — what needs action today, what's settling, what cash is available.

Both domains are accessed in a single question. An RM does not need to know which system holds which data.

---

## Domain 1 — Wealth Management Agents

### Holdings (`acme.wealth.holdings`)
**What it does:** Returns the current portfolio positions for a client relationship — every security held, quantity, market value, and how the portfolio is allocated across asset classes (equity, fixed income, cash, alternatives).

**When an RM asks this:**
- Before a client meeting to review what they own
- To check if an allocation has drifted from the target
- To find the largest positions for discussion

**Example questions:**
> "Show me the holdings for the Whitman Family Office."
> "What is this client invested in?"
> "Give me the portfolio breakdown for Whitman."
> "What's the current allocation across asset classes?"

**What comes back:** A list of positions (ticker, quantity, market value) and a summary of allocation percentages by asset class.

---

### Performance (`acme.wealth.performance`)
**What it does:** Returns how a client's portfolio has performed over a given period — year-to-date return, quarter-to-date return, and profit/loss in dollar terms.

**When an RM asks this:**
- When a client calls asking "how am I doing?"
- For quarterly review preparation
- To compare against a benchmark or target return

**Example questions:**
> "How has the Whitman account performed year-to-date?"
> "What are the YTD returns for this relationship?"
> "Show me the P&L for the Whitman portfolio this quarter."
> "Performance update for Whitman — how are we tracking?"

**What comes back:** Total return percentage, P&L in dollars, the period covered, and comparison to prior period if available.

---

### Goal Planning (`acme.wealth.goal_planning`)
**What it does:** Returns the status of a client's financial goals — retirement, education funding, wealth transfer — and whether each goal is on track given current portfolio value and projected growth.

**When an RM asks this:**
- Annual financial plan review
- When a client asks "am I on track?"
- When a portfolio event (large withdrawal, market move) may have affected goal funding

**Example questions:**
> "Is the Whitman Family Office on track for their goals?"
> "What's the goal planning status for this relationship?"
> "Are the Whitman goals fully funded?"
> "Financial plan progress for this client?"

**What comes back:** A list of goals (name, target amount, target date, current funding level, on-track status) and an overall funded percentage.

---

### Risk Profile (`acme.wealth.risk_profile`)
**What it does:** Returns a client's stated risk tolerance, their current portfolio risk score, and any concentration flags — situations where a single position, sector, or asset class is disproportionately large relative to the overall portfolio.

**When an RM asks this:**
- Before recommending a new investment
- When a position has grown and may now be over-concentrated
- For compliance and suitability review

**Example questions:**
> "What's the risk profile for the Whitman relationship?"
> "Are there any concentration flags on this account?"
> "What risk tolerance does this client have?"
> "Any suitability concerns on the Whitman portfolio?"

**What comes back:** Risk tolerance label (conservative / moderate / growth), a numeric risk score, and a list of concentration flags with the affected positions and recommended limits.

---

## Domain 2 — Asset Servicing Agents

### Custody Positions (`acme.servicing.custody_positions`)
**What it does:** Returns where each asset is physically held — which custodian bank (e.g. DTC, Euroclear, local custodian) holds which securities on behalf of the client.

**When an RM asks this:**
- When a client is transferring assets and needs to know where they are held
- For reconciliation between the wealth system and the custody record
- When a corporate action requires knowing the custodian

**Example questions:**
> "Where are the Whitman assets custodied?"
> "Custody holdings for this relationship."
> "Which custodian holds the Whitman fixed income positions?"
> "Give me the custody breakdown for this account."

**What comes back:** A list of positions grouped by custodian, showing which securities are held where and in what quantity.

---

### Settlement Status (`acme.servicing.settlement_status`)
**What it does:** Returns the status of all pending and recently failed trade settlements for a client — trades that have been executed but not yet settled (typically T+1 or T+2), and any that failed settlement and need attention.

**When an RM asks this:**
- Every morning as part of a daily briefing
- When a client asks why cash hasn't arrived
- When a large trade was placed and the RM needs to confirm it's settling

**Example questions:**
> "Any pending settlements on the Whitman account?"
> "What trades are still unsettled for this relationship?"
> "Are there any failed settlements I should know about?"
> "Settlement status for the Whitman Family Office today."

**What comes back:** A list of pending settlements (trade ID, security, settle date, amount) and any failed settlements requiring action, with failure reason.

---

### Corporate Actions (`acme.servicing.corporate_actions`)
**What it does:** Returns upcoming corporate actions affecting the client's holdings — dividend payments, stock splits, rights issues, merger elections, and tender offers — along with any elections the RM needs to make on behalf of the client.

**When an RM asks this:**
- Weekly review of what elections are due
- When a client holds a security going through a merger or acquisition
- To catch upcoming dividend income for cash flow planning

**Example questions:**
> "Any corporate actions coming up for Whitman?"
> "What elections are due on this account?"
> "Upcoming dividends or splits on the Whitman holdings?"
> "Corporate action calendar for this relationship."

**What comes back:** A list of upcoming corporate actions (type, security, ex-date, election deadline, action required) sorted by deadline.

---

### Cash Management (`acme.servicing.cash_management`)
**What it does:** Returns the current cash balances across all currencies for a client, broken down into settled cash (available now) and unsettled cash (pending settlement), plus projected cash position over the next 30 days.

**When an RM asks this:**
- When a client wants to make a new investment and needs to know available funds
- To check liquidity before a large withdrawal
- To understand the cash impact of pending settlements

**Example questions:**
> "What's the cash position for the Whitman Family Office?"
> "How much cash does this client have available?"
> "Projected liquidity for Whitman over the next month?"
> "Available cash balances on this account."

**What comes back:** Settled and unsettled cash by currency, total available cash, and projected cash position with expected settlement flows.

---

### NAV — Fund Valuation (`acme.servicing.nav`)
**What it does:** Returns the current Net Asset Value (NAV) for an internal fund — the per-unit price as of the latest valuation date. This agent operates at the **fund level**, not the relationship level.

**When an RM asks this:**
- When a client holds units in an internal fund and wants the current value
- For daily fund reporting
- When confirming a subscription or redemption price

**Example questions:**
> "What's the NAV on fund FND-7781 today?"
> "Latest net asset value for this fund."
> "What's the fund priced at as of today?"

**What comes back:** NAV per unit, total fund AUM, and the valuation date.

> **Note:** This agent takes a `fund_id` as input, not a `relationship_id`. This is intentional — it proves the routing system's selectivity. The hero relationship briefing prompt does **not** select this agent because no fund was mentioned.

---

## The hero scenario

> *"Give me a unified relationship briefing on the Whitman Family Office — current holdings, performance, and risk on the wealth side, plus any pending settlements, upcoming corporate actions, and cash position on the servicing side."*

Conduit:
1. Identifies the client: "Whitman Family Office" → `REL-00042`, owned by `rm_jane`
2. Confirms `rm_jane` is authorised to view this relationship (Cerbos check)
3. Selects 7 of the 9 agents — all except `goal_planning` (not asked) and `nav` (no fund mentioned)
4. Calls all 7 in parallel across both domains simultaneously
5. Merges the results into one grounded, attributed answer
6. Streams the response back to the RM

Total time from question to first word of answer: approximately 2–3 seconds.

---

## Entitlement — why Jane can't see Okafor

If `rm_jane` asks about the **Okafor account** (`REL-00188`), Conduit does not return that data. The Okafor relationship belongs to a different RM (`rm_ken`). Jane is not in Okafor's authorised book.

This is enforced by **Cerbos** — a policy engine — before any agent is called. The request is pruned at the gateway level. The response tells Jane explicitly that access was denied, and the reason appears in the glass-box observability panel.

This is not a UI restriction. It is enforced at the data layer, on every request, regardless of how the question is phrased.

---

## What Conduit does not do

- **It does not invent data.** Every number in every answer comes from an agent. If an agent fails, Conduit says so explicitly rather than guessing.
- **It does not execute trades or mutations.** All agents are read-only in this phase.
- **It does not search the internet.** It is grounded entirely in the bank's own systems.
- **It does not answer questions outside its domain.** Off-topic questions (weather, general knowledge) are handled as chitchat without routing to any agent.
