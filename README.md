# Meridian AI Gateway

An enterprise AI gateway for a bank. A relationship manager types one plain-English question into Meridian (a branded LibreChat); the gateway routes it to the right specialist agents across HTTP and MCP, enforces entitlements, merges results into one streamed answer, and shows the routing decision live in a glass-box panel.

---

## Prerequisites

| Tool | Version |
|------|---------|
| Docker + Compose v2 | Docker 24+ |
| JDK | 21+ (25 preferred — JEP-491 virtual-thread pinning fix) |
| Maven | 3.9+ |
| Python | 3.11+ |
| Node | 20+ |

---

## Quick start

```bash
# 1. Set your Z.AI API key (copy the example, fill in your key)
cp .env.example .env
# Edit .env and set ZAI_API_KEY=...

# 2. Build and start the core stack
docker compose up -d --build

# 3. Wait for all services to be healthy
./scripts/wait-for-healthy.sh

# 4. Open LibreChat
open http://localhost:3080
```

---

## Services

| Service | URL | Notes |
|---------|-----|-------|
| LibreChat (Meridian UI) | http://localhost:3080 | Sign up / log in, then chat |
| Meridian Gateway | http://localhost:8080 | OpenAI-compatible API |
| Gateway health | http://localhost:8080/actuator/health | |
| Redis Stack | localhost:6379 | Vector index + RedisJSON |
| RedisInsight | http://localhost:8001 | Optional dev UI |
| Mock Agents | http://localhost:8090 | Phase-1 placeholder |

---

## Run the full verification suite

```bash
./scripts/verify.sh
```

This builds the gateway, starts compose, waits for healthy, and runs API smoke tests.

---

## Scale profile (Phase 7 — load test only)

```bash
docker compose --profile scale up -d
# Grafana → http://localhost:3000  (admin / meridian)
# Prometheus → http://localhost:9090
```

---

## Architecture

```
LibreChat (Meridian branded)
        │  POST /v1/chat/completions  stream=true
        ▼
Meridian Gateway (Spring Boot, virtual threads)
   Resolver  →  ProtocolAdapter (HTTP | MCP)
   Harness   →  Resilience4j + OTel spans
   Synthesizer → Z.AI GLM streaming answer
        │
        ├── Redis Stack (vector index, agent registry, RedisJSON)
        ├── Cerbos PDP (ABAC entitlements)
        └── Mock Agents (Wealth HTTP / Asset Servicing MCP)
```

See `docs/technical-architecture-clear-boundaries.md` for the full picture.

---

## Build phases

| Phase | Gate |
|-------|------|
| **1** ✅ | Type in LibreChat → streamed reply appears |
| 2 | 9 mock agents respond with canned data |
| 3 | Hero prompt routes to the correct ~7 agents |
| 4 | Hero prompt returns one grounded answer (HTTP + MCP) |
| 5 | Glass-box live + out-of-book relationship denied |
| 6 | Clarification + agent-kill resilience + Meridian branding |
| 7 | Routing-accuracy eval + flat p99 under load |

See `BUILD_REPORT.md` for current status.
