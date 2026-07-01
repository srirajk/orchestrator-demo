# Governed Memory + Context Envelope Architecture

> **Status:** Design scaffold. No gateway implementation in this lane.
> The gateway stays lean: it emits runtime events and consumes context envelopes. A separate
> memory service owns the compaction ledger, governed summaries, retention, and summary prompts.

---

## Non-Negotiable Split

The gateway does not summarize conversations and does not own long-term memory. It only:

1. Loads domain, sub-domain, and agent manifests.
2. Requests a compact context envelope for the current conversation turn.
3. Uses that envelope as generic context for extraction, routing, authorization, and synthesis.
4. Emits append-only runtime events after each meaningful pipeline step.

The memory service owns:

1. The append-only compaction ledger.
2. Domain-aware summary generation.
3. Summary versioning, retention, redaction, and replay.
4. Envelope assembly from manifests plus runtime gateway events.

This keeps World B intact: adding a domain changes manifests and external services, not gateway
Java. Domain field names, preservation rules, and user-facing copy remain manifest data.

---

## Contract Inputs

Governed memory is built from four sources. None require gateway domain logic.

| Source | Owner | What It Contributes |
|---|---|---|
| Domain manifest | Domain team | `memory_compaction` policy: envelope version, fields to preserve, fields to drop, summary budget, ledger retention. |
| Sub-domain manifest | Domain team | `entity_types`, `required_context`, `clarification_schema`, `resource_scoped`, and agent membership. |
| Agent manifest | Agent owner | `agent_id`, `domain`, `sub_domain`, skills, protocol, and `constraints.data_classification`. |
| Runtime gateway events | Gateway | Facts observed during the turn: extracted/resolved entities, coverage checks, selected agents, agent outcomes, synthesis completion. |

The memory service joins these sources by manifest identifiers (`domain`, `sub_domain`,
`agent_id`) and event metadata. It never infers domain semantics from gateway code.

---

## Domain Manifest Memory Contract

Domain manifests declare governance policy, not summaries.

```json
{
  "domain_id": "wealth-management",
  "display_name": "Wealth Management",
  "coverage": {
    "discover_url": "${WEALTH_COVERAGE_URL}/coverage/{principal_id}",
    "check_url": "${WEALTH_COVERAGE_URL}/coverage/{principal_id}/resources/{id}",
    "resolve_url": "${WEALTH_COVERAGE_URL}/entities/resolve",
    "cache_ttl_seconds": 30
  },
  "memory_compaction": {
    "envelope_version": "context-envelope.v1",
    "must_preserve": ["relationship_id", "client_name", "period", "domain"],
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

Rules:

- `owner` is always `memory-service`. The gateway may read this policy but must not execute
  compaction itself.
- `must_preserve` contains manifest-declared field keys or externally resolved labels that must
  survive compaction.
- `can_drop` names event payload fields the memory service should not copy into summaries.
- `include_runtime_events` names gateway event types that are eligible summary inputs.
- Domain-specific values are allowed here because manifests are the onboarding surface.

---

## Context Envelope V1

The context envelope is the only memory object the gateway consumes. It is compact, typed, and
free of raw transcript or raw agent output.

```json
{
  "schema_version": "context-envelope.v1",
  "envelope_id": "env_01J...",
  "conversation_id": "conv_123",
  "request_id": "req_456",
  "created_at": "2026-07-01T21:30:00Z",
  "manifest_version": "registry-sha256:...",
  "principal": {
    "principal_id": "rm_jane",
    "tenant_id": "default"
  },
  "scope": {
    "domains": ["wealth-management"],
    "sub_domains": ["private-banking"],
    "agents": ["acme.wealth.holdings"]
  },
  "context": {
    "entities": {
      "relationship_id": {
        "value": "REL-00042",
        "display": "Whitman Family Office",
        "source_event_id": "evt_101",
        "observed_at": "2026-07-01T21:28:10Z"
      }
    },
    "literals": {
      "period": {
        "value": "QTD",
        "source_event_id": "evt_102",
        "observed_at": "2026-07-01T21:28:10Z"
      }
    },
    "summaries": [
      {
        "summary_id": "sum_2026_07_01_001",
        "domain": "wealth-management",
        "sub_domain": "private-banking",
        "text": "The active client context is Whitman Family Office (REL-00042), period QTD.",
        "token_count": 21,
        "covers_event_seq": { "from": 1, "to": 36 },
        "created_at": "2026-07-01T21:29:00Z"
      }
    ]
  },
  "authorization_observations": [
    {
      "resource_key": "relationship_id",
      "resource_id": "REL-00042",
      "verdict": "allow",
      "checked_at": "2026-07-01T21:28:11Z",
      "expires_at": "2026-07-01T21:28:41Z",
      "source_event_id": "evt_103"
    }
  ],
  "ledger": {
    "last_event_seq": 36,
    "last_compaction_seq": 24,
    "watermark": "ledger:conv_123:36"
  }
}
```

Important: `authorization_observations` are continuity hints only. The gateway must still perform
the live coverage/Cerbos checks required by the manifests. Session or memory state is never an
authorization proof.

---

## Runtime Event Contract

The gateway emits append-only events. The memory service stores them in the ledger and decides
when to compact.

Required event shell:

```json
{
  "schema_version": "memory-ledger-event.v1",
  "event_id": "evt_103",
  "event_seq": 12,
  "conversation_id": "conv_123",
  "request_id": "req_456",
  "type": "gateway.coverage_checked",
  "occurred_at": "2026-07-01T21:28:11Z",
  "source": "conduit-gateway",
  "manifest_refs": {
    "domain": "wealth-management",
    "sub_domain": "private-banking",
    "agent_id": null
  },
  "payload": {
    "resource_key": "relationship_id",
    "resource_id": "REL-00042",
    "verdict": "allow",
    "expires_at": "2026-07-01T21:28:41Z"
  }
}
```

Initial event types:

| Event Type | Emitted When | Memory Use |
|---|---|---|
| `gateway.request_started` | A turn enters the gateway. | Starts turn boundary and idempotency scope. |
| `gateway.intent_classified` | Intent and candidate domains are known. | Tags summaries by routed domain. |
| `gateway.agents_resolved` | Candidate agents are selected. | Records agent manifest refs. |
| `gateway.entity_extracted` | LLM extraction produces references/literals. | Stores non-authoritative observations. |
| `gateway.entity_resolved` | Coverage resolver returns canonical IDs. | Updates envelope entity facts. |
| `gateway.coverage_checked` | Coverage CHECK returns allow/deny. | Records advisory auth observations and TTL. |
| `gateway.agent_completed` | An agent returns or fails. | Captures outcome metadata and summary-eligible facts. |
| `gateway.response_completed` | SSE answer completes. | Closes turn and may trigger compaction. |
| `gateway.compaction_requested` | Gateway detects token/window pressure. | Asks memory service to summarize; gateway does not summarize. |
| `memory.summary_written` | Memory service writes a governed summary. | Advances ledger watermark. |

Payloads are event-specific and may contain domain keys, but the event shell is stable.

---

## Memory Service API Boundary

The first implementation should live outside the gateway as its own service. The exact runtime
stack can be chosen later; this lane only defines the contract.

| Method | Path | Owner | Purpose |
|---|---|---|---|
| `POST` | `/v1/envelopes/resolve` | Memory service | Given conversation, request, principal, and manifest version, return the current context envelope. |
| `POST` | `/v1/events` | Memory service | Append one gateway runtime event idempotently. |
| `POST` | `/v1/compactions` | Memory service | Force or schedule compaction for a conversation range. |
| `GET` | `/v1/conversations/{conversation_id}/ledger` | Memory service | Audit/replay endpoint for operators, not request path. |

The gateway request path only needs `resolve envelope` and `append event`. Audit/replay stays out
of gateway code.

---

## Request Lifecycle

```
Turn N arrives
  |
  | 1. Gateway validates identity and loads manifest refs
  v
