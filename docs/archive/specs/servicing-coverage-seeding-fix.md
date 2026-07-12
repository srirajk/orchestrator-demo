# Codex task — fix servicing coverage/book seeding so settlement_risk demos LIVE (no admin bypass)

> Data/config/manifest fix ONLY. **No gateway Java changes** (World-B: the gateway does segment +
> classification; it must NOT learn any client/book data). Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (pull latest).
> Stack running: docker compose `orchestrator-demo`; BFF http://localhost:8099 (real OIDC); IAM :8084.

## The architectural principle (do not violate it while fixing)
Authorization is layered on purpose:
- **Gateway = coarse, structural only** — business **segment** membership + data **classification**
  clearance. It holds ZERO book/client data.
- **Book-of-business = lower level** — "is THIS client in YOUR book?" is answered by the **coverage
  service** (the domain team's book service the gateway calls out to). The book belongs THERE, as the
  single source of truth — never in the gateway, and it should not be silently duplicated elsewhere.

## The bug (confirmed live — do not re-derive, verify then fix)
No client-facing persona can currently run the asset-servicing `settlement_risk` fan-in end-to-end,
because the book is **duplicated in two stores that drifted**:
- `rm_jane`: has the book + wealth `confidential-pii`, but her **servicing** clearance is only
  `confidential` → fails the **classification** gate for asset-servicing (needs `confidential-pii`).
- `ops_analyst_singh`: has servicing `confidential-pii` (passes classification), and REL-00188 is in her
  IAM `personal_resources` (migration `V9__seed_servicing_ops_coverage.sql`) — but that relationship
  **never resolves in the coverage service** that actually gates servicing relationship lookups (the
  demo found it's the wealth-coverage mock, tagged `private-banking`, which keeps its OWN book data
  separate from IAM) → fails the **coverage** gate, even for her own client.
- Proven via `admin` (bypasses gates): the DAG itself is FINE — the full 4-node fan-in
  (`settlement_status + custody_positions + cash_management → settlement_risk`) runs with real CSDR
  numbers (2-day AMZN fail, ~$185k penalty exposure, 250% ratio). So this is purely an
  entitlement-**seeding** gap, not an engine bug.

## The fix (config/data/seed only)
1. Confirm which coverage service gates asset-servicing relationship queries and how its book is seeded
   (find the coverage-service book data — likely a JSON/seed in the coverage mock or its container).
2. Make ONE servicing persona clear ALL THREE gates for a real relationship:
   - **segment/structural** — in the segment that reaches `asset-servicing` (Cerbos/segment seed);
   - **classification** — servicing = `confidential-pii`;
   - **coverage** — their relationship(s) (e.g. REL-00188) present in the **coverage service's** book.
   Prefer fixing `ops_analyst_singh` (she already has the clearance); the missing piece is her book in
   the coverage service.
3. **Reconcile the two book stores so they AGREE** for the demo personas — the coverage service is the
   authoritative book; align the IAM `personal_resources` seed to match it (or vice-versa) so there is
   no drift. (Full "coverage-service is the ONLY book, remove the IAM copy" refactor is T4 — do NOT do
   that refactor here; just make them consistent so the gate passes and nothing silently disagrees.)
4. OPTIONAL (only if needed): the demo found a terse "what is the settlement risk for REL-X" doesn't
   always pull all three producers. If so, strengthen the `settlement_risk` agent's `skills[].examples`
   (manifest data, World-B clean) so a natural settlement-risk question reliably triggers the fan-in.
   Do NOT paste test strings; use realistic phrasings.

## Prove (LIVE via BFF, NO admin bypass)
- The chosen servicing persona, signed in through the BFF (:8099, real OIDC), asks a natural
  settlement-risk question about their in-book relationship → the **full fan-in runs**
  (`settlement_status + custody_positions + cash_management → settlement_risk`, visible in `plan_graph`)
  and the answer is grounded in real CSDR/aging numbers — end to end, no admin.
- Entitlement stays TIGHT: the same persona asking about a relationship NOT in their book → denied
  ("not in your coverage"); confirm no gate was loosened to force the pass.
- `scripts/world-b-check.sh` CRITICAL 0; `cd gateway && mvn test` green; the wealth + insurance
  verticals still answer live.

## Report
The exact seed/config files changed; the persona that now clears all three gates and how (segment +
classification + coverage book); the LIVE `plan_graph` fan-in + grounded answer (conversation/request
ids); confirmation the out-of-book denial still fires; and world-b/mvn results. Flag anything you had to
touch beyond seed/config. Do NOT commit.
