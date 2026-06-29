#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# World B drift monitor / CI gate
#
# Enforces the World B invariant: the gateway is a manifest interpreter with
# ZERO embedded domain knowledge. See docs/WORLD-B-LOCKDOWN.md (§3 inventory,
# §8 definition of done).
#
# This is the deterministic half of the enforcement loop — it does not depend on
# any agent *remembering* World B. It greps the gateway source for domain
# coupling and reports every violation as a worklist.
#
# Usage:
#   scripts/world-b-check.sh           # full report + exit 1 if CRITICAL > 0
#   scripts/world-b-check.sh --quiet   # summary only
#
# Lifecycle:
#   - TODAY: the code is mid-refactor, so this FAILS by design. The violation
#     list IS the World B worklist. Each build-plan step (§7) drives it down.
#   - WHEN GREEN: wire it into scripts/verify.sh and a pre-commit hook as a hard
#     gate. From then on, no domain knowledge can re-enter the gateway silently.
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATEWAY_SRC="$ROOT/gateway/src/main/java"
QUIET="${1:-}"

if [[ ! -d "$GATEWAY_SRC" ]]; then
  echo "world-b-check: gateway source not found at $GATEWAY_SRC" >&2
  exit 2
fi

# Drop comment-only matches. grep -rn output is "path:lineno:content"; we test
# the content portion (after :lineno:) for a leading comment marker, so a javadoc
# line that mentions REL-00042 as an example is not counted as a code violation.
strip_comments() { grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'; }

LAST_COUNT=0   # set by scan(); read by the caller — keeps report off the count stream

# scan <label> <regex>  → prints matching code lines, sets LAST_COUNT
scan() {
  local label="$1" regex="$2"
  local hits count=0
  hits="$(grep -rnE "$regex" "$GATEWAY_SRC" --include='*.java' 2>/dev/null | strip_comments)"
  [[ -n "$hits" ]] && count="$(printf '%s\n' "$hits" | grep -c .)"
  LAST_COUNT="$count"
  if [[ "$count" -gt 0 && "$QUIET" != "--quiet" ]]; then
    echo ""
    echo "  ▸ $label  ($count)"
    printf '%s\n' "$hits" | sed "s#$GATEWAY_SRC/#      #"
  fi
}

echo "═══════════════════════════════════════════════════════════════════════"
echo " World B check — gateway must carry zero domain knowledge"
echo " target: ${GATEWAY_SRC#"$ROOT"/}"
echo "═══════════════════════════════════════════════════════════════════════"

# ── CRITICAL — these must NEVER appear in gateway code ───────────────────────
echo ""
echo "CRITICAL violations (hard gate — must be zero for World B):"

scan "Domain / sub-domain identifiers" \
  'wealth-management|asset-servicing|private-banking|custody-operations|corporate-actions|cash-management|institutional'; c1=$LAST_COUNT
scan "Hardcoded entity / client names" \
  '[Ww]hitman|[Cc]alderon|[Oo]kafor|[Aa]ndersen'; c2=$LAST_COUNT
scan "Hardcoded ID prefixes / patterns" \
  'REL-[0-9\\]|FND-[A-Za-z0-9\\]'; c3=$LAST_COUNT
scan "Hardcoded entity field names" \
  '"relationship_id"|"fund_id"|"relationship_reference"|"fund_reference"|"ticker_references"'; c4=$LAST_COUNT
scan "Hardcoded domain copy (user-facing strings)" \
  '[Bb]anking AI|client relationship|which client|in your coverage|mention the client|client name'; c5=$LAST_COUNT
scan "Hardcoded domain defaults" \
  '"QTD"'; c6=$LAST_COUNT

CRITICAL=$(( c1 + c2 + c3 + c4 + c5 + c6 ))

# ── REVIEW — likely violations, need human judgment (might read a manifest) ──
echo ""
echo "REVIEW (human judgment — flag, don't auto-fail):"

scan "Bare entity-type literals" \
  '= *"relationship"|= *"fund"|case *"relationship"|RESOURCE_REL'; r1=$LAST_COUNT
scan "Domain-specific @Value / env names" \
  'crm\.wealth|WEALTH_CRM|wealthCrmUrl|coverage\.wealth'; r2=$LAST_COUNT

REVIEW=$(( r1 + r2 ))

# ── Verdict ──────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " RESULT"
echo "   CRITICAL violations : $CRITICAL   (must be 0 for World B)"
echo "   REVIEW flags        : $REVIEW   (resolve or justify each)"
echo "═══════════════════════════════════════════════════════════════════════"

if [[ "$CRITICAL" -gt 0 ]]; then
  echo ""
  echo " ✗ NOT World B clean. The list above is the worklist."
  echo "   Drive CRITICAL → 0 via the build plan in docs/WORLD-B-LOCKDOWN.md §7."
  exit 1
fi

if [[ "$REVIEW" -gt 0 ]]; then
  echo ""
  echo " ⚠ No CRITICAL violations, but REVIEW flags remain — justify each before"
  echo "   wiring this into CI as a hard gate."
  exit 0
fi

echo ""
echo " ✓ World B clean — gateway carries no domain knowledge."
echo "   Safe to wire into scripts/verify.sh + pre-commit as a hard gate."
exit 0
