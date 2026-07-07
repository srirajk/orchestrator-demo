#!/usr/bin/env bash
# seed-users.sh — Idempotently seed demo principals into Redis. Principals ONLY.
#
# This is the Redis-principal step. It is invoked (over the Docker network, host
# redis-stack) as step (b) of the single consolidated seeder scripts/seed-all.sh, and is
# also runnable standalone on the host (`REDIS_HOST=localhost bash scripts/seed-users.sh`)
# to reseed identities after a Redis wipe. The Langfuse prices / BFF conversations /
# Langfuse dashboard seeding that used to trail this script now live in seed-all.sh so
# they are defined in exactly one place.
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

# NOTE: Langfuse model prices, BFF demo conversations, Langfuse datasets, and the Langfuse
# dashboard are seeded by the single consolidated seeder scripts/seed-all.sh (this script is
# its principal step). They are intentionally NOT duplicated here.
