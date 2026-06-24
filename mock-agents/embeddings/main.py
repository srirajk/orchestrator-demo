"""
OpenAI-compatible embedding service using sentence-transformers all-MiniLM-L6-v2.
Exposes POST /v1/embeddings — same response shape as OpenAI, so the gateway's
RemoteEmbeddingClient needs no special casing.

Model: all-MiniLM-L6-v2 (384-dim, cosine-normalized)
"""
import os
import logging
from contextlib import asynccontextmanager
from typing import Union

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("embeddings")

MODEL_NAME = os.getenv("EMBEDDING_MODEL", "all-MiniLM-L6-v2")
_model: SentenceTransformer = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    log.info("Loading sentence-transformer model '%s' …", MODEL_NAME)
    _model = SentenceTransformer(MODEL_NAME)
    log.info("Model loaded — dim=%d", _model.get_sentence_embedding_dimension())
    yield


app = FastAPI(title="Meridian Embedding Service", lifespan=lifespan)


# ── request / response shapes ─────────────────────────────────────────────────

class EmbedRequest(BaseModel):
    model: str = MODEL_NAME
    input: Union[str, list[str]]
    encoding_format: str = "float"


# ── routes ────────────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME, "dim": _model.get_sentence_embedding_dimension() if _model else None}


@app.post("/v1/embeddings")
def embed(req: EmbedRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded yet")

    texts = req.input if isinstance(req.input, list) else [req.input]
    vectors = _model.encode(texts, normalize_embeddings=True)

    return {
        "object": "list",
        "data": [
            {
                "object": "embedding",
                "index": i,
                "embedding": v.tolist(),
            }
            for i, v in enumerate(vectors)
        ],
        "model": MODEL_NAME,
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    }
