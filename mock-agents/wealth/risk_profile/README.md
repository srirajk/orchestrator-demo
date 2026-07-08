# Risk Profile Agent (with RAG)

**ID:** `meridian.wealth.risk_profile`
**Protocol:** HTTP (FastAPI)
**Endpoint:** `GET /risk-profile?relationship_id=REL-XXXXX`

## What it does
Returns the client's risk tolerance score, max drawdown tolerance, and any active
concentration flags. **RAG-augmented**: also retrieves the most relevant Meridian
Risk Policy sections from an internal knowledge base and includes them in the response
as `policy_context`.

## RAG Pipeline
```
user query → keyword extraction
                  ↓
            knowledge_base/retriever.py
                  ↓  (TF-IDF keyword match → top-2 policy docs)
            knowledge_base/docs.py  (5 Meridian Risk Policy chunks)
                  ↓
response includes `policy_context: ["[Policy Title]\n...text...", ...]`
```

**Production upgrade path:** Replace `retriever.py`'s keyword scorer with a call to the
embedding sidecar (`http://embeddings:8083/v1/embeddings`) + cosine similarity over a
ChromaDB collection of the full policy document corpus.

## OTel spans
`agent.meridian.wealth.risk_profile` — attributes: `entity.relationship_id`, `result.risk_score`,
`result.concentration_flags`, `rag.retrieved_docs`

## Fault knobs
| Param | Effect |
|-------|--------|
| `?_delay_ms=500` | Adds 500ms latency |
| `?_fail=true` | Returns HTTP 503 |

## Example
```bash
curl "http://localhost:8081/risk-profile?relationship_id=REL-00042"
```
