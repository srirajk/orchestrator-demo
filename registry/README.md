# Registry — how to onboard a new business

> This folder **is** the onboarding surface. Adding a business line to Conduit means adding
> files here (+ standing up its services) — **never changing gateway code.** This README
> explains what each folder is and gives a step-by-step checklist. The full rationale lives in
> [`../docs/AGENT-ONBOARDING-HANDBOOK.md`](../docs/AGENT-ONBOARDING-HANDBOOK.md).

---

## The mental model — three levels

A business is described top-down in three nested levels:

```
DOMAIN            "Insurance"            → which coverage service, display name, governed memory policy
  └─ SUB-DOMAIN   "Claims Servicing"     → entity types, what's required, clarify/denial copy,
       │                                    and the list of agents in this workflow
       └─ AGENT   "Policy Details"       → one capability: how to call it (HTTP/MCP), its skills
```

The gateway reads these at boot, embeds the agents' example prompts for routing, and from then
on the business "exists" — it routes, resolves, entitles, and answers. No Java was touched.
Governed memory follows the same rule: the gateway emits runtime events and consumes compact
context envelopes, while the external memory service owns the compaction ledger and summaries.

## What each folder/file is

```
registry/
├── agent-manifest.schema.json     ← THE CONTRACT. Every agent manifest must validate against
│                                     this (pinned, canonical — do not edit it to fit a manifest;
│                                     fix the manifest).
├── domain-manifest.schema.json    ← Contract for domains/<domain>.json, including coverage and
│                                     governed memory policy.
├── sub-domain-manifest.schema.json← Contract for domains/<domain>/<sub-domain>.json.
├── context-envelope.schema.json   ← Envelope returned by the memory service to the gateway.
├── memory-ledger-event.schema.json← Append-only event shell the gateway emits to memory service.
│
├── domains/
│   ├── <domain>.json              ← DOMAIN manifest — one per business line.
│   │                                 Keys: domain_id, display_name, coverage (discover/check/
│   │                                 resolve URLs), memory_compaction.
│   │                                 → "Insurance exists, and here's its coverage service."
│   │
│   └── <domain>/<sub-domain>.json ← SUB-DOMAIN manifest — one per workflow inside a domain.
│                                     Keys: sub_domain_id, parent_domain, entity_types,
│                                     required_context, denial_messages, messages,
│                                     clarification_schema, agents[].
│                                     → "What entities this workflow deals in, what it needs from
│                                        the user (drives CLARIFY), and which agents serve it."
│
└── manifests/
    └── <provider>.<domain>.<capability>.json   ← AGENT manifest — one per agent/capability.
                                      Keys: agent_id, domain, sub_domain, protocol, connection,
                                      skills (with example prompts → routing), constraints
                                      (is_mutating, data_classification, sla_timeout_ms).
                                      → "How to reach one specialist system and what it's for."
```

**Why two places that both say "manifest"?** `domains/` describes the *business* (its shape,
rules, coverage). `manifests/` describes the *agents* (the callable systems). One domain has
many agents; keeping them separate is what lets a domain team add an agent without touching the
domain config, and vice-versa.

### Naming conventions
- Agent file + `agent_id`: `**<provider>.<domain>.<capability>**` (e.g. `meridian.insurance.policy_details`).
- Sub-domain file lives under its domain folder: `domains/<domain>/<sub-domain>.json`.
- The `domain` field in an agent manifest must match a loaded `domain_id`; `sub_domain` must match
  a loaded `sub_domain_id`.

---

## Onboard a new business — checklist

Say you're adding **Lending**.

