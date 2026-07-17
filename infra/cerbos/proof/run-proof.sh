#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Axiom Story B1 — Cerbos scope-posture EMPIRICAL PROOF harness.
#
# Runs the pinned Cerbos (contract #1: ghcr.io/cerbos/cerbos:0.53.0) against the
# base+tenant scope fixtures in ./policies and captures the ACTUAL decisions for:
#   1. absentTenantScopeDeniesEverything      (strict search → hard DENY)
#   2. emptyChildFallsThroughToParent         (silent child inherits parent → fail-open)
#   3. tenantOverrideCannotExceedBaseCeiling  (parental consent ceiling)
#   4. denyAllTemplateDeniesUntilGranted      (explicit-DENY template)
# plus the lenientScopeSearch:true CONTRAST that proves strict search is load-bearing,
# then runs the reproducible test suite via `cerbos compile --test-output=junit`.
#
# Uses DISTINCT container names + ports (3600/3601, 3610/3611) so it NEVER disturbs
# the running demo's conduit-cerbos (:3594/:3595).
#
# Usage:  bash infra/cerbos/proof/run-proof.sh
# Override image:  CERBOS_IMAGE=ghcr.io/cerbos/cerbos:0.53.0 bash .../run-proof.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

CERBOS_IMAGE="${CERBOS_IMAGE:-ghcr.io/cerbos/cerbos:latest}"   # latest == 0.53.0 offline
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POL="$HERE/policies"
STRICT_NAME=cerbos-proof-b1
LENIENT_NAME=cerbos-proof-b1-lenient

cleanup() { docker rm -f "$STRICT_NAME" "$LENIENT_NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

echo "== Cerbos image =="
docker run --rm --entrypoint /cerbos "$CERBOS_IMAGE" --version 2>&1 | head -3
echo

echo "== Starting STRICT server (lenientScopeSearch:false) on :3600 =="
docker run --rm -d --name "$STRICT_NAME" \
  -v "$POL:/conf/policies:ro" -v "$HERE/config.yaml:/conf/config.yaml:ro" \
  -p 3600:3600 -p 3601:3601 "$CERBOS_IMAGE" server --config=/conf/config.yaml >/dev/null

echo "== Starting LENIENT server (lenientScopeSearch:true) on :3610 (contrast) =="
sed 's/lenientScopeSearch: false/lenientScopeSearch: true/; s/:3600/:3610/; s/:3601/:3611/' \
  "$HERE/config.yaml" > /tmp/cerbos-proof-lenient.yaml
docker run --rm -d --name "$LENIENT_NAME" \
  -v "$POL:/conf/policies:ro" -v "/tmp/cerbos-proof-lenient.yaml:/conf/config.yaml:ro" \
  -p 3610:3610 -p 3611:3611 "$CERBOS_IMAGE" server --config=/conf/config.yaml >/dev/null

# wait for health
for _ in $(seq 1 20); do
  curl -sf http://localhost:3600/_cerbos/health >/dev/null 2>&1 && break; sleep 1
done
for _ in $(seq 1 20); do
  curl -sf http://localhost:3610/_cerbos/health >/dev/null 2>&1 && break; sleep 1
done

# probe PORT SCOPE ACTIONS-json
probe() {
  local port="$1" scope="$2" acts="$3"
  curl -s -X POST "http://localhost:$port/api/check/resources" -H 'Content-Type: application/json' \
    -d "{\"principal\":{\"id\":\"u1\",\"roles\":[\"user\"],\"attr\":{}},\"resources\":[{\"resource\":{\"kind\":\"widget\",\"id\":\"w1\",\"scope\":\"$scope\",\"attr\":{}},\"actions\":$acts}]}" \
  | python3 -c "import sys,json;r=json.load(sys.stdin).get('results');print('   <no matching policy / error>') if not r else [print(f'   {a}: {v}') for a,v in sorted(r[0]['actions'].items())]"
}

echo
echo "════════ RAW CheckResources PROBES (strict, :3600) ════════"
echo "── base ceiling (scope='') — expect view/edit ALLOW, delete DENY"
probe 3600 "" '["view","edit","delete"]'
echo "── emptyChildFallsThroughToParent (scope='acme', silent on edit) — expect edit ALLOW (INHERITED)"
probe 3600 "acme" '["view","edit"]'
echo "── tenantOverrideCannotExceedBaseCeiling (scope='exceed' grants delete) — expect delete DENY"
probe 3600 "exceed" '["view","delete"]'
echo "── absentTenantScopeDeniesEverything (scope='ghost', NO policy) — expect DENY"
probe 3600 "ghost" '["view","edit"]'
echo "── denyAllTemplate BEFORE grant (scope='boot') — expect view/edit DENY"
probe 3600 "boot" '["view","edit"]'
echo "── denyAllTemplate AFTER grant (scope='boot2') — expect view ALLOW, edit DENY"
probe 3600 "boot2" '["view","edit"]'

echo
echo "════════ CONTRAST: absent scope under LENIENT search (:3610) ════════"
echo "── scope='ghost' with lenientScopeSearch:true — expect view/edit ALLOW (fail-open on missing tenant)"
probe 3610 "ghost" '["view","edit"]'

echo
echo "════════ REPRODUCIBLE SUITE (contract #1 command) ════════"
docker run --rm -v "$POL:/pol:ro" "$CERBOS_IMAGE" compile --test-output=junit /pol \
  | grep -E '<testsuites|failures=' | head -1
docker run --rm -v "$POL:/pol:ro" "$CERBOS_IMAGE" compile /pol 2>&1 | tail -3

echo
echo "Proof complete."
