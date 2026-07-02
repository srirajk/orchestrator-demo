# Conduit — Test Index

The master map of every test in the repo: what it checks and where to look. Cross-cutting
suites live under this `tests/` umbrella; fast unit tests stay co-located with the code they
cover (standard practice — see [Unit tests](#unit-tests-co-located)).

```
tests/
├── e2e/          Playwright — drives the real Conduit-branded LibreChat UI + gateway API
├── load/         k6 — concurrency / latency / scenario load tests
└── integration/  pytest — live gateway coverage-flow integration tests
```

---

## E2E suite — `tests/e2e/`

Playwright (Chromium, headless) driving the **running** Conduit-branded LibreChat at
`baseURL http://localhost:3080` plus direct gateway/IAM/Cerbos API assertions. Specs run
**sequentially** (`workers: 1`) because they share LibreChat conversation state.

**The full stack must be up first** (`docker compose up -d`, wait healthy) — there is no
Playwright `webServer` block; the stack is started externally.

### How to run
```bash
cd tests/e2e
npm install            # first time only (installs @playwright/test)
npm run e2e            # headless run of all specs
npm run e2e:ui         # interactive Playwright UI mode
npm run e2e:headed     # headed (watch the browser)
npm run e2e:report     # open the last HTML report
```
Override the UI target with `LIBRECHAT_URL`. Config: `tests/e2e/playwright.config.ts`
(`testDir: ./tests`, 10-minute per-test timeout for LLM synthesis).

### Specs — `tests/e2e/tests/*.spec.ts`

| Spec | What it verifies |
|---|---|
| `00-login.spec.ts` | Login/registration: login page renders, bad creds error, register/login lands on chat, session persists across reload, new-conversation button, input reflects typing. |
| `01-branding.spec.ts` | Conduit rebrand: root page loads, chat placeholder text, model selector hidden (`modelSelect: false`). |
| `02-hero-prompt.spec.ts` | Hero prompt streams an answer through the UI with grounded wealth-agent facts; `/v1/models` returns the model; SSE is well-formed and ends with `[DONE]`. |
| `03-jwt-identity.spec.ts` | JWT identity (M15): IAM health UP, JWKS returns RSA key, `/auth/token` issues RS256 JWT for `rm_jane` with correct book; gateway accepts valid JWT, rejects tampered/expired with 401, allows no-token trusted hop; book edits reflect in new JWTs. |
| `04-entitlements.spec.ts` | Entitlements (M8): Okafor query denied for `rm_jane` (intercepted SSE), Whitman (REL-00042) succeeds; book containing REL-00188 allows Okafor (200), absent book leaks no Okafor data. |
| `05-resilience.spec.ts` | Resilience (M11): gateway still answers with the servicing MCP fault active, acknowledges the missing data, UI still streams the degraded answer. |
| `06-glassbox.spec.ts` | Glass-box (M9): page is accessible; `/trace/stream` emits SSE trace events after a prompt. |
| `07-multi-turn.spec.ts` | Multi-turn context: second turn retains client context, 3-turn data→follow-up→comparison, new conversation resets context, same/different `X-Conversation-Id` shares/isolates context, FETCH_DATA→FOLLOW_UP intent path. |
| `08-domain-authz.spec.ts` | Domain-scoped ABAC (Phase 11): `rm_jane` gets wealth answers and is denied asset-servicing; `rm_diaz` gets servicing; platform/domain admins scoped correctly; Cerbos PDP allow/deny direct checks; JWT `segments` claims. |
| `09-cerbos-authz.spec.ts` | Cerbos PDP authz matrix: agent-resource (read-only gate, clearance tiers, segment match), relationship-resource structural role gate, IAM RBAC (auditor/policy author/approver/admin separation of duties), and IAM service API enforced end-to-end. |
| `10-coverage-flow.spec.ts` | Coverage flow (Phase 11): vague prompt triggers clarification with in-book member options, named in-book Whitman returns grounded answer, Okafor denied in UI + API, session reuse skips re-clarification. |
| `admin-ui.spec.ts` | Admin UI smoke: login page, admin login, dashboard stat cards, users/teams/roles/policies pages load. |
| `helpers.ts` | Shared helpers (not a spec). |

---

## Load suite — `tests/load/`

k6 scripts measuring throughput, TTFT, error rate, and per-scenario behaviour. Default
target `GATEWAY_URL=http://localhost:8080` (override via env). The gateway must be up.

### How to run
Locally (k6 installed):
```bash
GATEWAY_URL=http://localhost:8080 k6 run tests/load/smoke-test.js
```
Via the bundled k6 container (mounts `./tests/load` → `/scripts`, `scale` profile):
```bash
docker compose --profile scale run k6 run /scripts/load-test.js
```

| Script | Profile | Purpose / when to use |
|---|---|---|
| `smoke-test.js` | 3 VUs, 30s | Fast CI gate. Verifies gateway is up and serving SSE before a heavier run; pre-flights `/v1/models` + `/actuator/health`. Thresholds: <1% errors, p95 < 25s. |
| `load-test-light.js` | ramp 1→10 VUs, ~2 min | Lightweight demo/CI load. Measures TTFT p95 (<8s) and stream time (<30s), error rate <10%. Use for a quick latency read. |
| `load-test.js` | 4 phases, up to 50 VUs | Full phased load test (smoke → ramp 10 → hold 25 → stress 50) matching build milestones; finds steady-state throughput and verifies error rate under stress. This is the default k6 compose command. |
| `scenario-test.js` | 6 grouped scenarios, burst 20 VUs | Behaviour under concurrent load: hero fan-out (HTTP+MCP), Cerbos allow/deny, partial-failure resilience, routing correctness, FOLLOW_UP skip path, stress p99. Also needs `USER_MGMT_URL` (default `:8084`). Use to validate correctness-under-load, not just latency. |

---

## Integration — `tests/integration/`

Python (pytest) live integration test of the gateway **coverage flow** against a running
stack (gateway `:8080`, IAM `:8084`, wealth-coverage `:8086`).

### How to run
```bash
cd tests/integration
pip install -r requirements.txt
pytest test_gateway_coverage.py -v --tb=short
```

| File | Purpose |
|---|---|
| `test_gateway_coverage.py` | End-to-end coverage flow over real HTTP/SSE: clarification, in-book allow, out-of-book deny, session reuse. |

---

## Unit tests (co-located)

Fast unit/integration tests live next to the code they cover — intentional and standard;
they run in each module's own build, not from this umbrella.

### Gateway — `gateway/src/test/` (JUnit 5 + Testcontainers)
```bash
cd gateway && mvn test
```
| Test | Covers |
|---|---|
| `GatewayApplicationTests.java` | Spring context boots. |
| `domain/auth/AuthzFromMembershipTest.java` | Entitlements derived from the domain/member model. |
| `domain/auth/RoleAuthorizationTest.java` | Role-based authorization rules. |
| `domain/auth/RevocationCheckerTest.java` | JWT/credential revocation checks. |
| `domain/auth/SecurityRejectionIT.java` | Security rejection paths (tampered/expired/invalid tokens). |
| `domain/manifest/EffectiveManifestTest.java` | Effective-manifest resolution from registry. |
| `domain/manifest/EffectiveManifestMergeTest.java` | Manifest merge/override semantics. |
| `synthesis/input/EntityBagTest.java` | Map-based entity context (input synthesis). |
| `orchestration/harness/AgentHarnessResilienceIT.java` | Harness resilience — breaker trips, failed node yields partial result not a thrown request. |

### Mock agents — `mock-agents/tests/` and per-agent `*/tests/` (pytest)
```bash
cd mock-agents && pytest
```
| Test | Covers |
|---|---|
| `tests/test_wealth.py` | Wealth FastAPI agents — schema-valid canned data + fault knobs. |
| `tests/test_servicing.py` | Asset-servicing MCP tools. |
| `tests/test_insurance.py` | Insurance agent. |
| `tests/test_agent_integration.py` | Cross-agent integration. |
| `tests/test_concurrent_multiturn.py` | Concurrent multi-turn behaviour. |
| `tests/test_live.py` | Live smoke against running agents. |
| `wealth-coverage/tests/test_coverage.py` | Wealth coverage service. |
| `insurance-coverage/tests/test_coverage.py` | Insurance coverage service. |
