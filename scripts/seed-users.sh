#!/usr/bin/env bash
# seed-users.sh — Idempotently seed demo principals into Redis.
#
# The gateway's PrincipalStore reads principal:{userId} Redis hashes.
# Key format:  principal:{userId}
# Storage:     Redis Hash (HSET)
# Fields:      id, name, email, roles (JSON), clearance (int), segments (JSON),
#              domains (JSON), adminDomains (JSON), team
#
# Usage:
#   REDIS_HOST=localhost REDIS_PORT=6379 ./scripts/seed-users.sh
#
# Env vars (defaults shown):
#   REDIS_HOST  — Redis hostname  (default: localhost)
#   REDIS_PORT  — Redis port      (default: 6379)

set -euo pipefail

REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

redis_cmd() {
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" "$@"
}

# Verify connectivity before seeding
if ! redis_cmd PING > /dev/null 2>&1; then
  echo "[seed-users] ERROR: Cannot reach Redis at $REDIS_HOST:$REDIS_PORT" >&2
  exit 1
fi

echo "[seed-users] Connected to Redis at $REDIS_HOST:$REDIS_PORT"

# ── Helper ────────────────────────────────────────────────────────────────────
# seed_principal <id> <name> <email> <roles_json> <clearance>
#                <segments_json> <domains_json> <admin_domains_json> <team>
#
# No book field — book-of-business is enforced by the domain coverage service at
# runtime, not stored in Redis. Uses HSET (idempotent).
seed_principal() {
  local id="$1"
  local name="$2"
  local email="$3"
  local roles="$4"
  local clearance="$5"
  local segments="$6"
  local domains="$7"
  local admin_domains="$8"
  local team="$9"
  local key="principal:${id}"

  redis_cmd HSET "$key" \
    id            "$id" \
    name          "$name" \
    email         "$email" \
    roles         "$roles" \
    clearance     "$clearance" \
    segments      "$segments" \
    domains       "$domains" \
    adminDomains  "$admin_domains" \
    team          "$team" \
    > /dev/null

  echo "[seed-users] Seeded principal: $id (roles=$roles segments=$segments)"
}

# ── Principals ────────────────────────────────────────────────────────────────

# rm_jane — relationship manager, wealth + servicing segments
seed_principal \
  "rm_jane" \
  "Jane Smith" \
  "jane.smith@meridianbank.com" \
  '["relationship_manager"]' \
  "2" \
  '["wealth","servicing"]' \
  '["wealth-private-banking"]' \
  '[]' \
  "wealth-private-banking"

# rm_carlos — relationship manager, wealth segment only
seed_principal \
  "rm_carlos" \
  "Carlos Mendez" \
  "carlos.mendez@meridianbank.com" \
  '["relationship_manager"]' \
  "2" \
  '["wealth"]' \
  '["wealth-private-banking"]' \
  '[]' \
  "wealth-private-banking"

# rm_guest — relationship manager, wealth segment, no domain membership
seed_principal \
  "rm_guest" \
  "Guest RM" \
  "guest.rm@meridianbank.com" \
  '["relationship_manager"]' \
  "2" \
  '["wealth"]' \
  '[]' \
  '[]' \
  ""

# uw_sam — underwriter, insurance segment (proves the second domain's entitlement path).
# Same relationship_manager role + the insurance segment gate added to agent_resource.yaml.
# Book (POL-77001/77002 his; POL-88003 is uw_dana's) enforced by insurance-coverage service.
seed_principal \
  "uw_sam" \
  "Sam Underwood" \
  "sam.underwood@meridianbank.com" \
  '["relationship_manager"]' \
  "2" \
  '["insurance"]' \
  '["insurance-underwriting"]' \
  '[]' \
  "insurance-underwriting"

echo "[seed-users] Done. 4 principals seeded."
echo ""
echo "  rm_jane   → segments: wealth+servicing | domains: wealth-private-banking"
echo "  rm_carlos → segments: wealth           | domains: wealth-private-banking"
echo "  rm_guest  → segments: wealth           | domains: (none)"
echo "  uw_sam    → segments: insurance        | domains: insurance-underwriting"
echo ""
echo "  Book-of-business enforced by coverage services at runtime."
echo "  REL-00188 (Okafor) is NOT in rm_jane coverage → denied by wealth-coverage."
echo "  POL-88003 (Zenith) is NOT in uw_sam coverage  → denied by insurance-coverage."

# ── Register Langfuse model prices (so Langfuse's OWN cost view is populated) ─────
# Langfuse prices at INGESTION time, so this MUST run before any traffic. Keys come from
# the running gateway container (holds CONDUIT_INSIGHTS_LANGFUSE_*). Non-fatal.
if command -v python3 >/dev/null 2>&1; then
  _LF_URL="${LANGFUSE_URL:-http://localhost:3030}"
  # Langfuse prices at INGESTION, so it must be UP before we register prices (and before
  # any traffic). On a cold boot Langfuse takes a while — wait for it, else the seed skips
  # and cost stays $0. Up to ~2 min, then proceed (non-fatal).
  echo ""
  echo "[seed-users] Waiting for Langfuse to be ready (prices must register before traffic)..."
  for _i in $(seq 1 40); do curl -sf "$_LF_URL/api/public/health" >/dev/null 2>&1 && break; sleep 3; done
  _LF_PUB="${LANGFUSE_PROJECT_PUBLIC_KEY:-$(docker exec conduit-gateway printenv CONDUIT_INSIGHTS_LANGFUSE_PUBLIC_KEY 2>/dev/null)}"
  _LF_SEC="${LANGFUSE_PROJECT_SECRET_KEY:-$(docker exec conduit-gateway printenv CONDUIT_INSIGHTS_LANGFUSE_SECRET_KEY 2>/dev/null)}"
  echo "[seed-users] Registering Langfuse model prices (fresh traffic will be costed)..."
  python3 "$(dirname "$0")/seed-langfuse-models.py" \
    --langfuse-url "$_LF_URL" \
    --public-key "$_LF_PUB" \
    --secret-key "$_LF_SEC" \
    || echo "[seed-users] Langfuse price seed skipped — non-fatal"
fi

# ── Seed real demo conversations through the Chat BFF ────────────────────────────
# Real OIDC login per user (no bearer bypass, no DB injection) → real Mongo conversations.
# Idempotent (skips users who already have conversations) and non-fatal if the chat
# stack isn't up yet, so it is safe to run every time the system comes up.
if command -v python3 >/dev/null 2>&1; then
  echo ""
  echo "[seed-users] Seeding demo conversations via the Chat BFF..."
  python3 "$(dirname "$0")/seed-conversations-via-bff.py" \
    --bff-url "${CHAT_BFF_URL:-http://localhost:8099}" \
    --iam-url "${IAM_URL:-http://localhost:8084}" \
    --password "${SEED_PASSWORD:-Meridian@2024}" \
    || echo "[seed-users] conversation seed skipped (chat stack not ready) — non-fatal"
fi
