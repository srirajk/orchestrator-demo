# Repo Structure + Naming — PROPOSAL (not executed; behind a hard test gate)

> Status: PROPOSAL for Sriraj to approve. Nothing here is executed. Any manifest move or id rename is
> a real refactor with a wide blast radius — it ships only after the **Move-Safety Gate** (§4) is
> green. "If we move those manifests we need to test it very hard." Agreed.

## 1. The two problems (confirmed)
1. **Naming split.** All 14 `agent_id`s use the `meridian.*` namespace, but `provider.organization` is
   "Meridian Demo Bank" and the demo persona is Meridian. The id namespace and the persona disagree.
2. **Folder sprawl.** Docs live at BOTH repo root (`AUTHZ-SPEC.md`, `RAG-DESIGN.md`,
   `ARCHITECTURE-SPLIT.md`, `ADR-STATELESS-GATEWAY.md`, `USER-TESTING-GUIDE.md`, …) AND in `docs/`
   (31 files). The agent-manifest schema is DUPLICATED (`/agent-manifest.schema.json` root +
   `/registry/agent-manifest.schema.json`). `mock-agents/` mixes agents, coverage services, CRM, and
   embeddings with inconsistent names (`wealth`, `wealth-coverage`, `wealth-market-research`).

## 2. Naming — the decision to make
The first segment of an `agent_id` is a **tenant/namespace slug**, not necessarily the bank name.
`acme` is a leftover placeholder tenant. Two coherent options:
- **(A) Rename `meridian.*` → `meridian.*`** (recommended for a single-bank demo). Coherent end-state:
  `meridian.wealth.holdings`. Bigger blast radius (see §3), but removes the confusion permanently.
- **(B) Keep `acme` but DOCUMENT it** as the tenant slug and make the story explicit ("`acme` = the
  onboarding tenant; Meridian is the organization display"). Near-zero risk, but the confusion stays
  unless narrated every time. Reasonable interim.
Recommendation: **B now (document), A as a deliberate change behind the gate** — don't do a 14-id
rename in the same breath as everything else.

## 3. Blast radius of the `meridian.*` → `meridian.*` rename (what MUST change in lockstep)
- `registry/manifests/*.json` — `agent_id` (14 files) + filenames.
- `registry/domains/*/*.json` — the `agents[]` membership lists.
- Agent handlers/tools (`mock-agents/**`) — the `AGENT_ID = "meridian...."` constants + error-schema ids.
- **Cerbos policies** (`infra/cerbos/policies/`) — any resource/derived-role that references an
  agent_id or the segment mapping.
- Tests — fixtures and assertions referencing ids (`gateway/src/test/**`, `mock-agents/**/test_*`).
- Anything embedding ids: trace/eval assertions, the demo query catalog, dashboards.
- `scripts/world-b-check.sh` — patterns (does NOT currently flag `acme`, but verify after).

## 4. Move-Safety Gate — "prove nothing broke" (run for ANY move or rename)
A relocation/rename is DONE only when every one of these passes, before/after:
1. **Registry inventory (golden).** Boot the registry loader; assert the SAME count and the EXPECTED
   set of agent_ids load (old set, or the renamed set) — `RegistryBootstrapLoader — loaded N agents`.
2. **Schema validity.** All manifests validate against the three JSON Schemas (14/14).
3. **io-graph integrity.** Every `io.consumes.from` resolves to exactly one producer; zero ambiguous
   types (the whole-registry dataflow check).
4. **Golden derived-DAG.** Resolve the concentration goal → the SAME DAG shape (ids updated iff
   renamed). Snapshot-compare.
5. **Introspection reachability.** Each HTTP/MCP agent's `openapi.json`/`tools/list` still resolves
   its `operation_id`/`tool` (services up).
6. **Handler↔manifest binding.** Every handler `AGENT_ID` equals its manifest `agent_id`; canned-data
   keys unchanged (grep audit).
7. **Authz snapshot.** A matrix of principal × agent → allow/deny is IDENTICAL before/after (Cerbos
   references updated in lockstep). This is the security-critical check for a rename.
8. **docker-compose + env.** `CONDUIT_REGISTRY_LOCATION` + volume mounts + service names still correct
   after a folder move; `docker compose config` validates.
9. **Full suites green.** gateway JUnit (67+), agent pytest, resolver/executor/property batteries,
   `world-b-check` CRITICAL 0.
10. **E2E smoke.** The verified demo queries return the SAME answers through the BFF (live).
11. **Stale-reference audit.** `git grep` for the OLD path/prefix returns ZERO hits anywhere
    (manifests, code, compose, docs, policies, tests). Use `git mv` to preserve history.

## 5. Target folder structure (proposal)
```
registry/
  schema/            # the 3 schemas (single home; delete the root duplicate)
  domains/<domain>/<sub>.json  + <domain>.json
  manifests/<agent_id>.json
agents/              # rename of mock-agents/ — the external stand-ins
  <domain>/          # http agents by domain (wealth, insurance, servicing)
  coverage/<domain>/ # coverage (book-of-business) services, grouped
  platform/          # crm, embeddings, shared infra stand-ins
gateway/             # unchanged
infra/               # cerbos, compose fragments
docs/
  architecture/  authz/  orchestration/  eval/  runbooks/  vision/
  # move root-level *.md into the right docs/ subfolder; one docs home
```
Safe, low-risk first step (no code/id impact): **consolidate docs** — move the root `*.md` into
`docs/<area>/` and delete the duplicate root schema. That alone removes most of the confusion and
touches no runtime path. The `mock-agents → agents` rename and the id rename are the higher-risk
moves, gated by §4.

## 6. Recommended sequence
1. **Now (safe):** consolidate docs into `docs/<area>/`; remove the duplicate root schema; document
   `acme` = tenant slug (naming option B). No runtime path changes → light test (build + world-b).
2. **Deliberate, gated:** `mock-agents/ → agents/` folder rename (updates compose build contexts +
   Dockerfiles) → run §4.
3. **Deliberate, gated:** `meridian.* → meridian.*` id rename → run §4 in full (esp. #7 authz + #11 audit).
