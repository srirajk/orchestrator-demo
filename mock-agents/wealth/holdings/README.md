# Holdings Agent

**ID:** `acme.wealth.holdings`
**Protocol:** HTTP (FastAPI)
**Endpoint:** `GET /holdings?relationship_id=REL-XXXXX`

## What it does
Returns current portfolio positions (ticker, quantity, market value, asset class)
and the total allocation breakdown for a given client relationship.

## RAG / KB
None — canned data only. Positions are deterministic per relationship.

## Observability
- OTel span: `agent.acme.wealth.holdings` with `entity.relationship_id`, `result.position_count`, `result.total_value_usd`

## Fault knobs
| Param | Effect |
|-------|--------|
| `?_delay_ms=500` | Adds 500ms latency |
| `?_fail=true` | Returns HTTP 503 |

## Data
Canned data: `shared/canned_data.py → HOLDINGS`

## Example
```bash
curl "http://localhost:8081/holdings?relationship_id=REL-00042"
```
