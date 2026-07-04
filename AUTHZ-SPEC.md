# Authorization Model — Spec & Non-Breaking Rollout Plan

Status: **spec, approved in principle — review, then build.** Goal: one coherent ABAC model where every gate maps to something an agent or domain **actually declares**; existing behavior preserved exactly; two realistic agents added to show the full variation; the whole decision visible e2e in the glass box.

---

## 1. The model

### Two planes (don't conflate)
- **Admin plane (RBAC)** — govern the platform. Roles: `tenant_admin`, `domain_admin`, `agent_publisher`, `policy_author`, `policy_approver`, `auditor`.
- **Runtime plane (ABAC)** — invoke agents. **Everyone is `chat_user`** (front door to enterprise intelligence). What they reach = attributes + coverage.

### The role → policy → user chain (how access is wired)
```
POLICY  ──attached to──►  ROLE  ──assigned to──►  USER
(a rule)                  (a bundle, 1..*)        (a person, 1..* roles)
```
- A role holds **one-to-many policies**; a user holds **one-to-many roles**; effective access = **union of the policies of their roles**, each policy **attribute-conditioned**.
- `chat_user` carries the base policies; **attributes decide what those policies actually grant.** (Same shape as AWS IAM: policies → groups/roles → users, policies carry conditions.)
- Consequence: **few, stable policies**; per-user difference comes from `segments`/`data_classification`, never a new role or a per-user policy.

### Token claims (baked into the Axiom JWT) — **classification is PER-SEGMENT**
```jsonc
{
  "sub": "rm_jane",
  "roles": ["chat_user"],
  "segments": {                       // MAP: segment -> the tier the user holds IN that segment
    "wealth":    "confidential-pii",  // senior in wealth — sees PII
    "servicing": "confidential"       // only confidential in servicing — no PII
  }
}
```
`segments` is a **map**, not a flat list + one global tier. Multiple segments, each with its own ceiling. (Replaces the old flat `clearance` number entirely.)

### Agent capability contract (declared at registration; matched by gateway + policy)
| Field | Values | Meaning |
|---|---|---|
| `domain` | wealth-management, asset-servicing, insurance, hr | business line |
| `audience` | `segment` \| `enterprise` | **NEW** — enterprise = open to all, segment ignored |
| `access_mode` | `read` \| `write` | **RENAME of `is_mutating`** |
| `data_classification` | internal \| confidential \| confidential-pii | sensitivity |
| `tags` | (later) | optional extra gates |

### The 3 gates (evaluated in order, per request; agent in domain `D`, class `C`)
1. **Audience/segment** — `audience==enterprise` → skip. Else require `D` to be a key in `user.segments`.
2. **Classification (per-segment lookup)** — `rank(user.segments[D]) ≥ rank(C)`, where `internal < confidential < confidential-pii`. Uses *that segment's* tier.
3. **Coverage** — only if the agent is **entitled** (entity-scoped): domain's `/coverage/{userId}/resources/{id}`. **Knowledge** agents skip it.

Edge: `enterprise` agents have no segment to look up — they're `internal` by nature so everyone clears gate 2. If an enterprise agent is ever above internal, use the user's **highest** tier across any segment.

### Entitled vs knowledge agent
- **Entitled** — answer depends on *your* specific entity (client/policy) → coverage required.
- **Knowledge** — general info, not tied to something you own (market research, HR policy) → no coverage.

---

## 2. What changes — file-level impact map (the research)

