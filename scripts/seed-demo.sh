#!/usr/bin/env bash
# Meridian Gateway — Phoenix/Tempo demo seed
# Waits for the full stack to be healthy, then fires representative requests
# so Phoenix and Tempo open pre-populated with real LLM traces.
#
# Usage:
#   ./scripts/seed-demo.sh            # from repo root
#   docker compose up -d && ./scripts/seed-demo.sh

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "Meridian Gateway — demo seed"
echo "Waiting for stack to be healthy …"
"${SCRIPT_DIR}/wait-for-healthy.sh"

echo ""
exec python3 "${SCRIPT_DIR}/seed-demo.py" "$@"
