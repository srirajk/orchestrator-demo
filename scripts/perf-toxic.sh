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

# ── F3 authz/coverage legs: apply a toxic to a SET of proxies at once ─────────────
# The two request-path legs that are not agents (coverage + Cerbos) are disturbed together so a
# single profile reproduces the whole slow-dependency picture the OutboundGate must survive.
AUTHZ_PROXIES=${AUTHZ_PROXIES:-coverage coverage-insurance cerbos}
clear_proxy() {
  api "$ADMIN/proxies/$1" | python3 -c 'import sys,json;[print(t["name"]) for t in json.load(sys.stdin).get("toxics",[])]' \
    | while read -r t; do [ -n "$t" ] && api -X DELETE "$ADMIN/proxies/$1/toxics/$t" >/dev/null; done
  api -X POST "$ADMIN/proxies/$1" -d '{"enabled":true}' >/dev/null
}
add_authz() { for pr in $AUTHZ_PROXIES; do clear_proxy "$pr"; api -X POST "$ADMIN/proxies/$pr/toxics" -H 'Content-Type: application/json' -d "$1" >/dev/null; done; }
clear_authz() { for pr in $AUTHZ_PROXIES; do clear_proxy "$pr"; done; }

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
  # ── F3: the authz/coverage legs ─────────────────────────────────────────────────
  # slow-authz      latency 2000–8000ms on coverage + Cerbos. Proves the OutboundGate deadline +
  #                 fail-closed path: a slow-but-alive PDP (>read-timeout) fail-closes, a slow coverage
  #                 fails closed as CoverageUnavailable — WITHOUT parking carriers or draining permits.
  # slow-authz-body bandwidth 4 B/s on coverage + Cerbos. The trickle-body class: headers arrive, the
  #                 BODY streams under the socket read timeout forever — only the body-phase deadline
  #                 catches it. This is the leg a header-timeout-only fix would fail.
  slow-authz)      clear_authz; add_authz '{"name":"lat","type":"latency","stream":"downstream","attributes":{"latency":5000,"jitter":3000}}'
                   echo "slow-authz applied to: $AUTHZ_PROXIES"; exit 0 ;;
  slow-authz-body) clear_authz; add_authz '{"name":"bw","type":"bandwidth","stream":"downstream","attributes":{"rate":4}}'
                   echo "slow-authz-body applied to: $AUTHZ_PROXIES"; exit 0 ;;
  clear-authz)     clear_authz; echo "cleared authz toxics on: $AUTHZ_PROXIES"; exit 0 ;;
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
