# Cerebrum

> OpenWolf's learning memory. Updated automatically as the AI learns from interactions.
> Do not edit manually unless correcting an error.
> Last updated: 2026-06-26

## User Preferences

- **Never hardcode values in the gateway** — everything must be `@Value` / `application.yml` / env vars. No `private static final` constants for config.
- **Never return canned data when LLM is unreachable** — return the actual error. No silent fallback.
- **Gateway must be production-grade** — not a mock, not a stub. Real resilience, real error propagation.
- **No client-specific domain data in the gateway** — no `REL_NAMES`, no client names, no relationship IDs hardcoded. Agent throws an error; Cerbos handles access. Gateway is domain-agnostic.
- **Debate before code** — user explicitly wants to agree on mental model and approach before any code is written. Do NOT start coding until user says "let's do it" or "proceed".
- **Short explanations** — user asks for brevity. Do not write essays. One table or 3-5 bullet points max per topic.
- **Eval tools:** DeepEval for offline/release-gate evals. Langfuse for continuous/experimentation. No Phoenix (removed).
- **Model selection for tasks:** Use `claude-haiku-4-5-20251001` for cheap/fast tasks (running tests, scripting, data processing, CLI invocations). Use Sonnet (default session model) for assessing screenshots, code review, design evaluation, and architectural decisions. Never use Opus for routine tasks.
- **User wants to learn** both DeepEval and Langfuse — frame explanations so they understand the tool, not just copy-paste code.
- **React/Vite production structure:** For non-trivial UI slices, user expects a feature module (`features/<slice>/api.ts`, `types.ts`, `hooks/`, `components/`, utilities) and a tiny route wrapper, not a giant `pages/*.tsx` file or root-level feature API.
- **Onboarding product boundary (2026-07-12):** The user wants a guided onboarding/admission agent for teams that already own an agent or service, not an agent builder. Teams provide requirements, a test URL, examples/golden evidence, and confirm plain-language business/security decisions; they never edit Conduit JSON/YAML. The confirmed structured dossier is the source of truth, and a deterministic compiler generates manifests/eval assets behind the scenes. The system tests readiness, reports gaps, and requires approval before activation.
- **Onboarding Studio architecture decision (2026-07-12):** User wants onboarding as a separate Studio with a real UI, access control, approvals, and environment promotion. After inspecting Java condition, JMESPath, DAG, bounded-map, concurrency and validator paths, choose a Java/Spring Studio control plane plus React UI. Extract static admission into a shared Java module consumed by Studio and gateway; live execution and registry mutation remain gateway-owned. Use bounded OpenAI structured inference behind a Java port, not the Agents SDK in v1. The model must never have an approval or production activation operation.

## Key Learnings

- **Routing precision audit (2026-07-11):** `entityKnown` bypasses both contextual domain-margin and general min-score/min-margin abstention; it can be set by mere syntactic reference presence, not only successful allowed grounding. Post-entitlement correctness must preserve the requested primary capability/DAG goal, not merely any survivor in the same domain. The goal-pick harness ignores row personas, excludes cross-agent confusers from its release gate, and does not exercise the contextual/entitlement chat path.
- **Project:** Enterprise AI gateway for a bank (Meridian brand on LibreChat). RM types plain-English; gateway routes to 9 specialist agents across HTTP (FastAPI/Wealth) and MCP (Asset Servicing), enforces Cerbos ABAC, synthesizes one grounded answer streamed back.
- **Governed memory scaffold (2026-07-01):** Context compaction belongs outside the gateway. The gateway emits runtime events and consumes `context-envelope.v1`; an external memory service owns the append-only ledger, summaries, redaction/retention, and manifest-driven compaction policy. Domain/sub-domain/agent manifests plus gateway events are the inputs; no domain-specific memory logic belongs in gateway Java.
- **Stateless gateway implemented (2026-07-02):** Gateway chat memory is gone. `ConversationSession`/`ConversationSessionStore` were deleted; `conversationId` is trace grouping only. The gateway derives entity context from client-sent `messages[]` + JWT every turn, latest explicit user mention wins, prior user mentions can seed fresh follow-ups, assistant text never seeds extraction, and coverage/entitlement is re-run every turn.
- **Conduit TEST-KIT validation status (2026-07-02):** `scripts/smoke.sh` is the reliable current API smoke and passed 20/20 on the pristine `feat/conduit-chat` env. Follow-up fix verification passed: `scripts/smoke-ui.sh` 16/16 green, admin console `:5182` browser login succeeds, and `tests/e2e` 00-login + 01-branding now target canonical `:8099` Axiom OIDC and pass 11/11. Closing the browser mid-stream no longer logs `ERROR post-stream persistence failed`; a single interrupted-stream WARN remains expected/observed.
- **Axiom admin UI visual system (2026-07-01):** `admin-ui/tailwind.config.js` defines the premium navy/gold token layer (`axiom-*`, `gold-*`, `canvas`, `panel`, `line`, `ink-*`) and keeps `brand-*` aliased to Axiom navy for older components. Reusable direction lives in `admin-ui/src/index.css` (`surface-card`, `surface-panel`, `page-shell`, `page-kicker`, `section-heading`, `muted-copy`, `axiom-mark`) and `admin-ui/DESIGN-SYSTEM.md`.
- **Stack:** Java 21 + Spring Boot 3.5 + virtual threads (gateway); Python FastAPI + FastMCP (mock agents); Redis Stack (registry + sessions); Cerbos PDP (authz); Langfuse (evals); Docker Compose.
- **Agents:** 9 total — 4 Wealth HTTP agents (holdings, performance, goal-planning, risk-profile), 5 Asset Servicing MCP agents (custody, settlements, corporate-actions, cash, nav). All return canned data from `canned_data.py` keyed by `relationship_id`.
- **Golden dataset source:** `mock-agents/wealth/shared/canned_data.py` and `mock-agents/servicing/shared/canned_data.py`. 3 relationships: REL-00042 (Whitman), REL-00099 (Calderon Trust), REL-00188 (Okafor Family Account). These are the ONLY ground truth values for evals.
- **Harness bulkhead:** Dual Semaphore + `newVirtualThreadPerTaskExecutor()`. No Resilience4j BulkheadRegistry. `queueSlots` semaphore = instant reject when backlog full; `executingSlots` semaphore = VT parks cheaply. `boolean acquiredExecuting` flag prevents permit leaks on cancellation.
- **Circuit breaker:** Resilience4j CircuitBreaker only — kept for open/half-open state machine. All other R4j components removed from harness.
- **application.yml:** Single `llm:` block. Had a duplicate key bug (last-wins in SnakeYAML silently dropped first block). Fixed — all per-component LLM config (intent-classifier, entity-extractor, synthesizer base-url/model/api-key) plus retry/timeout config lives in one `meridian.llm:` block.
- **Guardrail vs Eval separation:** Guardrail = runtime enforcement, blocks NOW, zero LLM cost. Eval = offline/async LLM-as-judge, scores quality after the fact. Never mix — LLM-as-judge at runtime = 2-3s latency, unacceptable.
- **Eval mental model (three layers):** Layer 1 = agent built right (guardrails, grounding, observability). Layer 2 = DeepEval release gate (golden dataset + LLM-as-judge = agent certification before ship). Layer 3 = Langfuse continuous (criteria-based judge on every live trace, no expected answer needed).
- **Capability register / relationship routing:** The capability register does NOT need to know which relationships an agent serves. Data availability is the agent's concern (returns 404 if relationship unknown). Access control is Cerbos's concern. Gateway stays domain-agnostic.
- **World B manifest-driven input pipeline (Wave 2):** Sub-domain manifests (`registry/domains/*/*.json` + test mirror `gateway/src/test/resources/domains/*/*.json`) declare `entity_types[]` with `{key, extract_as, kind(resolvable|literal|list), display, id_pattern, resolve_type, required, default}`. `DomainManifestStore.entityTypes()` returns the dedup-by-key UNION across all sub-domains; `clarificationFor(key)` finds the clarification question. `EntityBag` is GENERIC (maps: references[extract_as], lists[extract_as], resolved[key]) — no wealth fields; accessors `reference()/resolved()/list()/withReference()`. `EntityExtractor`/`EntityResolver`/`InputSynthesizerImpl` all loop over `entityTypes()` — zero hardcoded `relationship_reference`/`fund_id`/`REL-`/`QTD`. The resolvable entity binding (relationship/fund) comes from manifest, read via `manifestStore.entityTypes().filter(isResolvable)` — never hardcode entity keys in Java. `world-b-check.sh` is the gate (was 67 CRITICAL at lockdown).
- **SubDomainManifest record:** adding a field breaks hand-built test fixtures (7-arg ctor in EffectiveManifest*Test). Keep a backwards-compatible secondary constructor delegating with `List.of()` so existing tests compile unchanged; Jackson still binds via the canonical all-args ctor.
- **Carved-out (separate World B passes, still hold CRITICAL flags):** IntentClassifier/AnswerSynthesizer SYSTEM_PROMPT text (prompt-compile pass), ChatService user-facing coverage copy (clarification-message pass / step 5), ConversationSessionStore `"relationship_id"`/`"fund_id"` Redis storage keys (acceptable storage-schema detail), EntityResolver `crm.wealth.url` @Value (URL-from-manifest = step 9).

