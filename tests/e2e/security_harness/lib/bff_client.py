"""
Drives the REAL Conduit Chat BFF exactly like the browser does: real OIDC login via
Axiom (iam-service), BFF session cookie (NO bearer token to the BFF — the BFF holds
the user's OIDC access token server-side and forwards it to the gateway itself), real
Mongo-backed conversations, and the byte-for-byte SSE stream proxied from the gateway.

login()/_follow_rewriting_callback() are loaded directly from
scripts/seed-conversations-via-bff.py (NOT reimplemented) per the task's instruction to
reuse the existing OIDC helpers rather than reinvent the login flow. That script's
filename has a hyphen so it can't be `import`ed normally — we load it by file path.
"""
from __future__ import annotations
import importlib.util
import json
import re
from dataclasses import dataclass, field
from pathlib import Path

import requests

from . import config

_SEED_SCRIPT = Path(__file__).resolve().parents[4] / "scripts" / "seed-conversations-via-bff.py"


def _load_seed_module():
    spec = importlib.util.spec_from_file_location("seed_conversations_via_bff", _SEED_SCRIPT)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"could not load seed script at {_SEED_SCRIPT}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


_seed = _load_seed_module()


def login(user: str, password: str = config.DEMO_PASSWORD) -> requests.Session:
    """Real OIDC login through the BFF. Returns a Session holding the BFF session cookie.

    Reused verbatim from scripts/seed-conversations-via-bff.py::login — walks the Axiom
    login form, POSTs credentials, and rewrites the callback hop to the BFF's internal
    base when required (a no-op on the host, where the BFF's public and internal
    addresses are the same http://localhost:8099).
    """
    return _seed.login(config.BFF_URL, user, password)


@dataclass
class ChatTurn:
    conversation_id: str
    answer_text: str
    http_status: int
    raw_sse: str = field(repr=False)


def create_conversation(session: requests.Session, title: str) -> str:
    resp = session.post(f"{config.BFF_URL}/api/conversations", json={"title": title[:40]}, timeout=20)
    resp.raise_for_status()
    body = resp.json()
    cid = body.get("id") or body.get("conversationId")
    if not cid:
        raise RuntimeError(f"BFF did not return a conversation id: {body}")
    return cid


_SSE_DATA_RE = re.compile(r"^data:\s*(.*)$")


def _collect_sse_text(raw: str) -> str:
    """Parse an OpenAI-shaped SSE body (`data: {...}` chunks, terminated by `data: [DONE]`)
    and concatenate the assistant's content deltas. Same shape as
    tests/integration/test_gateway_coverage.py::collect_sse_text — the BFF passes the
    gateway's stream through byte-for-byte (see apps/chat/bff ChatController)."""
    parts: list[str] = []
    for line in raw.splitlines():
        line = line.strip()
        if not line:
            continue
        m = _SSE_DATA_RE.match(line)
        if not m:
            continue
        payload = m.group(1).strip()
        if payload == "[DONE]":
            break
        try:
            chunk = json.loads(payload)
        except json.JSONDecodeError:
            continue
        for choice in chunk.get("choices", []):
            content = choice.get("delta", {}).get("content")
            if content:
                parts.append(content)
    return "".join(parts)


def send_message(session: requests.Session, conversation_id: str, content: str,
                  timeout: int = config.CHAT_TIMEOUT_S) -> ChatTurn:
    """POST a user turn and return the assembled assistant answer + raw SSE for evidence."""
    resp = session.post(
        f"{config.BFF_URL}/api/conversations/{conversation_id}/messages",
        json={"content": content},
        timeout=timeout,
    )
    text = _collect_sse_text(resp.text) if resp.ok else ""
    return ChatTurn(
        conversation_id=conversation_id,
        answer_text=text,
        http_status=resp.status_code,
        raw_sse=resp.text,
    )


def ask(session: requests.Session, content: str, title: str | None = None) -> ChatTurn:
    """Convenience: open a fresh conversation and send one turn."""
    cid = create_conversation(session, title or content)
    return send_message(session, cid, content)
