#!/usr/bin/env bash
# seed-all.sh — THE single demo-data seeder for Conduit.
#
# Runs once, at the end, after the whole system is healthy, INSIDE the `seeder` container
# (conduit-seeder). It consolidates every demo-data seeding step that used to be spread
# across four separate compose services (seed-users, seeder, seed-datasets,
# seed-langfuse-dashboard) into ONE ordered, idempotent run. There is NO docker exec, NO
# host dependency, NO docker socket — every endpoint/key/cred comes from the environment and
# every hop is over the Docker network.
#
# Steps, IN ORDER (order matters — see notes inline):
#   a. Wait for health  — redis-stack, gateway, conduit-chat, iam-service, langfuse, langfuse-db
#   b. Principals       — Redis HSET           (scripts/seed-users.sh, host redis-stack)
#   c. Langfuse prices  — seed-langfuse-models.py   (MUST run before traffic — prices at ingestion)
#   d. BFF conversations— seed-conversations-via-bff.py  (real OIDC via iam-service → conduit-chat)
#   e. Langfuse datasets— /eval/langfuse_seed_datasets.py (conduit-routing + conduit-synthesis)
#   f. Langfuse dashboard— seed-data/langfuse-dashboard.sql via psql into langfuse-db
#
# Each step is idempotent and non-fatal on its own (a late-coming service must not abort the
# rest) — but every failure is reported loudly and rolled into the final summary + exit code.
#
# Env (defaults are the in-network compose values):
#   REDIS_HOST / REDIS_PORT                         Redis (principals)
#   GATEWAY_URL / CHAT_BFF_URL / IAM_URL            health + BFF conversations
#   LANGFUSE_URL                                    Langfuse app (prices, datasets, health)
#   LANGFUSE_PROJECT_PUBLIC_KEY / _SECRET_KEY       Langfuse project keys
#   LANGFUSE_DB_HOST/_PORT/_USER/_PASSWORD/_NAME    Langfuse Postgres (dashboard)
#   SEED_PASSWORD                                   shared demo password for the seed users

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# ── Config (env-only, in-network defaults) ────────────────────────────────────────
REDIS_HOST="${REDIS_HOST:-redis-stack}"
REDIS_PORT="${REDIS_PORT:-6379}"
GATEWAY_URL="${GATEWAY_URL:-http://gateway:8080}"
CHAT_BFF_URL="${CHAT_BFF_URL:-http://conduit-chat:8095}"
IAM_URL="${IAM_URL:-http://iam-service:8084}"
LANGFUSE_URL="${LANGFUSE_URL:-http://langfuse:3000}"
LF_PUB="${LANGFUSE_PROJECT_PUBLIC_KEY:-${CONDUIT_INSIGHTS_LANGFUSE_PUBLIC_KEY:-}}"
LF_SEC="${LANGFUSE_PROJECT_SECRET_KEY:-${CONDUIT_INSIGHTS_LANGFUSE_SECRET_KEY:-}}"
LANGFUSE_DB_HOST="${LANGFUSE_DB_HOST:-langfuse-db}"
LANGFUSE_DB_PORT="${LANGFUSE_DB_PORT:-5432}"
LANGFUSE_DB_USER="${LANGFUSE_DB_USER:-langfuse}"
LANGFUSE_DB_PASSWORD="${LANGFUSE_DB_PASSWORD:-langfuse}"
LANGFUSE_DB_NAME="${LANGFUSE_DB_NAME:-langfuse}"
SEED_PASSWORD="${SEED_PASSWORD:-Meridian@2024}"

DATASETS_PY="/eval/langfuse_seed_datasets.py"
[ -f "$DATASETS_PY" ] || DATASETS_PY="${HERE}/../eval/langfuse_seed_datasets.py"
PRICE_CONFIG="/registry/model-prices.json"
[ -f "$PRICE_CONFIG" ] || PRICE_CONFIG="${HERE}/../registry/model-prices.json"

# ── Step result tracking ──────────────────────────────────────────────────────────
declare -a STEP_NAMES STEP_STATUS
overall_rc=0

