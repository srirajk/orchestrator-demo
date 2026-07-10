#!/usr/bin/env bash
# Conduit security+correctness E2E acceptance gate.
#
# Drives the REAL running stack (docker compose -p orchestrator-demo, core profile) — no
# mocking. Exits non-zero if the stack isn't reachable (fails fast, see conftest.py) or if
# any non-xfail check fails.
#
# Usage:
#   bash tests/e2e/security_harness/run.sh              # everything, verbose + evidence
#   bash tests/e2e/security_harness/run.sh -k identity   # pytest -k filter passthrough
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
python3 -m pytest tests/e2e/security_harness/ -v -s --tb=short "$@"
