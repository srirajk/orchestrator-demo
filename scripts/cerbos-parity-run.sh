#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Axiom Story B2.1 — DECISION PARITY GATE runner.
#
# Boots the pinned Cerbos (0.53.0 == local :latest offline) twice:
#   • PRE  server  (:3620) loading the PRE-migration policies  (arg 1)
#   • POST server  (:3621) loading the POST-migration policies (this worktree)
# then runs scripts/cerbos-parity-matrix.py to diff the FULL persona × resource × action
# matrix. Zero diffs = byte-identical. Also runs the scope-reproduction check (base "" vs
# tenant "default") against the POST server (B2.6).
#
# Distinct container names + ports (36xx) so it NEVER touches the running demo's
# conduit-cerbos (:3594/:3595).
#
# Usage:  bash scripts/cerbos-parity-run.sh <PRE_POLICIES_DIR>
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

PRE_DIR="${1:?usage: cerbos-parity-run.sh <PRE_POLICIES_DIR>}"
IMAGE="${CERBOS_IMAGE:-ghcr.io/cerbos/cerbos:latest}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POST_DIR="$HERE/infra/cerbos/policies"
PRE_NAME=cerbos-b2-pre
POST_NAME=cerbos-b2-post
TMP="$(mktemp -d)"

cleanup() { docker rm -f "$PRE_NAME" "$POST_NAME" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT
# Remove any stale containers from a prior run BEFORE writing configs (the config files
# live under $TMP, which cleanup also removes — so never call cleanup after writing them).
docker rm -f "$PRE_NAME" "$POST_NAME" >/dev/null 2>&1 || true

cat > "$TMP/pre.yaml"  <<'EOF'
server: {httpListenAddr: ":3620"}
engine: {lenientScopeSearch: false}
storage: {driver: disk, disk: {directory: /conf/policies, watchForChanges: false}}
EOF
sed 's/:3620/:3621/' "$TMP/pre.yaml" > "$TMP/post.yaml"

echo "== Cerbos image =="; docker run --rm --entrypoint /cerbos "$IMAGE" --version 2>&1 | head -1

docker run --rm -d --name "$PRE_NAME"  -v "$PRE_DIR:/conf/policies:ro"  -v "$TMP/pre.yaml:/conf/config.yaml:ro"  -p 3620:3620 "$IMAGE" server --config=/conf/config.yaml >/dev/null
docker run --rm -d --name "$POST_NAME" -v "$POST_DIR:/conf/policies:ro" -v "$TMP/post.yaml:/conf/config.yaml:ro" -p 3621:3621 "$IMAGE" server --config=/conf/config.yaml >/dev/null

for port in 3620 3621; do
  for _ in $(seq 1 30); do curl -sf "http://localhost:$port/_cerbos/health" >/dev/null 2>&1 && break; sleep 1; done
done

echo
echo "════════ B2.1  PRE vs POST parity (gateway path, scope='') ════════"
PARITY_RC=0
python3 "$HERE/scripts/cerbos-parity-matrix.py" --pre-url http://localhost:3620 --post-url http://localhost:3621 || PARITY_RC=$?

echo
echo "════════ B2.6  scope reproduction (POST: base '' vs tenant 'default') ════════"
REPRO_RC=0
python3 "$HERE/scripts/cerbos-parity-matrix.py" --url http://localhost:3621 --scope-repro || REPRO_RC=$?

echo
echo "parity_rc=$PARITY_RC  scope_repro_rc=$REPRO_RC"
[ "$PARITY_RC" -eq 0 ] && [ "$REPRO_RC" -eq 0 ]