log()  { echo "[seed-all] $*"; }
hr()   { echo "──────────────────────────────────────────────────────────────────────"; }
step() {
  # step <name> <shell-command...>
  local name="$1"; shift
  hr
  log "STEP: ${name}"
  hr
  if "$@"; then
    STEP_NAMES+=("$name"); STEP_STATUS+=("OK")
    log "STEP OK: ${name}"
  else
    local rc=$?
    STEP_NAMES+=("$name"); STEP_STATUS+=("FAILED (rc=${rc})")
    overall_rc=1
    log "STEP FAILED: ${name} (rc=${rc}) — continuing with remaining steps"
  fi
  echo ""
}

# ── (a) Health waits — poll each service over the network ─────────────────────────
wait_http() {
  # wait_http <label> <url> <max_tries> [sleep_s]
  local label="$1" url="$2" tries="${3:-60}" sl="${4:-3}" i=0
  log "waiting for ${label} @ ${url} ..."
  while [ "$i" -lt "$tries" ]; do
    if curl -sf -o /dev/null "$url"; then log "${label} is ready."; return 0; fi
    i=$((i + 1)); sleep "$sl"
  done
  log "WARN: ${label} not ready after $((tries * sl))s — proceeding anyway."
  return 1
}

wait_redis() {
  local i=0
  log "waiting for redis-stack @ ${REDIS_HOST}:${REDIS_PORT} ..."
  while [ "$i" -lt 60 ]; do
    if redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING >/dev/null 2>&1; then
      log "redis-stack is ready."; return 0
    fi
    i=$((i + 1)); sleep 3
  done
  log "WARN: redis-stack not ready — proceeding anyway."; return 1
}

wait_pg() {
  local i=0
  log "waiting for langfuse-db @ ${LANGFUSE_DB_HOST}:${LANGFUSE_DB_PORT} ..."
  while [ "$i" -lt 60 ]; do
    if PGPASSWORD="$LANGFUSE_DB_PASSWORD" pg_isready \
         -h "$LANGFUSE_DB_HOST" -p "$LANGFUSE_DB_PORT" -U "$LANGFUSE_DB_USER" >/dev/null 2>&1; then
      log "langfuse-db is ready."; return 0
    fi
    i=$((i + 1)); sleep 3
  done
  log "WARN: langfuse-db not ready — proceeding anyway."; return 1
}

wait_all_health() {
  # Compose already gates us on service_healthy; this is belt-and-suspenders + a clear log.
  wait_redis
  wait_http "gateway"      "${GATEWAY_URL}/actuator/health" 60 3
  wait_http "conduit-chat" "${CHAT_BFF_URL}/health"         60 3
  wait_http "iam-service"  "${IAM_URL}/actuator/health"     80 3
  # Langfuse prices at INGESTION — it MUST be up before any prices/traffic. Wait harder.
  wait_http "langfuse"     "${LANGFUSE_URL}/api/public/health" 60 3
  wait_pg
  return 0   # health waits are advisory; never abort the run
}

# ── (b) Principals → Redis ────────────────────────────────────────────────────────
seed_principals() {
  REDIS_HOST="$REDIS_HOST" REDIS_PORT="$REDIS_PORT" bash "${HERE}/seed-users.sh"
}

# ── (c) Langfuse model prices (BEFORE traffic) ────────────────────────────────────
seed_prices() {
  if [ -z "$LF_PUB" ] || [ -z "$LF_SEC" ]; then
    log "WARN: no Langfuse keys — skipping price registration."; return 1
  fi
  python3 "${HERE}/seed-langfuse-models.py" \
    --langfuse-url "$LANGFUSE_URL" \
    --public-key "$LF_PUB" \
    --secret-key "$LF_SEC" \
    --config "$PRICE_CONFIG"
}

# ── (d) BFF conversations (real OIDC → gateway) ───────────────────────────────────
seed_conversations() {
  python3 "${HERE}/seed-conversations-via-bff.py" \
    --bff-url "$CHAT_BFF_URL" \
    --iam-url "$IAM_URL" \
    --password "$SEED_PASSWORD"
}

