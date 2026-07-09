# Settlement Status Agent

**ID:** `meridian.servicing.settlement_status`
**Protocol:** MCP (FastMCP SSE)
**Tool:** `get_settlements(relationship_id)`

## What it does
Returns pending and failed settlement trades for a relationship. Failed trades are flagged
with a Phoenix trace alert attribute (`alert.failed_settlements`).

## OTel spans
`agent.meridian.servicing.settlement_status` — `entity.relationship_id`, `result.pending_count`, `result.failed_count`, `alert.failed_settlements`

## Fault knobs
Set `MCP_FAULT_TOOL=get_settlements` to make this tool fail for resilience testing.

## Example
```python
# Via MCP client:
result = await session.call_tool("get_settlements", {"relationship_id": "REL-00042"})
```