**1. Stand up the services** (outside this repo's gateway):
- one or more **agent services** (HTTP with an OpenAPI spec, or an MCP server),
- one **coverage service** that answers "what's in this user's book?" (discover / check / resolve).

**2. Add the domain manifest** — `domains/lending.json`:
```json
{
  "domain_id": "lending",
  "display_name": "Lending",
  "coverage": {
    "discover_url": "${LENDING_COVERAGE_URL}/coverage/{principal_id}",
    "check_url":    "${LENDING_COVERAGE_URL}/coverage/{principal_id}/resources/{id}",
    "resolve_url":  "${LENDING_COVERAGE_URL}/entities/resolve"
  },
  "memory_compaction": {
    "envelope_version": "context-envelope.v1",
    "must_preserve": ["loan_id", "borrower_name", "domain"],
    "can_drop": ["raw_agent_outputs", "routing_decisions"],
    "summary_policy": {
      "owner": "memory-service",
      "max_summary_tokens": 600,
      "refresh_after_turns": 8,
      "ledger_retention_days": 90,
      "include_runtime_events": [
        "gateway.entity_resolved",
        "gateway.coverage_checked",
        "gateway.agent_completed",
        "gateway.response_completed"
      ],
      "redact_fields": ["raw_agent_outputs"]
    }
  }
}
```

**3. Add a sub-domain manifest** — `domains/lending/originations.json`:
```json
{
  "sub_domain_id": "originations",
  "parent_domain": "lending",
  "resource_scoped": true,
  "entity_types": [
    { "key": "loan_id", "extract_as": "loan reference", "kind": "resolvable",
      "id_pattern": "^LN-\\d+$", "resolve_type": "loan", "required": true }
  ],
  "required_context": ["loan_id"],
  "denial_messages": { "no_coverage": "That loan is not in your book." },
  "messages": { "followup_clarification": "Which loan would you like — by id or borrower name?" },
  "clarification_schema": { "...": "the scoped question options" },
  "agents": ["meridian.lending.loan_status", "meridian.lending.amortization"]
}
```
> `required_context` is what drives deterministic CLARIFY: if the user names no loan, the gateway
> asks instead of guessing. `entity_types` is map-based — adding a type is an edit here, not a
> new Java field.

**4. Add an agent manifest per capability** — `manifests/meridian.lending.loan_status.json`:
```json
{
  "agent_id": "meridian.lending.loan_status",
  "name": "Loan Status",
  "description": "Current status, balance, and next payment for a loan.",
  "version": "1.0.0",
  "provider": { "organization": "acme" },
  "domain": "lending",
  "sub_domain": "originations",
  "protocol": "http",
  "connection": { "openapi_url": "${LENDING_HTTP_URL}/openapi.json", "operation_id": "getLoanStatus" },
  "capabilities": { "streaming": false },
  "skills": [
    { "id": "loan_status", "name": "Loan status", "description": "...",
      "tags": ["lending","loan","status"],
      "examples": ["What's the status of loan LN-100?", "Is LN-100 current?", "Next payment on LN-100?"] }
  ],
  "constraints": { "is_mutating": false, "data_classification": "confidential", "sla_timeout_ms": 5000 }
}
```
> `skills[].examples` (≥3) are embedded for semantic routing — write them the way users actually
> ask. `is_mutating` must be `false` (this platform is read-only).

**5. If structural authz needs it**, add a segment→domain line to the Cerbos policy
(`../infra/cerbos/policies/agent_resource.yaml`) — e.g. `lending` segment may invoke
`lending`-domain agents. This is config, not gateway code.

**6. Wire the service URLs** (`${LENDING_HTTP_URL}`, `${LENDING_COVERAGE_URL}`) in
`docker-compose.yml` / `.env`, seed any principals, and restart the gateway.

**7. Verify:**
```bash
python3 -m pytest tests/schema
bash ../scripts/world-b-check.sh     # must stay CRITICAL 0 — proves no domain logic leaked into the gateway
bash ../scripts/routing-measurement-gate.sh  # must pass before shipping new/changed agent routing examples
bash ../scripts/verify.sh
```

That's the whole onboarding. If you found yourself editing gateway Java, something is in the
wrong place — it belongs in one of the files above.
