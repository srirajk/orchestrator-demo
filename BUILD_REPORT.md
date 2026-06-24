# Build Report — Meridian AI Gateway

---

## PHASE 3 COMPLETE — Evidence captured below. Phase 4 in progress.

> **Status (2026-06-24):** Phases 1, 2, 3 done and verified live.
> Phase 4 (input synthesis + executor + Z.AI answer synthesis) is actively building.

---

## Phase 1 — Gateway Pipe + LibreChat SSE (M0 + M1) ✅ DONE

### Built

| Component | Notes |
|-----------|-------|
| Spring Boot 3.5.0 gateway | Java 21 docker, Java 25 local; virtual threads ON |
| `POST /v1/chat/completions` | Byte-exact OpenAI SSE: role δ → content δ → [DONE] |
| `GET /v1/models` | Returns `meridian-assistant` |
| LibreChat integration | Config-only; `titleConvo: false`; model selector hidden |
| Auto-title short-circuit | Keyword guard in ChatService; never reaches agent pipeline |
| MDC logging | `requestId`, `conversationId`, `userId` on every log line |
| OTel + Grafana stack | Always-on (Tempo, Prometheus, Grafana at :3000) |
| docker-compose.yml | All services with healthchecks |

### Live Evidence (verified 2026-06-24)

```
$ curl -s http://localhost:8080/v1/models
{"object":"list","data":[{"id":"meridian-assistant","object":"model","created":0,...}]}

$ curl -sN -X POST http://localhost:8080/v1/chat/completions \
    -H "Content-Type: application/json" \
    -d '{"model":"meridian-assistant","messages":[{"role":"user","content":"hello"}],"stream":true}'
data:{"id":"chatcmpl-10ddcb10...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant"},"finish_reason":null}]}
data:{"id":"chatcmpl-10ddcb10...","choices":[{"index":0,"delta":{"content":"Hello!"},"finish_reason":null}]}
... content chunks ...
data:{"id":"chatcmpl-10ddcb10...","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data:[DONE]

$ curl -so /dev/null -w "%{http_code}" http://localhost:3080/
200   ← LibreChat UP
```

### Hard rules
- ✅ (a) SSE byte-correct — verified above
- ✅ (a) Auto-title short-circuit — `titleConvo:false` + keyword guard
- ✅ (e) Simple path only — placeholder, no agent routing
- ✅ (f) LibreChat config-only, no code fork

### Human test steps
1. `docker compose up -d --build && ./scripts/wait-for-healthy.sh 180`
2. Open http://localhost:3080 → register → type "hello"
3. **PASS:** streamed reply appears word-by-word

---

## Phase 2 — Mock Agents (M2) ✅ DONE

### Built

| Component | Notes |
|-----------|-------|
| `mock-agents/wealth/` | FastAPI at :8081; 4 endpoints; auto-OpenAPI at `/openapi.json` |
| `mock-agents/servicing/` | FastMCP at :8082; 5 tools over SSE transport |
| `mock-agents/embeddings/` | FastAPI embedding sidecar at :8083; all-MiniLM-L6-v2 384-dim |
| Fault knobs HTTP | `?_delay_ms=N&_fail=true` per endpoint |
| Fault knobs MCP | `MCP_FAULT_TOOL`, `MCP_FAULT_ALL`, `MCP_FAULT_DELAY_MS` env vars |
| Canned data | REL-00042 (Whitman), REL-00099 (Chen), REL-00188; FND-7781 |

### Live Evidence (verified 2026-06-24)

