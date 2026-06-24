# Custody Positions Agent

**ID:** `acme.servicing.custody_positions`
**Protocol:** MCP (FastMCP SSE)
**Tool:** `get_custody_positions(relationship_id)`

## What it does
Returns assets held at each custodian (BNY Mellon, State Street, JPMorgan, Goldman Sachs)
for a relationship. Holdings are cross-referenced with Wealth portfolio data for consistency.

## OTel spans
`agent.acme.servicing.custody_positions` — `entity.relationship_id`, `result.custodian_count`

## Fault knobs
Set `MCP_FAULT_TOOL=get_custody_positions` to make this tool fail.

## Example
```python
result = await session.call_tool("get_custody_positions", {"relationship_id": "REL-00042"})
```
