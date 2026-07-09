# Corporate Actions Agent (with RAG)

**ID:** `meridian.servicing.corporate_actions`
**Protocol:** MCP (FastMCP SSE)
**Tool:** `get_corporate_actions(relationship_id)`

## What it does
Returns upcoming corporate actions (dividends, splits, rights issues). **RAG-augmented**:
retrieves relevant ISDA rules and Meridian SOP sections from the internal knowledge base
and includes them as `regulatory_context` in the response.

## RAG Pipeline
```
action types extracted from data
        ↓
knowledge_base/retriever.py (keyword match → top-2 ISDA/SOP docs)
        ↓
regulatory_context included in JSON response
```

## OTel spans
`agent.meridian.servicing.corporate_actions` — `entity.relationship_id`, `result.action_count`, `rag.retrieved_docs`

## Fault knobs
Set `MCP_FAULT_TOOL=get_corporate_actions` to make this tool fail.

## Example response snippet
```json
{
  "upcoming_actions": [...],
  "regulatory_context": ["[ISDA Rule 2023-07: Stock Split]\n...", "[Meridian SOP v1.4 §2]\n..."]
}
```
