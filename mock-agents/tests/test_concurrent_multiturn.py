#!/usr/bin/env python3
"""
Concurrent multi-turn gateway load test.

Usage:
  python test_concurrent_multiturn.py [--concurrency N] [--turns T] [--gateway URL]

Flow:
  1. Calls Z.AI (glm-4.5-flash) to generate N distinct banker conversation
     scenarios — each with T realistic multi-turn questions about the
     Whitman Family Office (REL-00042).
  2. Fires all N conversations in parallel (one thread per user).
  3. Within each conversation, turns are sequential (simulating a real user).
  4. Reports per-turn latency, pass/fail, and aggregate summary.
"""
import argparse
import concurrent.futures
import json
import sys
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

import requests
from openai import OpenAI

# ── CLI args ─────────────────────────────────────────────────────────────────

parser = argparse.ArgumentParser()
parser.add_argument("--concurrency",        type=int, default=3, help="Number of concurrent users")
parser.add_argument("--turns",             type=int, default=10, help="Turns per conversation")
parser.add_argument("--gateway",           default="http://localhost:8080", help="Gateway base URL")
parser.add_argument("--zai-base",          default="https://api.z.ai/api/paas/v4")
parser.add_argument("--zai-key",           default=None, help="Z.AI API key (or set ZAI_API_KEY env)")
parser.add_argument("--zai-scenario-model", default="glm-z1-plus",
                    help="Z.AI model for generating test scenarios (smarter = better coverage)")
parser.add_argument("--zai-persona-model",  default="glm-4.5-flash",
                    help="Z.AI model for labelling personas (cheaper)")
args = parser.parse_args()

import os
ZAI_KEY = args.zai_key or os.environ.get("ZAI_API_KEY", "not-configured")

# ── Generate test cases via Z.AI ─────────────────────────────────────────────

AGENT_CONTEXT = """
GATEWAY CONTEXT — Meridian AI Banking Gateway
==============================================
The gateway routes natural-language questions to specialist agents and synthesizes
one grounded answer. Agents available:

  Wealth HTTP agents (REL-00042 = Whitman Family Office):
  • Holdings        — positions: AAPL(1200, $318k), MSFT(800, $372k), GOOGL(150, $289.5k),
                      JPM(2500, $487.5k), T-Bill-2026($500k). Total $1,967,000.
  • Performance     — QTD: +12.4% ($243,908 P&L), alpha 2.2, Sharpe 1.43, vol 8.7%.
  • Risk Profile    — moderate, score 6, max drawdown 15%. AAPL at 16.2% (limit 15%).
  • Goal Planning   — retirement 2031, current funding 72%, on-track flag.

  Servicing MCP agents:
  • Settlement Status — 1 pending: MSFT buy $372k settle 2026-06-25 (BNY Mellon).
  • Cash Management  — USD settled $157k + unsettled $372k; GBP $45k.
  • Custody Positions — BNY Mellon (equities), State Street (T-Bills).
  • Corporate Actions — AAPL dividend $0.25/share ex-date 2026-07-10; MSFT split 2:1.
  • NAV              — FND-0042: NAV $142.87/unit, AUM $284.2M, daily change +0.43%.

User identity: rm_jane (relationship manager, has access to REL-00042 Whitman only).
Second relationship in system: REL-00099 (Chen Tech Ventures) — rm_jane has no access.
"""


