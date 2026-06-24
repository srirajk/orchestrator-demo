# Authorization (ABAC + Cerbos) — Deep Spec

*Once an agent is resolved, the platform must enforce **what this individual is allowed to
see and do**. This is the layer that makes the gateway safe for a bank — and the gate that
makes the eventual write phase fundable. ABAC via Cerbos, with the gateway as the
enforcement point.*

---

## 1. Why ABAC + Cerbos (and the one feature that clinches it)

- **ABAC over RBAC** because a bank's rule isn't "RMs can read holdings" — it's "this RM can
  read *this* relationship *because it's in their book*." That's an attribute comparison,
  not a role grant. ABAC expresses it; RBAC can't without role explosion.
- **Cerbos** because it externalizes that logic into version-controlled YAML policies
  evaluated by a stateless PDP — no authorization `if/else` scattered through the gateway,
  and policy changes ship without redeploying the gateway.
- **The clincher — `PlanResources`.** Cerbos answers two questions: *CheckResources* ("may
  this principal do X to this specific resource?") and *PlanResources* ("which resources of
  this kind may this principal touch?" → a filter expression). The second is what powers
  **entitlements as a routing filter** (§5).

---

## 2. PEP / PDP split

- **Gateway = PEP** (Policy Enforcement Point): it gathers principal + resource + action,
  asks Cerbos, and enforces the answer.
- **Cerbos = PDP** (Policy Decision Point): deployed as a **sidecar** next to the gateway,
  so every check is a localhost call — sub-millisecond, no hot-path network hop, and
  air-gappable. Use the **Cerbos Java SDK** from the gateway.
- **Keep evaluation pure:** pass all needed attributes *in* the check request. Don't make
  Cerbos fetch data mid-decision. Cache the user's attributes (roles, book) in Redis per
  session so each check is self-contained and fast.

---

## 3. The model

**Principal** (the user — from the validated JWT / `user_id`):
| attribute | example |
|---|---|
| `id` | `rm_jane` |
| `roles` | `["relationship_manager"]` |
| `book` | `["REL-00042","REL-00099"]` (relationships they own) |
| `segments` | `["wealth"]` |
| `clearance` | `2` |

**Resources:**
| kind | attributes |
|---|---|
| `agent` | `domain`, `is_mutating`, `data_classification`, `required_clearance` |
| `relationship` | `owning_rm`, `segment`, `classification` |
| `action` *(Phase 2)* | the specific mutation + its risk attributes |

**Actions:** `invoke` (on agent), `read` (on relationship); Phase 2 adds
`invoke:mutate` / `execute`.

---

## 4. Enforcement points — and where they sit relative to resolution

This is the crux: some checks can run early, but the important one **can only run after
resolution**, because you can't check "may Jane see the Whitman relationship" until
"Whitman Family Office" has resolved to `REL-00042`.

| # | Check | Cerbos API | When | Resource |
|---|---|---|---|---|
| 1 | May this user use this domain/platform at all? | CheckResources | front door, early | `domain` |
| 2 | Which agents/relationships may this user touch? | **PlanResources** | **before fan-out** → prune candidates | `agent`, `relationship` |
| 3 | May this user access *this resolved* relationship? | CheckResources | **after Stage C resolution**, in harness stage 1 | `relationship` (REL-00042) |
| 4 | May this user *trigger this write*? *(Phase 2)* | CheckResources | before a mutating invoke | `action` + `agent` |

Checks 1–2 shape the request cheaply up front. Check 3 is the entity-level gate that lives
in the harness **after** resolution — the placement your "once agents are resolved"
instinct correctly identified.

---

## 5. Entitlements as a routing filter (the strong move)

Don't only *deny after* resolving — *prune before* invoking. Before fan-out, call
`PlanResources`:

```
PlanResources(principal = jane,
              resource.kind = relationship,
              action = read)
   → filter:  resource.attr.owning_rm == "rm_jane"
```

The gateway applies that filter so it will **never resolve or fan out to a relationship
outside Jane's book** — an RM literally cannot reach another RM's client through the
platform. Do the same for `agent`/`invoke` to prune the candidate agent set coming out of
routing. Result: entitlements *shape the answer*, not just block it — cheaper, safer, and
it removes whole classes of "should this have been visible?" incidents.

---

## 6. Example policy + check

```yaml
# relationship.yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  resource: relationship
  version: default
  rules:
    - actions: ["read"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager"]
      condition:
        match:
          expr: request.resource.attr.owning_rm == request.principal.id
    - actions: ["read"]
      effect: EFFECT_ALLOW
      roles: ["compliance_officer"]
      condition:
        match:
          expr: request.resource.attr.segment in request.principal.attr.segments
```

```yaml
# agent.yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  resource: agent
  version: default
  rules:
    - actions: ["invoke"]
      effect: EFFECT_ALLOW
      roles: ["relationship_manager","compliance_officer","ops"]
      condition:
        match:
          expr: request.resource.attr.is_mutating == false   # Phase 1: reads only, enforced in policy
    - actions: ["invoke:mutate"]                              # Phase 2
      effect: EFFECT_ALLOW
      roles: ["ops_supervisor"]
      condition:
        match:
          expr: request.principal.attr.clearance >= request.resource.attr.required_clearance
```

Entity-level check (harness stage 1) in shape:
```jsonc
CheckResources {
  principal: { id:"rm_jane", roles:["relationship_manager"], attr:{ book:[...], segments:["wealth"] } },
  resource:  { kind:"relationship", id:"REL-00042", attr:{ owning_rm:"rm_jane", segment:"wealth" } },
  actions:   ["read"]
}  → EFFECT_ALLOW
```

> Note the `is_mutating == false` rule in `agent.yaml`: your Phase-1 read-only guarantee now
> lives in **policy**, not just a resolver filter — a second, externally-auditable line of
> defense.

---

## 7. Telemetry & compliance tie-in

Cerbos logs every allow/deny with context. Feed those into the observability plane:
- `authz.denials{reason}` as a metric; the decision as a span attribute on `authz.check`.
- The **glass-box shows the decision live** — e.g. `allow · rm_owns_relationship`, or a
  denied/filtered relationship greyed out — which turns entitlement enforcement into a
  visible demo beat, not invisible plumbing.
- The decision log doubles as a **compliance audit trail** (who accessed which relationship,
  when, allowed or denied) — exactly what a bank's risk review wants to see.

---

## 8. Phase-2 readiness (why this choice ages well)

The read→write leap is just more policy, not a new system:
- **Action-level rules** (`invoke:mutate`) gate writes by clearance/risk.
- **Combined agent + user evaluation** — policy can grant an agent broad capability but
  restrict the actual call by the *human's* clearance and the resource's sensitivity. That
  is the authorization half of human-in-the-loop for mutations, from the same engine.

Building the ABAC model now, during read-only, is what earns the right to turn on write.

---

## 9. PoC scope + the demo beat

**In:** Cerbos PDP sidecar (docker); 2 resource policies (`relationship`, `agent`);
principal attributes seeded for ~2 users (an RM and a compliance officer) in Redis; checks
1–3 wired (skip Phase-2 action check); glass-box shows the decision.

**Stub:** authentication — trust the `user_id` LibreChat forwards, map it to seeded
principal attributes. (Real build validates the bank's IdP JWT — leave the seam.)

**The demo beat that lands for a bank:**
1. As **RM Jane**, ask the Whitman briefing → allowed, because she owns it → full answer.
2. Ask about a relationship **outside her book** → the routing filter never even fans out;
   glass-box shows it filtered with the reason. *"The platform doesn't just answer — it
   enforces who's allowed to ask."*
3. (Optional) switch to a **compliance** principal → broader visibility, same prompt,
   different entitled scope — proving the policy is attribute-driven, not hardcoded.

---

## 10. Caveats

- **Entitlement quality depends on attribute data.** "Who owns which book" must come from
  the bank's CRM/IGA. For the PoC it's seeded; in production it's an integration, and bad
  attribute data means wrong access decisions — flag it as a real dependency, like routing
  depends on good example prompts.
- **Authentication is still required.** Cerbos authorizes; it does not authenticate. The
  gateway must validate a real IdP token before trusting `principal.id` outside the demo.
- **Start with few, clear policies.** Resist modeling every bank rule for the demo — two
  legible policies prove the model; a sprawling policy set just adds debugging surface.
