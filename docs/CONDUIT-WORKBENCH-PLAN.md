# Conduit Workbench + Axiom Control Plane Plan

> Status: implementation contract for the parallel worktree effort.
> Goal: turn Conduit from a branded chatbot demo into a premium enterprise AI gateway product.

## 1. Product Thesis

Conduit is not a generic chat UI. It is the governed execution surface for an enterprise AI
gateway: every answer is identity-aware, entitlement-checked, routed through specialist agents,
grounded in returned data, observable, and auditable.

Axiom is the governance and identity control plane. Conduit Workbench is the user-facing operating
surface. LibreChat can provide useful plumbing, but the product should feel native to
Meridian/Axiom rather than like a re-skinned open-source chat app.

## 2. Non-Negotiables

- Gateway stays lean: authenticate, resolve, authorize, route, fan out, synthesize, stream, emit
  events. It must not become a memory platform, file platform, admin UI backend, or domain logic
  dumping ground.
- World B remains intact: domain knowledge belongs in manifests, coverage services, and agent
  systems. No client names, ID patterns, domain literals, or user-facing domain copy in gateway
  Java.
- Axiom owns identity, tenants, roles, policy lifecycle, and audit.
- Conversation history can live in LibreChat/Mongo or a future Conduit chat store.
- Governed memory/compaction is a sidecar capability that consumes gateway events and produces a
  compact context envelope.
- Every UI claim should be backed by trace, policy, coverage, or agent result data.

## 3. Target Architecture

```text
Browser
  Axiom Login / Admin Console
  Conduit Workbench
    chat, projects, files, trace rail, context ledger

LibreChat-compatible shell/plumbing
  conversations, message storage, file UX, search, optional project primitives

Axiom IAM
  OIDC, JWT, users, teams, roles, policy lifecycle, audit

Conduit Gateway
  OpenAI-compatible API, SSE, routing, coverage checks, agent fan-out, synthesis, telemetry

Governed Context Service
  entity ledger, compaction policy, summaries, provenance, TTL/refetch rules

Observability / Eval
  Langfuse, OTel, traces, evals, drift, human review queues
```

## 4. UX North Star

The product should feel like a private-banking AI command center:

- dense, fast, keyboard-friendly;
- premium navy/gold trust language from the Axiom login;
- operational clarity from the current admin UI;
- trace-first transparency inspired by AI observability tools;
- finance-grade document/project workflows inspired by high-end research platforms;
- no generic SaaS-blue dashboard as the final brand;
- no toy chatbot framing.

Design tokens should converge around:

```text
navy        #060d1f
deep navy   #0c1a38
gold        #c9a54f
gold light  #e8c46a
smoke       #f4f6f9
muted       #8893a5
border      #dde3ed
error       #e5413c
radius      8-10px
font        Inter
```

## 5. Primary Surfaces

### 5.1 Axiom Login

Purpose: premium enterprise identity moment.

Must include:

- Meridian/Axiom brand mark;
- tenant/workspace language;
- trust badges: OIDC, RS256/JWKS, SOC/FIPS-style security cues;
- polished error, loading, and session-expired states;
- no default-password hints outside local/dev mode.

### 5.2 Conduit Workbench

Purpose: RM/banker daily operating surface.

Layout:

```text
Left rail: projects, conversations, files, saved workflows
Center:    streaming chat and answer cards
Right:     live trace rail / context ledger / sources
Top:       tenant, role, domain scope, gateway health, command menu
```

Core features:

- streaming chat against Conduit;
- conversation/project history;
- file/project context;
- trace rail with intent, entity extraction, coverage, agents, synthesis;
- context ledger showing carried entities, denied entities, files, period, refetch requirements;
- source/data cards for holdings, cash, settlements, policy, claims, etc.;
- explicit unavailable/denied/missing-data presentation.

### 5.3 Axiom Control Plane

Purpose: admins and platform operators govern AI access.

Surfaces:

- Dashboard: gateway health, auth failures, coverage denials, agent failures, eval trend.
- Users/Teams/Roles: identity and assignment, no stale book-of-business leakage.
- PolicyForge: generate, validate, explain, diff, test, promote policies.
- Effective Access Simulator: identity -> role -> domain policy -> resolve -> coverage -> decision.
- Audit Investigation: before/after state, correlated gateway trace, actor/session/resource.

### 5.4 Gateway Control Plane

Purpose: make the gateway inspectable.

Surfaces:

- Domain map: domains -> subdomains -> agents -> coverage services.
- Agent registry: protocol, health, classification, SLA, eval score.
- Coverage health: discover/resolve/check latency, error rate, denial reasons.
- Routing diagnostics: prompts, selected agents, skipped agents, confidence.
- Eval/drift: grounding, route correctness, denial correctness, latency/cost.

## 6. Governed Memory / Compaction

Do not build generic chatbot memory. Build governed conversation state.

Compaction output has two artifacts:

