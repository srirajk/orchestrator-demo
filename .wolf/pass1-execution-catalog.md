# Pass 1 Execution Catalog
> Written 2026-06-26. Read this before touching any file. Every agent must check this first.

## What already exists — do NOT rebuild

### Docker Compose services already present
- `otel-collector` — routes traces→Tempo + Langfuse, metrics→Prometheus. Config: `infra/otel-collector.yaml`
- `tempo` — grafana/tempo:2.6.1. Config: `infra/tempo.yaml`
- `prometheus` — Config: `infra/prometheus.yml`
- `grafana` — provisioning at `infra/grafana/provisioning/`
- `clickhouse` — clickhouse/clickhouse-server:24.3.3. ClickHouse user/pass: clickhouse/clickhouse
- `langfuse-db` — Postgres for Langfuse metadata. user/pass: langfuse/langfuse
- `langfuse` — langfuse/langfuse:3. Already receives traces from OTel collector via OTLP/HTTP
- MinIO for Langfuse blob storage

### Grafana provisioning already wired
- `dashboards.yaml` — scans provisioning/dashboards folder, 30s hot-reload
- `datasources.yaml` — Prometheus + Tempo wired with exemplar traceId correlation
- 4 dashboards already exist:
  - `meridian-demo.json` — 24 panels. Live demo view. Request rate, E2E latency, active streams, intent routing
  - `meridian-gateway.json` — 11 panels. Gateway ops. Throughput, intent classifier latency, JVM heap, virtual threads
  - `conversation-trace.json` — 10 panels. Trace explorer by RelationshipID. Uses Tempo.
  - `gateway-performance.json` — 13 panels. Request rate, p50/p95/p99, intent distribution, outbound agent calls

### Existing Micrometer metrics (already emitting)
- `chat.intent{type: FETCH_DATA|FOLLOW_UP|CLARIFY|CHITCHAT}` — ChatService counter
- `intent.classify.duration` — IntentClassifier timer
- `resolver.route.latency` — VectorIndex timer
- `registry.registrations{protocol}` — AgentRegistry counter
- AgentResolver has MeterRegistry (check what it emits)
- JVM metrics auto-collected by Spring Boot Actuator

### OTel Collector pipeline (infra/otel-collector.yaml)
- traces/infra: otlp → batch + resource → tempo (gRPC)
- traces/ai: otlp → batch + resource + filter/drop-actuator → langfuse (OTLP/HTTP)
- metrics: otlp → batch + resource → prometheus (scrape :8889)
- NO log pipeline exists yet

---

## What is MISSING — build this

### TASK 1: Loki — Logs + Correlation
**Files to modify:**
- `docker-compose.yml` — add `grafana/loki:3.0.0` + `grafana/promtail` services
- `infra/otel-collector.yaml` — add loki exporter + log pipeline
- `infra/grafana/provisioning/datasources/datasources.yaml` — add Loki datasource with traceId→Tempo derived field
- `infra/promtail/promtail.yaml` — NEW. Promtail config scraping Docker container logs.

**Loki compose service:**
```yaml
loki:
  image: grafana/loki:3.0.0
  container_name: meridian-loki
  ports: ["3100:3100"]
  command: -config.file=/etc/loki/config.yaml
  volumes:
    - ./infra/loki/config.yaml:/etc/loki/config.yaml:ro
    - loki-data:/loki
  healthcheck:
    test: ["CMD-SHELL", "wget -q --spider http://localhost:3100/ready || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 5

promtail:
  image: grafana/promtail:3.0.0
  container_name: meridian-promtail
  volumes:
    - ./infra/promtail/promtail.yaml:/etc/promtail/config.yaml:ro
    - /var/run/docker.sock:/var/run/docker.sock:ro
    - /var/lib/docker/containers:/var/lib/docker/containers:ro
  command: -config.file=/etc/promtail/config.yaml
  depends_on:
    loki:
      condition: service_healthy
```

