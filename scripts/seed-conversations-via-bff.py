#!/usr/bin/env python3
"""Seed REAL Conduit Chat conversations through the BFF, via the real OIDC login flow.

This drives the Chat BFF exactly like the browser does — it does NOT bypass BFF auth
and does NOT send a bearer token to the BFF (the BFF is a proper Backend-For-Frontend:
tokens stay server-side, the browser/holder uses a session cookie). For each user it:

  1. Runs the real OIDC login (GET /api/auth/login -> Axiom login form -> POST creds
     -> callback -> BFF session cookie), acting as a genuine authenticated user.
  2. POST /api/conversations            -> a real Mongo conversation (real ObjectId).
  3. POST /api/conversations/{id}/messages -> a real answer streamed through the gateway.

Idempotent: skips any user who already has conversations, so it is safe to run on
every boot ("when the system comes up"). Fully parameterized — no hardcoded creds.

Usage:
  python3 scripts/seed-conversations-via-bff.py \
      --bff-url http://localhost:8099 --iam-url http://localhost:8084 \
      --password "$SEED_PASSWORD" [--users rm_carlos,analyst_amy] [--dry-run]
"""
from __future__ import annotations
import argparse
import re
import sys
import time
from urllib.parse import urljoin, urlsplit, urlunsplit

import requests

# user -> the realistic questions to open as conversations (one conversation each)
DEFAULT_CONVERSATIONS: dict[str, list[str]] = {
    "rm_carlos":          ["Give me a risk summary for my top client's portfolio."],
    "wealth_adv_bianchi": ["What is the exposure on the Whitman Family Office holdings?"],
    "analyst_amy":        ["Summarize the insurance claims flagged this week."],
    "rm_nakamura":        ["Show me the commercial loan exposure for Northwind."],
    "multi_rm_fischer":   ["What changed in the Okafor portfolio this quarter?"],
    "rm_diaz":            ["What is the cash position across my book of clients?"],
}

_CSRF_RE = re.compile(r'name="_csrf"\s+value="([^"]+)"')
_CSRF_RE_ALT = re.compile(r'value="([^"]+)"\s+name="_csrf"')
_LOGIN_FORM_RE = re.compile(r'name="username"')


def _wait_ready(url: str, timeout: int = 120) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if requests.get(url, timeout=5).status_code < 500:
                return True
        except requests.RequestException:
            pass
        time.sleep(3)
    return False


def _follow_rewriting_callback(s: requests.Session, resp: requests.Response,
                               bff: str, max_hops: int = 12) -> requests.Response:
    """Follow a redirect chain manually, rewriting the OIDC callback hop.

    Axiom's registered redirect_uri is the PUBLIC BFF address (e.g. http://localhost:8099/
    api/auth/callback) — the host-published port that only the browser on the host can
    reach. From inside the seeder container over the Docker network that host is
    unreachable, so any hop whose path is the BFF callback is rewritten to the INTERNAL
    BFF base ($CHAT_BFF_URL, e.g. http://conduit-chat:8095). This is host-only rewriting:
    the code/state query is preserved and the same session cookie (set on the BFF host
    during /api/auth/login) is replayed, so Spring Security's state check passes and the
    token exchange still uses the stored redirect_uri. Axiom is never changed.
    """
    bff_parts = urlsplit(bff)
    while max_hops > 0 and resp.is_redirect:
        max_hops -= 1
        nxt = urljoin(resp.url, resp.headers["Location"])
        parts = urlsplit(nxt)
        if parts.path.startswith("/api/auth/callback"):
            nxt = urlunsplit((bff_parts.scheme, bff_parts.netloc,
                              parts.path, parts.query, parts.fragment))
        resp = s.get(nxt, allow_redirects=False, timeout=20)
    return resp


def login(bff: str, user: str, password: str) -> requests.Session:
    """Run the real OIDC login and return a Session holding the BFF session cookie."""
    s = requests.Session()
    # kick off OIDC; requests follows the redirect chain to the Axiom login form
    r = s.get(f"{bff}/api/auth/login", timeout=20)
    if not _LOGIN_FORM_RE.search(r.text):
        raise RuntimeError(f"did not reach the Axiom login form (landed at {r.url})")
    m = _CSRF_RE.search(r.text) or _CSRF_RE_ALT.search(r.text)
    if not m:
        raise RuntimeError("could not find the CSRF token on the login form")
    login_action = urljoin(r.url, "/login")
    # Do NOT auto-follow: the final hop targets the public redirect_uri (localhost:8099),
    # which is unreachable from the container. Walk the chain and rewrite the callback hop
    # to the internal BFF so the code/state lands back on the BFF over the Docker network.
    resp = s.post(
        login_action,
        data={"username": user, "password": password, "_csrf": m.group(1)},
        timeout=20,
        allow_redirects=False,
    )
    _follow_rewriting_callback(s, resp, bff)
    # verify the session was actually established
    check = s.get(f"{bff}/api/conversations", timeout=20)
    if check.status_code != 200:
        raise RuntimeError(f"login did not establish a session (GET /api/conversations = {check.status_code})")
    return s


def seed_user(s: requests.Session, bff: str, user: str, questions: list[str], dry_run: bool) -> list[str]:
    existing = s.get(f"{bff}/api/conversations", timeout=20).json() or []
    if existing:
        print(f"  {user}: already has {len(existing)} conversation(s) — skipping (idempotent)")
        return []
    made: list[str] = []
    for q in questions:
        if dry_run:
            print(f"  {user}: [dry-run] would open conversation: {q[:52]}")
            continue
        c = s.post(f"{bff}/api/conversations", json={"title": q[:40]}, timeout=20)
        c.raise_for_status()
        cid = c.json().get("id") or c.json().get("conversationId")
        m = s.post(f"{bff}/api/conversations/{cid}/messages", json={"content": q}, timeout=180)
        print(f"  {user}: {cid}  msg={m.status_code}  ({q[:44]}...)")
        made.append(cid)
    return made


def main() -> int:
    ap = argparse.ArgumentParser(description="Seed real Conduit conversations via the BFF (real OIDC login).")
    ap.add_argument("--bff-url", default="http://localhost:8099")
    ap.add_argument("--iam-url", default="http://localhost:8084")
    ap.add_argument("--password", required=True, help="shared demo password for the seed users")
    ap.add_argument("--users", default="", help="comma-separated subset; empty = all defaults")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if not _wait_ready(a.bff_url) or not _wait_ready(f"{a.iam_url}/login"):
        print("  services not ready in time — aborting", file=sys.stderr)
        return 1

    users = [u.strip() for u in a.users.split(",") if u.strip()] or list(DEFAULT_CONVERSATIONS)
    made: dict[str, list[str]] = {}
    for u in users:
        questions = DEFAULT_CONVERSATIONS.get(u, ["Give me a summary of my book of clients."])
        try:
            s = login(a.bff_url, u, a.password)
            made[u] = seed_user(s, a.bff_url, u, questions, a.dry_run)
        except Exception as exc:  # keep going for the other users
            print(f"  {u}: FAILED — {exc}", file=sys.stderr)

    total = sum(len(v) for v in made.values())
    print(f"\n  ===== {total} real conversation(s) created via the BFF (real Mongo IDs) =====")
    for u, ids in made.items():
        for cid in ids:
            print(f"    {u:20} {cid}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