| Area | File(s) | Change |
|---|---|---|
| **Registry** | `registry/agent-manifest.schema.json` | rename `is_mutating`→`access_mode`; add `audience` |
| | 11 × `registry/manifests/*.json` | `access_mode:"read"` + `audience:"segment"` |
| | `registry/domains/hr.json` *(new)* | HR domain, **no coverage block** |
| **Gateway** | `registry/model/AgentManifest.java` | model: `accessMode`, `audience` (drop `isMutating`) |
| | `domain/auth/CerbosEntitlementAdapter.java` | send `access_mode`+`audience`+`data_classification` as resource attrs |
| | `domain/auth/Principal.java` | `segments` → `Map<String,String>`; **drop `clearance`** (14 refs) |
| | `domain/auth/EntitlementService.java` | skip coverage for knowledge/enterprise agents |
| | `resolver/service/AgentResolver.java` | carry `audience`/`access_mode` through |
| | `infrastructure/telemetry/event/CheckDeniedData.java` | deny-reason strings per gate (for glass box) |
| **Cerbos** | `infra/cerbos/policies/agent_resource.yaml` | rewrite `chat_user` rule; **per-segment map lookup**; delete clearance ladder + phantom `restricted` |
| **Axiom** | `auth/OidcClaimEnricher.java` | **key file** — emit `segments` as a map; **drop `clearance`** |
| | `auth/JwtClaimsCustomizer.java` | claim shape |
| | `service/DataSeeder.java` + `V5__*.sql` *(new)* | users → `chat_user` + per-segment map; add `analyst_amy` |
| **Mock agents** | `mock-agents/wealth-market-research/` + `mock-agents/hr-policy/` *(new)* | 2 tiny knowledge agents |
| **Admin UI** | `pages/Users.tsx` | per-segment rows (segment + tier); default role `chat_user` |
| | `pages/Policies.tsx` | **FROZEN — read-only view only** (no new work) |
| | `api/client.ts` | types for the above |

Confirmed by grep: **coverage services read neither clearance nor segment** — already principal-agnostic (correct, World-B). ✅

---

## 3. New demo assets (all ADDITIVE — nothing existing changes behavior)
1. **`acme.wealth.market_research`** — house-view / market commentary. `wealth-management · audience:segment · access_mode:read · data_classification:internal`, **knowledge (no coverage)**. Non-PII because it's not client data.
2. **`acme.hr.policy_qa`** — HR policy Q&A (leave, benefits, conduct). `hr · audience:enterprise · access_mode:read · data_classification:internal`, **knowledge**. + `hr.json` domain, **no coverage** (exercises the no-coverage path — a real World-B check).
3. **Demo user `analyst_amy`** — `chat_user`, `segments:{wealth:"confidential"}`.
4. **Persona tiers (per-segment):** rm_jane `{wealth:pii, servicing:confidential}`, rm_carlos `{wealth:pii}`, uw_sam `{insurance:pii}`.

### The variation matrix (what the glass box shows e2e)
| User | Query | Result | Gate that decides |
|---|---|---|---|
| rm_jane | Whitman holdings | ✅ | all pass |
| rm_jane | Okafor holdings | 🔒 | **coverage** (not her book) |
| rm_jane | insurance policy | 🔒 | **segment** (not her line) |
| analyst_amy (`wealth:confidential`) | market_research | ✅ | classification allows (internal) |
| analyst_amy | Whitman holdings | 🔒 | **classification** (confidential < pii) |
| **anyone** | HR policy Q&A | ✅ | audience=enterprise, knowledge |

*(Optional later — to visibly fire the per-segment block: add a servicing-PII agent so rm_jane, PII in wealth, is denied it via her `confidential` servicing tier. Not required for the core demo.)*

---

## 4. Admin UX (what each screen does)
- **Users screen** — add user → pick **role(s)** (`chat_user`) + add **per-segment rows** (`wealth @ confidential-pii`, `servicing @ confidential`). No policies here.
- **Policies screen** — **PARKED / read-only.** It correctly *shows* the 3 Cerbos rules (transparency), but we do **no** authoring/description/per-user work now. Revisit later as the governance layer (SoD authoring, descriptions, versioning).
- **Capabilities view** (`/v1/me/capabilities`, the per-user explainer) — *"You're in Wealth (PII) + Servicing (confidential); you can ask about holdings, performance, cash; HR policy is open."* Computed from `roles ∩ policies ∩ segments ∩ coverage` — no new store. **This is the per-user "explain it back to me" surface**, replacing per-user policy UI.

---

## 5. Ordered steps — each with a VERIFY gate (don't advance on failure)

