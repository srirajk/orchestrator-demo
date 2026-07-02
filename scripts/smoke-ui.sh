#!/usr/bin/env bash
# smoke-ui.sh — Tier-1 fast gate. No LLM calls, no sleeps: catches integration breakage
# (a dead UI, a CORS reject on a browser login path, a broken persona login) in seconds.
# Invoked at the top of smoke.sh, but also runnable standalone: `bash scripts/smoke-ui.sh`.
#
# Exit code == number of failed checks (0 == green). Every check prints one PASS/FAIL line.
set -u

P=0; F=0
pass(){ printf '  \033[32m✅ PASS\033[0m %s\n' "$1"; P=$((P+1)); }
fail(){ printf '  \033[31m❌ FAIL\033[0m %s\n' "$1"; F=$((F+1)); }

IAM=${IAM_URL:-http://localhost:8084}
PW=${IAM_USER_PASSWORD:-Meridian@2024}

code(){ curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$@" 2>/dev/null; }

# ── A. Every browser-facing URL + backing service is reachable (HTTP 200) ────────────
echo "═══ TIER-1 A. URLs return 200 ═══"
check_url(){ # name url [want]
  local name=$1 url=$2 want=${3:-200} c
  c=$(code "$url")
  [ "$c" = "$want" ] && pass "$name  $url → $c" || fail "$name  $url → $c (want $want)"
}
check_url "chat"       http://localhost:8099/health
check_url "admin"      http://localhost:5182/
check_url "gateway"    http://localhost:8080/v1/models
check_url "iam"        http://localhost:8084/actuator/health
check_url "grafana"    http://localhost:3000/api/health
check_url "langfuse"   http://localhost:3030/api/public/health
check_url "glass-box"  http://localhost:4000/
check_url "prometheus" http://localhost:9090/-/healthy
check_url "loki"       http://localhost:3100/ready
check_url "tempo"      http://localhost:3200/status

# ── B. CORS preflight per browser origin (the check that catches bug #2) ──────────────
# A browser login path is broken when the API rejects the preflight — Spring returns
# 403 "Invalid CORS request". Fire the real OPTIONS preflight each browser UI would send
# against the API it actually calls, and assert it is NOT a CORS reject.
echo "═══ TIER-1 B. CORS preflight per browser origin ═══"
preflight(){ # origin url
  curl -s -o /dev/null -w '%{http_code}' --max-time 8 -X OPTIONS "$2" \
    -H "Origin: $1" -H 'Access-Control-Request-Method: POST' \
    -H 'Access-Control-Request-Headers: content-type' 2>/dev/null
}
cors_ok(){ # name origin url
  local name=$1 origin=$2 url=$3 c
  c=$(preflight "$origin" "$url")
  if [ "$c" = "403" ]; then
    fail "$name  preflight from $origin → 403 (CORS reject — browser login would fail)"
  elif [ "$c" -ge 200 ] 2>/dev/null && [ "$c" -lt 400 ] 2>/dev/null; then
    pass "$name  preflight from $origin → $c (not rejected)"
  else
    fail "$name  preflight from $origin → $c"
  fi
}
# chat UI (:8099) → its own BFF /api (same-origin); admin console (:5182) → iam (:8084).
cors_ok "chat→BFF /api" http://localhost:8099 http://localhost:8099/api/auth/login
cors_ok "admin→iam"     http://localhost:5182 "$IAM/auth/login"

# ── C. All four personas can mint an Axiom JWT ────────────────────────────────────────
echo "═══ TIER-1 C. Personas mint Axiom JWT ═══"
mint(){ curl -s --max-time 8 -X POST "$IAM/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" 2>/dev/null; }
for u in rm_jane rm_carlos rm_guest uw_sam; do
  tok=$(mint "$u" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("accessToken",""))' 2>/dev/null)
  [ -n "$tok" ] && pass "persona $u mints JWT" || fail "persona $u could not mint a JWT"
done

echo ""
echo "═══ TIER-1 RESULT: $P passed / $F failed ═══"
[ "$F" -eq 0 ] && echo "  🟢 TIER-1 GREEN" || echo "  🔴 TIER-1 FAIL"
exit "$F"