## Cerbos Policy Rules (Critical — re-read before touching any .yaml policy)

### Roles + derivedRoles in the same rule are OR'd, not AND'd
Cerbos evaluates `roles` and `derivedRoles` listed inside a single rule with **OR semantics**.
A principal matching EITHER the `roles` list OR the `derivedRoles` list will trigger the rule.

```yaml
# DANGEROUS — looks like AND but is OR:
- actions: ["create", "read"]
  effect: EFFECT_ALLOW
  roles: ["tenant_admin"]
  derivedRoles: ["same_tenant"]   # any principal with same_tenant ALSO matches!
```

**Correct pattern for tenant-scoped rules:** use ONLY derivedRoles, never both:
```yaml
- actions: ["create", "read"]
  effect: EFFECT_ALLOW
  derivedRoles: ["same_tenant"]   # same_tenant.parentRoles encodes the role requirement
```

And encode the role requirement inside the derived role's `parentRoles`.

### `parentRoles: ["*"]` in a derived role grants it to every principal
If a derived role has `parentRoles: ["*"]`, every authenticated principal acquires it
(subject to any `condition`). This is almost never what you want for access-control derived
roles. Scope `parentRoles` to the exact base roles that should be eligible.

```yaml
# WRONG — grants same_tenant to auditors, policy_authors, etc.:
- name: same_tenant
  parentRoles: ["*"]
  condition:
    match:
      expr: "P.attr.tenant_id == R.attr.tenant_id"

# CORRECT — only tenant_owner and tenant_admin can be "same_tenant":
- name: same_tenant
  parentRoles: ["tenant_owner", "tenant_admin"]
  condition:
    match:
      expr: "P.attr.tenant_id == R.attr.tenant_id"
```

### Explicit DENY (`EFFECT_DENY roles: ["*"]`) overrides every ALLOW, including platform_admin
A catch-all deny rule defeats purpose-specific allow rules entirely. Cerbos has **implicit
deny by default** — any action not explicitly allowed is denied. Never add a catch-all deny.

### Auditor pattern — roles-only + inline tenant CEL
Auditors are not in `same_tenant.parentRoles`. Encode their tenant isolation as a
condition on the rule itself:
```yaml
- actions: ["read", "list", "export"]
  effect: EFFECT_ALLOW
  roles: ["auditor"]           # roles only, no derivedRoles
  condition:
    match:
      all:
        of:
          - expr: "P.attr.tenant_id == R.attr.tenant_id"   # inline isolation
          - expr: "R.attr.resource_type == 'audit_log'"
```

### Cross-tenant isolation only works when the rule uses derivedRoles (not roles)
If a rule lists `roles: ["tenant_admin"]`, it fires for tenant_admin regardless of tenant.
Cross-tenant isolation only works when access is gated through a derived role whose
condition checks `P.attr.tenant_id == R.attr.tenant_id`.

### Cerbos host port mapping in this project
Docker maps container port 3592 → **host port 3594**. Use `localhost:3594` in tests and
client code, not 3592.

### Axiom B2 — base/tenant scoped model (delivered 2026-07-17)
- **The gateway sends NO `tenant_id` and NO `scope` to Cerbos** (`CerbosEntitlementAdapter.addPrincipal`
  sends only `segments`/`domains`/`admin_domains`; resources carry no scope). So every gateway
  Cerbos call hits the **base scope ("")**. This is WHY the tenant-equality backstop is parity-neutral
  today and why `agent`/`relationship`/`domain`/`insights` decisions are byte-identical after scoping.
  (IAM checks DO send `tenant_id`; only `iam-resource` uses it.)
- **Cerbos 0.53 `scopePermissions` quirk (VERIFIED):** setting `scopePermissions:
  SCOPE_PERMISSIONS_OVERRIDE_PARENT` EXPLICITLY on **more than one root-scoped ("") resource
  policy** raises a false `"scope permission conflicts"` compile error — even when the value is
  identical on both. Fix: LEAVE the root's `scopePermissions` UNSET (Cerbos default for a root IS
  OVERRIDE_PARENT). Empirically reproduces B1's exact posture (ceiling holds, silent child
  fall-through, strict-search absent-scope deny). Only TENANT children set REQUIRE_PARENTAL_CONSENT.
- **Tenant-equality backstop, parity-safe form:** `(has(P.attr.tenant_id) && has(R.attr.tenant_id))
  ? P.attr.tenant_id == R.attr.tenant_id : true` — TRUE when either side lacks tenant_id (single-tenant
  world → parity), denies cross-tenant once both present. Lives in `business_derived_roles.yaml`.
- **`platform_admin` is the documented cross-tenant SUPERUSER** — raw-role ALLOW, exempt from the
  tenant-equality lint (`scripts/cerbos-allow-tenant-equality-lint.py` CROSS_TENANT_SUPERUSER set),
  matching the pre-existing iam_resource.platform_admin posture.
- **Default-tenant TOTAL child pattern:** grant every base-ceiling (action, role) tuple UNCONDITIONALLY
  under REQUIRE_PARENTAL_CONSENT → effective = base decision (parental consent gates it). Reproduces
  today AND is total over the ceiling (totality lint passes). Fresh tenants use the deny-all template.
- **B2 harness:** `scripts/cerbos-parity-run.sh` (PRE vs POST 800-cell diff — THE gate),
  `scripts/cerbos-parity-matrix.py`, `scripts/cerbos-allow-tenant-equality-lint.py` (B2.3),
  `scripts/cerbos-tenant-totality-lint.py` (extended to expand derivedRoles→parentRoles). Run cerbos
  containers on DISTINCT names/ports (36xx) — never touch `conduit-cerbos` (:3594).

---

## Key Learnings (Phase 12 additions)

