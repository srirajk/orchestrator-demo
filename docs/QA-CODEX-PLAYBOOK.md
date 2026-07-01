# Conduit — QA Playbook (for Codex / automated browser QA)

> A full end-to-end acceptance suite an agent with **computer/browser control** (e.g. Codex) can
> execute like a QA tester. Every test: **action → expected → PASS/FAIL**. Codex *can type
> credentials*, so the SSO + UI flows are fully automatable.
>
> **Definition of done:** all tests PASS, and the **clean-slate rebuild** (§7) restores the whole
> product from default templates/seeds.

---

## 0. Preflight

### 0.1 URLs & logins
| Surface | URL | Login |
|---|---|---|
| Chat (LibreChat) | http://localhost:3080 | `rm_jane` / `Meridian@2024` — or **"Login with Meridian SSO"** |
| Identity (Axiom) | http://localhost:8084 | OIDC issuer; login form used inside SSO |
| Glass-Box | http://localhost:4000 | none |
| Langfuse | http://localhost:3030 | `admin@meridian.bank` / `changeme` |
| Grafana | http://localhost:3000 | `admin` / `changeme` |
| Gateway API | http://localhost:8080/v1 | `X-User-Id` header or JWT |

### 0.2 Demo identities (books of business)
| User | Password | Covers | Not covered |
|---|---|---|---|
| `rm_jane` | `Meridian@2024` | Whitman (`REL-00042`), Calderon (`REL-00099`) | Okafor (`REL-00188`) |
| `rm_carlos` | `Meridian@2024` | Okafor, Sterling | Whitman |
| `uw_sam` | `Meridian@2024` | insurance (Nakamura) | wealth |
| `rm_guest` | `Meridian@2024` | none | everything |

### 0.3 Host prereqs (fail early if missing)
- `/etc/hosts` contains `127.0.0.1 host.docker.internal` (required for browser SSO).
- `.env` has a real judge/LLM key (`CONDUIT_LLM_SYNTHESIZER_API_KEY` and `ZAI_API_KEY`).

### 0.4 Stack health gate — must all pass before UI tests
```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/v1/models      # 200
curl -s -o /dev/null -w "%{http_code}" http://localhost:3080/oauth/openid   # 302
curl -s -o /dev/null -w "%{http_code}" http://localhost:3000/api/health     # 200
curl -s -o /dev/null -w "%{http_code}" http://localhost:3030/api/public/health  # 200
docker compose ps            # all core containers Up/healthy; eval profile up for §6
```
**PASS:** all codes as noted; no container in a non-`Up` state.

---

## 1. SSO login (browser)
1. Navigate to `http://localhost:3080` → click **"Login with Meridian SSO"**.
2. **Expected:** redirect to the **Axiom** login page (`http://host.docker.internal:8084/login`),
   branded "AXIOM / Meridian Private Banking", over plain HTTP, **no TLS error**.
3. Enter `rm_jane` / `Meridian@2024` → submit.
4. **Expected:** land back in LibreChat, **logged in as Jane Kowalski**.
- **PASS:** you reach the chat UI authenticated. **FAIL:** any TLS error, `auth_failed`, or bounce
  back to login.
- *(Alt path to also verify:* local login `rm_jane`/`Meridian@2024` on the LibreChat form works too.)*

---

## 2. World B chat scenarios (the core)
Send each in the LibreChat chat; assert the described behavior. (Every number in an answer must
trace to a source system — none invented.)

### 2.1 Hero — cross-protocol fan-out + grounded answer
- **Send:** *"Give me a complete overview of the Whitman relationship: holdings, performance,
  settlement status, and cash position."*
- **Expected:** one streamed answer citing the **Whitman** relationship (`REL-00042`) with **real**
  holdings, a portfolio value, settlement status, and cash — pulled from the agents, nothing invented.
- **PASS:** grounded answer covering all four asks; **FAIL:** a hallucinated number, or "I can't".

### 2.2 Follow-up — session memory
- **Send (same conversation):** *"which holding is largest, and how much cash is unsettled?"*
- **Expected:** answers from prior context **without restating the client**.
- **PASS:** correct follow-up answer that clearly reused the Whitman context.

### 2.3 Entitlement denial — pruned before fetch
- **Send (as `rm_jane`):** *"Show me the Okafor relationship holdings."*
- **Expected:** a **denial** ("not in your coverage" style) — Okafor (`REL-00188`) is not in rm_jane's
  book, and it's denied **before** any agent/data call.
