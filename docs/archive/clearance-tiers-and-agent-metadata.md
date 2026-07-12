# Clearance Tiers — Tenant Schema, Agent Metadata & Policy

> **Design principle:** Clearance tier names are defined ONCE by the tenant at onboarding.
> The same names appear in TWO places — the agent manifest and the Cerbos policy.
> The gateway resolves names to ranks. Nothing downstream hardcodes numbers.

---

## Why Named Tiers, Not Numbers

Numbers (`clearance: 3`) are meaningless without a legend.
Named tiers are self-documenting and match how real enterprises talk.

| Industry | Tier names (low → high) |
|---|---|
| Financial services | `public` → `internal` → `confidential` → `restricted` → `highly_restricted` |
| Healthcare | `general` → `clinical` → `privileged` → `restricted` |
| Law firm | `standard` → `senior` → `partner` → `admin` |
| Government / defence | `unclassified` → `confidential` → `secret` → `top_secret` |
| Generic SaaS / tech | `public` → `internal` → `sensitive` → `admin_only` |

Any enterprise picks their own names. The system stores and compares them.
The gateway resolves tier name → numeric rank for ordered comparison.

---

## Place 1 — Tenant Clearance Schema (defined at onboarding)

When a tenant onboards, they define their tier hierarchy once.
Stored in the `groups` or `tenant_config` table.

```json
{
  "tenant_id": "meridian-bank",
  "clearance_schema": [
    { "name": "public",            "rank": 0 },
    { "name": "internal",          "rank": 1 },
    { "name": "confidential",      "rank": 2 },
    { "name": "restricted",        "rank": 3 },
    { "name": "highly_restricted", "rank": 4 }
  ]
}
```

```json
{
  "tenant_id": "city-general-hospital",
  "clearance_schema": [
    { "name": "general",    "rank": 0 },
    { "name": "clinical",   "rank": 1 },
    { "name": "privileged", "rank": 2 },
    { "name": "restricted", "rank": 3 }
  ]
}
```

**This schema is the single source of truth.**
Everything else references tier names from it — never raw numbers.

---

## Place 2a — Agent Manifest (when an agent registers)

The agent declares the minimum clearance tier required to invoke it.
Uses the tenant's named tier, not a number.

```json
{
  "agent_id": "portfolio-analytics",
  "domain": "wealth-management",
  "category": "data",
  "is_mutating": false,
  "min_clearance": "confidential",
  "sensitivity": "confidential",
  "tags": ["wealth", "portfolio", "read-only"]
}
```

```json
{
  "agent_id": "trade-execution",
  "domain": "wealth-management",
  "category": "execution",
  "is_mutating": true,
  "min_clearance": "restricted",
  "sensitivity": "restricted",
  "tags": ["wealth", "trading", "mutating"]
}
```

```json
{
  "agent_id": "patient-vitals",
  "domain": "cardiology",
  "category": "data",
  "is_mutating": false,
  "min_clearance": "clinical",
  "sensitivity": "clinical",
  "tags": ["clinical", "read-only"]
}
```

**Rule:** `min_clearance` in the manifest must be a valid name from the tenant's schema.
The registry validates this at registration time.

---

## Place 2b — Principal Attributes (on the user record)

The user record carries their clearance tier in `attributes JSONB`.
Same named tier, same tenant schema.

```json
{
  "id": "rm_jane",
  "attributes": {
    "clearance": "confidential",
    "segments": ["wealth"],
    "admin_domains": []
  }
}
```

```json
{
  "id": "dr_smith",
  "attributes": {
    "clearance": "privileged",
    "department": "cardiology",
    "license": "MD-12345"
  }
}
```

---

## Place 2c — Cerbos Derived Role (the policy enforcement point)

The gateway resolves tier names to ranks BEFORE calling Cerbos.
Cerbos receives `clearance_rank` (integer) alongside the tier name.
CEL stays simple — no tier list hardcoded in YAML.

```yaml
# derived_roles.yaml
apiVersion: api.cerbos.dev/v1
derivedRoles:
  name: meridian_derived_roles
  definitions:

    # Principal meets or exceeds the agent's minimum clearance requirement
    - name: sufficient_clearance
      parentRoles: ["*"]
      condition:
        match:
          expr: P.attr.clearance_rank >= R.attr.min_clearance_rank

    # RM is authorized for this specific resource (book check)
    - name: authorised_rm
      parentRoles: ["relationship_manager"]
      condition:
        match:
          expr: R.attr.resource_id in P.attr.book

    # Domain admin operating within their own domain
    - name: domain_owner
      parentRoles: ["domain_admin"]
      condition:
        match:
          expr: R.attr.domain in P.attr.admin_domains
```

