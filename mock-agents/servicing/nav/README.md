# NAV Agent

**ID:** `meridian.servicing.nav`
**Protocol:** MCP (FastMCP SSE)
**Tool:** `get_nav(fund_id)`

**Note:** Keyed by `fund_id` (e.g. `FND-7781`), NOT `relationship_id`. This is intentional —
NAV is a fund-level metric, not a relationship-level metric.

## What it does
Returns the latest Net Asset Value, AUM, shares outstanding, and daily change for a fund.

## OTel spans
`agent.meridian.servicing.nav` — `entity.fund_id`, `result.nav`, `result.aum`

## Fault knobs
Set `MCP_FAULT_TOOL=get_nav` to make this tool fail.

## Known funds
| Fund ID | Name |
|---------|------|
| FND-7781 | Meridian Global Equity Fund |
| FND-4423 | Meridian Short Duration Bond Fund |

## Example
```python
result = await session.call_tool("get_nav", {"fund_id": "FND-7781"})
```