```
# Wealth OpenAPI spec
$ curl -s http://localhost:8081/openapi.json | python3 -c "import sys,json;print(list(json.load(sys.stdin)['paths'].keys()))"
['/holdings', '/performance', '/goal-planning', '/risk-profile']

# Wealth holdings — REL-00042 (Whitman Family Office)
$ curl -s "http://localhost:8081/holdings?relationship_id=REL-00042"
{
  "relationship_id": "REL-00042",
  "relationship_name": "Whitman Family Office",
  "positions": [
    {"ticker":"AAPL","qty":1200,"value":318000},
    {"ticker":"MSFT","qty":800,"value":372000},     ← cross-matches settlement T-9912
    {"ticker":"GOOGL","qty":150,"value":289500},
    {"ticker":"JPM","qty":2500,"value":487500},
    {"ticker":"T-BILL-2026","value":180000}
  ],
  "total_market_value_usd": 1647000
}

# Fault knob (HTTP)
$ curl -s "http://localhost:8081/holdings?relationship_id=REL-00042&_fail=true"
→ HTTP 503 {"error": "Simulated failure"}

# MCP settlements — SSE protocol test
→ tools/call get_settlements {relationship_id: REL-00042}
→ pending:[{trade_id:T-9912, security:MSFT, amount:372000, settle_date:2026-06-25, status:pending}]
→ Amount $372k MATCHES MSFT holdings value ✓

# MCP health
$ curl -s http://localhost:8082/health
{"status":"ok","service":"servicing-mcp","version":"0.2.0"}
```

---

## Phase 3 — Agent Registry + Vector Resolver (M3 + M4) ✅ DONE

### Built

| Component | Notes |
|-----------|-------|
| 9 agent manifests | JSON Schema (draft 2020-12) validated; bundled in classpath:manifests/ |
| `AgentRegistry` | validate → introspect → store RedisJSON → embed → HNSW index |
| `VectorIndex` | HNSW 384-dim COSINE; raw-byte vector; dedup by agent_id |
| `RemoteEmbeddingClient` | RestTemplate → embedding sidecar; 384-dim semantic vectors |
| `HashEmbeddingClient` | SHA-256 fallback (meridian.embedding.provider=hash) |
| Bootstrap retry | Waits 45s for embedding service before registering manifests |
| `AgentIntrospector` | OpenAPI parser (HTTP) + MCP tools/list (MCP) |
| `AgentResolver` | Stage A: KNN + confidence floor 0.30; Stage B: is_mutating==false filter |
| Admin API | POST/GET/PUT/DELETE /admin/agents; GET /debug/resolve |

### Live Evidence (verified 2026-06-24)

```
# All 9 agents loaded and indexed
$ curl -s http://localhost:8080/admin/agents | python3 -c "import sys,json;a=json.load(sys.stdin);print(f'{len(a)} agents, all_indexed={all(x[chr(105)+chr(110)+chr(100)+chr(101)+chr(120)+chr(101)+chr(100)] for x in a)}')"
9 agents, all_indexed=True

Agents:
  acme.servicing.cash_management     mcp   indexed=True
  acme.servicing.corporate_actions   mcp   indexed=True
  acme.servicing.custody_positions   mcp   indexed=True
  acme.servicing.nav                 mcp   indexed=True
  acme.servicing.settlement_status   mcp   indexed=True
  acme.wealth.goal_planning          http  indexed=True
  acme.wealth.holdings               http  indexed=True
  acme.wealth.performance            http  indexed=True
  acme.wealth.risk_profile           http  indexed=True

# Invalid manifest rejected
$ curl -s -w "\nHTTP %{http_code}" -X POST http://localhost:8080/admin/agents \
    -H "Content-Type: application/json" -d '{"agent_id":"test.bad","name":"Bad"}'
{"error":"Manifest schema validation failed: required property 'description' not found; ..."}
HTTP 400

# Hero prompt routing (Phase 3 acceptance criterion)
Prompt: "Give me a unified relationship briefing on the Whitman Family Office
         relationship — current holdings, performance, and risk on the wealth side,
         plus any pending settlements, upcoming corporate actions, and cash position
         on the servicing side."

Result:
  selected = 8 agents   top_score = 0.727   fallback = False
  nav EXCLUDED ✓   (nav only selected when fund_id query is explicit)
  protocols = ['http', 'mcp']   ← both protocols ✓

  holdings          http  0.727
  settlement_status mcp   0.673
  performance       http  0.587
  cash_management   mcp   0.522
  goal_planning     http  0.468
  corporate_actions mcp   0.401
  custody_positions mcp   0.386
  risk_profile      http  0.313

# Nav selected for targeted query
$ curl -s "http://localhost:8080/debug/resolve?prompt=what+is+the+NAV+of+fund+FND-7781"
→ nav selected at score=0.881 ✓   (nav selected when relevant, excluded from hero ✓)
```

