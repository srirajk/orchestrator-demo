#!/usr/bin/env python3
"""
Audit reconciler + hash verifier (F5 spec §3f).

Three modes:

  verify <record.json> [--ledger]
      Recompute the tamper-evidence hash over the record's `events` and compare to the stored
      `contentSha256`. It re-emits the persisted `events` array ORDER-PRESERVING (no key re-sorting)
      with compact separators and ensure_ascii=False, so it reproduces Jackson's
      `ObjectMapper.configure(ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(events)` byte-for-byte —
      it never semantically reserializes. Byte-parity with the JVM assembler is gated by
      AuditVerifyFixtureTest before any AC cites this mode.
        exit 0 = match · exit 1 = TAMPER · exit 3 = UNVERIFIABLE (contentSha256 == "", the
        swallowed-exception path) — distinct from tamper on purpose.
      --ledger treats the argument as a ledger.jsonl and re-checks each line's artifact sha256.

  chain <record.json>
      Verify `prevHash` linkage when present. No chain fields exist today → warns and exits 0.

  reconcile --ids ids.json --listing listing.json [--warn-only]
      Compare the SSE-derived transaction-id list against an object-store listing. Both sides are
      normalized dash-stripped + lowercase (lossless for OTel trace-ids and the UUID fallback).
      Reports missing/duplicate ids; exits non-zero on any gap in enforce (default) mode; --warn-only
      documents loss (e.g. drop-oldest audit under burst) without failing.

CORRELATION PREREQUISITE: reconcile presumes the object key or record `transactionId` equals the
normalized SSE id. Today the gateway's stored `transactionId` is the raw requestId and the SSE id is
`chatcmpl-<requestId-dash-stripped>`; the normalization here bridges that, but end-to-end correlation
against MinIO is only exercised once a stub run writes both (F5 AC4). Until then reconcile runs
against provided listing files, not a live bucket.
"""
import argparse
import hashlib
import json
import sys


def canonical_events_bytes(events) -> bytes:
    """Re-emit the events array exactly as Jackson persisted it: order-preserving, compact, UTF-8."""
    return json.dumps(events, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalize_id(raw: str) -> str:
    """chatcmpl-<hex> / UUID → dash-stripped lowercase hex (see module docstring)."""
    if raw is None:
        return ""
    s = str(raw)
    if s.startswith("chatcmpl-"):
        s = s[len("chatcmpl-"):]
    return s.replace("-", "").lower()


def cmd_verify(args) -> int:
    if args.ledger:
        return _verify_ledger(args.record)

    with open(args.record, "r", encoding="utf-8") as fh:
        record = json.load(fh)

    stored = record.get("contentSha256")
    if stored is None or stored == "":
        print(f"UNVERIFIABLE {args.record}: contentSha256 is empty "
              f"(hash was not captured; tamper-evidence degraded, capture intact)", file=sys.stderr)
        return 3

    events = record.get("events", [])
    recomputed = sha256_hex(canonical_events_bytes(events))
    if recomputed == stored:
        print(f"OK {args.record}: contentSha256 matches ({recomputed})")
        return 0
    print(f"TAMPER {args.record}: stored={stored} recomputed={recomputed}", file=sys.stderr)
    return 1


def _verify_ledger(path: str) -> int:
    rc = 0
    with open(path, "r", encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            artifact, expected = entry.get("path"), entry.get("sha256")
            if not artifact or not expected:
                continue
            try:
                with open(artifact, "rb") as af:
                    actual = sha256_hex(af.read())
            except OSError as e:
                print(f"LEDGER-MISS line {lineno}: {artifact} unreadable ({e})", file=sys.stderr)
                rc = 1
                continue
            if actual != expected:
                print(f"LEDGER-TAMPER line {lineno}: {artifact} expected={expected} actual={actual}",
                      file=sys.stderr)
                rc = 1
    if rc == 0:
        print(f"OK {path}: all ledger artifact hashes match")
    return rc


def cmd_chain(args) -> int:
    with open(args.record, "r", encoding="utf-8") as fh:
        record = json.load(fh)
    prev = record.get("prevHash") or record.get("prev_hash")
    if prev is None:
        print(f"WARN {args.record}: no chain fields (prevHash absent) — per-record hash only today")
        return 0
    # Chain enforcement lands with the durable-audit/audit-chain story; today just surface the link.
    print(f"CHAIN {args.record}: prevHash={prev} (linkage verification is the audit-chain story's DoD)")
    return 0


def cmd_reconcile(args) -> int:
    with open(args.ids, "r", encoding="utf-8") as fh:
        ids_raw = json.load(fh)
    with open(args.listing, "r", encoding="utf-8") as fh:
        listing_raw = json.load(fh)

    ids_norm = [normalize_id(x) for x in ids_raw]
    listing_norm = {normalize_id(x) for x in listing_raw}

    seen, duplicates = set(), []
    for i in ids_norm:
        if i in seen:
            duplicates.append(i)
        seen.add(i)

    missing = sorted(i for i in seen if i not in listing_norm)

    for m in missing:
        print(f"MISSING transactionId={m}: in SSE ids, absent from object-store listing", file=sys.stderr)
    for d in sorted(set(duplicates)):
        print(f"DUPLICATE transactionId={d}: appears more than once in SSE ids", file=sys.stderr)

    gaps = len(missing) + len(set(duplicates))
    print(f"reconcile: {len(seen)} unique SSE ids, {len(listing_norm)} listed, "
          f"{len(missing)} missing, {len(set(duplicates))} duplicate")
    if gaps == 0:
        return 0
    if args.warn_only:
        print("reconcile: --warn-only — gaps reported, not failing (documents drop-oldest loss)")
        return 0
    return 2


def main() -> int:
    p = argparse.ArgumentParser(description="Audit reconciler + hash verifier (F5 §3f)")
    sub = p.add_subparsers(dest="mode", required=True)

    v = sub.add_parser("verify", help="recompute + compare the events content hash")
    v.add_argument("record")
    v.add_argument("--ledger", action="store_true", help="treat record as ledger.jsonl of artifact hashes")
    v.set_defaults(func=cmd_verify)

    c = sub.add_parser("chain", help="verify prevHash linkage when present")
    c.add_argument("record")
    c.set_defaults(func=cmd_chain)

    r = sub.add_parser("reconcile", help="reconcile SSE ids against an object-store listing")
    r.add_argument("--ids", required=True)
    r.add_argument("--listing", required=True)
    r.add_argument("--warn-only", action="store_true")
    r.set_defaults(func=cmd_reconcile)

    args = p.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
