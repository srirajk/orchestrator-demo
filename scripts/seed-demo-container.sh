#!/usr/bin/env bash
# seed-demo-container.sh — Seed the Conduit demo over the Docker network with NO host
# dependency (no docker exec, no docker socket, no host python). Runs INSIDE the dedicated
# `seeder` container (conduit-seeder). It does EXACTLY two things, in order:
#
#   1. Register Langfuse model prices  (seed-langfuse-models.py, reads /registry/model-prices.json)
#   2. Seed real demo conversations    (seed-conversations-via-bff.py, real OIDC login via the BFF)
#
# Order matters: Langfuse prices at INGESTION time, so prices MUST be registered BEFORE any
# traffic — otherwise the auto-seeded conversations land in Langfuse at cost 0.
#
# It does NOT seed Redis principals — that is the separate `seed-users` service, left untouched.
#
# Everything comes from env only (no hardcoded URLs/keys); each step is non-fatal (safe to
# re-run — both seeders are idempotent):
#   LANGFUSE_URL                    internal Langfuse URL   (e.g. http://langfuse:3000)
#   LANGFUSE_PROJECT_PUBLIC_KEY     Langfuse public key
#   LANGFUSE_PROJECT_SECRET_KEY     Langfuse secret key
#   CHAT_BFF_URL                    internal Chat BFF URL   (e.g. http://conduit-chat:8095)
#   IAM_URL                         internal IAM/Axiom URL  (e.g. http://iam-service:8084)
#   SEED_PASSWORD                   shared demo password for the seed users

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

LANGFUSE_URL="${LANGFUSE_URL:-http://langfuse:3000}"
LF_PUB="${LANGFUSE_PROJECT_PUBLIC_KEY:-${CONDUIT_INSIGHTS_LANGFUSE_PUBLIC_KEY:-}}"
LF_SEC="${LANGFUSE_PROJECT_SECRET_KEY:-${CONDUIT_INSIGHTS_LANGFUSE_SECRET_KEY:-}}"
CHAT_BFF_URL="${CHAT_BFF_URL:-http://conduit-chat:8095}"
IAM_URL="${IAM_URL:-http://iam-service:8084}"
SEED_PASSWORD="${SEED_PASSWORD:-Meridian@2024}"

# ── Step 1: Register Langfuse model prices (BEFORE any traffic) ───────────────────
# Langfuse prices at INGESTION, so it must be UP before we register prices (and before any
# traffic). On a cold boot Langfuse takes a while — wait for its health endpoint (up to
# ~2 min), then proceed regardless (non-fatal).
echo "[seed-demo] Waiting for Langfuse to be ready (prices must register before traffic)..."
for _i in $(seq 1 40); do
  curl -sf "${LANGFUSE_URL}/api/public/health" >/dev/null 2>&1 && { echo "[seed-demo] Langfuse is ready."; break; }
  sleep 3
done

echo "[seed-demo] Registering Langfuse model prices (fresh traffic will be costed)..."
# Prefer the mounted registry copy (/registry/model-prices.json) so the config is found
# regardless of where this script lives in the container.
PRICE_CONFIG="/registry/model-prices.json"
[ -f "$PRICE_CONFIG" ] || PRICE_CONFIG="${HERE}/../registry/model-prices.json"
python3 "${HERE}/seed-langfuse-models.py" \
  --langfuse-url "$LANGFUSE_URL" \
  --public-key "$LF_PUB" \
  --secret-key "$LF_SEC" \
  --config "$PRICE_CONFIG" \
  || echo "[seed-demo] Langfuse price seed skipped — non-fatal"

# ── Step 2: Seed real demo conversations through the Chat BFF ─────────────────────
# Real OIDC login per user (no bearer bypass, no DB injection) → real Mongo conversations.
# Uses the INTERNAL service URLs from env, never localhost. Idempotent (skips users who
# already have conversations) and non-fatal if the chat stack isn't fully up yet.
echo ""
echo "[seed-demo] Seeding demo conversations via the Chat BFF..."
python3 "${HERE}/seed-conversations-via-bff.py" \
  --bff-url "$CHAT_BFF_URL" \
  --iam-url "$IAM_URL" \
  --password "$SEED_PASSWORD" \
  || echo "[seed-demo] conversation seed skipped (chat stack not ready) — non-fatal"

echo ""
echo "[seed-demo] Done."