- **Principal record field order (Phase 12+, revised 2026-06-29):** `Principal(id, tenantId, roles, clearance, adminDomains, segments, domains)`. **NO `book` field** — book was removed as part of three-layer auth architecture. The `tenantId` is the SECOND field. Any test constructing a Principal directly needs `"default"` as the 2nd arg.
- **DomainManifest (Phase 12+):** No `entityRegistry()` / `authorizationContract()` fields. Has `coverage()` returning `Coverage(discoverUrl, checkUrl, resolveUrl, cacheTtlSeconds)` and `displayName` (not `name`).
- **SubDomainManifest (Phase 12+):** Has `resourceScoped()` boolean instead of `clarificationPriority()`. `ClarificationSchema` has `priority` int field and `default` (not `default_value`).
- **EffectiveManifest (Phase 12+):** Has `coverage()`, `resourceScoped()`, `requiresRelationship()`, `relationshipClarification()`. Removed: `authorizationContract()`, `entityRegistry()`, `clarificationPriority()`.
- **Phase 12 test audit result:** All 44 Java tests pass, TypeScript compiles clean after Phase 12. Tests were updated in the same commit as the source change — no lag between source and test updates.
- **Conduit Workbench UI slice (2026-07-01):** Keep this in `admin-ui` React/Tailwind and add a separate `/gateway-api` proxy for gateway calls. Existing real gateway endpoints support trace SSE/health (`/trace/*`), domain registry (`/admin/domains`), agent registry (`/admin/agents`), and non-streaming chat completions (`/v1/chat/completions`). There is no per-agent liveness endpoint yet, so UI health should label registry/index status as real and liveness as placeholder.
- **Workbench React module structure (2026-07-01):** Workbench lives under `admin-ui/src/features/workbench/`; route wrapper is `pages/Workbench.tsx`. Trace streaming uses a fetch-based SSE reader so Authorization headers can be sent; native `EventSource` is not suitable for future authenticated trace endpoints. `VITE_GATEWAY_API_BASE` is a non-secret optional proxy/base-path override and needs `src/vite-env.d.ts`.
- **Glass-box authz gate trace (2026-07-03):** The gateway emits ordered `gate` trace frames `{type:'gate', data:{gate,effect,reason,agent}}` for audience→segment→classification→coverage, alongside the existing `check_denied`. Structural gate verdicts come FROM Cerbos, not recomputed in Java: `CerbosEntitlementAdapter.checkAgentMembership` runs a membership-only `invoke_membership` probe action (added to `infra/cerbos/policies/agent_resource.yaml`) so the gateway distinguishes a segment miss (`invoke_membership` deny) from a classification miss (member but `invoke` deny) WITHOUT knowing the domain→segment mapping. `EntitlementService.explainStructuralGates` builds the frames; `ChatService.handleFetchData` publishes them + coverage allow/deny frames. Web rail: `apps/chat/web/src/components/TraceRail.tsx` + `hooks/useTraceStream.ts`; BFF proxy `GET /api/conversations/{id}/trace/stream` (`TraceController`) pipes the gateway's public `/trace/stream` gated by BFF session.

## Do-Not-Repeat

- **[2026-07-03] The domain→segment mapping lives ONLY in the Cerbos policy — never recompute it in gateway Java:** JWT `segments` are keyed by short segment name (`wealth`, `servicing`) while agent manifests declare `domain` (`wealth-management`, `asset-servicing`). Deciding segment membership in the gateway by `principal.segments().containsKey(manifest.domain())` is both WRONG (keys never match) and a World-B violation. For the glass-box gate trace, source verdicts from Cerbos (add an `invoke_membership` probe action) so the mapping + rank ladder stay in policy config.
- **[2026-07-03] apps/chat/web cannot import monorepo packages/ at Docker build time:** The `conduit-chat` image (`apps/chat/Dockerfile`) uses build context `./apps/chat`, so `packages/gateway-client` is outside the image context and a `../../../packages` Vite/tsconfig alias breaks the in-image `npm run build` (works locally, fails in Docker). Vendor the small reused surface into `apps/chat/web/src/lib/` (mirror of the package) instead of aliasing across the context boundary.

