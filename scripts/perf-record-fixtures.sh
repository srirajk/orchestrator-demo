#!/usr/bin/env bash
# Record AIMock fixtures once, so every later load run is free and deterministic.
#
#   scripts/perf-record-fixtures.sh [openai|ollama]
#
# Brings aimock up in --record mode (it proxies anything it can't match, and saves the response),
# drives every prompt the load test and e2e matrix use, then validates what landed on disk.
#
# WHY THE VALIDATION GATE: a small local model can emit malformed JSON or omit tool_calls. Since
# IntentClassifier now THROWS on a bad response (commit 4b3d9f9) rather than fabricating an intent,
# a bad fixture would surface later as a gateway ERROR and be misdiagnosed as our bug. Catch it here.
set -euo pipefail

PROVIDER="${1:-openai}"
COMPOSE="docker compose -p orchestrator-demo -f docker-compose.yml -f docker-compose.perf.yml"
FIXTURES="mock-agents/aimock/fixtures"
IAM=${IAM_URL:-http://localhost:8084}
GW=${GATEWAY_URL:-http://localhost:8080}
PW=${IAM_USER_PASSWORD:-Meridian@2024}

# The provider URL must NOT include /v1 — aimock appends the incoming request path
# (/v1/chat/completions). Passing .../v1 produces .../v1/v1/chat/completions, a 404, and aimock then
# SAVES THAT ERROR AS A FIXTURE. A poisoned fixture replays forever and looks like a gateway bug.
case "$PROVIDER" in
  openai) UPSTREAM="https://api.openai.com"; FLAG="--provider-openai" ;;
  # openchat returns NO tool_calls -> EntityExtractor would record garbage. qwen3 supports them.
  ollama) UPSTREAM="http://host.docker.internal:11434"; FLAG="--provider-ollama" ;;
  *) echo "usage: $0 [openai|ollama]" >&2; exit 2 ;;
esac

echo "▶ recording from $PROVIDER ($UPSTREAM)"
mkdir -p "$FIXTURES"

# Export for the WHOLE script: any later `up` that touches aimock must not silently recreate it with
# the default (--strict, zero fixtures) command, which exits 1 and takes the dependency chain down.
export AIMOCK_ARGS="--fixtures /fixtures --host 0.0.0.0 --metrics --record $FLAG $UPSTREAM"
$COMPOSE up -d --force-recreate aimock toxiproxy >/dev/null
sleep 7
$COMPOSE up -d --no-deps gateway servicing-mcp >/dev/null
for _ in $(seq 1 40); do
  [ "$(docker inspect -f '{{.State.Health.Status}}' conduit-gateway 2>/dev/null)" = "healthy" ] && break
  sleep 3
done

tok() { curl -s -X POST "$IAM/auth/token" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$PW\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])'; }

ask() { # $1=user $2=prompt
  local t; t=$(tok "$1")
  curl -s -X POST "$GW/v1/chat/completions" -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $t" --max-time 180 \
    -d "$(python3 -c 'import json,sys;print(json.dumps({"model":"conduit-assistant","stream":True,"messages":[{"role":"user","content":sys.argv[1]}]}))' "$2")" >/dev/null || true
  printf '  · %.60s\n' "$2"
}

echo "▶ driving prompts (flat, DAG, clarify)"
# flat — the load test's three
ask rm_jane "What's the latest NAV on fund FND-7781?"
ask rm_jane "Any upcoming corporate actions — dividends or splits — for REL-00042?"
ask rm_jane "What is Meridian's house view on equities this quarter?"
# DAG — multi-agent fan-in
ask rm_jane "Show me pending settlements and custody positions for REL-00042."
ask rm_jane "What is the concentration risk in the Whitman Family Office holdings?"
ask rm_jane "Give me a full overview of the Whitman relationship REL-00042."
# clarify path (ambiguous entity) and a denial path
ask rm_jane "Show me the holdings"
ask rm_jane "Show me the Okafor relationship holdings"

echo
echo "▶ validating fixtures in $FIXTURES"
python3 - "$FIXTURES" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
files = [p for p in root.rglob("*.json")]
if not files:
    sys.exit("FAIL: no fixtures recorded — did aimock proxy upstream? check `docker logs conduit-aimock`")

# 1. No credentials may land in a committed fixture.
KEY = re.compile(r"(sk-[A-Za-z0-9_\-]{20,}|Bearer\s+[A-Za-z0-9._\-]{20,}|api[_-]?key\"\s*:\s*\"[^\"]{8,})", re.I)
leaks = [str(p) for p in files if KEY.search(p.read_text())]
if leaks:
    sys.exit(f"FAIL: possible credential in fixture(s): {leaks}")

# 2. Reject POISONED fixtures. When the upstream call fails, aimock records the failure AS A FIXTURE
#    ({"error":{"type":"proxy_error"}}). That would replay an error forever and be misread as a
#    gateway bug. Observed: a wrong --provider URL produced /v1/v1/... -> 404 -> saved as a fixture.
poisoned = []
for p in files:
    try:
        doc = json.loads(p.read_text())
    except Exception:
        poisoned.append((p, "unparseable")); continue
    for fx in doc.get("fixtures", []):
        err = fx.get("response", {}).get("error")
        if err:
            poisoned.append((p, err.get("type") or err.get("message", "error")))
if poisoned:
    for p, why in poisoned:
        print(f"  POISONED: {p.name} -> {why}")
    sys.exit("FAIL: recorded error fixtures. Delete them, fix the upstream/provider URL, re-record.")

# 3. Shape sanity: at least one tool-call fixture (EntityExtractor) and one streamed answer.
blob = "\n".join(p.read_text() for p in files)
has_tools = ("toolCalls" in blob) or ("tool_calls" in blob)
has_content = '"content"' in blob

print(f"  files      : {len(files)}")
print("  no secrets : OK")
print("  no poison  : OK")
print(f"  tool_calls : {'OK' if has_tools else 'MISSING'}")
print(f"  content    : {'OK' if has_content else 'MISSING'}")
if not has_content:
    sys.exit("FAIL: no content fixture recorded")
if not has_tools:
    print("  WARN: no tool_calls fixture. EntityExtractor may be skipped (the classifier can return a "
          "pre-extracted bag), or your model does not support tools. If recording from ollama, use qwen3 "
          "— openchat returns no tool_calls.")
PY

echo
echo "▶ switching aimock back to strict replay"
unset AIMOCK_ARGS   # fall back to the compose default: --strict, replay-only
$COMPOSE up -d --force-recreate --no-deps aimock >/dev/null
sleep 5
echo "  aimock: $(docker inspect -f '{{.State.Status}}' conduit-aimock)"
echo "✅ done — fixtures recorded and validated. Load runs are now free and deterministic."
