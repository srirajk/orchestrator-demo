"""Keyword retriever for Corporate Action Rules KB."""
from .docs import CORP_ACTION_DOCS


def _score(query_terms: set, doc: dict) -> float:
    tag_hits = len(query_terms & set(doc["tags"]))
    content_terms = set(doc["content"].lower().split())
    content_hits = len(query_terms & content_terms) / max(len(content_terms), 1)
    return tag_hits * 2.0 + content_hits


def retrieve(query: str, top_k: int = 2) -> list:
    """Return top_k ISDA/SOP snippets most relevant to the query."""
    query_terms = set(query.lower().split())
    scored = [(_score(query_terms, doc), doc) for doc in CORP_ACTION_DOCS]
    scored.sort(key=lambda x: x[0], reverse=True)
    return [
        f"[{doc['title']}]\n{doc['content']}"
        for score, doc in scored[:top_k]
        if score > 0
    ]
