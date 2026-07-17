# Axiom A5 — Coverage services under tenancy (evidence)

**Branch:** `feature/axiom-A5-coverage-tenancy` (based on `conduit-platform-next` @ `06d79f8`,
the branch tip at STEP 0; `conduit-platform-next` has since advanced +2 commits with the C3
policy-studio moat, which touches only `iam-service` and nothing on A5's surface).

## Token path taken: **gateway-to-coverage token exchange (scoped) — NOT direct coverage-audience**

**Why.** A1 (`iam-service/.../auth/JwtClaimsCustomizer.java` + `TenantClaims.java`) mints only the
gateway audiences `conduit-gateway` and `conduit-gateway@<tenant_id>`. It does **not** mint a
`conduit-coverage@<tenant>` audience. Per doc 07 A5 (line 275) and A1 (line 120), when the token
carries no coverage audience, A5 "must use a gateway-to-coverage token exchange rather than
accepting a gateway-only audience." So the **direct** path is not available on this base.

**What A5 delivers now (the tested second gate).** A5's security value — and its entire tested
acceptance — is **tenant binding at the coverage/data layer**, which is independent of the audience
mechanism. The coverage service already independently verified signature / stable issuer / expiry /
audience (`jwt_verify.py`); A5 adds the missing tenant gate: it re-derives the tenant from the
verified JWT and from the requested book's owner and requires

    JWT tenant_id  ==  X-Tenant-Id header  ==  book-owner tenant

Any disagreement ⇒ **403 from coverage itself**. This makes coverage a genuine second gate: even a
compromised/buggy gateway sending tenant A's book under tenant B's header — or replaying a valid
tenant A token against a tenant B book — is rejected at the data layer.

**Honest deferral.** Swapping the forwarded credential to a dedicated `conduit-coverage@<tenant>`
audience via a real RFC 8693 exchange requires an IAM token-exchange endpoint on the gateway→IAM
hop. That touches IAM's token/authorization path (its own protected suites) and must be validated
against the live stack, so it is deliberately **not** built here. The coverage service's expected
audience stays config-driven (`AGENT_JWT_AUDIENCE`, default `conduit-gateway`) so the running demo
is not broken; flipping it to the coverage audience is a one-line config change once the exchange
mints it. No coverage-side code assumes the gateway audience — only the tenant binding is enforced.

## Acceptance

| ID | Claim | Evidence |
|----|-------|----------|
| A5.1 | Coverage rejects tenant mismatch (forged header) itself | `captured-403-*.json` (status 403 + body), `pytest-*-coverage.txt` (`test_forged_header_rejected_by_coverage`) |
| A5.2 | Cross-tenant book request denied at the data layer | `captured-403-*.json`, `pytest-*-coverage.txt` (`test_cross_tenant_book_request_rejected`) |
| A5.3 | Gateway header provenance is `TenantExecutionContext` | `CoverageClientTenantHeaderIT-surefire.txt` (1 run / 0 fail) |
| A5.4 | World-B clean (gateway touched: one new test only) | `world-b-check.txt` (CRITICAL 0) |

## Gates

- **pytest** — wealth-coverage **27 passed**, insurance-coverage **26 passed** (0 failed). This
  also **fixed 7 pre-existing reds on the base** (unrelated to A5): 6 HTTP integration tests never
  sent a bearer token (debt from when the JWT middleware landed) and 1 `rm_ken` unit assertion was
  a copy-paste drift from the `ops_analyst_singh` case. Fixes are strengthenings/corrections, not
  weakenings — see `pytest-*-coverage.txt`.
- **gateway `mvn -o test`** — **483 tests, 0 failures**, BUILD SUCCESS (`gateway-mvn-test-summary.txt`).
  The gateway pom uses default surefire (collects `*Test`/`*Tests` only); `*IT` classes (incl. the
  five existing ones) run under failsafe/`mvn verify`, so `CoverageClientTenantHeaderIT` is validated
  with an explicit surefire run (`CoverageClientTenantHeaderIT-surefire.txt`). Base
  `conduit-platform-next` has the same 483 (C3 added no gateway tests); the A5 gateway addition is an
  `*IT`, so the surefire count is unchanged. **Note:** the task's "≥487" is not reachable from this
  base — the base itself is 483 under `mvn test`.
- **world-b** — CRITICAL **0** (`world-b-check.txt`).
- **protected suites** — all **135** existing gateway test files byte-identical to
  `conduit-platform-next`; only `CoverageClientTenantHeaderIT.java` added
  (`protected-suites-byte-identity.txt`). This is a superset of the 7 protected suites.
