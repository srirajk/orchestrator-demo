#!/usr/bin/env bash
# Evidence capture → ledger (F5 spec §3e).
#
# Runs a command, tees stdout to an evidence dir, records exit code + metadata, and appends one
# SHA-256'd line per artifact to a shared ledger.jsonl. The ledger's own hashes are re-checkable with
#   scripts/audit-verify.py verify <ledger.jsonl> --ledger
#
# Usage: scripts/evidence.sh <story> <name> -- <cmd...>
#   e.g. scripts/evidence.sh story-0 world-b -- bash scripts/world-b-check.sh
set -euo pipefail

if [[ $# -lt 4 || "$3" != "--" ]]; then
  echo "usage: $0 <story> <name> -- <cmd...>" >&2
  exit 64
fi

STORY="$1"; NAME="$2"; shift 3
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="$ROOT/docs/implementation/evidence/$STORY/$NAME"
LEDGER="$ROOT/docs/implementation/evidence/ledger.jsonl"
mkdir -p "$OUT_DIR"

STDOUT_LOG="$OUT_DIR/stdout.log"
GIT_SHA="$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

set +e
"$@" >"$STDOUT_LOG" 2>&1
CODE=$?
set -e

echo "$CODE" > "$OUT_DIR/exit_code"
cat > "$OUT_DIR/meta.json" <<JSON
{"story":"$STORY","name":"$NAME","git_sha":"$GIT_SHA","ts":"$TS","exit_code":$CODE,"cmd":"$*"}
JSON

sha_of() { shasum -a 256 "$1" 2>/dev/null | awk '{print $1}' || sha256sum "$1" | awk '{print $1}'; }
for artifact in "$STDOUT_LOG" "$OUT_DIR/exit_code" "$OUT_DIR/meta.json"; do
  SHA="$(sha_of "$artifact")"
  echo "{\"story\":\"$STORY\",\"name\":\"$NAME\",\"path\":\"$artifact\",\"sha256\":\"$SHA\",\"git_sha\":\"$GIT_SHA\",\"ts\":\"$TS\",\"exit_code\":$CODE}" >> "$LEDGER"
done

echo "[evidence] $STORY/$NAME exit=$CODE → $OUT_DIR (ledger: $LEDGER)"
exit "$CODE"
