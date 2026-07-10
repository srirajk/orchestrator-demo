#!/usr/bin/env bash
# Toggle Toxiproxy toxics on the `llm` proxy — the latency knob and the only true hang.
#
#   [PROXY=llm|embeddings] scripts/perf-toxic.sh <profile>
#
#   gateway-only   no toxics. Total latency ≈ the gateway's own cost.
#   realistic      latency 1800ms ± 400  (the measured p50 of a real request)
#   slow           latency 4000ms ± 800  (the measured p95)
#   hang           timeout{timeout:0} — connection accepted, NEVER answered.
#                  Verified: the client hangs until its own deadline. This is the ONLY way to prove
#                  the untimed read in EntityExtractor / EntityResolver / RemoteEmbeddingClient,
#                  which all use SimpleClientHttpRequestFactory (read timeout default -1 = infinite).
#   slow-body      bandwidth 4 KB/s — separates a read timeout from a connect timeout.
#   truncate       limit_data 512 bytes — cuts the response mid-stream.
#   reset          reset_peer — RST mid-connection.
#   down           disable the proxy entirely.
#   clear          remove all toxics, re-enable.
#   show           list current toxics.
#
# NOTE: Toxiproxy latency is FIXED + JITTER, not a distribution. Sweep discrete points; never
# report a constant-latency run as though it were a distribution — that understates queueing.
set -euo pipefail

ADMIN=${TOXIPROXY_ADMIN:-http://localhost:8474}
# Which upstream to disturb: llm (aimock) or embeddings (the sentence-transformers sidecar).
P=${PROXY:-llm}

api() { curl -sS "$@"; }
clear_toxics() {
  api "$ADMIN/proxies/$P" | python3 -c 'import sys,json;[print(t["name"]) for t in json.load(sys.stdin).get("toxics",[])]' \
    | while read -r t; do [ -n "$t" ] && api -X DELETE "$ADMIN/proxies/$P/toxics/$t" >/dev/null; done
  api -X POST "$ADMIN/proxies/$P" -d '{"enabled":true}' >/dev/null
}
add() { api -X POST "$ADMIN/proxies/$P/toxics" -H 'Content-Type: application/json' -d "$1" >/dev/null; }

case "${1:-show}" in
  gateway-only|clear) clear_toxics ;;
  realistic) clear_toxics; add '{"name":"lat","type":"latency","stream":"downstream","attributes":{"latency":1800,"jitter":400}}' ;;
  slow)      clear_toxics; add '{"name":"lat","type":"latency","stream":"downstream","attributes":{"latency":4000,"jitter":800}}' ;;
  # stream=upstream on purpose. A TRUE hang means the request never reaches the server, so no
  # server ever holds connection state that can time out. With stream=downstream the request
  # DOES arrive: uvicorn answers, toxiproxy holds the bytes, and uvicorn then closes the idle
  # keep-alive connection after its default timeout_keep_alive=5s -> the client sees EOF at 5s,
  # which looks like a working read timeout but is the SERVER hanging up. Verified both ways.
  # NOTE: toxics apply to NEW connections. A pooled keep-alive socket opened before the toxic
  # dies with "Unexpected end of file" on first use — drive a second request for the real test.
  hang)      clear_toxics; add '{"name":"hang","type":"timeout","stream":"upstream","attributes":{"timeout":0}}' ;;
  slow-body) clear_toxics; add '{"name":"bw","type":"bandwidth","stream":"downstream","attributes":{"rate":4}}' ;;
  truncate)  clear_toxics; add '{"name":"cut","type":"limit_data","stream":"downstream","attributes":{"bytes":512}}' ;;
  reset)     clear_toxics; add '{"name":"rst","type":"reset_peer","stream":"downstream","attributes":{"timeout":0}}' ;;
  down)      api -X POST "$ADMIN/proxies/$P" -d '{"enabled":false}' >/dev/null ;;
  show)      : ;;
  *) echo "unknown profile: $1" >&2; exit 2 ;;
esac

echo "profile: ${1:-show}"
api "$ADMIN/proxies/$P" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("  proxy {}: {} -> {}  enabled={}".format(d["name"], d["listen"], d["upstream"], d["enabled"]))
tox = d.get("toxics", [])
print("  toxics:", "none" if not tox else "")
for t in tox:
    print("    {:10s} {:12s} {:10s} {}".format(t["name"], t["type"], t["stream"], t["attributes"]))'
