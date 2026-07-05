#!/usr/bin/env python3
"""
seed-demo-conversations.py — Drive REAL conversations through the live Conduit gateway.

Every data point on the Insights dashboards comes from a REAL run: the script authenticates
as each user (real IAM token) and sends real chat/completions requests through the gateway, so
the system itself produces the traces, cost, eval-eligible traces, decision traces (Redis) and
persisted per-user sessions. Nothing is written directly into Langfuse/Tempo/Redis/Prometheus.

Fully parameterized — no hardcoded URLs or credentials:
  --gateway-url   (default env CONDUIT_GATEWAY_URL or http://localhost:8080)
  --iam-url       (default env CONDUIT_IAM_URL     or http://localhost:8084)
  --password      shared demo password for the seeded users
                  (default env CONDUIT_DEMO_PASSWORD or 'Meridian@2024')
  --users         comma-separated subset of user ids to run (default: all in the plan)
  --limit         cap the number of chat turns actually sent (default: no cap)
  --dry-run       print the plan without calling the gateway

The plan mixes outcomes on purpose (answered / clarified / entitlement-denied) across segments
and business lines so the dashboards show real spread. Each user's turns share a stable
X-Conversation-Id so they persist as one replayable session per user.

Usage:
  python3 scripts/seed-demo-conversations.py --gateway-url http://localhost:8080 \
      --iam-url http://localhost:8084 --password 'Meridian@2024'
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# ── Conversation plan ─────────────────────────────────────────────────────────
# (user_id, conversation_suffix, [ (turn_label, question) ... ])
# Questions are chosen from the seeded book-of-business so outcomes are realistic:
#   * existing users with a book (rm_jane: Whitman/Calderon, rm_carlos: Sterling/Okafor,
#     uw_sam: POL-77001/77002) → ANSWERED on their own clients, DENIED on others' / other policies.
#   * a vague "my client" question → deterministic CLARIFY.
#   * HR-policy / market-research / general questions → ANSWERED for any segment (no book needed),
#     which is how the new bankers contribute real cost per user + segment.
PLAN = [
    # ── Existing book-holders: answered + denied + clarify (the classic authz story) ──
    ("rm_jane", "wealth-review", [
        ("answered/holdings",  "Show me the holdings for the Whitman Family Office relationship."),
        ("answered/perf",      "How is the Calderon Trust performing this quarter?"),
        ("denied/cross-book",  "Show me the Okafor Family Trust performance."),
        ("clarify/vague",      "Can you pull up my client's latest portfolio performance?"),
    ]),
    ("rm_carlos", "wealth-book", [
        ("answered/perf",      "What's the performance of Sterling Capital Partners?"),
        ("answered/holdings",  "Show the current holdings for Sterling Capital Partners."),
        ("clarify/vague",      "Give me a risk summary for my top client."),
    ]),
    ("rm_diaz", "dual-segment", [
        ("answered/research",  "What's the current outlook on technology equities?"),
        ("clarify/vague",      "How is my client's account doing?"),
    ]),
    ("uw_sam", "insurance-book", [
        ("answered/policy",    "What is the status of policy POL-77001?"),
        ("denied/cross-book",  "Give me the full details of policy POL-88003."),
    ]),
    ("analyst_amy", "wealth-research", [
        ("answered/research",  "Summarize the market view on fixed income for this quarter."),
        ("denied/pii",         "Show me the detailed holdings for the Whitman account."),
    ]),

    # ── New bankers: general/HR/research answers (real cost per user+segment) + denials ──
    ("rm_nakamura", "pb-intro", [
        ("answered/research",  "What's the outlook on emerging-market equities?"),
        ("clarify/vague",      "Pull up my client's portfolio performance please."),
    ]),
    ("wealth_adv_bianchi", "advisor-day", [
        ("answered/research",  "What's the house view on gold and commodities right now?"),
        ("answered/hr",        "How many vacation days does a full-time employee get?"),
    ]),
    ("comm_banker_okoro", "commercial", [
        ("answered/hr",        "What is the company's remote work policy?"),
        ("clarify/vague",      "What's the settlement status for my client's latest trade?"),
    ]),
    ("ops_analyst_singh", "ops", [
        ("answered/research",  "Give me a summary of this quarter's equity market outlook."),
        ("clarify/vague",      "What are the pending corporate actions for my account?"),
    ]),
    ("ins_uw_costa", "underwriting", [
        ("answered/hr",        "What is the parental leave policy?"),
        ("denied/wealth",      "Show me the holdings for the Sterling Capital account."),
    ]),
    ("hr_partner_lund", "hr-desk", [
        ("answered/hr",        "What is the parental leave policy?"),
        ("answered/hr2",       "How does the company handle remote work requests?"),
        ("denied/wealth",      "Show me the Whitman portfolio holdings."),
    ]),
    ("treasury_moreau", "treasury", [
        ("answered/research",  "What's the current outlook on short-term interest rates?"),
        ("clarify/vague",      "What's the cash position for my client today?"),
    ]),
    ("multi_rm_fischer", "multi-line", [
        ("answered/research",  "What's the market outlook for the banking sector?"),
        ("answered/hr",        "What is the company's parental leave policy?"),
        ("clarify/vague",      "How is my client's portfolio performing?"),
    ]),
]


def mint_token(iam_url: str, user_id: str, password: str) -> str:
    body = json.dumps({"username": user_id, "password": password}).encode()
    req = urllib.request.Request(f"{iam_url}/auth/token", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())["accessToken"]


def chat(gateway_url: str, token: str, conv_id: str, question: str, timeout: int) -> tuple[str, int]:
    payload = json.dumps({
        "model": "conduit", "stream": True,
        "messages": [{"role": "user", "content": question}],
    }).encode()
    req = urllib.request.Request(
        f"{gateway_url}/v1/chat/completions", data=payload, method="POST",
        headers={"Content-Type": "application/json",
                 "X-Conversation-Id": conv_id,
                 "Authorization": f"Bearer {token}"})
    parts, start = [], time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        for raw in r:
            line = raw.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                delta = json.loads(data).get("choices", [{}])[0].get("delta", {})
                if delta.get("content"):
                    parts.append(delta["content"])
            except Exception:
                pass
    return "".join(parts), int((time.monotonic() - start) * 1000)


def main() -> int:
    ap = argparse.ArgumentParser(description="Drive real Conduit conversations for demo data.")
    ap.add_argument("--gateway-url", default=os.environ.get("CONDUIT_GATEWAY_URL", "http://localhost:8080"))
    ap.add_argument("--iam-url", default=os.environ.get("CONDUIT_IAM_URL", "http://localhost:8084"))
    ap.add_argument("--password", default=os.environ.get("CONDUIT_DEMO_PASSWORD", "Meridian@2024"))
    ap.add_argument("--users", default="", help="comma-separated subset of user ids")
    ap.add_argument("--limit", type=int, default=0, help="cap total turns sent (0 = no cap)")
    ap.add_argument("--timeout", type=int, default=90)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    gw = args.gateway_url.rstrip("/")
    iam = args.iam_url.rstrip("/")
    wanted = {u.strip() for u in args.users.split(",") if u.strip()}
    plan = [p for p in PLAN if not wanted or p[0] in wanted]

    total_turns = sum(len(turns) for _, _, turns in plan)
    print(f"[demo] gateway={gw} iam={iam} users={len(plan)} turns={total_turns}"
          f"{' (capped '+str(args.limit)+')' if args.limit else ''}")
    if args.dry_run:
        for uid, suffix, turns in plan:
            print(f"  {uid} [{suffix}]")
            for label, q in turns:
                print(f"      - {label}: {q}")
        return 0

    sent = answered = failed = 0
    tokens: dict[str, str] = {}
    for uid, suffix, turns in plan:
        try:
            tokens[uid] = tokens.get(uid) or mint_token(iam, uid, args.password)
        except Exception as e:
            print(f"  [warn] token mint failed for {uid}: {e}", flush=True)
            continue
        conv_id = f"demo-{uid}-{suffix}"
        print(f"  {uid}  (session {conv_id})", flush=True)
        for label, q in turns:
            if args.limit and sent >= args.limit:
                print(f"[demo] limit {args.limit} reached — stopping.")
                _summary(sent, answered, failed)
                return 0
            try:
                text, ms = chat(gw, tokens[uid], conv_id, q, args.timeout)
                preview = (text[:70] + "…") if len(text) > 70 else text
                status = "ok " if text.strip() else "EMPTY"
                print(f"      [{status}] {label:<20} {ms:>6}ms  \"{preview}\"", flush=True)
                sent += 1
                answered += 1 if text.strip() else 0
                failed += 0 if text.strip() else 1
            except Exception as e:
                print(f"      [ERR] {label:<20} {e}", flush=True)
                sent += 1
                failed += 1
            time.sleep(0.3)  # gentle pacing
    _summary(sent, answered, failed)
    return 0


def _summary(sent: int, answered: int, failed: int) -> None:
    print(f"[demo] done — {sent} turns sent, {answered} returned text, {failed} empty/errored.")
    print("[demo] eval scores lag (async runner samples ~every 5 min) — expected, not faked.")


if __name__ == "__main__":
    raise SystemExit(main())