def generate_scenarios(n_users: int, n_turns: int) -> list[list[str]]:
    """Ask Z.AI (smarter model) to generate n_users distinct banker conversation scenarios."""
    print(f"[Z.AI:{args.zai_scenario_model}] Generating {n_users} scenarios ({n_turns} turns)...")
    client = OpenAI(base_url=args.zai_base, api_key=ZAI_KEY)

    prompt = f"""{AGENT_CONTEXT}

TASK: Generate exactly {n_users} distinct multi-turn RM conversation test scenarios.
Each scenario tests a DIFFERENT angle: risk review, pre-meeting prep, settlement ops,
NAV/fund check, portfolio rebalancing, compliance concern, client call prep, etc.

Rules:
- Each scenario has exactly {n_turns} turns (user questions only — no assistant replies).
- Turn 1 must reference "Whitman" or "REL-00042" to anchor the session.
- Subsequent turns should be natural follow-ups an RM would actually ask.
- Mix single-agent questions (e.g. "what's their cash?") with cross-agent ones
  (e.g. "given their risk score, should we be worried about the AAPL concentration?").
- At least one turn per scenario should reference a specific number from the context above.
- Scenarios must be distinct — different RM personas, different focus areas.

Return ONLY valid JSON — a list of {n_users} scenario objects:
[
  {{
    "persona": "Senior RM doing pre-meeting due diligence",
    "turns": ["turn 1 question", "turn 2 question", ..., "turn {n_turns} question"]
  }},
  ...
]
No explanation, no markdown fences, just the JSON array."""

    system_prompt = (
        "You are an expert QA engineer writing realistic load-test conversations for an "
        "enterprise AI banking gateway. Your job is to craft diverse, natural multi-turn "
        "dialogue that stress-tests agent routing, session carry-forward, and cross-agent "
        "synthesis. Each scenario must read like a real conversation between a relationship "
        "manager and their AI assistant — not like a test script. Use specific numbers and "
        "names from the context. Output ONLY the requested JSON, nothing else."
    )
    resp = client.chat.completions.create(
        model=args.zai_scenario_model,
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user",   "content": prompt},
        ],
        temperature=0.85,
    )
    raw = resp.choices[0].message.content.strip()
    # Strip markdown fences if present
    if raw.startswith("```"):
        raw = raw.split("```")[1]
        if raw.startswith("json"):
            raw = raw[4:]
    scenarios = json.loads(raw.strip())
    print(f"[Z.AI] Generated {len(scenarios)} scenarios")
    for i, s in enumerate(scenarios):
        print(f"  [{i+1}] {s['persona']} — {len(s['turns'])} turns")
    return [s["turns"] for s in scenarios]


# ── Gateway conversation runner ───────────────────────────────────────────────

GATEWAY_HEADERS = {
    "Content-Type": "application/json",
    "X-User-Id": "rm_jane",
    "Authorization": "Bearer rm_jane-token",
}


@dataclass
class TurnResult:
    turn: int
    latency: float
    chars: int
    ok: bool
    error: Optional[str] = None


@dataclass
class ConversationResult:
    user_id: int
    persona: str
    conversation_id: str
    turns: list[TurnResult] = field(default_factory=list)

    @property
    def ok_count(self): return sum(1 for t in self.turns if t.ok)
    @property
    def total_latency(self): return sum(t.latency for t in self.turns)
    @property
    def avg_latency(self): return self.total_latency / len(self.turns) if self.turns else 0


def stream_turn(conversation_id: str, messages: list[dict], gateway: str) -> tuple[str, float]:
    payload = {
        "model": "conduit-assistant",
        "messages": messages,
        "stream": True,
        "conversation_id": conversation_id,
    }
    t0 = time.time()
    resp = requests.post(
        f"{gateway}/v1/chat/completions",
        headers=GATEWAY_HEADERS,
        json=payload,
        stream=True,
        timeout=120,
    )
    resp.raise_for_status()

    collected = []
    for raw_line in resp.iter_lines():
        if not raw_line:
            continue
        line = raw_line.decode() if isinstance(raw_line, bytes) else raw_line
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            break
        try:
            chunk = json.loads(data)
            content = chunk.get("choices", [{}])[0].get("delta", {}).get("content", "")
            if content:
                collected.append(content)
        except json.JSONDecodeError:
            pass

    return "".join(collected), time.time() - t0