- **[2026-07-01] Admin UI DesignQC in sandbox needs dependencies + escalation:** In `/private/tmp/conduit-axiom-design-system/admin-ui`, `openwolf designqc` auto-start fails when `node_modules` is missing (`vite`/`tsc` unavailable), and Vite/browser launch can fail under sandbox (`listen EPERM` or Puppeteer launch failure). Fix: run `npm ci`, start `npm run dev -- --host 127.0.0.1` with escalation if port listen is blocked, then run `openwolf designqc --url http://127.0.0.1:<port> ...` with browser escalation. Unauthenticated `/` captures redirect to login unless a valid `meridian_admin_token` is seeded.
- **[2026-07-01] Do not leave Workbench/control-plane UI as one giant page:** The user rejected the initial root-level `pages/Workbench.tsx` + `src/api/workbench.ts` shape as not mergeable. Split non-trivial slices into feature modules with typed API boundaries, hooks, utilities, and focused components; keep pages as route wrappers.
- **[2026-07-01] Admin UI JWT storage must be centralized:** `useAuth` and API clients drifted between `meridian_admin_token` and `conduit_admin_token`, causing requests to omit Authorization after login. Use `admin-ui/src/auth/tokenStorage.ts`; keep legacy fallback there only.
- **[2026-07-01] QA playbook browser run can render the answer but leave LibreChat send disabled:** In the §2 SSO/browser path, the hero answer visibly rendered, but `#send-button` stayed disabled beyond 180s, preventing the follow-up. Post-rebuild logs showed 5/5 fan-out succeeded and synthesis began; investigate SSE `[DONE]` delivery / LibreChat generation completion state / summarize-title behavior before declaring browser chat green. Pre-rebuild, servicing MCP also emitted Starlette `AssertionError: Unexpected message: http.response.start` and the gateway timed out cash/settlement, causing an incomplete hero answer.
- **[2026-07-02] Do not reintroduce gateway conversation memory:** Memory/compaction belongs in the client/chat layer, not the gateway. No cross-turn Redis/session store, no `conversationId` memory key, no gateway summaries. `messages[]` is the context contract; run coverage CHECK every turn.
- **[2026-07-01] Pytest may fail in sandbox before collection because pytest_rerunfailures binds localhost:** `python3 -m pytest tests/schema` hit `PermissionError: [Errno 1] Operation not permitted` during plugin setup. Rerun important pytest commands with approved unsandboxed execution when this exact plugin/socket failure appears.
- **[2026-07-01] Registry schema tests caught NAV sub-domain drift:** `acme.servicing.nav` was listed under `corporate-actions` but declared `sub_domain: cash-management`. Existing docs/sub-domain membership put NAV under corporate actions; fix the agent manifest instead of relaxing the cross-reference check.
- **[2026-06-28] Duplicate SSE role delta breaks LibreChat send button:** If ChatService.handleChat sends a role delta up-front (chatcmpl-A) and then streamTextAndComplete sends a SECOND role delta with a fresh newId() (chatcmpl-B), LibreChat tracks both in-flight completions and never re-enables the send button when chatcmpl-A receives no [DONE]. Fix: do NOT send a role delta up-front in handleChat; instead send it at the start of each synthesizer method (synthesize/synthesizeFromHistory) and let streamTextAndComplete's own role delta stand alone. Each SSE stream should have exactly one completionId throughout.
- **[2026-06-28] Redis principal store must be seeded before gateway starts:** The gateway's PrincipalStore reads from Redis hashes at principal:{userId}. If these are absent (e.g. after a Redis restart), every user falls back to Principal.anonymous() which has empty segments → Cerbos denies all agents → every query returns "You do not have access". Fix: run scripts/seed-users.sh before running E2E tests.
- **[2026-06-28] registerOrLogin() must handle re-registration redirect to /login:** If the TEST_EMAIL already exists, LibreChat's register form redirects back to /login. waitForURL(/\/(c\/|login)/) matches /login and the function returns while still on the login page. Fix: after registration attempt, try waitForURL('**/c/**') first; if that times out, do a fresh login attempt.

- **[2026-06-26] Duplicate `llm:` key in application.yml:** YAML last-wins — second `llm:` block silently overwrote first, losing all per-component LLM config. Fix: single `llm:` block, merge all sub-keys into it.
- **[2026-06-26] Hardcoded constants in gateway:** User will immediately call this out. Every timeout, retry count, TTL, threshold → `@Value` with env-var override. No exceptions.
- **[2026-06-26] Canned data fallback when LLM unreachable:** Never do this. Return the error. User repeated this rule ~100 times.
- **[2026-06-27] Cerbos roles+derivedRoles are OR, not AND:** In a single rule, listing both `roles` and `derivedRoles` means a principal matching EITHER is granted. A derived role with `parentRoles: ["*"]` therefore grants every principal that match to EVERY rule that lists it — including admin-level CRUD rules. Fix: use ONLY `derivedRoles` in tenant-scoped rules, scope `parentRoles` tightly, use inline CEL conditions for roles (like auditor) that shouldn't be in any derived role.
- **[2026-06-27] Explicit DENY catch-all overrides platform_admin:** `roles: ["*"] effect: EFFECT_DENY` overrides ALL allow rules, including platform_admin. Cerbos denies by default — never add a catch-all deny.
- **[2026-06-27] Audit log `resourceId` is UUID, not name:** When filtering audit log entries by policy, use `created.id` (UUID), not `created.name`. The audit service stores the entity ID as `resource_id`.
- **[2026-06-26] AgentHarness constructor arity mismatch in tests:** When adding `@Value` params to constructor, update test helpers immediately. Use a `harness()` helper method with defaults so tests don't break on every param addition.
- **[2026-06-26] ThreadPoolBulkhead with virtual threads:** ThreadPoolBulkhead uses a platform thread pool — conflicts with virtual thread model. Use dual Semaphore + `newVirtualThreadPerTaskExecutor()` instead.
- **[2026-06-26] Permit leak — queued semaphore double-release:** `queued.release()` already runs inside VT after `executing.acquire()` succeeds. Releasing it again in the outer catch = semaphore count exceeds initial value (leak). Only release `queued` in catch if `cause instanceof InterruptedException` (the one case where the VT was interrupted before the internal release ran).

- **[2026-06-29] Three-layer auth: NO book in JWT, Principal, Cerbos, or Redis:** Book-of-business (which RMs cover which clients) is enforced ONLY by the domain coverage service (DISCOVER/CHECK/RESOLVE endpoints). Never put `book` in: JWT claims, Principal record, Cerbos policy attributes, Redis principal hashes, or seed scripts. Cerbos relationship_resource.yaml is role-only now. PrincipalStore reads no book field. seed-users.sh contains no book field.
- **[2026-06-29] Matcher.appendReplacement() silently parses replacement as regex:** When the env var fallback in `resolveEnvVars()` returned `m.group(0)` unquoted (e.g. `${WEALTH_COVERAGE_URL}`), `appendReplacement` treated `${...}` as a named back-reference and threw `IllegalArgumentException: named capturing group is missing trailing }`. Fix: ALWAYS wrap replacement strings in `Matcher.quoteReplacement()`, including fallback/passthrough values.
- **[2026-06-29] `@SpringBootTest` context fails when env vars are absent:** DomainManifestStore.load() runs at startup and resolves env var placeholders in domain manifests. If `WEALTH_COVERAGE_URL` etc. are unset (typical in unit test context), the unquoted `${...}` fallback crashed. Fix: `Matcher.quoteReplacement()` in resolveEnvVars() makes context loadable even with missing env vars.

## Axiom — What Was Built (CLOSED — do not reopen unless bug reported)

**Product name: Axiom** — AI-powered access governance platform.
**Feature name: PolicyForge** — the AI policy generation capability inside Axiom.
**Tagline:** "AI-powered access governance for the enterprise."

### IAM Service — What Was Built

The `iam-service` (Spring Boot 3.5, Java 21, PostgreSQL) is complete. Do NOT revisit unless
a specific bug is reported. Here is what it does:

**Auth:** Spring Authorization Server issues RS256 JWTs with `roles` claim.
  Clients: `gateway-client` (m2m), `admin-ui-client` (PKCE), `librechat` (OIDC).
  Login: `POST /auth/login` → `{accessToken, refreshToken}`.

**Identity:** `Principal` entity — username, email, bcrypt password, roles (many-to-many),
  teams, domains, tenant. No `book` field — book-of-business enforced by domain coverage service at runtime.

**RBAC:** `Role` entity — named roles (platform_admin, tenant_admin, domain_admin, auditor,
  policy_author, policy_approver, rm, junior_rm). Assign/remove via `POST /users/{id}/roles`.

**Policy lifecycle + LLM generation:**
  `POST /admin/policies/generate` — sends the PolicyIntent (structured form OR free-text `intent`)
  through `LlmPolicyGenerationService`, which calls Z.AI GLM-4.5 with a rich system prompt:
    - 6 enterprise IAM principles (Okta FGA / OPA / Cerbos model)
    - 5 Cerbos evaluation rules (especially the OR trap)
    - Data classification ladder (internal / confidential / confidential-pii / restricted)
    - Live roles + tenants injected from DB at call time
    - 3 few-shot examples (agent, relationship, iam-resource patterns)
  Returns `{yaml, explanation, warnings, valid, errors}`.
  Draft saved via `POST /admin/policies`, promoted via `PUT /admin/policies/{id}/status`.

**Audit log:** Every write emits an `AuditLog` row — actor, action, resource_type,
  resource_id, before/after state (JSONB), IP, correlation_id.

**Cerbos authz:** `CerbosAuthzService` + two policy files:
  `iam_resource.yaml` (RBAC matrix) + `relationship_resource.yaml` (book-based entitlement).

**Spring Security:** `@EnableMethodSecurity` + `JwtGrantedAuthoritiesConverter` (maps `roles`
  JWT claim → `ROLE_` authorities) → `@PreAuthorize` on controllers.

**Seed data:** V1 (schema) + V2 (rm_jane, rm_carlos, auditor_sarah, admin, etc. with books).

**Admin UI:** Dashboard, Users, Teams, Roles, Policies (with AI generate), Audit Log.

**Config keys:**
  ZAI_API_KEY — required for policy generation (GLM-4.5)
  IAM_POLICY_GENERATION_ENABLED — default true
  IAM_POLICY_GENERATION_MODEL — default glm-5.2 (Z.AI flagship model)
  Cerbos host port: container 3592 → host 3594

---

## Decision Log

- **[2026-07-01] Governed memory boundary:** Memory/compaction is an external service, not a gateway feature. Gateway responsibilities are limited to requesting compact context envelopes and emitting runtime ledger events. `memory_compaction.summary_policy.owner` is pinned to `memory-service`; domain compaction policy lives in manifests and is validated by schema tests.
- **[2026-06-26] Eval stack: DeepEval + Langfuse (no Phoenix).** Phoenix was removed. DeepEval = offline release gate (certification path: golden dataset + LLM-as-judge before agent goes live). Langfuse = continuous eval + experimentation (criteria-based judge on live traces + compare scores across builds/models).
- **[2026-06-26] Guardrails are in-code, not LLM-as-judge at runtime.** Grounding check (every number in answer must appear in agent output) runs synchronously. Blocks bad output with zero added latency. LLM-as-judge is eval-time only.
- **[2026-06-26] Dual Semaphore bulkhead chosen over R4j ThreadPoolBulkhead.** Virtual threads park cheaply on `Semaphore.acquire()` (unmount from carrier, ~KB). Platform thread pool (~1MB/thread) would negate the VT advantage. R4j CircuitBreaker kept; everything else removed.
- **[2026-06-26] Gateway is domain-agnostic.** No client/relationship data in gateway. Agent returns 404 for unknown relationship_id. Cerbos controls access. This keeps the gateway reusable across business domains.
- **[2026-06-27] Axiom IAM service — CLOSED (final name: Axiom / PolicyForge).** Platform: Axiom. AI generation feature: PolicyForge. LibreChat OIDC SSO wired (client: meridian-librechat, issuer: host.docker.internal:8084). All client creds now @Value-driven from env vars. 28/28 Cerbos E2E passing. 35/35 golden dataset passing. README at iam-service/README.md. Do NOT reopen unless a specific bug is reported.
- **[2026-06-27] Policy generation uses GLM-5.2 (Z.AI flagship model).** PolicyService.generatePolicy() accepts EITHER a structured PolicyIntent (from UI form) OR free-text {intent, resourceType}. buildIntentFromStruct() converts the form fields to a natural language prompt.
- **[2026-06-26] Agreed work order (not yet executed):** 1. User account seeding (rm_jane + other principals). 2. Golden dataset from canned_data.py. 3. Eval suite + proper guardrails (grounding should block, not warn). 4. Langfuse continuous eval setup.
- **[2026-06-26] verify.py compliance checker exits 0 ONLY at 100% compliance.** `sys.exit(0 if score == 1.0 else 1)`. Any gap causes a non-zero exit. Standard error schema pattern: `agent_id.*trace_id|trace_id.*agent_id|ErrorResponse`. Structured logging pattern: `convId|conv_id|conversationId|traceId|trace_id`. Wealth agents initially scored 71% — fixed by adding `agent_id`+`trace_id`+`status_code` to fault_knob error response and `trace_id`+`convId` to JWT rejection log line.
- **[2026-06-26] Pass 2 deliverables location:** eval/eval_deepeval.py (extended), eval/langfuse_continuous.py (NEW), eval/prompts/ (5 NEW contracts), .claude/skills/meridian-agent/SKILL.md + scripts/verify.py (NEW). eval/requirements.txt has both deepeval and langfuse.
- **[2026-06-26] Pass 3 final state:** verify.py 7/7 on wealth agents (100%). eval/langfuse_seed_datasets.py and eval/langfuse_run_experiment.py both parse cleanly. eval/golden-prompts.json has 3 items (REL-00042, REL-00099, REL-00188). Langfuse experiment script entry point: `python3 eval/langfuse_run_experiment.py --run-name baseline --dry-run`.

### Do-Not-Repeat — 2026-06-30 — LibreChat OIDC SSO with Spring Authorization Server
- LibreChat's `openid-client` authenticates to the token endpoint with **`client_secret_post`**
  (secret in the body), NOT HTTP Basic. A Spring AS `RegisteredClient` must declare
  `ClientAuthenticationMethod.CLIENT_SECRET_POST` or the token call fails with
  *"Client authentication failed: authentication_method"* → `invalid_client` → LibreChat shows
  *"server responded with an error in the response body"* (generic; useless on its own).
- To debug an opaque LibreChat OIDC failure, turn on `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_SECURITY=DEBUG`
  on the **iam-service** (Axiom) — Axiom's log states the real reason. LibreChat's
  `DEBUG_OPENID_REQUESTS` did NOT surface the error body.
- LibreChat needs **`email`** (and ideally `name`) to provision the SSO user. Spring AS default
  userinfo maps from the **id_token** claims, so enrich the **ID_TOKEN** (not just the access
  token) — `email`, `email_verified`, `preferred_username`, `name`.
- Validate browser OIDC flows headlessly by replicating them in curl with ONE cookie jar
  across both hosts: `GET /oauth/openid` (capture authorize URL + LibreChat PKCE cookie) →
  login → re-GET the authorize URL for the code → GET the callback so LibreChat does the
  real exchange. Avoid the `&continue` resume (curl drops JSESSIONID → 400); re-GET the
  original authorize URL instead.

### User Preferences — 2026-07-01 — Concurrency & model discipline
- Work in PARALLEL: run long jobs (e2e, builds) in the background while doing other work; batch independent tool calls.
- Use AGENT TEAMS (subagents) for independent, parallelizable work streams — don't do everything serially.
- Match the MODEL to the job: mechanical/low-reasoning subagents (queries+classify, small deterministic edits, log/API investigation, greps) → Sonnet (Haiku for trivial). Reserve Opus/high-effort for genuinely hard reasoning. Do NOT default every subagent to the inherited Opus model.

### Decision Log — 2026-07-01 — Langfuse observability model
ONE Langfuse project + per-domain TAGS + curated per-domain datasets/dashboards — NOT project-per-domain. Per-domain drift/experiments need domain-tagged traces + datasets, not separate projects (those are for org isolation only). Per-domain OTel/gateway routing deferred (complexity). Load-bearing requirement: every trace carries its resolved `domain` tag. Serves the World B demo (onboard by manifest → observability lights up).

### Decision Log — 2026-07-01 — Gateway auth model + deferral
JWT/Axiom is the real public API path (gateway extracts principal from JWT claims, ~1ms, no Redis — verified). X-User-Id + Redis principal store is a LibreChat-only trusted-hop fallback (LibreChat can't forward the SSO JWT). KNOWN HOLE: /v1/chat/completions is permitAll; no-identity -> "anonymous" -> processed (200); unknown/forged X-User-Id accepted; safety rests on network isolation only. DEFERRED: harden to JWT-only + reject anonymous/unknown + drop Redis seed — to be done during the planned LibreChat rewrite (new UI forwards JWT). Keep Redis seed (provisioner) until then.

### Do-Not-Repeat — 2026-07-01 — LibreChat send-button QA signal
- A disabled `Send message` button with an empty composer is normal after completion. Do not call that a stuck stream.
- Correct browser completion signal: the `Stop generating` control disappears, response action buttons appear, and the composer becomes send-enabled after follow-up text is present.
- Brave real-browser run verified this: hero response completed, follow-up sent, and Conduit answered the follow-up (`JPM $487,500`, unsettled cash `$372,000`).

### Key Learning — 2026-07-01 — E2E launch + pivot verification
- On macOS in Codex sandbox, Playwright Chromium can fail before app logic with `MachPortRendezvousServer ... Permission denied`; rerun `cd tests/e2e && npx playwright test` with escalation/unsandboxed execution. Fresh rebuilt env on branch `rename/conduit-axiom` passed `89 passed (53.0m)`. Manual OIDC LibreChat login as `rm_jane` then same-conversation Whitman → Okafor pivot returned `Access denied for this client relationship.`

### Key Learning — 2026-07-01 — LibreChat OIDC token forwarding
- LibreChat custom endpoint headers can resolve `{{LIBRECHAT_OPENID_ACCESS_TOKEN}}`, but only when `OPENID_REUSE_TOKENS=true` lets the `openidJwt` auth path rehydrate `req.user.federatedTokens` on later API calls. The configured custom endpoint `baseURL` must remain admin-owned (not `user_provided`) or LibreChat intentionally drops identity-bearing headers to avoid token leakage. Axiom OIDC access tokens forwarded to the gateway must carry the same configured audience as `CONDUIT_AUTH_REQUIRED_AUDIENCE` plus the gateway's structural claims (`roles`, `segments`, `admin_domains`, `clearance`, `tenant_id`); ID tokens stay for LibreChat identity/userinfo only.

### Decision Log — 2026-07-02 — Workbench Persona Tokens
- Axiom Workbench uses an admin-only token exchange instead of act-as headers. `/auth/impersonate` returns a short-lived, audited, RS256 persona JWT; gateway sees a normal Bearer token for the selected subject and re-runs coverage/entitlement per turn. The persona JWT stays structural only: no `book` claim.
- The Workbench must never send the logged-in platform admin token for demo chat turns. Admins select a persona, Axiom mints a persona token, and both `/v1/chat/completions` plus `/trace/stream?conversationId=...` use that persona Bearer token.

### Do-Not-Repeat — 2026-07-02 — Workbench/Admin UI Contracts
- `GET /users` returns `PageResponse<UserResponse>`, not `User[]`. UI list helpers unwrap `.content`; create/update match `CreateUserRequest`/`UpdateUserRequest`; role assignment is separate `/users/{id}/roles` using role UUIDs.
- `/trace/stream` is broadcast unless filtered. Workbench connects with its conversation ID; gateway server-filters when present; the client still drops non-matching events and clears trace state on persona/conversation reset.
- PolicyForge subject role rules should use Cerbos role names, not role UUIDs. Do not lose the old Axiom Cerbos policy generator path when modernizing the UI.

## Key Learnings (Safety/Governance wave)
- The stack runs under docker compose project **orchestrator-demo**. ALWAYS `docker compose -p orchestrator-demo build|up` — building without `-p` tags images under project `orchestrator-chat` and services without an explicit `image:` (e.g. admin-console) silently keep the old image on recreate. iam-service is safe because it pins `image: conduit/iam-service:latest`.
- `mvn package -DskipTests` still COMPILES test sources — a changed public constructor breaks the Docker build even though `mvn compile` (main only) passes locally. Run `mvn test-compile`.
- The gateway derives identity ONLY from the verified JWT `sub` (X-User-Id hop removed). `/debug/**` and `/admin/**` need platform_admin/domain_admin. Mint gateway-valid tokens via Axiom `POST /auth/login` (aud=conduit-gateway). iam-service regenerates its RS256 key on every boot, invalidating old tokens.
- The DeepEval release gate = scripts/eval-gate.sh -> eval/eval_deepeval.py; exit code driven ONLY by routing F1 (golden-prompts.json) vs threshold (0.75). Cerbos ABAC allow/deny lives in eval/cerbos_golden_dataset.json, run separately by cerbos_policy_eval.py.

## Key Learnings (append 2026-07-03)
- Chat BFF compaction is TOKEN-budget driven, NOT message-count. Config: chat.context.max-tokens (window budget, env CHAT_CONTEXT_MAX_TOKENS, default 3000), chat.context.summary-trigger-tokens (env CHAT_SUMMARY_TRIGGER_TOKENS, default 2000), chat.summary.max-tokens (env CHAT_SUMMARY_MAX_TOKENS, default 150). The task's "recentMessages/summaryAfterMessages" names are conceptual; the real knobs are token budgets.
- The rolling summary is generated ONCE (when transcript first exceeds trigger and summary is blank) then reused; it is NOT refreshed as the convo grows. So the early distinctive turn must already be in the transcript when the trigger first fires for it to be captured.
- Facts-free rule applies ONLY to the summary. The recent WINDOW legitimately contains real values/IDs (e.g. REL-00099) — that is expected, not a leak.
- Real BFF OIDC-over-curl works: keep ONE cookie jar across the whole chain (BFF SESSION + iam JSESSIONID), use --resolve host.docker.internal:8084:127.0.0.1 on every hop, GET the /login form to grab the session-bound _csrf, POST username+password+_csrf to host.docker.internal:8084/login with -L; Spring auto-resumes the saved authorize request → callback → authenticated SESSION. rm_jane / Meridian@2024. Sessions are Mongo-backed so they survive conduit-chat container recreation.
- The running stack uses docker compose project name "orchestrator-demo" (NOT the dir-default "orchestrator-chat"). Always pass `-p orchestrator-demo` to build/recreate a single service, plus `--no-deps` to avoid conflicts with the overlapping uac/backend project.

## Do-Not-Repeat (2026-07-03)
- Lost-update: a background post-stream save of a stale, whole conversation entity (touchAndMaybeTitle) clobbered the concurrently-written rolling summary, nulling it every turn and silently killing compaction. When two async writers touch the same Mongo document, use field-scoped $set updates (MongoTemplate.updateFirst), never full-document save() of a stale entity. Fixed in ConversationService.touchAndMaybeTitle.
- When querying conduit conversations in Mongo, _id is an ObjectId — use ObjectId('...'), not a string, or findOne returns null and looks like "no summary".

## Bug layer classification (added 2026-07-11)
Every bug MUST be tagged with a `layer` in buglog.json. Layers + ownership:
- **gateway** — routing, clarify, entitlement DECISIONS, grounding FAITHFULNESS, partial-results, SSE (our Java).
- **agent** — domain COMPUTATION & data (e.g. concentration math, CSDR penalties). External team's service (mock-agents/ in demo). Gateway CANNOT fix without violating World-B.
- **coverage-data** — books, aliases, entity classification (coverage service data).
- **manifest/config** — entity types, classifications, coverage URLs, example corpus (World-B config, not code).
- **iam** — identity, segments, roles. **ui/bff** — rendering, sessions. **infra** — images, compose, trace store.
Decision rule gateway-vs-agent: did the LLM faithfully echo the agent's output? If yes and the answer is still wrong → AGENT bug (grounding contract held). If the gateway routed/gated/altered wrongly → GATEWAY bug.

## Key Learning — 2026-07-11 — Routing preparation and capability continuity
- `EntityBag` is a scalar focal-entity carrier (`Map<String,String>`), not a mention/provenance model; multi-reference span masking requires generic per-message mention records before changing `GroundingResult` to a list.
- The gateway's coverage path gates only the first resource-scoped surviving manifest and injects one canonical id into a shared bag. Genuine mixed resource-domain partial fulfillment therefore needs per-requested-group coverage/binding, not only a post-prune domain guard.
- `handleFollowUp` uses the syntactic reference helper before entering the grounded fetch path. Removing syntactic `entityKnown` must introduce one shared pre-routing preparation result for FETCH_DATA, FOLLOW_UP, and production-path eval or terse/anaphoric fetch fallthrough regresses.

## Key Learning — 2026-07-11 — LLM model tiers are env-driven; compose/yaml defaults are GLM
- The gateway is provider-swappable per call site via `CONDUIT_LLM_*` env. The `docker-compose.yml`/`application.yml` **defaults are GLM** (glm-4.5-flash/glm-4.6); the real runtime models come from `.env` (gitignored), which points every request-path call site at OpenAI. Don't diagnose "which model runs" from the yaml — render `docker compose config` (it substitutes `.env`) or read `.env`.
- Locked tiers (MODEL-SELECTION.md), wired in `.env` 2026-07-11: intent=gpt-4.1-nano, extract=gpt-4.1-mini, rerank=gpt-5-mini, clarify=gpt-4.1-nano, synth=gpt-5-mini, judge=o4-mini. Compose defaults stay GLM so perf/load can't bill OpenAI.
- The gateway had **no `CONDUIT_LLM_ROUTING_RERANKER_*` passthrough** in compose — the reranker could only inherit the intent model. Added base-url/key (inherit intent) + independent model passthrough. Same inherit-intent fix applied to clarification composer (its model was selectable but base-url/key were stranded on the GLM default → mismatch).
- **gpt-5* and o1/o3/o4 reasoning models** reject a non-default `temperature` (only 1) and reject `max_tokens` (need `max_completion_tokens`). Call sites that hardcode `temperature`/`max_tokens` break when pointed at these models. Fixed in the reranker (`supportsCustomTemperature`); intent/extract/clarify are on gpt-4.1* (temperature OK); synth sends neither param. See [[bug-271]].

### Do-Not-Repeat (2026-07-11)
- An Edit can silently write intended spaces in string literals as NUL bytes (`"\x00"`). This makes the .java "data" to `file` and BINARY to grep/ugrep, which defeats `world-b-check`'s `strip_comments()` and turns pre-existing in-comment domain tokens (e.g. REL-00188/Okafor in a class javadoc) into phantom CRITICAL violations. If world-b-check spikes and reports "Binary file matches", run `file <path>` — if it says "data", hunt NUL/control bytes (`python3 -c "print(open(p,'rb').read().count(0))"`), don't chase non-ASCII glyphs. This repo's `grep` is `ugrep`.

## Key Learnings (clarify P234 session)
- BFF (apps/chat/bff) pom requires Java 25 (`<java.version>25</java.version>`); the shell's default Homebrew mvn uses JDK 23 → "release version 25 not supported". Build/test the BFF with `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home`. Gateway builds fine on 23.
- apps/chat/web had NO test tooling and an EMPTY node_modules; `npm install` + add vitest/jsdom/@testing-library. jsdom lacks `Element.prototype.scrollIntoView` — stub it in the vitest setup (MessageList calls it in an effect).
- Structured-clarification RESUME design: gateway consumes the descriptor by X-Clarify-Nonce header, GROUNDED_SELECTION re-drives descriptor.originatingQuery() with the chosen id injected into the EntityBag (via idPattern→extractAs lookup, World-B clean) so the coverage CHECK re-runs; FREE_TEXT/out-of-set demote → no privileged ground. BFF folds the answer + posts /api/clarify/resolve.
- The actual Phase-1 `structured_interaction` event shape uses `question`/`freeText`/`options[{value,label,secondaryLabel}]` (NOT the brief's `prompt`/`freeTextEscape`) — follow the emitted code, not the brief.

## Key Learnings (clarify capability-wire session, 2026-07-16)
- **Capability-clarify trigger (task #62):** the ONLY spot to intercept is the abstain triage in `ChatService.handleFetchData` (the `if (resolved.fallback() || resolved.selected().isEmpty())` block). Order is now: (1) answer-from-history → (2) `attemptRequiredEntityClarify` (entity form) → (2b) `attemptCapabilityClarify` (capability form, NEW) → (3) flat no-service. The entity-vs-capability distinction is AUTOMATIC: entity-clarify only fires for resource-scoped agents whose `required_context` is unsatisfied; when it declines (entity known, or enterprise/non-resource-scoped candidates) the capability form fires. Genuine no-service = capability form declines (no plausible, CHECK-passed candidate).
- **The capability candidate set is `resolved.skipped()` filtered by `conduit.resolver.confidence-floor`** (same "plausible" floor the entity triage reuses), de-duped by agentId, then `entitlementService.filterAgents` (Cerbos) BEFORE building options — enumeration-oracle discipline. Options: value=agentId (route hint / submit token), label=`manifest.name`, secondary=`manifest.description` (all DATA, World-B clean). Trigger via `ClarificationTrigger.shouldOfferForm(true, allowedCount)`.
- **Capability RESUME is a ROUTE HINT, not an entity ground.** Added `ClarifyResume.Mode.CAPABILITY_SELECTION` + `Decision.capabilityHint` (7th field). ChatService carries it on a `capabilityHintCarry` ThreadLocal (mirrors `clarifyDepthCarry`, set on the CAPABILITY_SELECTION resume branch, removed in `finally`). At the routing seam in handleFetchData, `applyCapabilityHint` force-selects the hinted agent IFF still in selected∪skipped ≥ floor, then the normal pipeline runs — so `filterAgents` (Cerbos) still gates it and a now-denied capability → DENY at resume (TOCTOU-safe). Never inject the capability id into the EntityBag (that path is entity-only).
- **SPA needs NO change for new clarify kinds:** `apps/chat/web/src/lib/gatewayTrace.ts` types `StructuredInteraction.kind` as optional string; `selectStructuredInteraction` + `ClarificationForm.tsx` render `question`/`options`/`freeText` blind with zero `kind`-branching. Any new `structured_interaction` kind renders identically.
- **The DENY copy for an enterprise agent's capability-unavailable is manifest-declared** (e.g. "that capability isn't available to you right now."), not the generic Java fallback — assert on denial semantics, not a fixed string, in resume-deny tests.

## Do-Not-Repeat (2026-07-16)
- **[2026-07-16] Agent worktrees: BUILD FROM THE WORKTREE PATH, not the shared checkout.** In an isolated worktree (`.claude/worktrees/agent-*`), `Edit` writes to the worktree copy, but `cd /Users/.../orchestrator-demo/gateway && mvn test` builds the SHARED checkout (a different branch) — so new test files "don't exist" / `No tests matching pattern`, and `mvn compile` passes against unmodified sources giving a false green. Always `cd .claude/worktrees/agent-XXX/gateway` (or use relative `gateway/` from the worktree cwd) for mvn/world-b-check. Symptom: surefire "No tests matching pattern" for a file you just wrote.
- **[2026-07-16] Gateway builds on JDK 25 here** (`gateway/pom.xml` `<java.version>21</java.version>` bytecode target, but compile/test with `JAVA_HOME=.../zulu-25.jdk`); the earlier cerebrum note "gateway builds fine on 23" is stale for this tree — Homebrew mvn default JDK may be too old.

## Key Learnings (Axiom A2 tenant-context seam, 2026-07-17)
- Tenant is NOT identity: Principal no longer carries tenantId. The ONLY reader of the `tenant_id` claim string is `TenantContextResolver` (+ the A1 verifier `SecurityConfig`). Enforced by `TenantContextSeamArchTest.onlyResolverReadsTenantClaim` (source scan for the `"tenant_id"` literal).
- Tenant flows as an explicit immutable `TenantExecutionContext(tenantId, actorTenantId, activePolicyVersion)`, captured in the filter → parked in `RequestContext` (capture-only) → read by the controller on the servlet thread → threaded as a param through ChatService/routing/coverage/InvocationContext/audit. Downstream must NOT call `RequestContext.getTenant()` (ArchUnit-enforced; only the 2 controllers may).
- No request-path tenant-directory I/O: `SnapshotProvisionedTenantDirectory` serves an AtomicReference snapshot; `ConfigBackedTenantSnapshotSource` (@Profile("!multi-tenant"), seeds `default`) is the demo/test source; `TenantDirectorySnapshotClient` refreshes on a daemon timer. Readiness (`TenantDirectoryReadiness` HealthIndicator) is DOWN until the first snapshot.
- Fail-closed lives at the FILTER (401 missing tenant_id / 403 unprovisioned / 503 no snapshot) BEFORE the controller and any I/O — proven by asserting the FilterChain is never entered. GovernedInvoker's tenant check is defense-in-depth only: it denies an ATTACHED-but-unresolved tenant, and treats a null tenant (legacy/test/executor-fallback contexts) as deferring to the filter — this keeps every existing invoke/ask-lane unit test byte-unmodified.
- Config convention: this codebase does NOT use @ConfigurationProperties (CLAUDE.md §5) — bind via @Value. A comma-separated `tenantId:policyVersion` spec parses cleanly; `${prop:default:config-v1}` keeps the colon in the default value.

## Decision Log (Axiom A2, 2026-07-17)
- Removed `Principal.tenantId` outright (vs. keeping it and sourcing from TEC): the ArchUnit "only resolver reads the claim" rule requires Principal to stop reading `tenant_id`. Blast radius was 1 main + 6 test constructions, none in protected suites.
- GovernedInvoker defense-in-depth (reject attached-but-unresolved, not null): the spec's "reject a missing tenant" at the invoker would flip the ask-lane/GovernedInvoker* unit tests (which use tenant-less contexts) to DENIED. The task said STOP and reconsider the seam if threading breaks an ask-lane assertion — so the authoritative missing/unknown gate is the filter, and the invoker guards integrity. Zero test churn.
- Anonymous callers get a BLANK tenant string (not "default"): satisfies A2.4 (no default tenant reachable for a data op); anonymous has no coverage and is denied at CHECK anyway.

## Do-Not-Repeat (2026-07-17)
- Do NOT add `readinessState` to a custom `management.endpoint.health.group.readiness.include` unless probes are enabled — it fails context startup with "Included health contributor readinessState ... does not exist", breaking EVERY @SpringBootTest. Gate custom readiness on your own HealthIndicator bean name.
- The base `conduit-platform-next` has a PRE-EXISTING A1 fixture drift: `CapabilityClarifyFixture.mintToken()` minted a bare-audience, tenant-less token that A1's decoder rejects (5 tests 401). When a full-context test 401s, check the base first (`git worktree add --detach /tmp/x <base>`) before assuming your change caused it. Fix = A1-consistent token: `audience(List.of("conduit-gateway","conduit-gateway@default"))` + `claim("tenant_id","default")`.

## Key Learnings (Axiom C3 independent test-gen, 2026-07-17)
- The "moat" against the LLM oracle problem is enforced at the TYPE level: the test-oracle input record (TestScenarioRequest) carries only pre-generation grounding facts (intent+vocab+scope+ceiling) and has NO YAML/PolicyIR component. TestGenIsolationArchTest proves it via reflection over record components + ArchUnit dependency check. Adding a YAML field to "improve tests" turns the test red — that's the tripwire.
- Asymmetry that keeps the moat real: GENERATION context is fenced (oracle authored blind); RUN context is sighted (evaluator sees the candidate). Fence exactly TestScenarioRequest + TestScenarioModelClient, not the whole test-gen area.
- In the B2 tenant-restriction-child model, a child under REQUIRE_PARENTAL_CONSENT cannot over-grant BEYOND the base ceiling (parental consent neutralizes it) — so the moat's real threat is UNDER-restriction: an explicit child ALLOW where the intent wanted a DENY (validator totality passes because the tuple has *an* opinion; the independent oracle catches the wrong opinion). Avoid corpus items that rely on beyond-ceiling leaks; the evaluator honestly does NOT model that neutralization.
- Catch-rate harness measures THE GATE, not LLM quality: seeded deterministic oracle (NO network/LLM) replays a per-intent IntentSpec authored from the intent (never the YAML). Reproduces byte-for-byte. Pin model=stub/temperature=NA/seed=corpus-order/retry=none in evidence.
- PolicyExpectationEvaluator is a CONSTRAINED evaluator, not a Cerbos reimpl: models rule match, DENY-overrides, fall-through to base ceiling, tenant-equality backstop, + a tight CEL subset (==,!=,in,&&,||,has) with fail-closed missing-attr (missing in a comparison → ConditionError → rule non-match). Throws IndeterminateException outside the subset rather than guess — never score an item on a guess.
- iam-service is a STANDALONE maven module (no root pom); mvn runs with cwd=iam-service. Tests that write repo-root evidence must walk up to find docs/implementation (fall back to target/). Compile-gate default baseBundleDir "infra/cerbos/policies" only resolves from repo root — pass runCompile=false for reproducible in-process tests.
- cerbos-policy-gate.sh runs 74/74 via ephemeral `docker run --rm` (unnamed/no-port) — safe alongside running conduit-cerbos:3594. C3.4 "invariant suite on every draft" = compiling candidate+base bundle, which embeds tests/invariants/*.yaml, so the compile executes the hand-owned B3 invariants for free.

### Key Learning — Axiom C4 consequence-diff (2026-07-17)
- Real Cerbos "CheckResources batch" without running a server: render the matrix as a Cerbos test suite asserting `EFFECT_ALLOW` for every cell, then `cerbos compile -o json --skip-batching --test-filter=suite=<name>`. Per cell: RESULT_PASSED ⇒ ALLOW; RESULT_FAILED ⇒ `details.failure.actual` is the real effect. Robust, deterministic, uses the same ephemeral `docker run --rm` (no name/no port) pattern as CerbosCompileGate — never touches the running conduit-cerbos.
- The pinned cerbos image is `ghcr.io/cerbos/cerbos:latest` (== 0.53.0 offline). `--entrypoint /cerbos`.
- C4 reproducibility mirrors C3: 6 harness tests use an in-process fidelity evaluator (PolicyExpectationEvaluator behind a PdpDecisionSource port) for a byte-stable number; a separate assumeTrue-gated test runs REAL Cerbos for the headline evidence and writes it to docs/implementation/evidence/studio/c4/.
- Under REQUIRE_PARENTAL_CONSENT_FOR_ALLOWS, a child ALLOW only takes effect if the base (parent) also allows. platform_admin has an UNCONDITIONAL base ALLOW (cross-tenant superuser), so it's the clean role for demonstrating a real DENY→ALLOW widening at a tenant scope; chat_user/domain_admin base ALLOWs are ABAC-conditional (need domain/segment/classification attrs) so a real-Cerbos widening on them needs the full attr set.
- iam-service tests need JDK 25 (JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home). world-b-check.sh scans ONLY the gateway module, not iam-service — so policystudio Java is out of its scope, but still phrase consequences from ManifestVocabulary (no domain literals) on principle.

## Key Learnings (Policy Studio S5 manifest grounding, 2026-07-17)
- `ManifestBackedStudioGroundingProvider` grounding is now derived, not literal. Actions/roles/resource-kind/base-ceiling/approved-imports come from the **base Cerbos policy bundle** (`iam.policy-studio.base-bundle-dir` = `infra/cerbos/policies`) via `BaseBundleGrounding.read(dir, kind)`: parse `<kind>_resource.yaml` root ALLOW rules → ceiling tuples (derivedRoles expanded to parentRoles via `business_derived_roles.yaml`); roles vocab = raw roles ∪ imported-module parentRoles (this is what re-adds `conduit_admin` for `agent`); `carriesTenantEqualityBackstop` = an imported module's condition contains `P.attr.tenant_id == R.attr.tenant_id`. Classifications/attributes still come from `registry/` manifests.
- The consequence matrix is no longer only same-tenant-positive-empty-attrs. For the "front-door" role (a ceiling role whose granted action-set is a strict subset of the full action surface — i.e. `chat_user`/`relationship_manager`, not the admins/superuser) it adds an attributed `segment_positive` + four negatives: `attribute_removed` (principal segment membership dropped), `cross_tenant` (resource tenant ≠ principal, both `tenant_id` set → backstop DENY), `missing_attribute` (resource `data_classification` omitted → `resourceClassRecognized` false), `wrong_segment` (principal holds a different segment key). Verified against real Cerbos: positive ALLOW, all four DENY at base.
- Segment cell attribute VALUES are manifest/config-derived: domain/audience/access_mode/data_classification from a segment agent manifest; segment key + classification ladder from `infra/cerbos/tenants/default/domain-segment-map.yaml` (config `iam.policy-studio.tenant-deployment-root` + `deployment-tenant`). Map/manifest absent → matrix degrades to same-tenant positives (never throws) so grounding always produces a matrix.
- Gotcha: `C5LifecycleFixtures` / `PolicyStudioFixtures` are **package-private**; a policystudio-package test can't call them. Build in-memory `ActiveTenantDirectory(mock(ActiveTenantRepository))` + `mock(PolicyBundleRepository)` inline instead.
- Gotcha: the repo's `mvn` picks JDK 23 (Homebrew) by default → "release version 25 not supported". Always `export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home` before iam-service maven.
