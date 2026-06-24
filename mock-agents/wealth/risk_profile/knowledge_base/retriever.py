"""
Simple keyword-based retriever for the Risk Policy KB.

Computes term-frequency overlap between the query and each document's tags + content.
Returns top-k most relevant policy snippet strings.

In production: replace with vector DB lookup (ChromaDB, Pinecone, Weaviate)
and use the embedding sidecar (http://embeddings:8083/v1/embeddings) for query encoding.
"""
from __future__ import annotations
from .docs import POLICY_DOCS


def _score(query_terms: set, doc: dict) -> float:
    tag_hits = len(query_terms & set(doc["tags"]))
    content_terms = set(doc["content"].lower().split())
    content_hits = len(query_terms & content_terms) / max(len(content_terms), 1)
    return tag_hits * 2.0 + content_hits


def retrieve(query: str, top_k: int = 2) -> list:
    """Return top_k policy snippets most relevant to the query."""
    query_terms = set(query.lower().split())
    scored = [(_score(query_terms, doc), doc) for doc in POLICY_DOCS]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [
        f"[{doc['title']}]\n{doc['content']}"
        for score, doc in scored[:top_k]
        if score > 0
    ]