```json
{
  "structured_context": {
    "active_entities": [],
    "denied_entities": [],
    "verified_access": [],
    "open_questions": [],
    "files_in_scope": [],
    "domains_in_scope": [],
    "agent_capabilities_used": [],
    "must_refetch": []
  },
  "narrative_summary": "Human-readable summary of non-authoritative conversation context."
}
```

What drives compaction:

- tenant/project settings;
- domain manifest `memory_compaction`;
- subdomain `entity_types` and `required_context`;
- agent manifest capabilities, constraints, and `max_response_tokens`;
- runtime gateway events;
- file metadata and ACLs.

When to compact should be configurable:

```json
{
  "enabled": true,
  "max_messages": 30,
  "max_tokens": 24000,
  "target_context_headroom_pct": 35,
  "preserve_recent_turns": 6,
  "after_file_upload": true,
  "after_domain_switch": true,
  "manual": true
}
```

Critical rules:

- Explicit current-turn entities override prior session context.
- LLM may extract references; deterministic services resolve IDs.
- Access must be rechecked or backed by short TTL auth cache.
- Volatile financial values should be marked `must_refetch`, not treated as durable memory.
- Denied resources may be remembered as denied, but sensitive data must not be stored.
- Every compaction should be auditable.

## 7. Parallel Lane Ownership

| Lane | Branch | Worktree | Primary ownership |
|---|---|---|---|
| Auth | `codex-auth-librechat-oidc` | `/private/tmp/conduit-auth-librechat-oidc` | LibreChat token forwarding, conversation ID propagation, E2E auth path |
| Design | `codex-axiom-design-system` | `/private/tmp/conduit-axiom-design-system` | Axiom/admin UI visual system, premium login/shell, reusable tokens |
| Memory | `codex-governed-memory` | `/private/tmp/conduit-governed-memory` | Compaction policy, context envelope, sidecar design/schema/docs |
| Workbench | `codex-conduit-workbench` | `/private/tmp/conduit-workbench` | Chat workbench shell, trace/context panels, control-plane UI structure |
| Spec | `codex-product-ux-architecture` | `/private/tmp/conduit-product-ux-architecture` | Integration contract and review bar |

## 8. Review Bar

A lane is not merge-ready unless it can answer:

- What product problem did this solve?
- Which enterprise invariant did it preserve?
- What data is real vs placeholder?
- What auth path is being used?
- What was tested in terminal and browser?
- What screenshot or observable proof exists for UI changes?
- Did gateway changes pass `scripts/world-b-check.sh`?
- Did browser E2E prove Whitman allow and Okafor deny where relevant?

## 9. Testing Gates

Minimum gates by slice:

- `scripts/world-b-check.sh` whenever gateway Java changes.
- API smoke for gateway/OpenAI-compatible behavior.
- TypeScript build for UI changes.
- Java tests for gateway/Axiom/service changes.
- Browser run in Chrome/Brave/Safari for login, chat, and premium UI screenshots.
- Regression prompts:
  - Whitman overview: allowed and substantive.
  - Follow-up: sends after hero and uses correct context.
  - Okafor for `rm_jane`: resolves globally and denies explicitly.
  - New conversation: no stale entity context.

## 10. Critical Risks

- Forking LibreChat too deeply could create maintenance drag. Prefer targeted integration first.
- File/RAG paths must not bypass Conduit authorization.
- Axiom should not reintroduce book-of-business into JWT/principal state.
- Memory summaries must not become ground truth for financial values.
- Design polish without trace/context visibility would miss the product thesis.
- Gateway bloat would undermine the architecture.

## 11. First Integration Order

1. Auth/OIDC token forwarding and conversation ID path.
2. Product design tokens and premium login/shell.
3. Governed context envelope contract.
4. Workbench trace/context panels using real gateway events where available.
5. Access simulator and PolicyForge upgrades.
6. File/project governance and compaction settings.
7. Full browser QA and screenshots.

## 12. Merge Protocol

Merge lanes in this order unless review finds a blocker:

1. Product/UX architecture doc.
2. Auth/OIDC lane.
3. Design-system lane.
4. Memory contract lane.
5. Workbench lane.

Before merging a lane:

- inspect `git diff --stat` and every source diff;
- reject broad rewrites that do not serve the lane goal;
- reject any gateway domain coupling;
- reject UI that looks like generic admin CRUD without exposing gateway value;
- rerun the lane's stated tests;
- capture screenshots for UI lanes;
- record any known residual risk in the final merge note.

## 13. Reviewer Stance

Be skeptical. The standard is not "does it work on localhost"; the standard is whether a bank
operator would trust it with governed AI workflows.

Push back on:

- decorative UI that hides operational state;
- memory features that store stale financial facts as truth;
- RAG/file paths outside Conduit authorization;
- auth shortcuts that pass demos but fail real OIDC/JWT propagation;
- control-plane screens that duplicate Axiom instead of showing gateway-specific insight;
- implementation that makes LibreChat the product center of gravity.
