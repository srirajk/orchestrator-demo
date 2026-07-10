"""Small helper for printing diagnosable evidence blocks under `pytest -v -s`.

Every check in this harness should call `evidence()` at least once with whatever it
based its PASS/FAIL decision on (trace excerpt, response snippet, HTTP status) so a
failure is debuggable straight from the pytest output, without re-running anything.
"""
from __future__ import annotations
import json
import textwrap


def evidence(title: str, payload) -> None:
    print(f"\n--- EVIDENCE: {title} " + "-" * max(4, 60 - len(title)))
    if isinstance(payload, (dict, list)):
        text = json.dumps(payload, indent=2, default=str)
    else:
        text = str(payload)
    print(textwrap.shorten(text, width=4000, placeholder=" …[truncated]…") if len(text) > 4000 else text)
    print("-" * 70)
