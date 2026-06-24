# Goal Planning Agent

**ID:** `acme.wealth.goal_planning`
**Protocol:** HTTP (FastAPI)
**Endpoint:** `GET /goal-planning?relationship_id=REL-XXXXX`

## What it does
Returns the client's financial goals (retirement, education, etc.) with current progress,
target amounts, target dates, and on-track status.

## OTel spans
`agent.acme.wealth.goal_planning` — attributes: `entity.relationship_id`, `result.goal_count`, `result.overall_on_track`

## Fault knobs
| Param | Effect |
|-------|--------|
| `?_delay_ms=500` | Adds 500ms latency |
| `?_fail=true` | Returns HTTP 503 |

## Example
```bash
curl "http://localhost:8081/goal-planning?relationship_id=REL-00042"
```