### Hard rules
- ✅ (b) Zero fabricated IDs — entity resolution is deterministic lookup only (built in Phase 4)
- ✅ (e) Simple path — flat resolver, no hierarchical routing
- ✅ (h) OTel instrumentation active from M3 onwards

---

## Phase 4 — End-to-End Answer (M5, M6, M7) — IN PROGRESS

### Building
- [x] Input synthesis `EntityExtractor` (Z.AI GLM tool-calling)
- [x] `EntityResolver` (deterministic lookup, no LLM)
- [x] `InputSynthesizerImpl` (Extract→Resolve→Bind)
- [ ] `ProtocolAdapter` interface + `HttpAdapter` + `McpAdapter`
- [ ] `FlatPlanExecutor` + Resilience4j harness per agent
- [ ] Z.AI GLM answer synthesis (streaming, grounded)
- [ ] Wire into `/v1/chat/completions` pipeline

### Human test gate (after Phase 4)
1. Open LibreChat at http://localhost:3080
2. Type the hero prompt (verbatim, from `docs/agent-catalog.md`)
3. **Confirm** one merged answer streams in covering: holdings, performance, risk, settlements, corporate actions, cash
4. **Pick a number** in the answer — confirm it matches canned data (grounding works)
5. (Optional) Set `MCP_FAULT_TOOL=get_settlements`, re-ask → answer returns, states settlement data unavailable

**PASS =** the real demo answer appears, grounded and merged across both protocols.

---

## Running Stack (2026-06-24)

```
meridian-gateway       UP (healthy)  :8080   ← Spring Boot + virtual threads
meridian-redis         UP (healthy)  :6379   ← Redis Stack (RediSearch HNSW + RedisJSON)
meridian-embeddings    UP (healthy)  :8083   ← sentence-transformers all-MiniLM-L6-v2
meridian-wealth-http   UP (healthy)  :8081   ← FastAPI Wealth domain (4 endpoints)
meridian-servicing-mcp UP (healthy)  :8082   ← FastMCP Asset Servicing (5 tools, SSE)
meridian-mongodb       UP (healthy)  :27017  ← LibreChat persistence
meridian-librechat     UP            :3080   ← Chat UI
meridian-grafana       UP            :3000   ← Dashboards (admin/meridian)
meridian-prometheus    UP            :9090
meridian-tempo         UP            :3200
meridian-otel          UP            :4317/4318
```

## Quick Verify Commands

```bash
# Phase 1
curl -s http://localhost:8080/v1/models
curl -sN -X POST http://localhost:8080/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"meridian-assistant","messages":[{"role":"user","content":"hi"}],"stream":true}' | head -5

# Phase 2
curl -s "http://localhost:8081/holdings?relationship_id=REL-00042" | python3 -m json.tool | head -20
curl -s "http://localhost:8081/openapi.json" | python3 -c "import sys,json;print(list(json.load(sys.stdin)['paths'].keys()))"

# Phase 3
curl -s http://localhost:8080/admin/agents | python3 -c "import sys,json;a=json.load(sys.stdin);print(len(a),'agents')"
curl -s "http://localhost:8080/debug/resolve?prompt=current+holdings+risk+and+settlements+for+Whitman" | python3 -m json.tool

# Observability
open http://localhost:3000        # Grafana (admin/meridian)
open http://localhost:9090        # Prometheus
open http://localhost:3080        # LibreChat
```

---

*Updated: 2026-06-24 — Phase 3 complete, Phase 4 in progress*
