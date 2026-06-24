# Phase 11 — Verify, Enforce, Authorize (intent + proof, not mechanism)

> **Read this first — how this phase is different.** Earlier phases handed you detailed
> implementation. This one deliberately does **not**. Each item below gives you the **decision**
> (what must be true) and the **proof** (the test that shows it's true), and leaves the
> **mechanism** (exact client, wire format, endpoint, config) for you to figure out against the
> *installed* versions of Cerbos, Spring Security, and the OTel libraries — you'll verify those
> better than a spec written from memory. Where this doc and your reading of the live docs
> conflict, trust the live docs and note the deviation in `BUILD_REPORT.md`.
>
> **Run the steps in order. Each is a hard gate — do not start the next until the current one's
> proof passes and you've written the result to `BUILD_REPORT.md`.** If a step's proof can't be
> made to pass, stop and report rather than working around it.

---

## Step 0 — Audit the existing tests before trusting them (do this first)

**Why.** Phases 8/8.5 report "21/21 unit + 25/25 E2E pass." A passing count proves nothing if the
*dangerous-case* tests were never written. The identity layer is the foundation the rest of this
phase builds on, so confirm its negative tests exist **before** building anything else.

**Do.** Enumerate every gateway test. For the JWT verifier, confirm there are tests that send a
**forged-signature**, **expired**, **wrong-`aud`**, **wrong-`iss`**, **tampered-payload**, and
**missing** token and assert the request is rejected (401). For entitlement, confirm there's a
test where a principal asks for a relationship **not** in their book and is denied.

**Proof / report (no code change unless gaps found):**
- Write to `BUILD_REPORT.md` a table: each dangerous case → the test that covers it (file + name),
  or **MISSING**.
- For every **MISSING** case, write the test (mint tokens with a throwaway keypair so you can
  forge bad ones). Re-run; all pass.
- **Gate:** the report shows every dangerous case is now covered by a named, passing test. Halt
  for review.

---

## Step 1 — Make Cerbos the authoritative entitlement decision

**Why.** Cerbos runs and the policies are correct, but the gateway never calls it —
`EntitlementService` does a local `book.contains()`. So "Cerbos enforces our entitlements" is not
true today. Make the PDP authoritative.

**Decisions (these are fixed — honor them):**
- The **Cerbos PDP verdict is the entitlement answer.** The local book check survives only as an
  optional fallback, never as the default path.
- **Batch the prune-before-fan-out.** When several relationships are in scope, check them in **one**
  PDP call, not N. (Today `filterCovered` loops — don't turn that into N round-trips.)
- **Pick a fail-mode explicitly** and put it behind config: `closed` (Cerbos unreachable → deny;
  the production-correct default) or `local` (fall back to the book check + log loudly, for demo
  resilience). Default `closed`; document which is on.
- **Reconcile roles.** The policies key on `admin` / `relationship_manager`; the tokens issue
  `platform_admin` / `domain_admin` / `relationship_manager`. Update the policy so `platform_admin`
  gets the see-all rule and `domain_admin` + `relationship_manager` get the book rule — or a
  `platform_admin` token will match no rule and be denied.
- Record a **decision source** on each entitlement result (`cerbos` / `local-fallback`) so the
  glass-box/audit shows where the verdict came from.

**Mechanism is yours.** Figure out the Cerbos client, the `CheckResources` request/response shape,
the batch call, and the resilience wrapper against the **installed** Cerbos version's docs. Build
HTTP clients from the injected builder (not `new RestTemplate()`) so trace context propagates.

**Proof (REQUIRED — this is the test that separates real from decorative):**
- **Policy-flip test:** rm_jane is denied REL-00188. Edit the relationship policy to remove the
  book condition, restart Cerbos, re-ask → she is now **allowed**. Revert → denied again. *If
  behavior does not change when the policy changes, the PDP is not enforcing — fail the step.*
- rm_jane → REL-00042 allowed, REL-00188 denied, with `source=cerbos` in the decision.
- `filterCovered` over N relationships issues **one** Cerbos call (assert it — request log or span
  count), not N.
- Fail-mode behaves as documented when Cerbos is stopped.
- The existing Okafor-denial E2E still denies — now via the PDP.
- **Gate:** all the above pass. Halt for review.

---

## Step 2 — Role-based endpoint authorization (the admin plane is currently open)

**Why.** Today any caller reaching the gateway can `POST`/`DELETE /admin/agents` — there's no
role check. Close it.

**Decisions (fixed):**
- Three roles: `platform_admin` (global), `domain_admin` (their domain only),
  `relationship_manager` (member).
- **Agent registration** (`/admin/agents` writes) → **admin only** (platform or domain). A
  `domain_admin` may only manage agents **in their own domain** — and because the URL rule can't
  see the agent's domain, that domain-scope check must be a **resource-level** check in the
  handler, not just a URL pattern.
- **Chat (`/v1/chat/completions`) and trace (`/trace/**`)** → **any authenticated member**; data
  access still bounded by the relationship entitlement from Step 1. `platform_admin` → free
  everywhere. `/v1/models`, `/actuator/**` → open.
- **Preload the org declaratively:** user-mgmt loads the teams + people + roles + memberships +
  admin grants from a seed file idempotently at startup; books are **derived** from membership,
  not stored. Editing the file + restart resets the demo. (Sample set: teams
  `wealth-private-banking`, `intl-wealth`, `servicing-ops`, `platform`; a platform admin, a domain
  admin per team, and RMs.)

**Mechanism is yours.** Decide whether to migrate to Spring Security's resource-server or extend
the existing filter — figure it out against the installed Spring Security version, and map the JWT
`roles` claim to authorities. **Note:** endpoint role checks run on the servlet thread (the filter
chain) and the admin endpoints are synchronous, so `SecurityContext` not crossing the chat
pipeline's async boundary is **not** a problem — don't add an authz check *inside* the async
pipeline expecting it to be populated; use the principal that's already passed in.

**Proof (REQUIRED):**
- `relationship_manager` → `POST /admin/agents` → **403**; → chat → **200**; → `/trace/health` → **200**.
- `domain_admin` of wealth-private-banking → register agent in **wealth-private-banking** → **201**;
  in **intl-wealth** → **403** (the resource-level scope check).
- `platform_admin` → register in **any** domain → **201**.
- **No token** → `/admin/agents` → **401**.
- A clean `docker compose up` seeds the org from the file; re-running is idempotent; rm_jane's
  derived book = her domains' relationships.
- **Gate:** all pass. Halt for review.

---

## Step 3 — Glass-box trace persistence (most "figure it out yourself")

**Why.** Glass-box trace events are in-memory only — nothing is stored, no history, no replay.
Persist them so a trace can be retrieved after the fact, keyed by conversation.

**Decisions (fixed):**
- Persist trace events behind a **storage adapter** (so the demo backend can be swapped for a
  cloud object store later) — don't hard-couple to one store.
- **Key by the unified IDs** that Phase 8.5 already established: the LibreChat **conversationId**
  and the OTel **trace_id**. (These are the keys; that's the whole reason they were unified first.)
- Support "fetch the last N traces for a conversation."

**Mechanism is yours** — the store, the adapter interface, the key scheme, the retrieval endpoint.
Keep it lean; this is a demo, not a data platform.

**Proof:**
- Run a request, then retrieve its trace by `trace_id` after it completes.
- Retrieve the last N traces for a `conversationId`; they're in order and belong to that
  conversation.
- Restart the gateway → previously-run traces are still retrievable.
- **Gate:** all pass. Halt for review.

---

## What this phase is NOT
- Not embedding the glass-box inside LibreChat (frontend fork — use a split-screen wrapper you
  own; post-sale decision).
- Not per-hop agent JWT verification (separate workstream; note it as a known gap if untouched).
- Not a place to add new product features. Verify, enforce, authorize, persist — nothing else.

## ■ PHASE COMPLETE
When Steps 0–3 each have a passing proof recorded in `BUILD_REPORT.md`, post `PHASE 11 COMPLETE`
with the four gate results and halt. **The next thing you bring back is this build report — the
proofs, not a description of the work.**