**Loki config (infra/loki/config.yaml):**
```yaml
auth_enabled: false
server:
  http_listen_port: 3100
ingester:
  lifecycler:
    ring:
      kvstore:
        store: inmemory
      replication_factor: 1
schema_config:
  configs:
    - from: 2024-01-01
      store: boltdb-shipper
      object_store: filesystem
      schema: v11
      index:
        prefix: index_
        period: 24h
storage_config:
  boltdb_shipper:
    active_index_directory: /loki/index
    cache_location: /loki/index_cache
  filesystem:
    directory: /loki/chunks
limits_config:
  reject_old_samples: true
  reject_old_samples_max_age: 168h
```

**Promtail config (infra/promtail/promtail.yaml):**
```yaml
server:
  http_listen_port: 9080
positions:
  filename: /tmp/positions.yaml
clients:
  - url: http://loki:3100/loki/api/v1/push
scrape_configs:
  - job_name: docker
    docker_sd_configs:
      - host: unix:///var/run/docker.sock
        refresh_interval: 5s
    relabel_configs:
      - source_labels: ['__meta_docker_container_name']
        regex: '/?(.*)'
        target_label: container
      - source_labels: ['__meta_docker_container_label_com_docker_compose_service']
        target_label: service
    pipeline_stages:
      - json:
          expressions:
            traceId: traceId
            convId: convId
            userId: userId
            level: level
      - labels:
          traceId:
          convId:
          userId:
          level:
```

**Loki datasource in datasources.yaml (add to existing file):**
```yaml
  - name: Loki
    type: loki
    uid: loki
    access: proxy
    url: http://loki:3100
    editable: false
    jsonData:
      derivedFields:
        - name: traceId
          matcherRegex: 'traceId=([a-f0-9]{32})'
          url: '${__value.raw}'
          datasourceUid: tempo
          urlDisplayLabel: 'Open in Tempo'
        - name: traceId_json
          matcherRegex: '"traceId":"([a-f0-9]{32})"'
          url: '${__value.raw}'
          datasourceUid: tempo
          urlDisplayLabel: 'Open in Tempo'
```

Add `loki-data` to volumes section in compose.

---

### TASK 2: AgentHarness + Gateway Micrometer Metrics
**File:** `gateway/src/main/java/ai/meridian/gateway/orchestration/harness/AgentHarness.java`

Inject `MeterRegistry` as constructor parameter (add @Value-style after existing params).

**Metrics to add in AgentHarness:**

```java
// 1. Per-agent call counter — business + ops
Counter.builder("meridian.agent.calls")
    .description("Total agent invocations by outcome")
    .tag("agentId", agentId)
    .tag("protocol", agent.protocol())
    .tag("status", result.status().name())  // OK, FAILED, TIMEOUT, BREAKER_OPEN, QUEUE_FULL
    .register(meterRegistry).increment();

// 2. Per-agent latency histogram — ops
Timer.builder("meridian.agent.latency")
    .description("Agent response time distribution")
    .tag("agentId", agentId)
    .tag("protocol", agent.protocol())
    .publishPercentiles(0.5, 0.95, 0.99)
    .publishPercentileHistogram()
    .register(meterRegistry)
    .record(latencyMs, TimeUnit.MILLISECONDS);

// 3. Circuit breaker state gauge — ops (register once per agentId on first call)
// Use computeIfAbsent pattern on a ConcurrentHashMap<String, CircuitBreaker> cbMap
// Register gauge: 0=CLOSED, 1=HALF_OPEN, 2=OPEN
Gauge.builder("meridian.circuit.breaker.state", cb,
    c -> switch (c.getState()) {
        case CLOSED -> 0.0;
        case HALF_OPEN -> 1.0;
        case OPEN -> 2.0;
        default -> -1.0;
    })
    .description("Circuit breaker state: 0=CLOSED 1=HALF_OPEN 2=OPEN")
    .tag("agentId", agentId)
    .register(meterRegistry);

// 4. Bulkhead executing gauge — ops
Gauge.builder("meridian.bulkhead.executing",
    executingSlots.get(agentId),
    s -> (double)(bulkheadMaxConcurrent - s.availablePermits()))
    .description("Currently executing calls (0 to max-concurrent)")
    .tag("agentId", agentId)
    .register(meterRegistry);

// 5. Bulkhead queued gauge — ops
Gauge.builder("meridian.bulkhead.queued",
    queueSlots.get(agentId),
    s -> (double)(bulkheadQueueCapacity - s.availablePermits()))
    .description("Calls waiting for execution slot (0 to queue-capacity)")
    .tag("agentId", agentId)
    .register(meterRegistry);
```

