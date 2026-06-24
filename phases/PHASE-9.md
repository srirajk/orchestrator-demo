# Next Test Run — Verify Identity, Authorization & Correlation Are *Real*

> **One doc, no new features.** This is the set of checks to run after Phase 8 (identity) to
> confirm the gateway's identity + authorization + correlation actually work at runtime — not
> just on the happy path. A code audit found several things that *look* correct in a demo but
> are silently wrong. Each section below is a fix to verify and a test that proves it. Work
> top-down; the **★ items are demo-critical** (they break the beats you'll show a bank).

**How to read this:** each item = the problem (so you understand *why* the test exists), the
fix, and the **pass condition**. If a pass condition fails, that capability isn't real yet.

---

## ★ 1. The verified token must actually drive entitlements (CRITICAL)

**Problem.** The gateway verifies the JWT on the servlet thread, then runs the pipeline on a
*different* thread (`CompletableFuture.runAsync`). The verified identity is held in a plain
`ThreadLocal`, which does **not** cross that thread boundary — so the pipeline loses it and
falls back to a **hardcoded seed** of who-can-see-what. It looks fine because rm_jane's seed
matches her token today. But it means the token isn't really in control.

**Fix.** Carry the identity across the async hop — either pass the resolved principal explicitly
into the pipeline, or use a context-propagating executor (Micrometer `ContextSnapshot`) so the
identity, logs (MDC), and trace context all cross together.

**★ PASS — the "flip" test:**
- Issue rm_jane a token whose `book` claim contains **REL-00188** → she is **allowed** REL-00188.
- Issue rm_jane a token **without** it → she is **denied** REL-00188.
- The decision follows the **token**, not the seed. *(Today this fails — the gateway reads the
  seed.)*

> **Operational gotcha — re-issue the token.** The `book` is baked into the JWT at issue time. So
> after you change rm_jane's domain membership, you must **re-login / re-issue her token** before
> the flip shows — the same cached token won't change mid-session. If you test with the old token
> and see "still denied," that's expected; get a fresh token first. (This is also the honest demo
> note: membership changes take effect on next token, not instantly.)

---

## ★ 2. The JWT verifier must reject bad tokens (CRITICAL — and currently untested)

**Problem.** The verification code is correct (RS256, signature via JWKS, `exp`/`iss`/`aud`), but
there are **no tests** proving it rejects forgeries. Security code with no negative tests has no
guard — one refactor turns it into decode-and-trust and nothing catches it. Also: a tampered
token live is a great demo beat.

**Fix.** Add a gateway test class that mints tokens with a throwaway keypair so it can forge bad
ones.

**★ PASS — the rejection matrix (each → HTTP 401):**
- wrong-key signature · expired · wrong `aud` · wrong `iss` · tampered payload (valid header,
  mutated claim) · missing token on a protected route.
- And a **valid** token → 200 with the correct `sub` extracted.

---

## ★ 3. One conversation ID — from LibreChat's header

**Problem.** The gateway uses **two** different conversation IDs: the real one from the
`X-Conversation-Id` header, and a made-up one from hashing the first message
(`"conv-" + hashCode`). They disagree; the hash collides and changes if the first message is
edited; it never matches LibreChat.

**Fix.** Use the **header `X-Conversation-Id`** as the single conversation ID everywhere (logs,
session, and any future history feature). Generated fallback only when the header is absent.

