# Codex task spec — Asset-Servicing `settlement_risk` analytics vertical

> Self-contained build spec. Hand this to Codex as the task. It lights up the THIRD domain
> (asset-servicing) so all three domains demo multi-step orchestration. Do NOT commit — a reviewer
> commits after review.

## Repo / environment
- Repo: `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
- Stack is running: docker compose project `orchestrator-demo` (gateway, servicing MCP service, conduit-chat BFF at http://localhost:8099, iam-service at :8084, redis, cerbos).

## Copy the two committed reference verticals EXACTLY (same structure)
- **Wealth `concentration`** (HTTP): `mock-agents/wealth/concentration/{compute.py,handler.py}`, manifest `registry/manifests/wealth-management/meridian.wealth.concentration.json`.
- **Insurance `renewal_risk`** (HTTP, 2-producer fan-in): `mock-agents/insurance/renewal_risk/{compute.py,handler.py,test_compute.py}`, manifest `registry/manifests/insurance/meridian.insurance.renewal_risk.json`, wired in `mock-agents/insurance/main.py`.

Each is a **fan-in analytics agent**: a pure hermetic `compute.py` (no FastAPI/shared deps), a handler, and a manifest whose `io.consumes[]` items carry `from` (a producer's `io.produces[].type`) + a JMESPath `select` that projects that producer's output into what the consumer needs.

## The data contract (already committed — rely on it)
The gateway blackboard merges N producers keyed by each producer's `io.produces[].name`, so the fan-in body is `{settlement_status:{...}, custody_positions:{...}, cash_position:{...}}`. `io.consumes[].select` (JMESPath) reshapes each producer output; a bound input that fails validation/completeness is **never dispatched** — the node fails safe (honest "data unavailable", no 422, no wrong number). So make the `select` projections precise, and honor completeness.

## Verified finance — use `docs/DOMAIN-KNOWLEDGE-VERIFIED.md` verbatim (do NOT invent)
- **CSDR settlement discipline: cash penalties are LIVE (since 1 Feb 2022); mandatory buy-in is NOT activated (suspended under the CSDR Refit).** So `settlement_risk` may compute/flag **CSDR cash-penalty exposure** and fails aging — it **must NOT claim mandatory buy-in is in force**. State it as current/time-sensitive.
- Any "buy-in age" / exposure trigger is **firm-discretionary** → flag against a firm-configured env parameter (e.g. `SETTLEMENT_BUYIN_AGE_DAYS`), labeled firm policy — no invented universal cutoff.
- Be honest in the output `notes` about every simplification (mirror `renewal_risk`'s notes discipline). **If a needed data field is genuinely missing, STOP and report it — do not fabricate.**

## Build steps
1. Read the REAL servicing MCP data agents for their actual output shapes: `mock-agents/servicing/` (settlement, custody, cash tools) + `mock-agents/servicing/shared/canned_data.py`. Compute from real fields (pending/failed settlements, positions, cash balances, any trade/settlement date for aging).
2. `settlement_risk` compute (pure/hermetic like the references; `math.isfinite` guards; reject non-finite/negative): fails aging (buckets by age vs an as-of date), a CSDR cash-penalty exposure estimate **if the data supports it** (else say so honestly), and a firm-configured aging/exposure flag. Env threshold, labeled firm policy.
3. Reachability: the servicing service is **MCP (FastMCP)** — add `settlement_risk` as an MCP tool that accepts the merged fan-in input (the gateway `McpAdapter` invokes MCP tools with a JSON input). Match how existing servicing tools are registered. Its input schema must be introspectable so the data-contract validator has teeth.
4. Manifest `registry/manifests/asset-servicing/meridian.servicing.settlement_risk.json` (validate vs `registry/agent-manifest.schema.json`): domain `asset-servicing`, correct `sub_domain` (see `registry/domains/asset-servicing/`), audience `segment`, protocol `mcp`, constraints read/`confidential-pii`, ≥3 skill examples, and:
   - `io.consumes` = `[{from:"servicing.settlement_status", select:"..."}, {from:"servicing.custody_positions", select:"..."}, {from:"servicing.cash_position", select:"..."}]` — **confirm the exact produced-type strings** in `registry/manifests/asset-servicing/*.json`.
   - `io.produces` = `[{name:"settlement_analysis", type:"servicing.settlement_analysis"}]`.
   Add the agent id to the correct sub-domain `agents[]` list.

## Prove (run it — this is the point)
- `cd gateway && mvn test` — stays green (report totals). `scripts/world-b-check.sh` — CRITICAL 0. All manifests validate.
- Rebuild the servicing service + gateway from current source; boot loads the new agent (16 manifests, 0 failed).
- **Live via BFF** (http://localhost:8099, real OIDC — reuse `scripts/seed-conversations-via-bff.py`'s login helper; find the seeded servicing/ops user in the seed data): ask a settlement-risk question about a fund/account in their book → assert `plan_graph` shows the fan-in (`settlement_status ∥ custody_positions ∥ cash → settlement_risk`), and the answer states fails-aging / CSDR cash-penalty exposure **grounded in the agent output**, flagged vs the firm parameter, **without claiming buy-in is active**. Capture the trace.
- A deliberately-wrong `select` → node not dispatched (fails safe). Add unit tests (like `renewal_risk/test_compute.py`) + a `test_servicing_settlement_multistep` in `tests/e2e/security_harness/`.

## Constraints
- Do NOT commit. Do NOT touch `uac/backend` or any stashed work. World-B CRITICAL 0. Meridian-agent standard for the agent.

## Report back
- Files added/changed; the `select` expressions; the aging/CSDR-exposure formula as implemented (and what real data supported vs what you honestly omitted).
- `mvn` / world-b / manifest-validation results.
- Live BFF evidence: the `plan_graph` fan-in + the grounded settlement-risk answer.
- Any servicing-data limitation you hit (a missing field is a finding, not something to fabricate).