**Note on gauge registration:** Gauges must be registered once (not on every call). Use a `Set<String> registeredGauges` to track which agentIds have gauges already registered. Register inside `computeIfAbsent` for executingSlots/queueSlots so it happens exactly at first-call-per-agent.

**File:** `gateway/src/main/java/ai/meridian/gateway/domain/auth/EntitlementService.java`
Add MeterRegistry, emit:
```java
Counter.builder("meridian.authz.decisions")
    .description("Authorization decisions by outcome and source")
    .tag("decision", allowed ? "ALLOW" : "DENY")
    .tag("resource_type", "relationship")
    .tag("source", result.source())  // "cerbos" | "local-fallback"
    .register(meterRegistry).increment();
```

**File:** `gateway/src/main/java/ai/meridian/gateway/domain/chat/ChatService.java`
Add to existing metrics:
```java
// End-to-end request outcome — business
Counter.builder("meridian.request.outcome")
    .description("Request resolution outcome")
    .tag("outcome", outcome)  // ANSWERED, CLARIFIED, DENIED, FAILED, ERROR
    .register(meterRegistry).increment();

// Fan-out duration — ops
Timer.builder("meridian.fanout.duration")
    .description("Time from routing decision to all agents completing")
    .tag("agent_count", String.valueOf(selectedAgents.size()))
    .publishPercentiles(0.5, 0.95, 0.99)
    .register(meterRegistry);
```

---

### TASK 3: W3C Baggage Propagation
**Goal:** convId + userId flow as W3C baggage on every outbound call so Langfuse and downstream agents see who made the call.

