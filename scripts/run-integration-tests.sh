#!/usr/bin/env bash
set -e

echo "=== Meridian Integration Tests ==="

echo ""
echo "1. Checking services are up..."

curl -sf http://localhost:8080/actuator/health \
  | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('status')=='UP' else 1)" \
  || { echo "ERROR: Gateway (http://localhost:8080) is not up or not healthy. Run: docker compose up -d"; exit 1; }
echo "   Gateway OK"

curl -sf http://localhost:8086/health \
  | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('status')=='ok' else 1)" \
  || { echo "ERROR: Coverage service (http://localhost:8086) is not up. Run: docker compose up -d"; exit 1; }
echo "   Coverage service OK"

echo ""
echo "2. Running pytest gateway integration tests..."
cd "$(dirname "$0")/../tests/integration"
pip install -r requirements.txt -q
python -m pytest test_gateway_coverage.py -v --tb=short 2>&1
PYTEST_EXIT=$?

echo ""
echo "3. Running Playwright E2E (coverage flow spec)..."
cd "$(dirname "$0")/../e2e"
npx playwright test tests/10-coverage-flow.spec.ts --reporter=list 2>&1
PLAYWRIGHT_EXIT=$?

echo ""
echo "=== Done ==="

if [ $PYTEST_EXIT -ne 0 ] || [ $PLAYWRIGHT_EXIT -ne 0 ]; then
  echo "One or more test suites FAILED (pytest=$PYTEST_EXIT, playwright=$PLAYWRIGHT_EXIT)"
  exit 1
fi

echo "All tests PASSED"
exit 0
