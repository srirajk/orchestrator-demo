"""
Shared fixtures + harness plumbing for the Conduit security+correctness E2E gate.

Entry point:  python3 -m pytest tests/e2e/security_harness/ -v -s
              (or)  bash tests/e2e/security_harness/run.sh

This talks to the REAL running stack (docker compose -p orchestrator-demo, core
profile) — it is not hermetic and does not mock anything. `pytest_configure` below
checks every required service is reachable BEFORE collection so a down stack fails
fast with a clear message instead of hanging on the first test's timeout.
"""
from __future__ import annotations
import sys
from pathlib import Path

import pytest
import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lib import config as harness_config  # noqa: E402
from lib import bff_client  # noqa: E402

# ── Fail-fast stack check ──────────────────────────────────────────────────────────


def pytest_configure(config: pytest.Config) -> None:
    unreachable = []
    for name, url in harness_config.REQUIRED_SERVICES.items():
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code >= 500:
                unreachable.append(f"{name} ({url}) -> HTTP {resp.status_code}")
        except requests.RequestException as exc:
            unreachable.append(f"{name} ({url}) -> {exc.__class__.__name__}: {exc}")
    if unreachable:
        msg = (
            "\n\nSTACK NOT READY — refusing to run (would hang on the first live test).\n"
            "Unreachable services:\n  - " + "\n  - ".join(unreachable) +
            "\n\nBring the stack up first:\n"
            "  cd /Users/srirajkadimisetty/projects/orchestrator-chat\n"
            "  docker compose -p orchestrator-demo up -d\n"
        )
        pytest.exit(msg, returncode=2)


# ── Session-scoped logins (real OIDC through the BFF, once per user per run) ───────


@pytest.fixture(scope="session")
def jane_session() -> requests.Session:
    """rm_jane — entitled to Whitman Family Office (REL-00042)."""
    try:
        return bff_client.login(harness_config.USER_ENTITLED)
    except Exception as exc:
        pytest.exit(
            f"\n\nCould not log in as '{harness_config.USER_ENTITLED}' via the BFF OIDC flow: {exc}\n"
            f"Check the user is seeded (scripts/seed-users.sh) and the password matches "
            f"CONDUIT_DEMO_PASSWORD (default {harness_config.DEMO_PASSWORD!r}).\n",
            returncode=2,
        )


@pytest.fixture(scope="session")
def sam_session() -> requests.Session:
    """uw_sam — insurance underwriter, entitled to POL-77001/POL-77002 (not POL-88003)."""
    try:
        return bff_client.login(harness_config.USER_INSURANCE_UNDERWRITER)
    except Exception as exc:
        pytest.exit(
            f"\n\nCould not log in as '{harness_config.USER_INSURANCE_UNDERWRITER}' via the BFF OIDC flow: {exc}\n"
            f"Check the user is seeded (scripts/seed-users.sh) and the password matches "
            f"CONDUIT_DEMO_PASSWORD (default {harness_config.DEMO_PASSWORD!r}).\n",
            returncode=2,
        )


@pytest.fixture(scope="session")
def ops_session() -> requests.Session:
    """ops_analyst_singh — asset-servicing operations, confidential-pii ceiling."""
    try:
        return bff_client.login(harness_config.USER_SERVICING_OPS)
    except Exception as exc:
        pytest.exit(
            f"\n\nCould not log in as '{harness_config.USER_SERVICING_OPS}' via the BFF OIDC flow: {exc}\n"
            f"Check the user is seeded (scripts/seed-users.sh) and the password matches "
            f"CONDUIT_DEMO_PASSWORD (default {harness_config.DEMO_PASSWORD!r}).\n",
            returncode=2,
        )


@pytest.fixture(scope="session")
def admin_session() -> requests.Session:
    """admin — platform admin used for cross-domain live DAG evidence."""
    try:
        return bff_client.login(harness_config.USER_ADMIN)
    except Exception as exc:
        pytest.exit(
            f"\n\nCould not log in as '{harness_config.USER_ADMIN}' via the BFF OIDC flow: {exc}\n"
            f"Check the user is seeded and the password matches "
            f"CONDUIT_DEMO_PASSWORD (default {harness_config.DEMO_PASSWORD!r}).\n",
            returncode=2,
        )


@pytest.fixture(scope="session")
def carlos_session() -> requests.Session:
    """rm_carlos — NOT entitled to Whitman Family Office (REL-00042); book = REL-00099 only."""
    try:
        return bff_client.login(harness_config.USER_NOT_ENTITLED)
    except Exception as exc:
        pytest.exit(
            f"\n\nCould not log in as '{harness_config.USER_NOT_ENTITLED}' via the BFF OIDC flow: {exc}\n"
            f"Check the user is seeded (scripts/seed-users.sh) and the password matches "
            f"CONDUIT_DEMO_PASSWORD (default {harness_config.DEMO_PASSWORD!r}).\n",
            returncode=2,
        )


# ── PASS / FAIL / XFAIL summary table ───────────────────────────────────────────────

_RESULTS: list[tuple[str, str, str]] = []  # (nodeid, outcome, one-line reason)

_OUTCOME_ICON = {
    "passed": "PASS",
    "failed": "FAIL",
    "xfailed": "XFAIL",
    "xpassed": "XPASS!",  # unexpectedly passed — an xfail marker needs revisiting
    "skipped": "SKIP",
}


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()

    # A setup-phase failure (e.g. a fixture raised) is the only way this test's outcome
    # will ever be reported, since "call" never runs — record it as failed.
    if report.when == "setup" and report.outcome == "failed":
        reason = str(report.longrepr).splitlines()[-1][:160] if report.longrepr else "setup error"
        _RESULTS.append((item.nodeid, "failed", reason))
        return

    if report.when != "call":
        return

    wasxfail = getattr(report, "wasxfail", None)
    if wasxfail is not None and report.outcome == "skipped":
        outcome_name, reason = "xfailed", wasxfail or ""
    elif wasxfail is not None and report.outcome == "passed":
        outcome_name, reason = "xpassed", wasxfail or ""
    elif report.outcome == "failed":
        outcome_name = "failed"
        reason = str(report.longrepr).splitlines()[-1][:160] if report.longrepr else ""
    else:
        outcome_name, reason = report.outcome, ""
    _RESULTS.append((item.nodeid, outcome_name, reason))


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    tr = terminalreporter
    tr.write_sep("=", "SECURITY + CORRECTNESS GATE — SUMMARY")
    name_w = max((len(n) for n, _, _ in _RESULTS), default=40)
    name_w = min(name_w, 70)
    for nodeid, outcome_name, reason in _RESULTS:
        icon = _OUTCOME_ICON.get(outcome_name, outcome_name.upper())
        short_name = nodeid.split("::", 1)[-1]
        line = f"{icon:<7} {short_name:<{name_w}}"
        if reason:
            line += f"  — {reason}"
        color = {"PASS": "green", "FAIL": "red", "XFAIL": "yellow", "XPASS!": "red"}.get(icon)
        tr.write_line(line, **({color: True} if color else {}))
    tr.write_sep("=")
