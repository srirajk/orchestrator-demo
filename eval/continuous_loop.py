#!/usr/bin/env python3
"""
Resilient loop wrapper for eval/langfuse_continuous.py.

Runs the continuous trace scorer (grounding / partial-honesty / relevance /
safety) every EVAL_INTERVAL_SECONDS. Each iteration runs in its own subprocess
so a single failure (Langfuse not ready, transient network error, judge error)
is logged and the loop continues — the container never crashes.

This is a *wrapper only*: it does not modify or re-implement any eval logic.

Env:
  EVAL_INTERVAL_SECONDS  seconds between iterations (default 300)
  (all other config — LANGFUSE_*, ZAI_API_KEY, JUDGE_MODEL,
   EVAL_LOOKBACK_HOURS, EVAL_TRACE_LIMIT — is read by langfuse_continuous.py)
"""

import logging
import os
import subprocess
import sys
import time

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [eval-loop] %(levelname)s %(message)s",
)
log = logging.getLogger("eval-loop")

INTERVAL = int(os.environ.get("EVAL_INTERVAL_SECONDS", "300"))
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "langfuse_continuous.py")


def main() -> None:
    log.info(
        "Continuous eval loop starting — interval=%ds  script=%s  host=%s",
        INTERVAL,
        SCRIPT,
        os.environ.get("LANGFUSE_HOST", "http://localhost:3000"),
    )
    while True:
        started = time.time()
        try:
            rc = subprocess.call([sys.executable, "-u", SCRIPT])
            log.info("Iteration finished rc=%d in %.1fs", rc, time.time() - started)
        except Exception as exc:  # never let one bad iteration kill the loop
            log.error("Iteration crashed (continuing): %s", exc)
        log.info("Sleeping %ds until next iteration...", INTERVAL)
        time.sleep(INTERVAL)


if __name__ == "__main__":
    main()