```yaml
# agent_resource.yaml
apiVersion: api.cerbos.dev/v1
resourcePolicy:
  version: default
  resource: agent
  importDerivedRoles:
    - meridian_derived_roles
  rules:
    # Non-mutating agents: role match + clearance sufficient
    - actions: [invoke]
      effect: EFFECT_ALLOW
      derivedRoles: [sufficient_clearance]
      condition:
        match:
          expr: >
            R.attr.is_mutating == false &&
            P.attr.segments.exists(s, s == R.attr.domain)

    # Mutating agents: additionally require explicit write role
    - actions: [invoke]
      effect: EFFECT_ALLOW
      roles: [senior_trader, attending_physician, partner]
      derivedRoles: [sufficient_clearance]
      condition:
        match:
          expr: R.attr.is_mutating == true

    # Platform admin: unrestricted
    - actions: ["*"]
      effect: EFFECT_ALLOW
      roles: [platform_admin]
```

---

## How the Gateway Resolves Tiers Before Calling Cerbos

```python
# Gateway — before building the Cerbos CheckResources call

tenant_schema = load_clearance_schema(tenant_id)
# e.g. {"public":0, "internal":1, "confidential":2, "restricted":3}

# Resolve principal's clearance name → rank
principal_clearance_name = principal.attributes.get("clearance", "public")
principal_clearance_rank = tenant_schema.get(principal_clearance_name, 0)

# Resolve agent's min_clearance name → rank
agent_min_clearance_name  = agent_manifest.get("min_clearance", "public")
agent_min_clearance_rank  = tenant_schema.get(agent_min_clearance_name, 0)

# Build Cerbos principal attributes
cerbos_principal_attrs = {
    **principal.attributes,
    "clearance_rank": principal_clearance_rank,   # int for CEL comparison
}

# Build Cerbos resource attributes
cerbos_resource_attrs = {
    **agent_manifest,
    "min_clearance_rank": agent_min_clearance_rank,   # int for CEL comparison
}
```

Cerbos sees clean integers for comparison. Humans see readable names everywhere else.

---

## Capability Resolver — Using Clearance in Routing

The capability resolver (vector search + scoring) already uses agent metadata.
With clearance tiers, it adds a **pre-filter** before semantic scoring:

```
1. Vector search → top-N candidate agents
2. Pre-filter: remove agents where agent.min_clearance_rank > principal.clearance_rank
   (principal cannot invoke these regardless of semantic match)
3. Score remaining candidates
4. Return authorized + relevant subset
```

This means the routing result is always clearance-aware — the resolver never
surfaces an agent the user cannot access. The Cerbos check at fan-out confirms it.
Two layers, same clearance schema, consistent result.

---

## Policy Generator — System Prompt Grounding

When the LLM generates a Cerbos policy, it is grounded with the tenant's actual tier names:

```
## Clearance Tiers for This Tenant (meridian-bank)
Use ONLY these names in policies — do not invent others:
  public (rank 0) → internal (rank 1) → confidential (rank 2)
  → restricted (rank 3) → highly_restricted (rank 4)

## Available Derived Roles
sufficient_clearance — principal.clearance_rank >= resource.min_clearance_rank
authorised_rm        — RM has this resource in their personal scope
domain_owner         — domain admin operating within their domain

## Rule: Never write a clearance tier name that is not in the list above.
## Rule: Always use derived roles — never inline clearance comparison in rules.
```

The LLM generates `min_clearance: "confidential"` not `min_clearance: 2`.
Human reviewers read the policy and immediately understand it.

---

## Summary — The Two-Place Rule

```
Tenant defines tier schema once
        │
        ├──→ Agent manifest:   min_clearance: "confidential"
        │    (at registration, validated against schema)
        │
        ├──→ Principal attrs:  clearance: "confidential"
        │    (in user record, set by admin)
        │
        └──→ Cerbos policy:    derivedRole: sufficient_clearance
             (rank resolved by gateway before Cerbos call)

One schema. Two registration points. One comparison in Cerbos.
The gateway is the bridge that resolves names to ranks.
```