Gateway -> Memory Service: POST /v1/envelopes/resolve
  |
  | 2. Memory service returns compact Context Envelope V1
  v
Gateway uses envelope as generic context
  |
  | 3. Gateway performs manifest-driven extraction, resolution, CHECK, fan-out, synthesis
  |    The envelope can pre-fill context, but it cannot skip required authorization.
  v
Gateway -> Memory Service: POST /v1/events after each pipeline event
  |
  | 4. Memory service appends ledger entries
  | 5. If compaction is due, memory service summarizes using domain memory_compaction policy
  | 6. Memory service writes memory.summary_written and advances watermark
  v
Next turn receives a newer envelope
```

The gateway can proceed if event append is transiently unavailable only if the product explicitly
allows a degraded "no memory update" mode. It must never proceed on stale authorization if the
coverage service itself is unavailable.

---

## Compaction Rules

The memory service compactor must:

1. Read `memory_compaction` from the relevant domain manifests.
2. Use sub-domain `entity_types` and `required_context` to classify facts.
3. Use agent manifests to attach `agent_id`, `domain`, `sub_domain`, and data classification.
4. Include only runtime event payload fields allowed by `include_runtime_events`.
5. Preserve every available `must_preserve` field.
6. Exclude `can_drop` and `redact_fields` payload fields from summaries.
7. Write an append-only `memory.summary_written` ledger event with the covered event range.

The compactor summarizes agent outputs as facts already observed by the gateway. Agent outputs
remain untrusted DATA, not instructions.

---

## Build Order

1. Registry schemas for domain manifests, sub-domain manifests, context envelopes, and ledger events.
2. Registry validation tests that cross-check domain/sub-domain/agent references.
3. Memory service API contract and storage design.
4. Gateway adapter that calls `/v1/envelopes/resolve` and `/v1/events` behind an interface.
5. Compactor implementation in the memory service.
6. Replay/audit tooling for compaction ledger inspection.

This lane covers steps 1 and 2 plus the design contract for step 3.

---

## What Must Not Happen

```
No domain-specific compaction code in gateway Java.
No gateway-owned summary prompt per business domain.
No memory service authorization shortcuts.
No summaries built from raw agent output without manifest governance.
No use of session state or memory state as proof of access.
No domain onboarding that requires changing gateway Java.
```