**PASS:** two turns in the same LibreChat conversation show the **same** conversation ID in the
logs *and* in the session store, and it equals what LibreChat sent.
*(Prereq: LibreChat is configured to forward the header — `X-Conversation-Id:
{{LIBRECHAT_BODY_CONVERSATIONID}}` in `librechat.yaml`. Verify that's set.)*

---

## ★ 4. One request ID — the OpenTelemetry `trace_id`

**Problem.** Three different IDs for one request: the log/MDC `requestId` (a UUID), the glass-box
`requestId` (`req-…`), and the OTel `trace_id`. None match, so you can't follow a request from
the glass-box to its logs or its trace.

**Fix.** Read the OTel `trace_id` at request entry and use it as the single ID for: the glass-box
event key, the MDC `requestId`, and any stored trace. Retire the `req-…` generator.

**PASS:** the ID shown on a glass-box event equals the `trace_id` in the logs **and** in Tempo
for that same request — one ID, all three surfaces. **And the negative:** no glass-box event
carries a `req-…` id anymore (a half-done fix where some events still mint the old id would pass a
casual "do they match?" glance — confirm the old generator is gone).

---

## 5. The glass-box must show the agents it *skipped*

**Problem.** The glass-box's "Not selected: ~~nav~~" beat never appears — the gateway always
sends an empty "filtered" list, and the resolver doesn't even report what it skipped.

**Fix (two parts):** (1) the resolver returns the skipped/below-threshold candidates with a
reason; (2) the gateway includes them in the `agents_resolved` event.

**PASS:** the demo prompt shows `nav` (and any other below-threshold agent) struck out under
"Not selected," with a reason.

---

## 6. Entitlement enforcement — make the story honest

**Problem.** The entitlement decision is a **local** `book.contains(relId)` check; the Cerbos PDP
is **advisory** (its verdict isn't enforced). So "Cerbos enforces our entitlements" isn't true at
runtime.

**Fix / decision:** either route the decision through Cerbos (`CheckResources`) so the PDP is
authoritative, **or** keep the local check and **state it honestly** — the gateway is the
enforcement point (PEP) and Cerbos expresses the policy. Don't claim PDP enforcement you don't do.

**PASS:** the enforcement path is either genuinely through Cerbos, or documented truthfully — and
the rm_jane→Okafor denial still works either way.

---

## 7. Security at every hop — agents verify too (if attempting M16)

**Problem.** The mock agents (FastAPI / MCP) don't verify the JWT — they trust the gateway. So
"security all the way through" isn't true yet.

**Fix.** Each agent verifies the JWT (signature via JWKS, `exp`/`aud`) before serving; the gateway
forwards the token on every hop.

**PASS:** calling an agent **directly** with no / invalid / expired token → **401 at the agent**;
with a valid token → serves. *(Only test this once you've decided to build per-hop verification —
otherwise mark it as a known gap.)*

---

## Hygiene (fold in opportunistically — not gating)

- Remove the **"tax lots"** suggestion from the clarify message — there's no such agent.
- Build the gateway's HTTP client from the injected builder (`RestClient.Builder`), not
  `new RestTemplate()`, so trace context propagates on the HTTP-agent path.
- `JwksClient` can spuriously reject a token as "unknown kid" during a concurrent key refresh —
  block briefly or retry once on miss.
- `anonymous` principal currently grants real access to Whitman demo data — give it an **empty**
  book so "no token" ≠ "can see a client."

---

---

## 8. Non-regression — don't break what already worked

**Why.** Items 1–4 touch the request hot path (the async boundary, the IDs). It's possible to go
green on all of them and still have broken the core flow. So re-run the baseline beats:

**PASS:**
- The demo prompt (Whitman, full picture) still returns a **grounded, multi-agent** answer that
  streams to the chat.
- The **Okafor denial** still denies (and still streams the denial message).
- **Partial failure** still degrades gracefully: knock one agent down (fault knob) → the answer
  still comes back grounded on the agents that succeeded, not an error.
- A **follow-up** question in the same conversation still uses the cached session results.

If any baseline beat regressed, stop — a correctness fix broke the demo path, and that outranks
the new checks.

---

## Run summary — what "all green" means

| # | Capability | Pass condition | Demo-critical |
|---|------------|----------------|:---:|
| 1 | Token drives authz | flip test: REL-00188 allowed iff in token's book | ★ |
| 2 | Verifier rejects forgeries | wrong-key/expired/wrong-aud/iss/tampered/missing → 401 | ★ |
| 3 | One conversation ID | header id matches across logs + session | ★ |
| 4 | One trace ID | glass-box id == logs == Tempo | ★ |
| 5 | Skipped-agent visual | `nav` shown struck out | |
| 6 | Entitlement honesty | Cerbos enforces, or documented truthfully | |
| 7 | Per-hop verification | agent 401s a bad token directly | (M16) |
| 8 | No regression | demo prompt, denial, partial-failure, follow-up all still work | ★ |

**Green = the identity, authorization, and correlation story is *real*** — the token (not a seed)
decides access, forged tokens are rejected, and one conversation ID + one trace ID flow across
logs, the glass-box, and traces. Items 1–4 are the ones that make or break a bank demo; do those
first.