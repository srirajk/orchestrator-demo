# Codex task spec — rename agent-id namespace `acme.*` → `meridian.*`

> Self-contained. Hand to Codex. A WIDE, mechanical rename with a HARD safety gate — a botched
> rename breaks the whole demo, so every step below is mandatory. Do NOT commit — a reviewer commits
> after the gate is green. Work in `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch
> `feat/conduit-chat` (pull latest first). Stack running: docker compose project `orchestrator-demo`.

## Goal
Every agent identifier `acme.<domain>.<capability>` becomes `meridian.<domain>.<capability>` (e.g.
`acme.wealth.holdings` → `meridian.wealth.holdings`). The `acme` first-segment is a leftover tenant
slug; the org is Meridian. Nothing else changes — same domains, same behavior.

## FIRST: find the full blast radius (grep before you touch)
Run `git grep -n "acme\." | wc -l` and `git grep -ln "acme\."` and enumerate EVERY hit. It spans at
least:
- **Manifests**: `registry/manifests/**/*.json` — the `agent_id` field AND the **filename**
  (`acme.wealth.holdings.json` → `meridian.wealth.holdings.json`) — use `git mv`.
- **Domain/sub-domain manifests**: `registry/domains/**/*.json` — the `agents[]` membership lists.
- **Agent code**: `mock-agents/**` — `AGENT_ID = "acme...."` constants in handlers/tools, error-schema
  ids, and any canned-data / test references.
- **Cerbos policies**: `infra/cerbos/policies/**` — ANY reference to an agent_id or a rule keyed on it.
  (Authz MUST behave identically after — see the gate.)
- **Gateway**: `gateway/src/main/java/**` should carry NO hardcoded `acme.*` (World-B) — but grep to be
  sure; `gateway/src/test/**` fixtures/assertions; `gateway/src/test/resources/**` manifest fixtures.
- **Dashboards / metrics**: `infra/grafana/**/*.json` PromQL and panel labels (e.g. `goal="acme..."`),
  and any place a dashboard hardcodes an id.
- **e2e harness + eval**: `tests/e2e/**`, `eval/**`, `scripts/**` — any id constants/assertions.
- **Docs/specs**: update references in `docs/**` only where they name a live id (leave prose history).
Do a case-sensitive, word-boundaried replace of `acme.` → `meridian.` (do NOT touch unrelated words).
`provider.organization` is already "Meridian Demo Bank" — leave it.

## MOVE-SAFETY GATE — the rename is DONE only when ALL pass (capture before/after evidence)
1. **Registry inventory (golden):** rebuild + boot the gateway; boot log shows the SAME count with the
   renamed ids — `Registry bootstrap complete: 16 loaded, 0 failed`, and every id now `meridian.*`.
2. **Schema:** all manifests validate against `registry/agent-manifest.schema.json`.
3. **io-graph integrity:** every `io.consumes[].from` still resolves to exactly one producer (the
   `produces.type` strings are `domain.thing`, NOT `acme.*`, so they should be UNAFFECTED — confirm you
   did not rename produced-type strings, only agent_ids).
4. **Golden derived-DAG:** the resolver derives the same DAGs (holdings→concentration, the insurance
   and servicing fan-ins) with the renamed node ids. `cd gateway && mvn test` GREEN (99+).
5. **Authz snapshot (critical — FULL cross-product, not a sample):** BEFORE the rename, capture Cerbos
   allow/deny for the **complete cross-product of ALL 16 agent ids × ALL seeded principals**, generated
   by a script (not a hand-picked handful — a policy that after rename matches NO agent id flips to a
   silent deny-by-default that a 5-agent sample never sees). AFTER: rename the Cerbos policies in
   lockstep, **restart Cerbos and CONFIRM from its startup log/admin that the renamed policy files
   loaded (same policy count)** before capturing the after-matrix (else you snapshot a stale container).
   The full before/after matrices must be IDENTICAL. Any flip = the policy rename is wrong; fix first.
6. **Agent tests:** `mock-agents` pytest suites green; `python3 -m pytest tests/e2e/security_harness` —
   the previously-passing checks still pass (identity xfails stay xfail).
7. **Live BFF (rebuild gateway + all agent services + Cerbos):** the three verticals still answer
   live — rm_jane concentration (`plan_graph` `meridian.wealth.holdings → meridian.wealth.concentration`),
   uw_sam renewal, the servicing settlement question — and an out-of-book question is still denied. The
   Grafana dashboard PromQL now keys on `meridian.*` and still populates from that traffic.
8. **World-B:** `scripts/world-b-check.sh` CRITICAL 0.
9. **Stale-reference audit (the finisher):** `git grep -n "acme\."` returns **ZERO** hits anywhere
   (manifests, code, policies, tests, dashboards, harness, eval, compose). Any remaining `acme.` means
   the rename is incomplete.

## Constraints / report
- Use `git mv` for manifest file renames (preserve history). Rename Cerbos + dashboards in the SAME pass.
- Do NOT commit. Do NOT touch `uac/backend` or stashed work. Do NOT rename the produced-type strings
  (`wealth.holdings`, etc.) — only the agent_ids (`acme.* → meridian.*`).
- Report: the count of ids renamed, every file category touched, the Cerbos authz before/after matrix
  (proving identical), the live BFF evidence (renamed plan_graph + a grounded answer), the dashboard
  still populating, and the final `git grep acme\.` = 0 result. If ANY gate step fails, STOP and report
  exactly where — do not push through a partial rename.
