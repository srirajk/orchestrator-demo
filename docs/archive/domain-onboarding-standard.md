# Domain Onboarding Standard — retired

> **This document has moved.** Its content now lives, corrected and current, in
> **[`AGENT-ONBOARDING-HANDBOOK.md`](AGENT-ONBOARDING-HANDBOOK.md)** — the single canonical
> onboarding document.

This file previously described a domain/agent-manifest schema that **never shipped** (top-level
`connection.url`, `tool_name`, `capabilities` as a keyword array, `example_prompts`, a required
`max_response_tokens`, `vector + BM25` routing, an external memory service on the request path). A
newcomer copying its examples failed schema validation on the first try. Rather than leave two docs
disagreeing on the same facts, it was folded into the handbook and retired.

Where its still-valid content went:

| You were looking for… | Now in the handbook |
|---|---|
| Copy-paste onboarding walkthrough | §0 Quickstart |
| Domain manifest fields (`domain_context`, `clarify_style`, `coverage`, `memory_compaction`) | §3.5 + §14.2 |
| Sub-domain manifest fields + `entity_types` anatomy | §3.5 + §14.3 |
| Agent manifest fields (the **real** schema) | §3 + §14.1 |
| Coverage service contract — DISCOVER / CHECK / RESOLVE, reason codes, RESOLVE-is-principal-agnostic | §7.5 |
| The three authorization gates | §7 |
| What happens at ingestion + fail-loud validation | §8 |
| Failure-mode catalog (real error strings) | §13 |
| What the gateway never does (World B) | §1 + `.claude/rules/world-b.md` |

One correction worth calling out explicitly: coverage calls carry `Authorization: Bearer <the
forwarded end-user JWT>` (the caller's own token) plus `X-Tenant-Id` — **not** a
"gateway-service-token." See handbook §7.5.
