#!/usr/bin/env bash
# ═════════════════════════════════════════════════════════════════════════════
#  Cerbos mutation smoke  (Axiom Story B3.3) — THE TEETH TEST.
# ═════════════════════════════════════════════════════════════════════════════
#  Proves the policy gate is not coverage theater: a suite that no mutant can break
#  is worthless. For each planted over-permissive mutant under infra/cerbos/mutants/:
#    1. copy the REAL policy tree to a fresh temp dir (the working tree is NEVER touched),
#    2. overlay exactly ONE mutant in place of its MUTANT-TARGET,
#    3. run the FULL policy gate (scripts/cerbos-policy-gate.sh) against the temp dir,
#    4. assert it exits NON-ZERO — a test/invariant (or a lint) must catch the over-grant.
#  If ANY mutant PASSES the gate, that is a real hole (the suite missed an over-grant):
#  this script exits 1 LOUDLY and names the escaping mutant.
#
#  Usage:  scripts/cerbos-mutation-smoke.sh
#  Exit 0 = every mutant was caught (teeth intact); 1 = at least one mutant escaped.
# ═════════════════════════════════════════════════════════════════════════════
set -uo pipefail   # NOT -e: we intentionally run a gate we expect to fail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POLICIES_DIR="$ROOT/infra/cerbos/policies"
MUTANTS_DIR="$ROOT/infra/cerbos/mutants"
GATE="$ROOT/scripts/cerbos-policy-gate.sh"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/cerbos-mutation-smoke.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "═══════════════════════════════════════════════════════════════════════"
echo "  Cerbos mutation smoke — planted over-permissive mutants must FAIL the gate"
echo "═══════════════════════════════════════════════════════════════════════"
echo "  working temp dir: $WORK  (working tree untouched)"

# Sanity: baseline (clean policies) must PASS the gate, else the smoke is meaningless.
echo ""
echo "▶  baseline: clean policies must PASS the gate ..."
if ! "$GATE" "$POLICIES_DIR" "$WORK/baseline-junit.xml" >"$WORK/baseline.log" 2>&1; then
  echo "   ✗  BASELINE FAILED — clean policies do not pass the gate. Aborting (fix the gate first)."
  tail -20 "$WORK/baseline.log"
  exit 2
fi
echo "   ✅  baseline clean policies pass."

declare -a ROWS
ESCAPES=0
COUNT=0

shopt -s nullglob
for MUT in "$MUTANTS_DIR"/*.yaml; do
  COUNT=$((COUNT+1))
  MNAME="$(basename "$MUT")"
  TARGET="$(grep -m1 'MUTANT-TARGET:' "$MUT" | sed 's/.*MUTANT-TARGET:[[:space:]]*//')"
  if [[ -z "$TARGET" ]]; then
    echo "   ✗  $MNAME has no MUTANT-TARGET marker — cannot apply."; ESCAPES=$((ESCAPES+1)); continue
  fi

  # 1. fresh copy of the real tree
  SANDBOX="$WORK/$MNAME.policies"
  rm -rf "$SANDBOX"; cp -R "$POLICIES_DIR" "$SANDBOX"
  # 2. overlay the single mutant in place of its target (must exist)
  if [[ ! -f "$SANDBOX/$TARGET" ]]; then
    echo "   ✗  target $TARGET not found in policy tree for $MNAME."; ESCAPES=$((ESCAPES+1)); continue
  fi
  cp "$MUT" "$SANDBOX/$TARGET"

  # 3. run the full gate against the sandbox
  LOG="$WORK/$MNAME.log"
  "$GATE" "$SANDBOX" "$WORK/$MNAME-junit.xml" >"$LOG" 2>&1
  EXIT=$?

  # 4. classify which layer caught it (gate stops at first failing layer)
  if   grep -q "COMPILE/TESTS FAILED" "$LOG"; then CAUGHT="cerbos compile (test/invariant)"
  elif grep -q "TOTALITY LINT FAILED" "$LOG"; then CAUGHT="tenant-totality lint"
  elif grep -q "EQUALITY LINT FAILED" "$LOG"; then CAUGHT="tenant-equality lint"
  elif [[ $EXIT -eq 0 ]]; then                     CAUGHT="— (ESCAPED)"
  else                                             CAUGHT="gate error (exit $EXIT)"
  fi

  if [[ $EXIT -ne 0 ]]; then VERDICT="CAUGHT ✅"; else VERDICT="ESCAPED ❌"; ESCAPES=$((ESCAPES+1)); fi
  # pull the failing testcase names (if any) for the compile layer
  FAILDETAIL=""
  if grep -q "COMPILE/TESTS FAILED" "$LOG"; then
    FAILDETAIL="$(grep -Eo '[A-Za-z0-9_]+ \[FAILED\]' "$LOG" | head -3 | tr '\n' ';' )"
  fi
  ROWS+=("$MNAME|$TARGET|non-zero|$EXIT|$CAUGHT|$VERDICT|$FAILDETAIL")

  printf '   %-40s target=%-26s exit=%s  %s\n' "$MNAME" "$TARGET" "$EXIT" "$VERDICT"
done

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo "  MUTATION SUMMARY TABLE"
echo "═══════════════════════════════════════════════════════════════════════"
printf '%-40s | %-26s | %-9s | %-4s | %-32s | %s\n' "MUTANT" "TARGET" "EXPECTED" "EXIT" "CAUGHT BY" "VERDICT"
printf '%s\n' "---------------------------------------------------------------------------------------------------------------------------------------------------"
for r in "${ROWS[@]}"; do
  IFS='|' read -r m t e x c v d <<< "$r"
  printf '%-40s | %-26s | %-9s | %-4s | %-32s | %s\n' "$m" "$t" "$e" "$x" "$c" "$v"
  [[ -n "$d" ]] && printf '%-40s   ↳ failing tests: %s\n' "" "$d"
done
echo ""

if [[ $ESCAPES -ne 0 ]]; then
  echo "❌  MUTATION SMOKE RED — $ESCAPES of $COUNT planted mutant(s) ESCAPED the gate."
  echo "    A real over-grant would slip through. Add/strengthen an invariant to catch it."
  exit 1
fi
echo "✅  MUTATION SMOKE GREEN — all $COUNT planted mutants were caught. The gate has teeth."
exit 0
