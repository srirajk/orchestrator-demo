# Codex task T2 — per-hop identity: zero-trust propagation + agent verification (fail-closed)

> SECURITY-CRITICAL, enterprise-grade — this is the surface a bank's security review scrutinizes hardest.
> Touches GATEWAY code (World-B applies — auth is generic, no domain/client/ID literals) AND the Python mock
> agents' verifiers (external systems). Run `scripts/world-b-check.sh` before/after; report CRITICAL (0). Do
> NOT commit (reviewer commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch
> `feat/conduit-chat` (pull latest — T3/T6/map committed). Stack: docker compose `orchestrator-demo`; BFF
> :8099 (real OIDC), IAM :8084. **This is NOT a demo shortcut — fail CLOSED everywhere; if a gateway service
> token or the wrong user's token can pass, the task has FAILED. If anything is ambiguous, STOP and report.**

## Why (perimeter is done; the INTERIOR fails open)
Today: the user authenticates to the gateway via real OIDC and the gateway runs full 3-layer authz — the
**perimeter** is solid. But the **interior is zero-trust-broken**, verified:
- **Propagation lost on the multi-step path:** `HttpAdapter.extractBearerToken()` reads
  `SecurityContextHolder` (a ThreadLocal); on the DAG path the adapter runs on `DagPlanExecutor`'s virtual
  threads, the ThreadLocal doesn't propagate → `token == null` → **no bearer is sent** to downstream agents.
- **Agents fail OPEN:** `mock-agents/*/shared/jwt_verify.py` uses the WRONG JWKS URL
  (`.well-known/jwks.json`; real is `<iam>/oauth2/jwks`), `httpx.get` without `follow_redirects` → 301 →
  `JWKSUnreachable` → **`return True` dev-fallback**, plus **no-token → `return True`**. Net: RS256 signature
  verification is a **no-op for every token, tampered or not.**
- **No audience check:** a token minted for the BFF, replayed at any agent, verifies (lateral movement).
- `RequestContext` is itself a ThreadLocal (Opus) — so the fix CANNOT just move to it.

## The stash
A near-complete implementation is stashed at **`git stash@{0}`** ("F-IDENTITY deferred … recoverable").
`git stash show -p stash@{0}` and re-apply what still fits **post-rename** (expect `AGENT_ID`/path drift in
`mock-agents/*/jwt_verify.py` — hand-resolve, never `checkout --theirs` a whole file). Finish it to the spec
below. Do NOT rely on any ThreadLocal for propagation.

## Part A — Propagate the end-user token as immutable DATA to every hop
The end-user's verified token (captured at ingress) must ride the invoke path as **data**, not a ThreadLocal:
- Thread it onto `PlanNode` / an explicit argument of `ProtocolAdapter.invoke(...)` (today `invoke(manifest,
  input)` has no token param — add it), so it survives the DAG virtual threads.
- Fix **BOTH** `HttpAdapter` AND `McpAdapter` to send it (`Authorization: Bearer <user-token>` for HTTP; the
  MCP equivalent the servicing server reads).
- **Coverage-service calls** (`coverageClient`) must ALSO carry the end-user token (they're a hop too).
- It must be the **END-USER's** token, never a gateway service/client-credentials token.

## Part B — Agents verify, and FAIL CLOSED (all agent trees + coverage services)
Fix `mock-agents/*/shared/jwt_verify.py` (wealth, insurance, servicing, hr-policy, market-research, AND both
coverage services — every service that receives a hop):
- Correct JWKS URL (`<iam>/oauth2/jwks`); `follow_redirects=True`; cache keys by `kid`.
- **REMOVE every fail-open path**: no "JWKS unreachable → allow", no "no token → allow". Any failure → **reject
  (401)**.
- Verify **RS256 signature**, `iss` (the IAM issuer), `exp` (expiry), and **`aud`** (the agent's expected
  audience). A validly-signed token with the wrong `aud` → reject. (If you deliberately waive `aud`, you must
  add a `docs/orchestration-architecture/DECISION-LOG.md` entry justifying it — no silent omission.)
- Fail closed on: missing token, malformed token, bad signature, expired, wrong issuer, wrong audience,
  JWKS unreachable.

## Part C — Token lifetime vs plan deadline
A user JWT can expire mid-plan (long fan-out). At bind, check `exp`; if the token is expired before a hop,
fail that node as a distinct **`auth_expired`** status (not a generic agent failure), surfaced honestly.

## Part D — Agents echo their VERIFIED sub (the propagation-proof mechanism)
So the harness can prove *whose* identity crossed (not merely "a JWT was accepted"), each agent must expose
the `sub` it verified — e.g. a response header `X-Conduit-Verified-Sub: <sub>` (the caller's own identity;
safe to echo). The gateway/harness reads it to assert propagation. Without this, propagation is unprovable.

## HARNESS — the hardened gate (first-class, repeatable; extend `tests/e2e/security_harness/`)
The existing identity harness only probes wealth-http and only checks "a JWT was accepted" — that lets a
gateway service token or wrong-user token pass. Fix it:
1. **Every service, no-token + tampered-signature:** parametrize `test_no_token_rejected` and
   `test_tampered_signature_rejected` over **every agent service — including the MCP servicing server AND both
   coverage services**, not just wealth-http. Each → 401.
2. **`test_hop_carries_end_user_sub` (THE core assertion):** the agent echoes `X-Conduit-Verified-Sub`, and
   the harness asserts it **equals the turn's end-user `sub`** — on the **flat path AND a DAG downstream node**
   (the concentration hop, which runs on the VT where ThreadLocals die). A gateway service token or wrong-user
   token passing this = **FAIL**.
3. **`test_jwks_outage_fails_closed`:** stop `iam-service`, call a data endpoint with an uncached-`kid` token →
   401/503, never 200.
4. **`test_wrong_audience_rejected`:** a validly-signed token minted for the wrong audience → 401 (or the
   documented waiver).
5. **`test_token_expired_midplan`:** an about-to-expire token → the affected node is `auth_expired`, honestly
   surfaced.
(Note: the old log-line "propagating JWT" count is weak — rely on the `sub`-echo assertion, not the log.)
Same pytest-unavailability caveat as map is acceptable — run the assertions through the seeder runtime if
pytest isn't installable, but the files must be committed and the assertions must actually execute.

## GATE (prove it; fail-closed is non-negotiable)
- All five harness checks pass; the sub-echo assertion holds on flat AND DAG paths for the real personas.
- Regression: the 3 verticals + conditionals + map still answer live for ENTITLED users (they now carry a
  valid token end-to-end); out-of-book still denied.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0.
- **Negative proof:** a request with NO user token, a TAMPERED token, and a WRONG-USER token each fail — at
  every service, including MCP + coverage. Demonstrate at least one of each.

## Constraints / anti-gaming
- FAIL CLOSED everywhere — remove EVERY fail-open fallback; do not add new ones "to make tests pass".
- Propagate the **end-user** token, never a service/client-credentials token (that would pass the sub-echo
  check only if the agent echoes the wrong sub — the harness asserts it equals the END USER, so this is caught).
- Do NOT weaken any verifier or the harness to pass. Do NOT build coverage per-producer (that's T4). World-B
  clean (auth is generic). Do NOT commit.

## Report
Files changed; how the token rides the invoke path as data (the `ProtocolAdapter.invoke` signature change,
HTTP + MCP + coverage); the jwt_verify fix per service (JWKS URL, redirects, removed fail-open paths, iss/aud/
exp); the `auth_expired` handling; the sub-echo mechanism; the five harness tests + evidence they pass
(including the negative proofs — no-token/tampered/wrong-user at MCP + coverage, and the sub-echo on a DAG
hop); mvn / World-B / regression results; and any `aud` waiver decision-log entry. STOP and report anything
the spec didn't anticipate.
