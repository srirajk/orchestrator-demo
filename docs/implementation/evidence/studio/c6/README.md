# C6 — Break-glass / emergency access (evidence)

The FINAL Axiom story. Break-glass is a **normal scoped policy grant whose ALLOW carries a CEL time
condition** (`now() < timestamp(expiresAt)`), paired with a complementary DENY (`now() >= …`) so
expiry fails **closed** even under `REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS`. Enforcement is therefore
**self-limiting inside the PDP** — nothing external revokes the grant.

## Files

| File | What it proves |
|---|---|
| `break-glass-policy-expired.yaml` | A compiled break-glass artifact **with the CEL time condition in the ALLOW** (baked `expiresAt` in the past) + the deny-all baseline (totality). This is what `BreakGlassPolicyCompiler` emits and what the C2 `GeneratedPolicyValidator` blesses. |
| `break-glass-policy-live.yaml` | The same artifact shape with a short-window `expiresAt` (the live-flip grant). |
| `expiry-run-controlplane-down.log` | **C6.1 HEADLINE** — the live run: an ephemeral Cerbos PDP (Testcontainers, a **distinct** container + random host port, **never** `conduit-cerbos:3594`), driven by real `CheckResources`, with **no studio / no control plane running**. An already-expired grant ⇒ `EFFECT_DENY`; a fresh grant ⇒ `EFFECT_ALLOW`; the live grant ⇒ `EFFECT_DENY` once wall-clock crosses `expiresAt`. If expiry needed any external revocation this would be RED — the control plane is not running. |
| `sod-two-person.txt` | **C6.4** — surefire for `BreakGlassTwoPersonTest`: the expedited path still refuses a self-approval (author≠approver) and a non-approver-role approver (same SoD as C1's `policy_approval`). |
| `audit-records-sample.txt` | **C6.3** — the grant issuance + each use, as `audit_log` rows landed in the tenant's **A6 partition** (who requested, who approved, scope, action, expiry). |

## Acceptance

- **C6.1** expires with control plane down — `expiry-run-controlplane-down.log` (PDP-only DENY after expiry; the CEL time condition is IN the artifact — `break-glass-policy-expired.yaml`).
- **C6.2** cannot cross tenant scope — `BreakGlassCannotCrossTenantTest` (rejected at C2's segment-wise scope check; a `startsWith` sibling is rejected too).
- **C6.3** fully audited — `audit-records-sample.txt` (grant + use → A6 records).
- **C6.4** two-person / SoD preserved — `sod-two-person.txt`.
- **C6.5** TTL/action bounds + clock requirement — `BreakGlassBoundsTest` (TTL > 60m, wildcard, malformed expiry, off-allowlist action/resource all rejected **before** compilation; the clock-sync SLO readiness gate reports DOWN outside ±5s).

## The template idiom

`infra/cerbos/templates/break-glass-grant.yaml` documents the shape (mirrors the B1
`tenant-deny-all.yaml` idiom). It compiles against a parent ceiling (verified via ephemeral
`cerbos compile`).

## Honest deferrals

- The **live break-glass drill** (issue a real grant in the running demo, watch it expire) is **held
  for the user** — this evidence proves the mechanism against an ephemeral PDP, not the live stack.
- **Clock-sync (≤5s skew)** is an **operational SLO**: the readiness *logic* is enforced
  (`BreakGlassClockReadiness`), but the true skew measurement (NTP/chrony) is deployment wiring; the
  default `ClockSkewGauge` reports a configured assumed skew.
