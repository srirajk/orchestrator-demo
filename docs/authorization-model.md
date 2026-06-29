# Enterprise AI Gateway — Authorization Model

> **Design principle:** The gateway has zero hardcoded domain knowledge.
> It only reads claims from the token, attributes from agent manifests, and policies
> written by org admins. Swap in any industry, any identity provider, any agent catalog —
> the same seven-point model applies.

---

## The Core Problem

Every enterprise AI gateway faces the same three-axis authorization problem:

```
WHO (principal)  ×  WHAT (agent/resource)  ×  CONTEXT (when, how, conditions)
```

The gateway enforces the intersection. It never needs to understand what an agent
*does* — only what class it is and what the caller is permitted to access.

---

## Request Lifecycle — The Seven Authorization Points

```
User types prompt
      │
      ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 1 — Identity Verification                                    │
│  Validate JWT signature via JWKS. Normalize claims → Principal.     │
│  Fail fast: expired token, unknown issuer, missing required claims. │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Principal object
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 2 — Structural Authorization (Cerbos)                        │
│  Does this role TYPE have permission to invoke this AGENT CLASS?    │
│  Coarsest filter. Runs before routing. No domain knowledge.         │
│  Deny → 403 immediately, no routing attempted.                      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Structurally allowed
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  ROUTING — Vector search finds candidate agents                     │
│  Embed prompt → HNSW search → top-N candidates across all domains   │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Candidate agent list
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 3 — Operational Scope Filter (Cerbos PlanResources)          │
│  Prune any agent whose domain ∉ principal's operational scope.      │
│  One batch call → returns permitted subset for fan-out.             │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Scope-filtered agent list
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 4 — Sensitivity × Clearance Gate (per agent)                │
│  principal.clearance >= agent.min_clearance?                        │
│  Mutating agent? Requires explicit write-capable role + higher CL.  │
│  Failed agents → removed from plan, noted in glass-box.            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Authorized plan
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  FAN-OUT — Parallel agent invocations (HTTP + MCP)                  │
│                                                                     │
│         ┌──────────┐  ┌──────────┐  ┌──────────┐                  │
│         │ Agent A  │  │ Agent B  │  │ Agent C  │  …               │
│         └────┬─────┘  └────┬─────┘  └────┬─────┘                  │
│              │              │              │                         │
│         POINT 5 ─────────────────────────────────────────────────  │
│         Personal entitlement check inside each agent                │
│         Agent calls: GET /entitlements/{uid}/resources/{rid}/access │
│         Gateway forwards identity; agent owns this decision.        │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Agent responses (partial OK)
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 6 — Temporal / Compliance Gates (Cerbos, hot-reloadable)    │
│  Blackout rules, market hours, jurisdiction checks.                 │
│  Compliance team edits YAML → Cerbos hot-reloads, no restart.      │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Compliance-filtered responses
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  SYNTHESIS — LLM merges agent outputs into one answer               │
└──────────────────────────────┬──────────────────────────────────────┘
                               │ Draft answer
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  POINT 7 — Response Filtering & Audit Emission                      │
│  PII scrub based on clearance. Partial-result honesty injected.     │
│  Structured audit event → SIEM (every sensitive-agent touch).       │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
                    Streamed answer to user
```

---

## Principal Model — Three Layers, Any Industry