- **PASS:** denial message, no holdings shown. **FAIL:** any Okafor data returned.

### 2.4 Clarify — deterministic, not a guess
- **Send:** *"What's the latest on my client?"*
- **Expected:** a **scoped clarifying question** (which client / by id or name), not a hallucinated answer.
- **PASS:** a clarify prompt. **FAIL:** it guesses a client.

### 2.5 Partial-result tolerance — honest degradation
1. Stop an agent: `docker compose stop conduit-wealth-http`.
2. **Send:** the §2.1 hero question again.
3. **Expected:** an answer from the **surviving** agents that **explicitly states what's missing**
   (the wealth/holdings part), not a silent wrong answer or a total failure.
4. Restore: `docker compose start conduit-wealth-http`.
- **PASS:** partial answer that names the gap. **FAIL:** total failure, or a "complete" answer that
  hides the missing data.

---

## 3. Glass-Box (live decision trace)
1. Open `http://localhost:4000` beside the chat.
2. **Expected:** top-right shows **"Connected"**.
3. Send any §2 prompt and watch.
4. **Expected:** the **six stages** light up live — Request Received → Intent Classification →
   Agent Routing (selected **and** rejected) → Entitlement (ALLOWED/DENIED + entity) → Agent Fan-out
   (each agent, HTTP or MCP, latency, ✓/✗) → Answer Synthesis — ending in a summary (total latency,
   *N/M agents succeeded*).
- **PASS:** all six render for a request; denial run (§2.3) shows the Entitlement stage DENIED.

---

## 4. Grafana — validate all 7 dashboards
Login `admin`/`changeme`. Open each (left nav → Dashboards), set range **Last 15 minutes**, refresh.
Generate a little chat traffic first so rate panels populate.

| Dashboard (uid) | Key panels must render data |
|---|---|
| **Conduit — Live Demo View** (`conduit-demo`) | Requests/sec, Avg/Max E2E latency, Active Streams, Intents-by-type, Circuit-breaker calls |
| **Conduit — Agent Health** (`conduit-agent-health`) | per-agent calls/min, error %, p95 latency (select an agent in the variable) |
| **Conduit — Business Overview** (`conduit-business`) | intents by type, answer success rate, agent status |
| **Conduit Gateway** (`conduit-gateway-perf`) | Chat requests/sec, E2E latency, Intents (last 5m), JVM |
| **Conduit — Conversation Trace Explorer** (`conduit-trace`) | set `convId` to a `conv-…` id (from a trace); logs (Loki) + spans (Tempo) + intent panel |
| **Conduit Gateway — Performance** (`conduit-perf`) | request rate, **p50/p95/p99 latency**, intent distribution by type |
| **Conduit — Resource Usage** (`conduit-resources`) | JVM heap/CPU, container CPU/memory |
- **PASS:** each dashboard's listed panels show data (not "No data"). Note: some panels are
  in-flight gauges (0 at rest) or need failure traffic (error series) — those may legitimately read 0.
- **Note for `convId`:** it's the **gateway-derived** `conv-…` id (from a Langfuse trace's sessionId),
  **not** the LibreChat URL UUID.

---

## 5. Langfuse — traces, tags, sessions, scores, datasets
Login `admin@meridian.bank`/`changeme`.

### 5.1 Traces & tags
1. Tracing → **Sessions**: a conversation appears as one session (`conv-…`); each turn is a `chat-turn` trace under it.
2. Open a `chat-turn` from a **domain** question (§2.1).
- **Expected:** trace has **input** and **output** (the answer), child spans per agent (HTTP + MCP),
  and **tags** `domain:*` + `agent:*` (e.g. `domain:wealth-management`, `agent:acme.wealth.holdings`).
- **PASS:** input+output present, agent spans present, domain/agent tags present. *(Chitchat/greeting
  traces legitimately have no tags.)*

### 5.2 Scores (continuous eval)
1. On a scored `chat-turn`, check the Scores section (or Tracing → Scores).
- **Expected:** **grounding / partial_honesty / relevance / safety** with real values (0–1), posted by
  the continuous worker (`conduit-eval-continuous`).
- **PASS:** the four scores present with varied (non-constant-0.5) values.
- *(If absent: confirm the `eval` profile is up — see §7.5.)*

