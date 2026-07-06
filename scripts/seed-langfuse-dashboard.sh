#!/usr/bin/env sh
# seed-langfuse-dashboard.sh — Idempotently seed the "Conduit — LLM Quality & Cost"
# Langfuse dashboard (6 widgets) by applying scripts/seed-data/langfuse-dashboard.sql to
# the Langfuse Postgres database.
#
# The Langfuse public API creates widgets but cannot place them on a dashboard grid, so the
# widgets + dashboard row (with its grid layout) are written straight into Postgres. The SQL
# is self-contained and idempotent, so this is safe to re-run and survives `down -v` → up.
#
# Connection comes from env only (no host dependency), matching the other seeders:
#   LANGFUSE_DB_HOST      Postgres host      (default: langfuse-db  — the compose service)
#   LANGFUSE_DB_PORT      Postgres port      (default: 5432)
#   LANGFUSE_DB_USER      Postgres user      (default: langfuse)
#   LANGFUSE_DB_PASSWORD  Postgres password  (default: langfuse)
#   LANGFUSE_DB_NAME      Postgres database  (default: langfuse)
#
# Two ways to reach psql, tried in order:
#   1. a `psql` on PATH (used inside the in-network one-shot compose service), or
#   2. `docker exec conduit-langfuse-db psql` (host/dev convenience — set DB host localhost-less).

set -u

HERE="$(cd "$(dirname "$0")" && pwd)"
SQL_FILE="${HERE}/seed-data/langfuse-dashboard.sql"

DB_HOST="${LANGFUSE_DB_HOST:-langfuse-db}"
DB_PORT="${LANGFUSE_DB_PORT:-5432}"
DB_USER="${LANGFUSE_DB_USER:-langfuse}"
DB_PASSWORD="${LANGFUSE_DB_PASSWORD:-langfuse}"
DB_NAME="${LANGFUSE_DB_NAME:-langfuse}"

if [ ! -f "$SQL_FILE" ]; then
  echo "[seed-dashboard] ERROR: SQL not found at $SQL_FILE" >&2
  exit 1
fi

run_with_psql() {
  # Wait for Postgres to accept connections (cold boot can be slow).
  i=0
  while [ "$i" -lt 40 ]; do
    if PGPASSWORD="$DB_PASSWORD" pg_isready -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" >/dev/null 2>&1; then
      break
    fi
    i=$((i + 1)); sleep 2
  done
  PGPASSWORD="$DB_PASSWORD" psql -v ON_ERROR_STOP=1 \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -f "$SQL_FILE"
}

run_with_docker() {
  # Host/dev path: pipe the SQL into psql inside the langfuse-db container.
  docker exec -i conduit-langfuse-db \
    env PGPASSWORD="$DB_PASSWORD" psql -v ON_ERROR_STOP=1 \
    -U "$DB_USER" -d "$DB_NAME" < "$SQL_FILE"
}

if command -v psql >/dev/null 2>&1; then
  echo "[seed-dashboard] Applying dashboard via psql -> ${DB_HOST}:${DB_PORT}/${DB_NAME}"
  run_with_psql
elif command -v docker >/dev/null 2>&1; then
  echo "[seed-dashboard] psql not found; applying via docker exec conduit-langfuse-db"
  run_with_docker
else
  echo "[seed-dashboard] ERROR: neither psql nor docker available to reach Langfuse Postgres" >&2
  exit 1
fi

echo "[seed-dashboard] Done."
