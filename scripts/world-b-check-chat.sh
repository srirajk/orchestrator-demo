#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# World B drift monitor — CHAT (apps/chat) structured-clarification surface
#
# The clarification FORM must be self-describing: every label / option / prompt is
# DATA carried on the `structured_interaction` event payload, authored by the
# manifest + coverage book. The SPA form and the BFF resume handler render/relay it
# BLIND — they hold zero domain knowledge (no domain names, client/entity names, ID
# prefixes, entity-type literals, or hardcoded form copy). This is the same invariant
# scripts/world-b-check.sh enforces on the gateway, extended to the chat clarify code.
#
# Scope: the clarify-OWNED production files only. Shared chat modules (gatewayTrace.ts,
# useTraceStream.ts, ChatPane.tsx) carry legacy trace-rail heuristics with domain
# literals that predate this surface — they are deliberately out of clarify scope; the
# selector + wiring added there contain no domain copy. Test fixtures (*.test.*) are
# excluded (they legitimately carry sample labels).
#
# Usage:
#   scripts/world-b-check-chat.sh           # full report + exit 1 if CRITICAL > 0
#   scripts/world-b-check-chat.sh --quiet   # summary only
# ─────────────────────────────────────────────────────────────────────────────
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
QUIET="${1:-}"

# The clarify-owned production files across the SPA (web) and the BFF.
CLARIFY_FILES=(
  "$ROOT/apps/chat/web/src/components/ClarificationForm.tsx"
  "$ROOT/apps/chat/bff/src/main/java/ai/conduit/chat/chat/ClarifyController.java"
  "$ROOT/apps/chat/bff/src/main/java/ai/conduit/chat/chat/ClarifyResolveRequest.java"
)

# Keep only the files that exist (a partial checkout must not error).
EXISTING=()
for f in "${CLARIFY_FILES[@]}"; do
  [[ -f "$f" ]] && EXISTING+=("$f")
done
if [[ ${#EXISTING[@]} -eq 0 ]]; then
  echo "world-b-check-chat: no clarify files found under apps/chat" >&2
  exit 2
fi

# Drop comment-only matches (JSDoc `*`, `//`, `/* */`, and Java comments alike). grep -rn
# output is "path:lineno:content"; test the content portion for a leading comment marker.
strip_comments() { grep -vE ':[0-9]+:[[:space:]]*(\*|//|/\*)'; }

LAST_COUNT=0

# scan <label> <regex>  → prints matching code lines, sets LAST_COUNT
scan() {
  local label="$1" regex="$2"
  local hits count=0
  hits="$(grep -rnE "$regex" "${EXISTING[@]}" 2>/dev/null | strip_comments | grep -v '^[[:space:]]*$')"
  [[ -n "$hits" ]] && count="$(printf '%s\n' "$hits" | grep -c .)"
  LAST_COUNT="$count"
  if [[ "$count" -gt 0 && "$QUIET" != "--quiet" ]]; then
    echo ""
    echo "  ▸ $label  ($count)"
    printf '%s\n' "$hits" | sed -e "s#$ROOT/##"
  fi
}

echo "═══════════════════════════════════════════════════════════════════════"
echo " World B check — chat clarification surface must carry zero domain knowledge"
echo " targets:"
for f in "${EXISTING[@]}"; do echo "         ${f#"$ROOT"/}"; done
echo "═══════════════════════════════════════════════════════════════════════"

echo ""
echo "CRITICAL violations (hard gate — must be zero for World B):"

scan "Domain / sub-domain identifiers" \
  'wealth-management|asset-servicing|private-banking|custody-operations|corporate-actions|cash-management|institutional'; c1=$LAST_COUNT
scan "Hardcoded entity / client names" \
  '[Ww]hitman|[Cc]alderon|[Oo]kafor|[Aa]ndersen'; c2=$LAST_COUNT
scan "Hardcoded ID prefixes / patterns" \
  'REL-[0-9\\]|FND-[A-Za-z0-9\\]|POL-[0-9\\]'; c3=$LAST_COUNT
scan "Hardcoded entity-type / field literals" \
  '"relationship_id"|"fund_id"|"relationship_reference"|"fund_reference"|relationship_reference|"relationship"|"fund"'; c4=$LAST_COUNT
scan "Hardcoded domain / form copy (labels must come from the event payload)" \
  'which client|client relationship|in your coverage|mention the client|client name|Which .*relationship'; c5=$LAST_COUNT

CRITICAL=$(( c1 + c2 + c3 + c4 + c5 ))

echo ""
echo "═══════════════════════════════════════════════════════════════════════"
echo " RESULT (chat clarify surface)"
echo "   CRITICAL violations : $CRITICAL   (must be 0 for World B)"
echo "═══════════════════════════════════════════════════════════════════════"

if [[ "$CRITICAL" -gt 0 ]]; then
  echo ""
  echo " ✗ NOT World B clean. Labels/options/prompts must come from the"
  echo "   structured_interaction event payload, never hardcoded in the form/BFF."
  exit 1
fi

echo ""
echo " ✓ World B clean — chat clarification surface renders the payload blind."
exit 0
