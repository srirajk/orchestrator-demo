# Performance Agent

**ID:** `acme.wealth.performance`
**Protocol:** HTTP (FastAPI)
**Endpoint:** `GET /performance?relationship_id=REL-XXXXX&period=YTD`

## What it does
Returns YTD/1Y total return, P&L, benchmark comparison, alpha, Sharpe ratio.

## OTel spans
`agent.acme.wealth.performance` — attributes: `entity.relationship_id`, `query.period`, `result.total_return_pct`, `result.alpha`

## Fault knobs
| Param | Effect |
|-------|--------|
| `?_delay_ms=500` | Adds 500ms latency |
| `?_fail=true` | Returns HTTP 503 |

## Example
```bash
curl "http://localhost:8081/performance?relationship_id=REL-00042&period=YTD"
```
