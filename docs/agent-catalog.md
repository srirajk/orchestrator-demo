# Agent Catalog — The 9 Demo Agents (Conduit demo)

*Every agent the demo registers, fully specified, in one place. Two domains, split by
protocol so the hero prompt fans across HTTP **and** MCP live.*

---

## Design rules (apply to all)

- All agents are **read-only** (`is_mutating: false`) — Phase 1.
- All accept a single **`relationship_id`** for the hero (no cross-agent lookup → clean
  parallel fan-out), **except `nav`** which keys on `fund_id` (so it is correctly *not*
  selected by the hero — proving selectivity).
- Every endpoint returns **canned-but-realistic JSON** matching its output shape.
- Every endpoint supports a **fault knob**: `?_delay_ms=<n>` (inject latency) and
  `?_fail=true` (force 503) — powers the resilience beat.
- `sla_timeout_ms: 2500` unless noted.

---

## Domain 1 — Wealth Management  (protocol: **HTTP / OpenAPI**, port 8081)

| agent_id | skill | input | output (key fields) | data class |
|---|---|---|---|---|
| `acme.wealth.holdings` | `get_holdings` | `relationship_id` | `positions[]`, `allocation_by_class[]` | confidential-pii |
| `acme.wealth.performance` | `get_performance` | `relationship_id`, `period` | `total_return_pct`, `pnl`, `period` | confidential-pii |
| `acme.wealth.goal_planning` | `get_goal_status` | `relationship_id` | `goals[]`, `on_track` | confidential-pii |
| `acme.wealth.risk_profile` | `get_risk_profile` | `relationship_id` | `risk_tolerance`, `concentration_flags[]` | confidential-pii |

**Example prompts (≥4 each, for routing):**
- holdings: "current holdings for the Whitman relationship" · "portfolio allocation for this client" · "what is this account invested in" · "position breakdown for the relationship"
- performance: "how has this account performed" · "year-to-date returns for the client" · "P&L for the relationship this quarter" · "performance of the Whitman portfolio"
- goal_planning: "is this client on track for their goals" · "financial plan status for the relationship" · "are the Whitman goals funded" · "goal progress for this account"
- risk_profile: "risk profile for this client" · "how concentrated is this portfolio" · "risk tolerance for the relationship" · "any concentration flags on this account"

**Canned data shape (example — holdings):**
```jsonc
{ "relationship_id": "REL-00042",
  "positions": [
    { "ticker":"AAPL", "qty":1200, "value":318000 },
    { "ticker":"MSFT", "qty":800,  "value":372000 } ],
  "allocation_by_class": [
    { "asset_class":"Equity", "pct":68 },
    { "asset_class":"Fixed Income", "pct":24 },
    { "asset_class":"Cash", "pct":8 } ] }
```

---

## Domain 2 — Asset Servicing  (protocol: **MCP**, SSE on port 8082)

| agent_id | tool (skill) | input | output (key fields) | data class |
|---|---|---|---|---|
| `acme.servicing.custody_positions` | `get_custody_positions` | `relationship_id` | `holdings_by_custodian[]` | confidential |
| `acme.servicing.settlement_status` | `get_settlements` | `relationship_id` | `pending[]`, `failed[]` | confidential |
| `acme.servicing.corporate_actions` | `get_corporate_actions` | `relationship_id` | `upcoming_actions[]` | confidential |
| `acme.servicing.nav` | `get_nav` | **`fund_id`** | `nav`, `as_of_date` | internal |
| `acme.servicing.cash_management` | `get_cash` | `relationship_id` | `balances[]`, `projected_cash` | confidential |

**Example prompts (≥4 each):**
- custody_positions: "custody holdings for the relationship" · "what's held at the custodian" · "custody positions for this account" · "where are these assets custodied"
- settlement_status: "any pending settlements on this account" · "failed trades for the relationship" · "settlement status for this client" · "unsettled trades I should know about"
- corporate_actions: "upcoming corporate actions" · "any dividends or splits coming up" · "elections due on this account" · "corporate action calendar for the relationship"
- nav: "latest NAV for fund X" · "net asset value of the fund" · "fund NAV as of today" · "what's the NAV on fund 7781"
- cash_management: "cash position for the relationship" · "projected cash for this account" · "available cash balances" · "liquidity on the Whitman account"

**Canned data shape (example — settlements):**
```jsonc
{ "relationship_id":"REL-00042",
  "pending":[ { "trade_id":"T-9912", "security":"MSFT", "settle_date":"2026-06-25", "amount":372000 } ],
  "failed":[]  }
```

---

## The hero prompt and what it resolves to

> *"Give me a unified relationship briefing on the Whitman Family Office relationship —
> current holdings, performance, and risk on the wealth side, plus any pending settlements,
> upcoming corporate actions, and cash position on the servicing side."*

Resolves to **7 of 9**, across both protocols, in parallel:

| Selected (HTTP) | Selected (MCP) | NOT selected |
|---|---|---|
| holdings | settlement_status | `goal_planning` (not asked) |
| performance | corporate_actions | `nav` (fund-level, no relationship_id) |
| risk_profile | cash_management | |
| | custody_positions* | |

\* custody_positions is borderline — include it or not via the confidence floor; either way
the demo shows a *subset* chosen, not a blind fan-out.

The entity **"Whitman Family Office"** is extracted, resolved deterministically to
`relationship_id: "REL-00042"`, and bound to every selected agent's input.

---

## Seed entities (for the demo lookup table)

| reference | resolves to |
|---|---|
| "Whitman Family Office" / "the Whitman relationship" | `REL-00042` (owning_rm: `rm_jane`) |
| "the Calderon Trust" | `REL-00099` (owning_rm: `rm_jane`) — for a second-relationship test |
| "the Okafor account" | `REL-00188` (owning_rm: `rm_ken`) — **out of Jane's book** (entitlement-denial beat) |
| fund reference for nav | `FND-7781` |

The Okafor entry exists specifically to demo the entitlement filter: as `rm_jane`, asking
about Okafor is pruned before fan-out and shown denied in the glass-box.