### 5.3 Datasets & experiments
1. **Datasets:** `conduit-routing` (35 items) and `conduit-synthesis` (5 items) exist.
2. *(Optional)* run an experiment: `docker exec conduit-eval-continuous python3 /app/langfuse_run_experiment.py --run-name qa-check` → a run appears under the dataset.
- **PASS:** both datasets present; (optional) an experiment run visible.

---

## 6. API smoke (no browser) — quick regression
```bash
# models
curl -s http://localhost:8080/v1/models | grep -q conduit-assistant           # PASS if found
# a grounded chat (non-stream)
curl -s -X POST http://localhost:8080/v1/chat/completions -H 'Content-Type: application/json' \
  -H 'X-User-Id: rm_jane' -d '{"model":"conduit-assistant","stream":false,
  "messages":[{"role":"user","content":"cash position for the Whitman relationship"}]}' \
  -o /dev/null -w "%{http_code}\n"                                             # 200
# world-b gate
bash scripts/world-b-check.sh | grep "CRITICAL violations : 0"                 # PASS
```

---

## 7. Clean-slate rebuild — the reproducibility test (the big one)
**Goal:** tearing the volumes + env down and bringing it back yields a working product from
**default templates/seeds** — no hand-editing.

> **Current reality (documented honestly):** a plain `docker compose up` self-restores Grafana
> dashboards/datasources, the Langfuse project+keys, the admin DB user, and the Redis routing index
> (rebuilt from manifests on gateway boot). **Three steps are still manual** — `seed-users`, the
> `--profile eval` workers, and dataset seeding. The **boot provisioner** (TODO) will fold these in;
> until then they're explicit steps below.

### 7.1 Tear down (destroys volumes)
```bash
docker compose --profile eval down -v --remove-orphans
```
**Expected:** all conduit volumes removed; a non-conduit project (if any) untouched.

### 7.2 Build (both Java services are source-built)
```bash
docker compose build gateway iam-service
```
**Expected:** both images build with **no errors** (no pre-`mvn` step needed).

### 7.3 Bring up core
```bash
docker compose up -d
```
**Expected:** all core containers become healthy (gateway, iam-service, librechat, redis, cerbos,
grafana, langfuse, prometheus, loki, tempo, otel, the agents + coverage).

### 7.4 Verify auto-provisioning (should need NO manual step)
- Grafana: `http://localhost:3000` → all 7 dashboards present + the Prometheus datasource (provisioned).
- Langfuse: project `meridian-gateway` + keys exist (self-seeded via `LANGFUSE_INIT_*`).
- Redis routing index rebuilt: `docker exec conduit-redis redis-cli FT._LIST` → shows `intent_idx`.
- SSO works: repeat §1.

### 7.5 The three currently-manual steps
```bash
bash scripts/seed-users.sh                                   # rm_jane / rm_carlos / uw_sam / rm_guest
docker compose --profile eval up -d                          # continuous scorer + gate worker
docker exec conduit-eval-continuous python3 /app/langfuse_seed_datasets.py   # conduit-routing + conduit-synthesis
```

### 7.6 Re-verify the whole product
- Re-run §1 (SSO) → §2 (a hero + a denial) → §4 (dashboards have data) → §5 (traces + tags + scores + datasets).
- **PASS (clean-slate):** everything returns and behaves as before **with only the §7.2/§7.3 commands +
  the three §7.5 steps** — no config hand-editing, no manual dashboard/import, no manual Langfuse setup.
- **Provisioner acceptance (future):** once the boot provisioner lands, §7.5 collapses into `up`, and
  §7.4+§7.6 pass after **`docker compose up` alone**.

---

## Overall pass criteria
- §1–§6 all PASS on the running stack.
- §7 clean-slate PASS: product fully restored from templates/seeds via the documented commands.
- No hallucinated numbers in any §2 answer; denial denies **before** fetch; clarify never guesses;
  partial-result states what's missing.

## Notes for the executor (Codex)
- You **can** type credentials into login forms (Axiom, Grafana, Langfuse) — that's expected here.
- Take a screenshot at each major PASS/FAIL for the report.
- If a UI step fails 2–3× (element not found, page not loading), capture the state and report — don't loop.
- The reference behaviors + exact prompts are in `README.md` and `docs/OPERATOR-RUNBOOK.md`.
