# Wealth Management HTTP Service

**Port:** 8081
**Protocol:** HTTP (FastAPI)
**OpenAPI spec:** http://localhost:8081/openapi.json

## Agents

| Agent | Folder | Endpoint | RAG |
|-------|--------|----------|-----|
| Holdings | `holdings/` | `GET /holdings` | No |
| Performance | `performance/` | `GET /performance` | No |
| Risk Profile | `risk_profile/` | `GET /risk-profile` | Yes — Meridian Risk Policy KB |
| Goal Planning | `goal_planning/` | `GET /goal-planning` | No |

## Production-grade features per agent
- **OTel spans**: each agent wraps its handler in `shared/telemetry.agent_span()` — traces exported to OTel Collector
- **Prompt templates**: `prompts.py` in each folder defines system/user templates for when the agent becomes LLM-powered
- **Pydantic schemas**: `schema.py` documents the response model
- **README**: per-agent README explains purpose, KB, and observability

## RAG agent: risk_profile
The risk profile agent augments canned data with relevant Meridian Risk Policy sections:
```
request → retrieve(risk_tolerance + flags, top_k=2) → include policy_context in response
```
Production upgrade: swap `knowledge_base/retriever.py` for ChromaDB + embedding sidecar.

## Fault knobs (all endpoints)
| Param | Effect |
|-------|--------|
| `?_delay_ms=500` | 500ms added latency |
| `?_fail=true` | HTTP 503 returned |

## Running locally
```bash
cd mock-agents/wealth
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8081 --reload
```
