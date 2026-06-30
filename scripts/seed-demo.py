#!/usr/bin/env python3
"""
Conduit Gateway — Demo Seed Script

Fires representative requests at startup so Phoenix and Tempo are pre-populated
with real LLM traces. Each request carries a fixed conversation_id so Phoenix's
Sessions view shows a full multi-turn conversation.

Run after `docker compose up -d` and services are healthy.
Usage:
    python3 scripts/seed-demo.py
    # or via seed-demo.sh which waits for health first
"""

import json
import sys
import time
import urllib.request
import urllib.error

GATEWAY_URL = "http://localhost:8080"
USER_MGMT_URL = "http://localhost:8084"

# Fixed conversation IDs for Phoenix session grouping — same ID across turns
# means Phoenix shows them all together in the Sessions tab.
SESSION_HERO = "seed-hero-001"
SESSION_MULTI = "seed-multi-001"
SESSION_OKAFOR = "seed-okafor-001"

TIMEOUT = 45  # seconds per request


def mint_token(user_id: str) -> str:
    try:
        req = urllib.request.Request(
            f"{USER_MGMT_URL}/auth/token",
            data=json.dumps({"user_id": user_id}).encode(),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as r:
            return json.loads(r.read())["access_token"]
    except Exception as e:
        print(f"  [warn] token mint failed for {user_id}: {e}", flush=True)
        return ""


def chat(prompt: str, token: str, conv_id: str, turn_label: str) -> str:
    """Fire a single chat request and return the full response text."""
    payload = json.dumps({
        "model": "conduit",
        "stream": True,
        "messages": [{"role": "user", "content": prompt}],
    }).encode()

    headers = {
        "Content-Type": "application/json",
        "X-Conversation-Id": conv_id,
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    try:
        req = urllib.request.Request(
            f"{GATEWAY_URL}/v1/chat/completions",
            data=payload,
            headers=headers,
            method="POST",
        )
        text_parts = []
        start = time.monotonic()
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            for line in r:
                line = line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                    delta = chunk.get("choices", [{}])[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        text_parts.append(content)
                except Exception:
                    pass
        elapsed = int((time.monotonic() - start) * 1000)
        result = "".join(text_parts)
        preview = (result[:80] + "…") if len(result) > 80 else result
        print(f"  ✓ {turn_label:<30} {elapsed:>5}ms  \"{preview}\"", flush=True)
        return result
    except Exception as e:
        print(f"  ✗ {turn_label:<30} FAILED: {e}", flush=True)
        return ""


def chat_multi(messages: list, token: str, conv_id: str, turn_label: str) -> str:
    """Fire a multi-turn chat request with explicit message history."""
    payload = json.dumps({
        "model": "conduit",
        "stream": True,
        "messages": messages,
    }).encode()

    headers = {
        "Content-Type": "application/json",
        "X-Conversation-Id": conv_id,
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    try:
        req = urllib.request.Request(
            f"{GATEWAY_URL}/v1/chat/completions",
            data=payload,
            headers=headers,
            method="POST",
        )
        text_parts = []
        start = time.monotonic()
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            for line in r:
                line = line.decode("utf-8").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                    delta = chunk.get("choices", [{}])[0].get("delta", {})
                    content = delta.get("content", "")
                    if content:
                        text_parts.append(content)
                except Exception:
                    pass
        elapsed = int((time.monotonic() - start) * 1000)
        result = "".join(text_parts)
        preview = (result[:80] + "…") if len(result) > 80 else result
        print(f"  ✓ {turn_label:<30} {elapsed:>5}ms  \"{preview}\"", flush=True)
        return result
    except Exception as e:
        print(f"  ✗ {turn_label:<30} FAILED: {e}", flush=True)
        return ""


def wait_for_gateway(max_wait: int = 120) -> bool:
    print(f"Waiting for gateway at {GATEWAY_URL}/actuator/health …", flush=True)
    deadline = time.monotonic() + max_wait
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"{GATEWAY_URL}/actuator/health", timeout=3) as r:
                body = json.loads(r.read())
                if body.get("status") == "UP":
                    print("  ✓ gateway healthy", flush=True)
                    return True
        except Exception:
            pass
        time.sleep(3)
    print("  ✗ gateway did not become healthy in time", flush=True)
    return False


def main():
    print("=" * 60, flush=True)
    print("Conduit Gateway — Phoenix/Tempo seed", flush=True)
    print("=" * 60, flush=True)

    if not wait_for_gateway():
        sys.exit(1)

    print("\nMinting tokens …", flush=True)
    jane_token = mint_token("rm_jane")
    bob_token = mint_token("rm_bob")
    print(f"  rm_jane token: {'ok' if jane_token else 'MISSING'}", flush=True)
    print(f"  rm_bob  token: {'ok' if bob_token else 'MISSING'}", flush=True)

    # ── Session 1: Hero — multi-agent fan-out across HTTP + MCP ──────────────
    print("\n[Session 1] Hero — multi-agent, as rm_jane", flush=True)
    t1_reply = chat(
        "Give me the full picture for the Whitman Family Office — "
        "holdings, YTD performance, risk profile, and any pending settlements.",
        jane_token, SESSION_HERO, "hero/full-picture"
    )
    time.sleep(1)

    if t1_reply:
        t1_preview = t1_reply[:300]
        chat_multi(
            [
                {"role": "user",
                 "content": "Give me the full picture for the Whitman Family Office — "
                            "holdings, YTD performance, risk profile, and any pending settlements."},
                {"role": "assistant", "content": t1_preview},
                {"role": "user", "content": "What's the largest equity position and what's its current risk rating?"},
            ],
            jane_token, SESSION_HERO, "hero/follow-up"
        )
        time.sleep(1)

    # ── Session 2: Multi-turn cross-domain ────────────────────────────────────
    print("\n[Session 2] Multi-turn cross-domain, as rm_jane", flush=True)
    t1 = chat(
        "Show me the cash position for Whitman Family Office.",
        jane_token, SESSION_MULTI, "cross-domain/cash"
    )
    time.sleep(1)

    if t1:
        t1_preview = t1[:300]
        t2 = chat_multi(
            [
                {"role": "user", "content": "Show me the cash position for Whitman Family Office."},
                {"role": "assistant", "content": t1_preview},
                {"role": "user", "content": "What settlements are pending that affect this cash balance?"},
            ],
            jane_token, SESSION_MULTI, "cross-domain/settlements"
        )
        time.sleep(1)

        if t2:
            t2_preview = t2[:300]
            chat_multi(
                [
                    {"role": "user", "content": "Show me the cash position for Whitman Family Office."},
                    {"role": "assistant", "content": t1_preview},
                    {"role": "user", "content": "What settlements are pending that affect this cash balance?"},
                    {"role": "assistant", "content": t2_preview},
                    {"role": "user", "content": "Summarise what I should focus on today."},
                ],
                jane_token, SESSION_MULTI, "cross-domain/summary"
            )
            time.sleep(1)

    # ── Session 3: Entitlement denial ─────────────────────────────────────────
    print("\n[Session 3] Entitlement denial — Okafor, as rm_jane", flush=True)
    chat(
        "What are the holdings for the Okafor account?",
        jane_token, SESSION_OKAFOR, "entitlement/okafor-denied"
    )
    time.sleep(1)

    # ── Standalone: chitchat (no agents, CHITCHAT intent) ─────────────────────
    print("\n[Standalone] Chitchat + rm_bob performance query", flush=True)
    chat(
        "Hello! What can you help me with today?",
        jane_token, "seed-chitchat-001", "chitchat/greeting"
    )
    time.sleep(1)
    chat(
        "Show me YTD performance for the Whitman account.",
        bob_token, "seed-bob-001", "bob/performance"
    )

    print("\n" + "=" * 60, flush=True)
    print("Seed complete. Open Phoenix at http://localhost:6006", flush=True)
    print("  → Projects → default → Sessions tab", flush=True)
    print("  → Filter by session.id to see each conversation", flush=True)
    print("Open Tempo at http://localhost:3000 (Grafana → Explore → Tempo)", flush=True)
    print("  → Search by service=conduit-gateway, tag session.id=seed-hero-001", flush=True)
    print("=" * 60, flush=True)


if __name__ == "__main__":
    main()
