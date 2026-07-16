"""
Self-contained tests for scripts/audit-verify.py (F5 spec §3f, harness items 8 & 9 — the local,
no-MinIO slice). JVM byte-parity is proven separately by AuditVerifyFixtureTest; these prove the
CLI's own control flow: clean verify, one-byte tamper, empty-sha UNVERIFIABLE, and reconcile gap
detection with distinct exit codes.
"""
import json
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "audit-verify.py"


def _canonical_sha(events):
    import hashlib
    data = json.dumps(events, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(data).hexdigest()


def _run(*args):
    return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True)


def _record(tmp_path, events, sha=None):
    if sha is None:
        sha = _canonical_sha(events)
    record = {
        "schemaVersion": "1",
        "transactionId": "req-1",
        "events": events,
        "contentSha256": sha,
    }
    f = tmp_path / "record.json"
    f.write_text(json.dumps(record))
    return f


def test_audit_verify_passes_on_clean_record(tmp_path):
    events = [{"type": "request_start", "requestId": "req-1", "timestamp": 1, "data": {"a": "1"}}]
    result = _run("verify", str(_record(tmp_path, events)))
    assert result.returncode == 0, result.stderr


def test_audit_verify_detects_tamper(tmp_path):
    events = [{"type": "request_start", "requestId": "req-1", "timestamp": 1, "data": {"a": "1"}}]
    good = _record(tmp_path, events)
    # Flip one byte inside the stored events without updating contentSha256.
    record = json.loads(good.read_text())
    record["events"][0]["data"]["a"] = "2"
    good.write_text(json.dumps(record))

    result = _run("verify", str(good))
    assert result.returncode == 1, f"tamper must exit 1, got {result.returncode}: {result.stderr}"
    assert "TAMPER" in result.stderr


def test_empty_sha_reports_unverifiable(tmp_path):
    events = [{"type": "request_start", "requestId": "req-1", "timestamp": 1, "data": {"a": "1"}}]
    result = _run("verify", str(_record(tmp_path, events, sha="")))
    assert result.returncode == 3, f"empty sha must exit 3 (not 1), got {result.returncode}"
    assert "UNVERIFIABLE" in result.stderr


def test_reconcile_detects_missing_record(tmp_path):
    ids = tmp_path / "ids.json"
    listing = tmp_path / "listing.json"
    # Two SSE ids (normalized forms); the object store is missing the second.
    ids.write_text(json.dumps(["chatcmpl-AAAA1111", "chatcmpl-BBBB2222"]))
    listing.write_text(json.dumps(["aaaa1111"]))

    result = _run("reconcile", "--ids", str(ids), "--listing", str(listing))
    assert result.returncode == 2, f"a gap in enforce mode must be non-zero, got {result.returncode}"
    assert "bbbb2222" in result.stderr  # names the normalized missing transactionId


def test_reconcile_warn_only_does_not_fail(tmp_path):
    ids = tmp_path / "ids.json"
    listing = tmp_path / "listing.json"
    ids.write_text(json.dumps(["chatcmpl-AAAA1111", "chatcmpl-BBBB2222"]))
    listing.write_text(json.dumps(["aaaa1111"]))

    result = _run("reconcile", "--ids", str(ids), "--listing", str(listing), "--warn-only")
    assert result.returncode == 0, "warn-only documents loss without failing"


def test_reconcile_clean_run_passes(tmp_path):
    ids = tmp_path / "ids.json"
    listing = tmp_path / "listing.json"
    ids.write_text(json.dumps(["chatcmpl-AAAA1111"]))
    listing.write_text(json.dumps(["AAAA-1111"]))  # dash + case differences are normalized away

    result = _run("reconcile", "--ids", str(ids), "--listing", str(listing))
    assert result.returncode == 0, result.stderr