The three layers apply universally. Only the values change per org.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        PRINCIPAL                                    │
│                                                                     │
│  Layer 1 — STRUCTURAL (what you ARE)                               │
│  ─────────────────────────────────                                  │
│  Roles — coarse, stable, from your IdP                             │
│                                                                     │
│  Bank:      relationship_manager | domain_admin | platform_admin   │
│  Hospital:  attending_physician  | nurse        | billing_admin    │
│  Law firm:  partner              | associate    | paralegal        │
│  Trading:   trader               | risk_manager | analyst          │
│                                                                     │
│  Layer 2 — OPERATIONAL SCOPE (what you BELONG TO)                 │
│  ──────────────────────────────────────────────                    │
│  Teams, departments, business units, regions                        │
│  Determines which DOMAIN of agents you can reach                   │
│                                                                     │
│  Bank:      segments: [wealth, servicing]                          │
│  Hospital:  departments: [cardiology], wards: [ICU]                │
│  Law firm:  practice_groups: [M&A], offices: [NYC]                 │
│  Trading:   desks: [equity, rates], regions: [APAC]               │
│                                                                     │
│  Layer 3 — PERSONAL ENTITLEMENTS (what YOU specifically can touch) │
│  ──────────────────────────────────────────────────────────────    │
│  Individual resource access. NEVER in the JWT. Always a live call. │
│                                                                     │
│  Bank:      book: the client relationships you personally manage   │
│  Hospital:  patients: the patients under your direct care          │
│  Law firm:  matters: the cases you are assigned to                 │
│  Trading:   mandates: the funds you are authorized to trade        │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Agent Manifest Model — Domain-Agnostic Attributes

Every agent registers with these fields regardless of what it does.
The gateway reads only these — never agent-specific business logic.

```
┌─────────────────────────────────────────────────────────────────────┐
│                       AGENT MANIFEST                                │
│                                                                     │
│  id              unique identifier                                  │
│  domain          which org unit owns this agent                    │
│  category        coarse class (data | execution | admin | search)  │
│  sensitivity     low | medium | high | restricted                  │
│  is_mutating     true = changes state, false = read only           │
│  min_clearance   minimum principal clearance required (e.g. L3)    │
│  tags            arbitrary labels for routing & filtering           │
│  protocol        http | mcp | a2a                                  │
│                                                                     │
│  Examples across industries:                                        │
│                                                                     │
│  Bank agent:     domain=wealth, sensitivity=high, is_mutating=false │
│  Hospital agent: domain=cardiology, sensitivity=restricted, …=false │
│  Law agent:      domain=M&A, sensitivity=high, is_mutating=false   │
│  Trade agent:    domain=equity, sensitivity=high, is_mutating=TRUE  │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Point 2 — Structural Authorization Deep Dive

Cerbos policy shape (generic — no domain names hardcoded):

```yaml
# Generic structural policy — works for any industry
# Replace role names and agent categories with your org's values

apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: default
  resource: agent
  rules:
    # Read-capable roles can invoke read-only agents in their category
    - actions: [invoke]
      effect: EFFECT_ALLOW
      roles: [analyst, associate, nurse, relationship_manager]
      condition:
        match:
          expr: >
            R.attr.is_mutating == false &&
            P.attr.clearance >= R.attr.min_clearance

    # Write-capable roles can invoke mutating agents
    - actions: [invoke]
      effect: EFFECT_ALLOW
      roles: [trader, attending_physician, partner]
      condition:
        match:
          expr: >
            P.attr.clearance >= R.attr.min_clearance

    # Admin roles can register / deregister agents in their scope
    - actions: [register, deregister]
      effect: EFFECT_ALLOW
      roles: [domain_admin, platform_admin]

    # Platform admins can do everything
    - actions: ["*"]
      effect: EFFECT_ALLOW
      roles: [platform_admin]
```

**Key rule:** structural policies check role type → agent class.
They never check specific client IDs, case numbers, patient IDs — that is Layer 3.

---

## Point 3 — Operational Scope Filter

Before fan-out, one `PlanResources` call to Cerbos:
*"Which of these N candidate agents can this principal invoke?"*

```
Principal:   { roles: [analyst], desks: [equity], clearance: 2 }
Candidates:  [equity-data-agent, rates-execution-agent, credit-agent]

Cerbos evaluates:
  equity-data-agent    → domain=equity  ∈ principal.desks=[equity] → ALLOW
  rates-execution-agent → domain=rates  ∉ principal.desks=[equity] → DENY
  credit-agent          → domain=credit ∉ principal.desks=[equity] → DENY

