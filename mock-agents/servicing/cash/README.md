# Cash Management Agent

**ID:** `meridian.servicing.cash`
**Protocol:** MCP (FastMCP SSE)
**Tool:** `get_cash(relationship_id)`

## What it does
Returns cash balances (settled and unsettled) by currency, plus projected total cash in USD.
Unsettled cash reflects pending settlement trades (cross-references with Settlements agent).

## OTel spans
`agent.meridian.servicing.cash` — `entity.relationship_id`, `result.projected_cash_usd`, `result.currency_count`

## Fault knobs
Set `MCP_FAULT_TOOL=get_cash` to make this tool fail.

## Example
```python
result = await session.call_tool("get_cash", {"relationship_id": "REL-00042"})
```