**Step 0 — Baseline.** Run the 4-persona API test; record exact allow/deny per persona × query. Golden truth to preserve. *Verify: recorded.*

**Step 1 — Schema + manifests.** Rename `is_mutating`→`access_mode` (all `"read"`); add `audience:"segment"` to all 11; update schema. *Verify: schema validates all 11.*

**Step 2 — Gateway model + adapter.** `AgentManifest` gains `accessMode`/`audience`; `Principal.segments` → `Map`; drop `clearance`; adapter sends the 3 resource attrs. *Verify: `mvn package` green.*

**Step 3 — Cerbos policy.** Rewrite `chat_user` rule: audience skip, segment membership, **per-segment classification lookup**, `access_mode:read`. Delete clearance + `restricted`. *Verify: policy lints; dry-run 4 personas.*

**Step 4 — Token + seed.** `OidcClaimEnricher` emits `segments` map; drop `clearance`; `V5` seeds personas per-segment + `chat_user`; add `analyst_amy`. *Verify: each token carries `roles:[chat_user]` + segments map.*

**Step 5 — 🚦 NON-BREAKING GATE.** Rebuild gateway + iam. Re-run Step-0 test. **Every allow/deny IDENTICAL to baseline.** *If any differs → stop, fix, do not proceed.*

**Step 6 — Add `market_research`.** Manifest + tiny mock. *Verify: analyst_amy → research ✅, holdings ✗; rm_jane → both ✅.*

**Step 7 — Add HR domain + `hr.policy_qa`.** `hr.json` (no coverage) + manifest (`audience:enterprise`) + mock. *Verify: rm_jane, uw_sam, analyst_amy ALL → HR ✅; confirms no-coverage path works.*

**Step 8 — e2e + glass box.** Log in as each persona in the chat; confirm the variation matrix (§3) fires and each **deny shows the right gate/reason in the glass box.** `smoke-ui.sh` green; `world-b-check.sh` CRITICAL 0.

**Step 9 — Capabilities view** (per-user explainer). Compose-only endpoint + a small chat surface.

**Step 10 — Users screen** per-segment rows + default `chat_user`.

*(Policies screen: no step — frozen read-only.)*

---

## 6. Definition of done (nothing missed)
- [ ] Step-0 baseline preserved **exactly** for all 4 existing personas (Step 5 gate).
- [ ] `is_mutating` → `access_mode` everywhere (schema, manifests, gateway model, adapter, policy).
- [ ] `audience` added; **numeric `clearance` removed** (gateway `Principal` + Axiom `OidcClaimEnricher`); phantom `restricted` removed.
- [ ] Token: `roles:[chat_user]` + `segments` as **per-segment map**.
- [ ] Cerbos gate does **per-segment classification lookup**.
- [ ] `market_research` (wealth, internal, knowledge) + `hr.policy_qa` (enterprise, no coverage) live.
- [ ] `analyst_amy` shows the classification gate (research ✅ / holdings ✗); HR open to everyone.
- [ ] Each deny surfaces its gate/reason in the **glass box**.
- [ ] `smoke-ui.sh` green; `world-b-check.sh` CRITICAL 0.
- [ ] Users screen = role + per-segment rows. Capabilities view live.
- [ ] All on `feat/conduit-chat` → `conduit-platform`; **`backend` untouched**; **Axiom auth logic unchanged** beyond the claim shape.

## 7. Explicitly PARKED (not now — don't scope-creep)
- **Policies screen authoring** (descriptions, SoD, versioning) — frozen read-only; revisit as governance layer.
- `tags` 4th gate · `chat_operator` + `write` agents · a servicing-PII agent for the per-segment block demo.
- SCIM / federation / `me/capabilities` auto-provisioning (see `AXIOM-SCIM-ROADMAP.md`).

## 8. Rollback safety
Behavior-changing steps (2–5) are gated by the Step-5 identity check against baseline. New agents/users (6–7) are additive. If Step 5 can't reach parity, revert the policy/token deltas and keep the harmless schema rename — **never a half-broken state.**
