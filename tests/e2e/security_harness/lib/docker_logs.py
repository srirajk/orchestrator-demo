"""Thin wrapper around `docker logs` for pulling evidence out of gateway/agent containers.

Used sparingly and only for evidence-gathering (never to drive test control flow that a
down/renamed container should silently skip) — if docker isn't available the caller gets
a clear RuntimeError, not a hang.
"""
from __future__ import annotations
import subprocess


def tail(container: str, lines: int = 500) -> str:
    try:
        result = subprocess.run(
            ["docker", "logs", "--tail", str(lines), container],
            capture_output=True, text=True, timeout=15,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("docker CLI not found — cannot pull container log evidence") from exc
    # `docker logs` writes to stderr for a container using the default json-file driver's
    # stdout/stderr split; combine both since these mock agents/gateway log via stdout OR
    # stderr depending on the framework's default logging handler.
    return (result.stdout or "") + (result.stderr or "")


def grep(container: str, needle: str, lines: int = 1000) -> list[str]:
    text = tail(container, lines)
    return [ln for ln in text.splitlines() if needle in ln]
