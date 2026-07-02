# TEST-KIT Findings — persona seed/auth drift (write-up before fix)

Status: **captured, not yet fixed.** From Codex's `TEST-KIT.md` validation pass, 2026-07-02. Bugs logged `bug-234`..`bug-239` in `.wolf/buglog.json`.

## Headline
The **golden path is healthy**: new chat UI `:8099`, OIDC login as rm_jane, Whitman grounded (PASS), Okafor denied *"Access denied for this client relationship."* (PASS), no stale data leaked. Stack + all telemetry surfaces up; 7 Grafana dashboards provisioned; live Prometheus metrics populated (intents=24, authz=84, outcomes=24, agent-calls=13).

**The blocker to full kit completion is persona seed/auth DRIFT** — not a code defect in the request path.

---

## Root cause: two identity paths, seeded independently

There are **two** ways a principal reaches the gateway, each with its own seed and its own data — and they've drifted:

| Path | Source of truth | Seeded by | Reality found |
|---|---|---|---|
| **OIDC / Axiom** (the chat's real path) | Axiom IAM user table | (Axiom seeding) | has rm_jane + rm_carlos; **rm_guest + uw_sam missing → 401**; rm_jane `segments:[wealth]` (lost `servicing`) |
| **`X-User-Id` trusted-hop** (legacy) | Redis `principal:{id}` hashes | `scripts/seed-users.sh` | all 4 personas; rm_jane `[wealth,servicing]` |
| **Coverage services** (book-of-business) | wealth/insurance-coverage | (coverage seed) | rm_carlos book returns `[]` while his Axiom token lists REL IDs |

`TEST-KIT.md` was written from `seed-users.sh` comments (the Redis path) — so it matches the legacy path, not what the chat actually uses.

## The fix direction (decided): retire `X-User-Id`, Axiom OIDC = single source of truth

Now that the chat does verified OIDC end-to-end, the `X-User-Id` trusted-hop is **redundant and is the known auth hole** (any caller could assert any user, no verification) — always slated for removal once the chat was rewritten. Removing it:
- **Resolves the drift by construction** — one identity store (Axiom), one seed, one truth.
- **Closes the security hole.**
- Forces all personas + segments + books to be correct in Axiom (+ coverage), which is what the chat needs anyway.

**Fix steps (ordered):**
1. **Seed all 4 personas into Axiom (OIDC)** with correct passwords (`Meridian@2024`), roles, **segments** (rm_jane = wealth+**servicing**), and domain membership (rm_guest = none). Make Axiom the authoritative persona seed.
2. **Sync the coverage services** (wealth/insurance-coverage) to the intended books (rm_carlos's RELs, uw_sam's POLs) so entitlement CHECK matches the tokens.
3. **Remove the `X-User-Id` principal-resolution path** from the gateway; update smoke/e2e that rely on it to use OIDC/JWT.
4. Re-run `TEST-KIT.md` for all 4 personas; update the kit if any intended entitlement changes.

---

## Per-bug breakdown

| Bug | Symptom | Root cause | Fix |
|---|---|---|---|
| **234** | rm_guest, uw_sam → 401 on Axiom login | not in Axiom user table (only in Redis principal seed) | seed them in Axiom (step 1) |
| **235** | rm_jane `segments:[wealth]`, not wealth+servicing | Axiom record drifted from the Redis seed | fix rm_jane's Axiom segments (step 1) |
| **236** | rm_jane cash prompt → only "8% allocation", no exact cash | (a) missing `servicing` segment via OIDC; (b) possible servicing-agent data gap | fix segment (step 1); then verify cash_management agent returns a figure |
| **237** | rm_carlos Axiom book vs wealth-coverage `[]` mismatch | coverage service not seeded for rm_carlos | sync coverage (step 2) |
| **238** | POL-88003 denial copy says "client" not "policy" | insurance **policy_details** domain denial falls back to generic copy (`claims-servicing.json` has the correct "Access denied for this policy." but policy domain doesn't) | add `denial_messages.default = "Access denied for this policy."` to the insurance policy domain manifest (World-B: manifest data, not code) |
| **239** | glass-box trace: no explicit CHECK-deny event (jumps agents_resolved → request_complete) | gateway doesn't emit a trace event on entitlement denial | instrument the entitlement CHECK to publish a `check_denied` trace event (gateway trace emission — a real gap for the glass-box value prop) |

**Classes:** 234/235/236/237 = seed/auth drift (fixed by the OIDC-consolidation above). 238 = manifest copy (data). 239 = gateway trace instrumentation.

---

## Not-blocked / confirmed healthy
- rm_jane grounded + denial (the golden demo path).
- Stack, URLs, telemetry surfaces, Grafana dashboards, Prometheus metrics.
- Chat restart-safe token, persistence.

**Next:** implement the OIDC-consolidation fix (retire `X-User-Id`, seed Axiom authoritatively, sync coverage), then 238 (manifest copy) + 239 (trace event). Re-validate with `TEST-KIT.md` across all 4 personas.
