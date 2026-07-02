# Axiom Roadmap — SCIM provisioning + Capabilities view (PARKED, don't forget)

Status: **future vision, parked.** Captured 2026-07-02 so we don't lose it. Not part of the current seed-reconciliation fix — but that fix is the hand-cranked version of what this automates.

## The vision in one line
Axiom becomes a real enterprise IdP: **SCIM** provisions users + groups from Okta/Entra and maps groups → business-line domains, and a **`me/capabilities`** view dynamically shows each user *"here's what you can query"* — so domain assignment is **IdP-driven and self-describing**, never a manual/static list to maintain.

---

## Part 1 — SCIM integration

**What it does:** SCIM 2.0 (System for Cross-domain Identity Management) is the standard REST protocol by which an enterprise IdP (Okta, Entra/Azure AD) **provisions Users and Groups into Axiom automatically** — onboarding, offboarding, and membership changes flow in with no seed edits.

**The mapping that matters:**
- **SCIM Groups → Axiom `segments` / domain membership.** "Assign a user to Wealth" = put them in the Wealth IdP group → SCIM syncs it → they're in the wealth domain. No manual DB seed.
- **User lifecycle for free:** leaver removed in the IdP → SCIM de-provisions in Axiom → entitlements gone. Pairs perfectly with our **re-check-every-turn / no-stale-auth** rule: automated offboarding + fresh checks = correct by construction.

**Surface:** SCIM 2.0 endpoints — `/scim/v2/Users`, `/scim/v2/Groups`, with `PATCH` semantics, `filter`, and pagination. Axiom already being an OIDC provider is the right home for it.

**Honest notes:** SCIM 2.0 is a finicky spec (PATCH ops, filtering grammar, error schemas, pagination). Use a library/managed connector rather than hand-rolling. It's a real project — roadmap it, don't underestimate it.

---

## Part 2 — Capabilities view ("what can I query?")

A single composed view — **no new source of truth**, just an aggregation:
```
GET /v1/me/capabilities →
{ domains:  ["wealth","servicing"],                    // from the token (SCIM-provisioned)
  actions:  { wealth:["holdings","performance","goals"] }, // from Cerbos (what my role allows)
  coverage: { wealth:["Whitman REL-00042","Calderon REL-00099"] } } // from the coverage service
```
- **Composed = token(segments/domains) ∩ Cerbos(allowed actions) ∩ coverage(entities).**
- **Dynamic:** reflects whatever SCIM provisioned — so we **do not depend on manually-managed domain assignments**; the view updates as group membership changes.
- **Powers:** chat onboarding (*"You have Wealth + Servicing — ask me about holdings, performance, cash"*), plus transparency/audit (user *and* compliance see the exact reachable scope).
- Universal domains (HR, Ethics) show up here for everyone automatically — no book, no coverage, just "allowed."

---

## Part 3 — Why this is the proven pattern (Databricks / Unity Catalog parallel)

| Databricks Unity Catalog | Conduit / Axiom |
|---|---|
| SCIM syncs users + groups from Azure AD/Okta | **Axiom** (identity + segments/domains, SCIM-fed) |
| Unity Catalog grants groups on catalogs/schemas | **Cerbos + coverage** (structural + data authz) |
| catalog/schema = data domain | **domain manifest + coverage service** |

Same two-layer split: **standards-based identity/provisioning up top, fine-grained policy + data authz below.** We're landing on the enterprise-proven shape, not inventing one.

## Part 4 — Why it stays World-B (gateway never changes)
SCIM feeds Axiom → the token carries segments/domains → the gateway's structural check and the capabilities view **just consume it**. Add a domain, add a group, drop users in it → **zero gateway code**. Onboarding a business line and provisioning its users are both config/manifest, never code.

## Part 5 — Federation / identity brokering (upstream SSO to the enterprise IdP)

Axiom doesn't have to **be** the primary IdP — it can **broker** to the enterprise's existing one (Azure Entra/AD, Okta) via upstream OIDC/SAML. The user signs in with their **corporate** credentials against Entra; Axiom receives the claims, **maps on a stable identifier** (Entra `oid` or `employeeId` — NOT email/UPN, which drift), and issues **its own** OIDC token in the normalized claim shape Conduit already expects.

**Why "nothing internal changes" is exactly right:** Conduit only ever sees *Axiom's* token. The whole downstream — gateway, Cerbos, coverage, capabilities — is **insulated from the identity source**. Swap Entra → Okta → local-password and Conduit neither knows nor cares. That insulation is the entire reason to put Axiom in the middle as a broker. Classic pattern (Keycloak/Auth0/Okta): federate up, normalize claims, issue your own token down.

**SSO + SCIM are complementary, not either/or:**
- **SSO / federation** = *authentication* — proves who you are, via Entra, at login.
- **SCIM** = *provisioning* — your groups/domains pushed from Entra ahead of time, so Axiom already knows your entitlement context when you arrive.
- Together: log in with corporate creds, land with the right segments/domains, zero manual mapping.

**The one thing to nail:** map on a **stable subject** (`oid`/`employeeId`) as the Axiom principal key. Email/UPN drift; a stable id keeps a person's identity + entitlements consistent across renames/moves. Everything downstream keys off Axiom's `sub`, so the mapping happens once, at the broker.

## Suggested phasing
1. **Capabilities view** (compose-only: token ∩ Cerbos ∩ coverage) — cheap, high UX value, no new store.
2. **SCIM Users** (provision/de-provision lifecycle).
3. **SCIM Groups → domain membership** (the "assign to domains" automation).
4. **Federation** — broker upstream SSO to Entra/Okta; map on the stable subject.
5. **Retire the manual DB seed** once SCIM + federation are authoritative.

## Relationship to today's work
The current fix — reconciling the seed (users → segments → domains) by hand — **is exactly what SCIM will later automate.** So it's not throwaway; it establishes the model SCIM feeds, and the capabilities view surfaces.