Permitted fan-out: [equity-data-agent]
Pruned (shown in glass-box as DENIED): rates, credit
```

This works identically for:
```
Hospital:  user.departments=[cardiology] → prunes oncology agents
Law firm:  user.practice_groups=[M&A]   → prunes litigation agents
Bank:      user.segments=[wealth]        → prunes servicing agents
```

---

## Point 4 — Sensitivity × Clearance Matrix

```
                    AGENT SENSITIVITY
                 low    medium   high   restricted
               ┌──────┬────────┬──────┬───────────┐
PRINCIPAL   L1 │  ✓   │   ✗    │  ✗   │     ✗     │
CLEARANCE   L2 │  ✓   │   ✓    │  ✗   │     ✗     │
            L3 │  ✓   │   ✓    │  ✓   │     ✗     │
            L4 │  ✓   │   ✓    │  ✓   │     ✓     │
            L5 │  ✓   │   ✓    │  ✓   │     ✓     │
               └──────┴────────┴──────┴───────────┘

Mutating agents: require min L3 regardless of sensitivity tier.
Break-glass override: L5 only, emits mandatory audit event.
```

---

## Point 5 — Personal Entitlement (Lives at the Agent, Not the Gateway)

The gateway cannot make this check — it does not know what specific resource
the agent will touch. The agent does the check, using the forwarded identity.

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  Gateway forwards:  X-Principal-ID: rm_jane                        │
│                     X-Principal-Roles: relationship_manager         │
│                     X-Principal-Clearance: 2                        │
│                                                                     │
│  Agent receives query, extracts resource reference from it          │
│  (e.g. client name → resolved to REL-00042)                        │
│                                                                     │
│  Agent calls:                                                       │
│    GET /entitlements/rm_jane/resources/REL-00042/access            │
│                                                                     │
│  Entitlement service checks its own data:                          │
│                                                                     │
│  Bank:      Is REL-00042 in rm_jane's relationship book?           │
│  Hospital:  Is PAT-1234 on dr_smith's active care team?            │
│  Law firm:  Is MAT-5678 assigned to associate_lee?                 │
│  Trading:   Is FUND-A in trader_kim's authorized mandates?         │
│                                                                     │
│  Returns: { allowed: true/false, reason: "..." }                   │
│                                                                     │
│  If denied: agent returns partial result with denial noted.         │
│  Gateway synthesizer states the gap. Never silently omits.         │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Point 6 — Temporal & Compliance Gates

These change at runtime without code deploys.
Compliance or operations edits a Cerbos policy file; it hot-reloads.

```
┌──────────────────────┬─────────────────────────────────────────────┐
│ Gate type            │ Generic shape                               │
├──────────────────────┼─────────────────────────────────────────────┤
│ Blackout window      │ DENY all agents tagged [sensitive-data]     │
│                      │ during org-defined blackout periods         │
│                      │ (earnings window / deal blackout / audit)   │
├──────────────────────┼─────────────────────────────────────────────┤
│ Operating hours      │ DENY mutating agents outside business hours │
│                      │ (market hours / clinic hours / court hours) │
├──────────────────────┼─────────────────────────────────────────────┤
│ Jurisdiction         │ DENY if principal.region ≠ resource.region  │
│                      │ and applicable_regulation requires same     │
│                      │ (GDPR / HIPAA / FINRA / FCA)               │
├──────────────────────┼─────────────────────────────────────────────┤
│ Emergency lock       │ DENY all agents in a category instantly     │
│                      │ Compliance flips flag → hot-reload in <1s   │
├──────────────────────┼─────────────────────────────────────────────┤
│ Break-glass override │ L5 principal passes justification header    │
│                      │ Gateway ALLOWS but emits mandatory audit    │
│                      │ Time-limited: policy expires automatically  │
└──────────────────────┴─────────────────────────────────────────────┘
```

---

## Point 7 — Response Filtering & Audit

```
After synthesis, before stream to user:

  ┌─────────────────────────────────────────────────────────────────┐
  │  PII SCRUB                                                      │
  │  Fields tagged PII in agent response:                          │
  │  principal.clearance < field.min_clearance → redact            │
  │  e.g. account numbers, SSNs, full names where sensitivity=high  │
  └─────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────┐
  │  PARTIAL RESULT HONESTY                                         │
  │  Any agent pruned at Points 3, 4, or 5:                        │
  │  Synthesizer must state: "Data from [domain] was not available  │
  │  for this query." Never silently omit.                          │
  └─────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────┐
  │  AUDIT EMISSION (mandatory, not configurable off)               │
  │  Structured event for every request touching sensitivity≥high:  │
  │  { principal, agents_invoked, agents_denied, timestamp,         │
  │    query_hash, break_glass: bool, jurisdiction_flags }          │
  │  Destination: SIEM (Splunk / Datadog / OpenSearch / custom)     │
  └─────────────────────────────────────────────────────────────────┘
