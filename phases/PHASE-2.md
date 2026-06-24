# Phase 2 — The Nine Agents Respond

**Goal:** All 9 mock agents are live and return realistic canned data — Wealth (4) over
FastAPI (auto-OpenAPI), Asset Servicing (5) over MCP — each with a fault knob.

**Milestones:** M2
**Read first:** `docs/agent-catalog.md` (the 9 agents, exact I/O, example prompts, canned
data shapes, seed entities, fault knobs).

## Build (scope — simple path only)
- **Wealth = FastAPI** service (Python): 4 endpoints (`holdings`, `performance`,
  `goal_planning`, `risk_profile`). FastAPI auto-serves `/openapi.json`. Each returns canned
  JSON per the catalog. Fault knob: `?_delay_ms=` and `?_fail=true`.
- **Asset Servicing = Python MCP server** (`mcp` / FastMCP, SSE): 5 tools
  (`get_custody_positions`, `get_settlements`, `get_corporate_actions`, `get_nav`,
  `get_cash`). Canned JSON per the catalog. Equivalent fault knob via tool arg/env.
- Wire both into docker-compose (`mock-agents`).
- Python tests: pytest + FastAPI `TestClient`; an MCP client smoke test.

## Do NOT build
Registry, routing, gateway-to-agent calls. The agents stand alone this phase.

## Automated acceptance (you run these)
- Each Wealth endpoint returns schema-valid canned JSON; `/openapi.json` is served.
- The MCP server lists 5 tools via `tools/list`; each `tools/call` returns canned data.
- Fault knobs work (latency injected; `_fail` → 503 / error).

## ■ HUMAN TEST GATE → STOP
Tell the human to:
1. Open `http://<mock-agents-host>/openapi.json` in a browser → see the 4 Wealth operations.
2. `curl` the holdings endpoint with `relationship_id=REL-00042` → see canned positions +
   allocation.
3. Call it again with `?_fail=true` → see a 503 (proves the fault knob).
4. (Optional) Use any MCP client to list tools on the Asset-Servicing server → see 5 tools.

**PASS =** the agents respond with realistic data and the fault knob visibly works.
Write steps + status to `BUILD_REPORT.md`, post the `PHASE 2 COMPLETE` banner, and **halt**
until "proceed to Phase 3".
