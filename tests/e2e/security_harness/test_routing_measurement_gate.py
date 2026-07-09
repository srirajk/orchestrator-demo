"""Live routing measurement gate.

Runs the same fleet-coverage goal-pick gate that agent teams use before changing
or adding manifests. It talks to the booted gateway's real /debug/resolve path,
so scores come from the real embeddings and registry index.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from lib import config
from lib.evidence import evidence


ROOT = Path(__file__).resolve().parents[3]


def test_routing_measurement_gate_passes_healthy_fleet():
    output = "/tmp/routing-measurement-gate-test.json"
    env = os.environ.copy()
    env.update({
        "CONDUIT_DEMO_PASSWORD": config.DEMO_PASSWORD,
    })
    result = subprocess.run(
        [
            sys.executable,
            str(ROOT / "eval" / "goal-pick" / "measure_goal_pick.py"),
            "--gateway-url",
            config.GATEWAY_URL,
            "--iam-url",
            config.IAM_URL,
            "--dataset",
            str(ROOT / "eval" / "goal-pick" / "labeled_queries.json"),
            "--manifest-root",
            str(ROOT / "registry" / "manifests"),
            "--output",
            output,
        ],
        cwd=ROOT,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=180,
    )
    evidence("routing measurement gate", {
        "returncode": result.returncode,
        "output": result.stdout,
        "json_output": output,
    })
    assert result.returncode == 0, result.stdout