```

---

## Integration Map

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│   IDENTITY PROVIDER                    POLICY ENGINE                 │
│   Okta / Azure AD / Auth0              Cerbos / OPA / AWS Cedar      │
│   Keycloak / Ping / LDAP               (swappable via interface)     │
│   Any OIDC / SAML compatible           ↑                             │
│          │                             │ Points 2, 3, 4, 6           │
│          │ JWT (JWKS validated)        │                             │
│          ▼                             │                             │
│   ┌──────────────────────────────────────────────────────────┐      │
│   │                                                          │      │
│   │              ENTERPRISE AI GATEWAY                       │      │
│   │                                                          │      │
│   │  Principal    →    Router    →    Executor    →  Synth  │      │
│   │  (from JWT)       (vector)       (harness)     (LLM)   │      │
│   │                                                          │      │
│   └──────┬───────────────────────────────────┬──────────────┘      │
│          │                                   │                      │
│          ▼                                   ▼                      │
│   ENTITLEMENT SERVICE               AGENT CATALOG                   │
│   user-mgmt / enterprise IAM        Redis (manifests + HNSW)       │
│   HR system / custom                                                 │
│   Called by AGENTS (Point 5)        ↑ registered by agents         │
│                                     │                               │
│          ▼                          ▼                               │
│   AUDIT / SIEM                  AGENTS (HTTP / MCP / A2A)           │
│   Splunk / Datadog              Each agent enforces Point 5         │
│   OpenSearch / custom           and calls entitlement service       │
│   (Point 7 emission)                                                 │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

---

## What Keeps This Domain-Agnostic

The gateway code contains none of the following:
- Industry names (bank, hospital, law, trading)
- Domain names (wealth, servicing, cardiology, M&A)
- Resource types (relationship, patient, matter, mandate)
- Role names (relationship_manager, physician, partner, trader)

These live in three places the gateway reads at runtime:
1. **JWT claims** — normalized to a standard `Principal` shape by the identity provider
2. **Agent manifests** — domain, sensitivity, is_mutating, min_clearance registered by each agent team
3. **Cerbos policies** — written by the org's admins, referencing their own roles and domains

Change any of those three and the same gateway serves a different industry.
The admin UI policy generator's job is to make writing those Cerbos policies approachable
without knowing CEL syntax — which is exactly why the prompt-first design matters.

---

## What Is Currently Built vs Missing

```
Point 1  Identity verification (JWT/JWKS)          ✓ Built
Point 2  Structural authorization (Cerbos)          ✓ Built
Point 3  Operational scope filter (PlanResources)   ✗ Missing — FlatPlanExecutor
                                                      prunes by confidence but not
                                                      by user's domain scope
Point 4  Sensitivity × clearance gate               ✗ Partial — clearance in JWT
                                                      but not checked vs agent
                                                      min_clearance at runtime
Point 5  Personal entitlement (agent-level)         ✓ Built (book API exists,
                                                      called by agents)
Point 6  Temporal / compliance gates                ✗ Missing — Cerbos hot-reload
                                                      is wired but no condition
                                                      policies written yet
Point 7  Response filtering & audit                 ✗ Partial — partial-result
                                                      honesty in synthesizer but
                                                      no PII scrub, no SIEM emit
```

Next priority to wire: **Point 3** (scope filter in executor) — it's one
`PlanResources` call and closes the biggest real-world gap.
