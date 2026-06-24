# Asset Servicing MCP Service

**Port:** 8082
**Protocol:** MCP over SSE (FastMCP)
**SSE endpoint:** http://localhost:8082/sse

## Agents

| Agent | Folder | MCP Tool | RAG |
|-------|--------|----------|-----|
| Settlements | `settlements/` | `get_settlements(relationship_id)` | No |
| Corporate Actions | `corporate_actions/` | `get_corporate_actions(relationship_id)` | Yes — ISDA + Meridian SOP KB |
| Custody Positions | `custody/` | `get_custody_positions(relationship_id)` | No |
| NAV | `nav/` | `get_nav(fund_id)` | No |
| Cash Management | `cash/` | `get_cash(relationship_id)` | No |

## Production features
- OTel spans per agent (service.name: `meridian-servicing`)
- RAG in `corporate_actions/`: ISDA rules + Meridian SOP retrieved on each call
- Prompt templates in each agent's `prompts.py`
- Pydantic response schemas in `schema.py`

## Fault knobs
| Env var | Effect |
|---------|--------|
| `MCP_FAULT_TOOL=get_settlements` | That tool fails |
| `MCP_FAULT_ALL=true` | All tools fail |
| `MCP_FAULT_DELAY_MS=500` | 500ms latency on every call |

## Running locally
```bash
cd mock-agents/servicing
pip install -r requirements.txt
python server.py
```