def run_conversation(user_id: int, scenario_turns: list[str], persona: str) -> ConversationResult:
    cid = f"u{user_id}-{uuid.uuid4().hex[:6]}"
    result = ConversationResult(user_id=user_id, persona=persona, conversation_id=cid)
    messages: list[dict] = []

    for i, user_text in enumerate(scenario_turns, 1):
        messages.append({"role": "user", "content": user_text})
        try:
            reply, latency = stream_turn(cid, messages, args.gateway)
            messages.append({"role": "assistant", "content": reply})
            result.turns.append(TurnResult(turn=i, latency=latency, chars=len(reply), ok=True))
            print(f"  [u{user_id}] turn {i:>2} OK  {latency:5.1f}s  {len(reply):>5} chars")
        except Exception as exc:
            result.turns.append(TurnResult(turn=i, latency=0, chars=0, ok=False, error=str(exc)))
            messages.append({"role": "assistant", "content": f"[error]"})
            print(f"  [u{user_id}] turn {i:>2} FAIL  {exc}")

    return result


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    print(f"\n{'='*70}")
    print(f"Concurrent multi-turn test | users={args.concurrency} | turns={args.turns}")
    print(f"Gateway: {args.gateway}")
    print(f"{'='*70}\n")

    # Step 1: generate scenarios via Z.AI
    try:
        all_turns = generate_scenarios(args.concurrency, args.turns)
    except Exception as exc:
        print(f"[ERROR] Failed to generate scenarios via Z.AI: {exc}")
        sys.exit(1)

    # Derive personas from scenario count for display
    personas = [f"User-{i+1}" for i in range(len(all_turns))]
    try:
        client = OpenAI(base_url=args.zai_base, api_key=ZAI_KEY)
        raw = client.chat.completions.create(
            model=args.zai_persona_model,
            messages=[
                {"role": "system", "content":
                    "You describe banking relationship manager personas — senior RMs, portfolio managers, "
                    "compliance officers at private wealth banks. One vivid sentence: their role, the client they "
                    "serve (Whitman Family Office), and what they're worried about right now."},
                {"role": "user", "content":
                    f"Write {len(all_turns)} distinct RM persona descriptions for the Whitman Family Office account. "
                    f"JSON array of strings only, no explanation."},
            ],
        ).choices[0].message.content.strip()
        if raw.startswith("```"):
            raw = raw.split("```")[1].lstrip("json").strip()
        personas = json.loads(raw)[:len(all_turns)]
    except Exception:
        pass  # keep default labels

    # Step 2: fire all conversations concurrently
    print(f"\n[RUN] Starting {args.concurrency} concurrent conversations...\n")
    wall_t0 = time.time()

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = {
            pool.submit(run_conversation, i + 1, all_turns[i], personas[i]): i
            for i in range(args.concurrency)
        }
        results = []
        for fut in concurrent.futures.as_completed(futures):
            try:
                results.append(fut.result())
            except Exception as exc:
                print(f"[ERROR] conversation thread failed: {exc}")

    wall_elapsed = time.time() - wall_t0
    results.sort(key=lambda r: r.user_id)

    # Step 3: summary
    print(f"\n{'='*70}")
    print("SUMMARY")
    print(f"{'='*70}")
    print(f"Wall-clock total : {wall_elapsed:.1f}s")
    print(f"Concurrency      : {args.concurrency}")
    print(f"Turns per user   : {args.turns}")
    print()

    total_turns = sum(len(r.turns) for r in results)
    total_ok    = sum(r.ok_count for r in results)
    all_latencies = [t.latency for r in results for t in r.turns if t.ok]
    avg_lat = sum(all_latencies) / len(all_latencies) if all_latencies else 0
    max_lat = max(all_latencies) if all_latencies else 0

    print(f"{'User':>5}  {'Persona':<28}  {'OK/Tot':>8}  {'AvgLat':>8}  {'TotalLat':>10}")
    print(f"{'-'*70}")
    for r in results:
        print(f"  u{r.user_id:<3}  {r.persona:<28}  {r.ok_count:>3}/{len(r.turns):<3}  "
              f"{r.avg_latency:>7.1f}s  {r.total_latency:>9.1f}s")

    print(f"\nAggregate: {total_ok}/{total_turns} turns OK "
          f"| avg {avg_lat:.1f}s | max {max_lat:.1f}s | wall {wall_elapsed:.1f}s")

    if total_ok < total_turns:
        sys.exit(1)


if __name__ == "__main__":
    main()
