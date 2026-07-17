#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════════════════
#  Cerbos policy gate  (Axiom Story B3.1) — the HARD CI gate for authz policies.
# ═════════════════════════════════════════════════════════════════════════════
#  A single deterministic gate that fails the build on a bad policy. Three layers,
#  all hard (any non-zero fails the whole gate):
#    1. cerbos compile --test-output=junit  — the authoritative compile + the
#       hand-owned invariant suite + the 45 B2 regression tests (pinned 0.53.0).
#    2. cerbos-tenant-totality-lint.py       — no base-allowed tuple falls through a
#       tenant child (fail-open by inheritance is INVISIBLE to decision tests).
#    3. cerbos-allow-tenant-equality-lint.py — every ALLOW is tenant-scoped or a
#       documented superuser (no raw cross-tenant bypass).
#
#  Runtime parity: uses the SAME pinned Cerbos as the runtime PDP (0.53.0). On CI the
#  cerbos binary is provided by cerbos/cerbos-setup-action; locally/offline we fall
#  back to the pinned docker image. Either way the version is asserted == 0.53.0.
#
#  Usage:  scripts/cerbos-policy-gate.sh [POLICIES_DIR] [JUNIT_OUT_XML]
#     POLICIES_DIR  default: infra/cerbos/policies
#     JUNIT_OUT_XML default: <repo>/.cerbos-junit.xml
#  Exit 0 = all layers clean; non-zero = at least one layer failed.
# ═════════════════════════════════════════════════════════════════════════════
set -euo pipefail

CERBOS_VERSION="0.53.0"
# Runtime and validation use the same explicit image tag; never let a moving tag alter policy truth.
CERBOS_IMAGE="${CERBOS_IMAGE:-ghcr.io/cerbos/cerbos:0.53.0}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POLICIES_DIR="${1:-$ROOT/infra/cerbos/policies}"
JUNIT_OUT="${2:-$ROOT/.cerbos-junit.xml}"

POLICIES_DIR="$(cd "$POLICIES_DIR" && pwd)"   # absolutise (docker mount + lints)

# ── pick the cerbos runner: local pinned binary (CI) else pinned docker image ──
# Emits the argv prefix for "cerbos compile <policies>" honouring $POLICIES_DIR.
run_compile() {   # args: extra flags... ; policies mounted at a fixed container path
  local out
  if command -v cerbos >/dev/null 2>&1; then
    local v; v="$(cerbos --version 2>/dev/null || true)"
    if [[ "$v" != "$CERBOS_VERSION" ]]; then
      echo "   ✗  local cerbos is '$v', expected pinned $CERBOS_VERSION" >&2; return 3
    fi
    cerbos compile "$@" "$POLICIES_DIR"
  else
    docker run --rm \
      -v "$POLICIES_DIR:/policies:ro" \
      --entrypoint /cerbos "$CERBOS_IMAGE" compile "$@" /policies
  fi
}

# Assert the docker image really is the pinned version (parity with the runtime PDP).
assert_docker_cerbos_version() {
  command -v cerbos >/dev/null 2>&1 && return 0   # CI path uses the pinned binary
  local v; v="$(docker run --rm --entrypoint /cerbos "$CERBOS_IMAGE" --version 2>/dev/null | head -1)"
  if [[ "$v" != "$CERBOS_VERSION" ]]; then
    echo "   ✗  docker image $CERBOS_IMAGE reports cerbos '$v', expected pinned $CERBOS_VERSION" >&2
    exit 3
  fi
}

echo "▶  [cerbos-gate] policies: $POLICIES_DIR"
echo "▶  [cerbos-gate] cerbos:   $CERBOS_VERSION ($(command -v cerbos >/dev/null 2>&1 && echo 'local binary' || echo "docker $CERBOS_IMAGE"))"
assert_docker_cerbos_version

# ── Layer 1: compile + tests (junit artifact + human tree) ───────────────────
# junit is the CI artifact + the exact spec command; a second tree run is the
# human-readable console summary (identical binary, dir — negligible cost).
echo ""
echo "▶  [1/3] cerbos compile --test-output=junit  (compile + invariant suite + B2 regression)"
if ! run_compile --test-output=junit > "$JUNIT_OUT" 2>/dev/null; then
  echo "   ✗  COMPILE/TESTS FAILED — see failures below:"
  run_compile || true          # re-run in tree form to show the failing testcases
  echo "   ✗  junit written to $JUNIT_OUT"
  exit 1
fi
# succeeded — show the readable tree summary
run_compile | sed 's/^/       /'
echo "   ✅  compile + tests passed (junit → $JUNIT_OUT)"

# ── Layer 2: tenant totality lint ────────────────────────────────────────────
echo ""
echo "▶  [2/3] cerbos-tenant-totality-lint.py  (no fall-through holes)"
if ! python3 "$ROOT/scripts/cerbos-tenant-totality-lint.py" "$POLICIES_DIR"; then
  echo "   ✗  TOTALITY LINT FAILED — a base-allowed tuple falls through a tenant child (fail-open)."
  exit 1
fi
echo "   ✅  totality lint clean."

# ── Layer 3: allow tenant-equality lint ──────────────────────────────────────
echo ""
echo "▶  [3/3] cerbos-allow-tenant-equality-lint.py  (no raw cross-tenant ALLOW)"
if ! python3 "$ROOT/scripts/cerbos-allow-tenant-equality-lint.py" "$POLICIES_DIR" >/dev/null; then
  echo "   ✗  EQUALITY LINT FAILED — an ALLOW bypasses the tenant-equality backstop."
  python3 "$ROOT/scripts/cerbos-allow-tenant-equality-lint.py" "$POLICIES_DIR" | tail -6
  exit 1
fi
echo "   ✅  equality lint clean."

echo ""
echo "✅  [cerbos-gate] all three layers passed."