**File:** Create `gateway/src/main/java/ai/meridian/gateway/infrastructure/web/BaggagePropagationFilter.java`

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)  // after JWT filter, before business logic
public class BaggagePropagationFilter implements WebFilter {
    // 1. Extract convId from X-Conversation-Id header (LibreChat sends this) or MDC
    // 2. Extract userId from SecurityContext (JWT sub claim)
    // 3. Put both into OTel Baggage:
    //    Baggage.builder().put("convId", convId).put("userId", userId).build().makeCurrent()
    // 4. Bridge OTel Baggage → MDC so local log lines include them
    //    MDC.put("convId", convId); MDC.put("userId", userId);
    // 5. In finally: MDC.remove("convId"); MDC.remove("userId");
}
```

OTel auto-injects baggage header on all outbound HTTP calls via `HttpAdapter`. FastAPI agents receive `baggage: convId=abc,userId=rm_jane` alongside `traceparent`.

---

### TASK 4: New Grafana Dashboards
Create these two new JSON files in `infra/grafana/provisioning/dashboards/`:

#### 4A: `business-overview.json` — Meridian Business Overview
Audience: executives, product owners. Panels:

Row 1 — Today's Activity
- Stat: Total Queries Today — `sum(increase(meridian_chat_completions_requests_total[24h]))` — big number, green
- Stat: Queries This Hour — `sum(increase(meridian_chat_completions_requests_total[1h]))` 
- Stat: Active Conversations — (from Redis key count or chat metric)
- Stat: Avg Response Time — `histogram_quantile(0.50, rate(http_server_request_duration_seconds_bucket{uri="/v1/chat/completions"}[5m]))*1000` in ms

Row 2 — What Bankers Are Asking
- Pie: Intent Breakdown — `sum by (type) (rate(chat_intent_total[1h]))` — FETCH_DATA=blue, CLARIFY=orange, FOLLOW_UP=green, CHITCHAT=gray
- Bar (horizontal): Capability Demand — `sum by (agentId) (increase(meridian_agent_calls_total{status="OK"}[1h]))` — shows which agents are most used (business insight: what data do RMs actually want?)
- Timeseries: Query Volume Trend — last 24h with intent breakdown

Row 3 — Access Control
- Stat: Authorization Allow Rate — `sum(rate(meridian_authz_decisions_total{decision="ALLOW"}[1h])) / sum(rate(meridian_authz_decisions_total[1h])) * 100` — green if >95%, red if <90%
- Pie: Allow vs Deny — `sum by (decision) (rate(meridian_authz_decisions_total[1h]))`
- Stat: Cerbos vs Fallback — `sum(rate(meridian_authz_decisions_total{source="cerbos"}[1h])) / sum(rate(meridian_authz_decisions_total[1h])) * 100` — should be ~100%

Row 4 — Agent Availability (Business Health Grid)
- Table: Agent Status — one row per agentId. Columns: Agent, Protocol, State, Calls/min, Error%, p95ms
  - State colored: CLOSED=green, HALF_OPEN=yellow, OPEN=red
  - Error% threshold: <5%=green, 5-15%=yellow, >15%=red

Row 5 — Request Outcomes
- Bar: Answered vs Clarified vs Denied vs Failed — `sum by (outcome) (rate(meridian.request.outcome_total[1h]))`
- Gauge: Success Rate — answered/(answered+failed) * 100

Dashboard variables: `$__range`, time range = last 6h default, auto-refresh 30s.

#### 4B: `agent-health.json` — Agent Health Deep Dive
Audience: SREs, gateway engineers. Panels:

Row 1 — Overview
- Heatmap: Agent Latency Distribution — `sum by (agentId, le) (rate(meridian_agent_latency_seconds_bucket[5m]))` — x=time, y=latency buckets, color=frequency
- Timeseries: All-agent Error Rate — one line per agentId, `rate(meridian_agent_calls_total{status!="OK"}[5m]) / rate(meridian_agent_calls_total[5m])`
- Bar chart: Protocol Comparison — avg latency HTTP vs MCP

Row 2 — Per-Agent Detail (repeat panel by agentId variable OR 9 stat rows)
For EACH agent (one row per agent, 9 rows total):
- Stat: Call Rate (req/min)
- Stat: Error Rate % (threshold: green<5, yellow<15, red≥15)
- Stat: p95 Latency ms (threshold: green<1000, yellow<3000, red≥3000)
- Stat: CB State (value map: 0=CLOSED 1=HALF_OPEN 2=OPEN, colors green/orange/red)
- Bar Gauge: Bulkhead Executing (0 to max-concurrent)
- Bar Gauge: Bulkhead Queued (0 to queue-capacity)
- Stat: Timeout Rate %

Row 3 — Resilience Story
- Timeseries: Circuit Breaker State History — all agents over time (color coded)
- Timeseries: Queue Depth Trend — bulkhead pressure over time
- Stat: Agents Currently OPEN — `count(meridian_circuit_breaker_state == 2)` — threshold: 0=green, >0=red

Dashboard variables: `$agentId` (multi-select, all agents), time range = last 1h, auto-refresh 10s.

#### 4C: Enhance `conversation-trace.json`
ADD to existing dashboard:
- NEW Row: Conversation Logs (needs Loki datasource)
  - Variable: `$convId` text input
  - Log panel: `{service=~"meridian.*"} |= "$convId"` — shows all log lines for this conversation across all services
  - Log panel filter: level ERROR/WARN highlighted red
  - Derived field: traceId → "Open in Tempo" link

#### 4D: Enhance `meridian-demo.json`
ADD to existing 24 panels:
- Stat: Agent Health Score — `(count(meridian_circuit_breaker_state == 0) / count(meridian_circuit_breaker_state)) * 100` — "X/9 agents healthy"
- Stat: Last Request Outcome — latest value of `meridian.request.outcome` label
- Timeseries: Live CB State — real-time circuit breaker states for demo drama

---

### TASK 5: User Account Seeding
**File:** Create `scripts/seed-users.sh` + `scripts/seed-data/principals.json`

3 principals to seed into Redis (Cerbos reads principal attributes from Redis):

```json
[
  {
    "id": "rm_jane",
    "role": "relationship_manager",
    "domain": "wealth",
    "book": ["REL-00042", "REL-00099"],
    "clearance": "internal"
  },
  {
    "id": "rm_carlos",
    "role": "relationship_manager", 
    "domain": "wealth",
    "book": ["REL-00099"],
    "clearance": "internal"
  },
  {
    "id": "rm_guest",
    "role": "relationship_manager",
    "domain": "wealth",
    "book": [],
    "clearance": "internal"
  }
]
```

Script: idempotent Redis `SET` for each principal. Run via compose `depends_on` with service-completed-successfully. Add as a one-shot service in compose.

---

## Metric → Dashboard mapping (verification checklist)

| Metric | Emitted by | Used in dashboard |
|---|---|---|
| `chat.intent{type}` | ChatService | meridian-gateway, meridian-demo, business-overview |
| `intent.classify.duration` | IntentClassifier | meridian-gateway, gateway-performance |
| `resolver.route.latency` | VectorIndex | gateway-performance |
| `meridian.agent.calls{agentId,protocol,status}` | AgentHarness (NEW) | agent-health, business-overview, gateway-performance |
| `meridian.agent.latency{agentId,protocol}` | AgentHarness (NEW) | agent-health, conversation-trace |
| `meridian.circuit.breaker.state{agentId}` | AgentHarness (NEW) | agent-health, business-overview, meridian-demo |
| `meridian.bulkhead.executing{agentId}` | AgentHarness (NEW) | agent-health |
| `meridian.bulkhead.queued{agentId}` | AgentHarness (NEW) | agent-health |
| `meridian.authz.decisions{decision,source}` | EntitlementService (NEW) | business-overview |
| `meridian.request.outcome{outcome}` | ChatService (NEW) | business-overview, meridian-demo |
| `meridian.fanout.duration{agent_count}` | ChatService (NEW) | gateway-performance |
| Loki logs with traceId label | Promtail | conversation-trace (Loki panel) |
| W3C baggage convId/userId | BaggagePropagationFilter (NEW) | Langfuse session view |

---

## Files touched — conflict map

| File | Task |
|---|---|
| `docker-compose.yml` | Task 1 only (Loki + Promtail services + loki-data volume) |
| `infra/otel-collector.yaml` | Task 1 only (add log pipeline) |
| `infra/grafana/provisioning/datasources/datasources.yaml` | Task 1 only (add Loki datasource) |
| `infra/loki/config.yaml` | Task 1 (NEW file) |
| `infra/promtail/promtail.yaml` | Task 1 (NEW file) |
| `infra/grafana/provisioning/dashboards/business-overview.json` | Task 4A (NEW) |
| `infra/grafana/provisioning/dashboards/agent-health.json` | Task 4B (NEW) |
| `infra/grafana/provisioning/dashboards/conversation-trace.json` | Task 4C (enhance existing) |
| `infra/grafana/provisioning/dashboards/meridian-demo.json` | Task 4D (enhance existing) |
| `AgentHarness.java` | Task 2 only |
| `EntitlementService.java` | Task 2 only |
| `ChatService.java` | Task 2 only |
| `BaggagePropagationFilter.java` | Task 3 (NEW file) |
| `scripts/seed-users.sh` | Task 5 (NEW) |
| `scripts/seed-data/principals.json` | Task 5 (NEW) |

NO file is touched by more than one task. Zero merge conflicts between parallel agents.

---

## Definition of done for Pass 1

- [ ] `docker compose up -d` → all services healthy including Loki + Promtail
- [ ] Grafana loads 6 dashboards at startup (4 existing + 2 new) — no manual import
- [ ] Loki datasource appears in Grafana → `{service="meridian-gateway"}` returns logs
- [ ] Click traceId in a Loki log line → opens Tempo trace
- [ ] `/actuator/prometheus` returns `meridian_agent_calls_total`, `meridian_circuit_breaker_state`, `meridian_authz_decisions_total`
- [ ] `business-overview.json` shows non-zero data after one hero prompt
- [ ] `agent-health.json` shows all 9 agents with CB state CLOSED
- [ ] Seeding script creates 3 principals; rm_jane allowed REL-00042, denied REL-00188
- [ ] W3C baggage header `convId=xxx` visible on agent outbound calls (verify via Langfuse trace)
