"""
Stub LLM — an OpenAI-compatible /v1/chat/completions that records real responses once and
replays them offline with a configurable latency.

WHY THIS EXISTS
    Load-testing the gateway against a real provider measures the provider, not the gateway.
    At 25 VUs Conduit issues ~26 chat completions/sec (2-3 per request, plus one per agentic
    agent tool call); a live run produced 622 x HTTP 429 and then exhausted the account's quota.
    The binding constraint was OpenAI's rate limit, so the run could neither prove nor disprove
    anything about the gateway. It also cost real money.

WHY RECORD/REPLAY RATHER THAN SYNTHESISED RESPONSES
    The four gateway call sites expect four different shapes, and the *fields* the classifier
    emits are derived from the domain manifests (entity_type.extract_as) — not knowable to a
    stub without embedding domain knowledge, which is exactly what World-B forbids. Recording a
    real response and replaying it byte-for-byte sidesteps the whole problem: the payload is
    valid by construction, for every call site, forever.

MODES  (STUB_MODE)
    record   forward upstream, return the real response, and persist it to the cassette
    replay   serve from the cassette; a miss is a hard 503 (never silently fabricate)
    passthru forward upstream, persist nothing (debugging)

LATENCY
    STUB_LATENCY_MS is applied before the response. For streams it is spread across the chunks,
    so time-to-first-token stays realistic. This is the knob: hold the gateway fixed, vary
    latency, and you are finally measuring the gateway.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import os
import random
import time
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("stub-llm")

MODE = os.getenv("STUB_MODE", "replay").lower()
UPSTREAM = os.getenv("STUB_UPSTREAM_BASE_URL", "https://api.openai.com/v1").rstrip("/")
UPSTREAM_KEY = os.getenv("STUB_UPSTREAM_API_KEY", "")
LATENCY_MS = int(os.getenv("STUB_LATENCY_MS", "0") or 0)
JITTER_MS = int(os.getenv("STUB_JITTER_MS", "0") or 0)
CASSETTE = Path(os.getenv("STUB_CASSETTE", "/cassettes/cassette.json"))

app = FastAPI(title="stub-llm")
_lock = asyncio.Lock()
_cassette: dict[str, Any] = {}
_stats = {"hit": 0, "miss": 0, "recorded": 0}


def _load() -> None:
    global _cassette
    if CASSETTE.exists():
        _cassette = json.loads(CASSETTE.read_text())
        log.info("loaded %d cassette entries from %s", len(_cassette), CASSETTE)
    else:
        _cassette = {}
        log.info("no cassette at %s (mode=%s)", CASSETTE, MODE)


async def _save() -> None:
    async with _lock:
        CASSETTE.parent.mkdir(parents=True, exist_ok=True)
        CASSETTE.write_text(json.dumps(_cassette, indent=1, sort_keys=True))


def _key(body: dict) -> str:
    """
    Cache key over the request's *semantic* fields. Deliberately excludes anything volatile
    (ids, timestamps) so a replay of the same conversation hits.
    """
    salient = {
        "model": body.get("model"),
        "stream": bool(body.get("stream")),
        "messages": [{"role": m.get("role"), "content": m.get("content")}
                     for m in body.get("messages", [])],
        "tools": body.get("tools"),
        "response_format": body.get("response_format"),
    }
    blob = json.dumps(salient, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(blob.encode()).hexdigest()


async def _sleep(ms: int) -> None:
    if ms <= 0:
        return
    jitter = random.uniform(-JITTER_MS, JITTER_MS) if JITTER_MS else 0.0
    await asyncio.sleep(max(0.0, (ms + jitter) / 1000.0))


def _stream_response(content: str, model: str, chunks: int = 24):
    """Re-emit a recorded answer as OpenAI SSE deltas, spreading LATENCY_MS across the chunks."""
    created = 1_700_000_000
    cid = "chatcmpl-stub"
    size = max(1, len(content) // chunks)
    parts = [content[i:i + size] for i in range(0, len(content), size)] or [""]
    per_chunk = (LATENCY_MS / 1000.0) / max(1, len(parts))

    async def gen():
        head = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": model,
                "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}]}
        yield f"data: {json.dumps(head)}\n\n"
        for p in parts:
            await asyncio.sleep(per_chunk)
            d = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": model,
                 "choices": [{"index": 0, "delta": {"content": p}, "finish_reason": None}]}
            yield f"data: {json.dumps(d)}\n\n"
        tail = {"id": cid, "object": "chat.completion.chunk", "created": created, "model": model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}
        yield f"data: {json.dumps(tail)}\n\n"
        yield "data: [DONE]\n\n"

    return StreamingResponse(gen(), media_type="text/event-stream")


async def _upstream(body: dict) -> tuple[dict | None, str | None]:
    """Call the real provider. Returns (json_response, streamed_content)."""
    headers = {"Authorization": f"Bearer {UPSTREAM_KEY}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=120) as client:
        if body.get("stream"):
            content = []
            async with client.stream("POST", f"{UPSTREAM}/chat/completions",
                                     json=body, headers=headers) as r:
                r.raise_for_status()
                async for line in r.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if payload == "[DONE]":
                        break
                    try:
                        delta = json.loads(payload)["choices"][0]["delta"].get("content")
                        if delta:
                            content.append(delta)
                    except Exception:
                        pass
            return None, "".join(content)
        r = await client.post(f"{UPSTREAM}/chat/completions", json=body, headers=headers)
        r.raise_for_status()
        return r.json(), None


@app.on_event("startup")
async def startup() -> None:
    _load()


@app.get("/health")
async def health() -> dict:
    return {"mode": MODE, "entries": len(_cassette), "latency_ms": LATENCY_MS, **_stats}


@app.post("/v1/chat/completions")
async def chat_completions(request: Request):
    body = await request.json()
    key = _key(body)
    streaming = bool(body.get("stream"))
    model = body.get("model", "stub")

    if MODE == "replay":
        entry = _cassette.get(key)
        if entry is None:
            _stats["miss"] += 1
            # Fail loudly. A stub that invents a response would reintroduce exactly the class of
            # bug we spent this week removing: fabricated data that looks like a real answer.
            log.error("cassette MISS key=%s model=%s stream=%s msgs=%d — record it first",
                      key[:12], model, streaming, len(body.get("messages", [])))
            return JSONResponse(
                status_code=503,
                content={"error": {"message": f"stub-llm cassette miss ({key[:12]})",
                                   "type": "cassette_miss", "code": "cassette_miss"}})
        _stats["hit"] += 1
        if streaming:
            return _stream_response(entry["content"], model)
        await _sleep(LATENCY_MS)
        return JSONResponse(content=entry["response"])

    # record / passthru
    try:
        resp, content = await _upstream(body)
    except httpx.HTTPStatusError as e:
        return JSONResponse(status_code=e.response.status_code, content=e.response.json())

    if MODE == "record":
        _cassette[key] = {"content": content} if streaming else {"response": resp}
        _cassette[key]["_meta"] = {"model": model, "stream": streaming,
                                   "recorded_at": int(time.time())}
        _stats["recorded"] += 1
        await _save()
        log.info("recorded key=%s model=%s stream=%s (%d entries)",
                 key[:12], model, streaming, len(_cassette))

    if streaming:
        return _stream_response(content or "", model)
    return JSONResponse(content=resp)
