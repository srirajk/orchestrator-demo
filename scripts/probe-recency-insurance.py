#!/usr/bin/env python3
"""Insurance cross-domain recency probe (uw_sam, one conversation). Mirrors the wealth turn-5/6
anaphor-recency case in a DIFFERENT domain to prove the focal-recency fix is generic:
establish policy A, switch to policy B, RE-NAME policy A explicitly, then use a bare pronoun ->
the pronoun MUST bind to the just-named policy A (POL-77002), not the older B (POL-77001)."""
from __future__ import annotations
import json, re, time
from urllib.parse import urljoin
import requests

BFF, PW, USER = "http://localhost:8099", "Meridian@2024", "uw_sam"

# uw_sam covers POL-77002 (Aurora Mfg Property) + POL-77001 (Continental Freight Liability).
TURNS = [
    ("setup",        "Give me the policy details for Aurora Mfg Property"),     # 1 establish A = POL-77002
    ("switch",       "Now pull up Continental Freight Liability's policy details"),  # 2 switch B = POL-77001
    ("rename-A",     "claim status for Aurora Mfg Property"),                    # 3 RE-NAME A -> focus back to A
    ("anaphor",      "and what's their coverage limit?"),                       # 4 PRONOUN -> must bind to A (POL-77002)
    ("anaphor-2",    "what about their deductible?"),                           # 5 PRONOUN continuity -> still A
]

_CSRF = re.compile(r'name="_csrf"\s+value="([^"]+)"')

def login():
    s = requests.Session()
    r = s.get(f"{BFF}/api/auth/login", timeout=20)
    s.post(urljoin(r.url, "/login"), data={"username": USER, "password": PW, "_csrf": _CSRF.search(r.text).group(1)}, timeout=20)
    assert s.get(f"{BFF}/api/conversations", timeout=20).status_code == 200
    return s

def send(s, cid, content):
    r = s.post(f"{BFF}/api/conversations/{cid}/messages", json={"content": content}, timeout=180, stream=True)
    out = []
    for line in r.iter_lines(decode_unicode=True):
        if line and line.startswith("data:"):
            p = line[5:].strip()
            if p == "[DONE]": continue
            try: j = json.loads(p)
            except Exception: continue
            for ch in j.get("choices", []):
                d = (ch.get("delta") or {}).get("content")
                if d: out.append(d)
    return "".join(out).strip()

s = login()
cid = s.post(f"{BFF}/api/conversations", json={"title": "insurance recency probe"}, timeout=20).json().get("id")
print(f"CONVERSATION: {cid}")
print("=" * 96)
for i, (kind, t) in enumerate(TURNS, 1):
    try: ans = send(s, cid, t)
    except Exception as e: ans = f"[ERROR {e}]"
    print(f"\n[{i}] ({kind}) Q: {t}")
    print(f"    A: {' '.join(ans.split())[:260] or '(empty)'}")
    time.sleep(1)
print("\n" + "=" * 96)
print(f"CONVERSATION_ID={cid}")