# ── (e) Langfuse datasets (conduit-routing, conduit-synthesis) ────────────────────
# Health=200 is not enough — Langfuse can accept the request before its project store is
# ready to persist items (it "seeds 0"). So seed AND verify the datasets are listed,
# retrying until both land (same logic as the retired seed-datasets.sh).
seed_datasets() {
  if [ ! -f "$DATASETS_PY" ]; then
    log "WARN: ${DATASETS_PY} not found (mount ./eval:/eval) — skipping datasets."; return 1
  fi
  export LANGFUSE_HOST="$LANGFUSE_URL"
  export LANGFUSE_PUBLIC_KEY="$LF_PUB"
  export LANGFUSE_SECRET_KEY="$LF_SEC"
  local i present
  for i in $(seq 1 60); do
    python3 "$DATASETS_PY" 2>&1 || true
    present=$(python3 - <<'PY'
import os, json, base64, urllib.request
try:
    k = base64.b64encode(
        (os.environ["LANGFUSE_PUBLIC_KEY"] + ":" + os.environ["LANGFUSE_SECRET_KEY"]).encode()
    ).decode()
    req = urllib.request.Request(os.environ["LANGFUSE_HOST"].rstrip("/") + "/api/public/datasets")
    req.add_header("Authorization", "Basic " + k)
    data = json.load(urllib.request.urlopen(req, timeout=5)).get("data", [])
    names = {d.get("name") for d in data}
    print(len({"conduit-routing", "conduit-synthesis"} & names))
except Exception:
    print(0)
PY
)
    if [ "${present:-0}" -ge 2 ]; then
      log "datasets present (${present}/2) — done"; return 0
    fi
    log "langfuse/project not ready yet (present=${present}) — retry ${i}/60 in 6s"
    sleep 6
  done
  log "WARN: datasets did not both land after 60 tries."; return 1
}

# ── (f) Langfuse dashboard (widgets + grid) via psql ──────────────────────────────
seed_dashboard() {
  LANGFUSE_DB_HOST="$LANGFUSE_DB_HOST" \
  LANGFUSE_DB_PORT="$LANGFUSE_DB_PORT" \
  LANGFUSE_DB_USER="$LANGFUSE_DB_USER" \
  LANGFUSE_DB_PASSWORD="$LANGFUSE_DB_PASSWORD" \
  LANGFUSE_DB_NAME="$LANGFUSE_DB_NAME" \
    sh "${HERE}/seed-langfuse-dashboard.sh"
}

# ── Run ───────────────────────────────────────────────────────────────────────────
hr
log "Conduit one-seeder starting — all demo data, one container, over the network."
log "  redis=${REDIS_HOST}:${REDIS_PORT}  gateway=${GATEWAY_URL}  bff=${CHAT_BFF_URL}"
log "  iam=${IAM_URL}  langfuse=${LANGFUSE_URL}  langfuse-db=${LANGFUSE_DB_HOST}:${LANGFUSE_DB_PORT}"
hr
echo ""

# (a) is advisory (never fails the run); (b)-(f) are tracked steps.
wait_all_health
echo ""

step "b. Principals → Redis"          seed_principals
step "c. Langfuse model prices"       seed_prices
step "d. BFF conversations"           seed_conversations
step "e. Langfuse datasets"           seed_datasets
step "f. Langfuse dashboard"          seed_dashboard

# ── Summary ─────────────────────────────────────────────────────────────────────
hr
log "SUMMARY — one-seeder finished"
hr
for idx in "${!STEP_NAMES[@]}"; do
  printf '[seed-all]   %-28s %s\n' "${STEP_NAMES[$idx]}" "${STEP_STATUS[$idx]}"
done
hr
if [ "$overall_rc" -eq 0 ]; then
  log "All steps OK."
else
  log "One or more steps FAILED — see the per-step logs above."
fi
exit "$overall_rc"
