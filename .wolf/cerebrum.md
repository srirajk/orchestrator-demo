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

## Key Learnings

- **Project:** Enterprise AI gateway for a bank (Meridian brand on LibreChat). RM types plain-English; gateway routes to 9 specialist agents across HTTP (FastAPI/Wealth) and MCP (Asset Servicing), enforces Cerbos ABAC, synthesizes one grounded answer streamed back.
- **Governed memory scaffold (2026-07-01):** Context compaction belongs outside the gateway. The gateway emits runtime events and consumes `context-envelope.v1`; an external memory service owns the append-only ledger, summaries, redaction/retention, and manifest-driven compaction policy. Domain/sub-domain/agent manifests plus gateway events are the inputs; no domain-specific memory logic belongs in gateway Java.
- **Stateless gateway implemented (2026-07-02):** Gateway chat memory is gone. `ConversationSession`/`ConversationSessionStore` were deleted; `conversationId` is trace grouping only. The gateway derives entity context from client-sent `messages[]` + JWT every turn, latest explicit user mention wins, prior user mentions can seed fresh follow-ups, assistant text never seeds extraction, and coverage/entitlement is re-run every turn.
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

---

## Key Learnings (Phase 12 additions)

- **Principal record field order (Phase 12+, revised 2026-06-29):** `Principal(id, tenantId, roles, clearance, adminDomains, segments, domains)`. **NO `book` field** — book was removed as part of three-layer auth architecture. The `tenantId` is the SECOND field. Any test constructing a Principal directly needs `"default"` as the 2nd arg.
- **DomainManifest (Phase 12+):** No `entityRegistry()` / `authorizationContract()` fields. Has `coverage()` returning `Coverage(discoverUrl, checkUrl, resolveUrl, cacheTtlSeconds)` and `displayName` (not `name`).
- **SubDomainManifest (Phase 12+):** Has `resourceScoped()` boolean instead of `clarificationPriority()`. `ClarificationSchema` has `priority` int field and `default` (not `default_value`).
- **EffectiveManifest (Phase 12+):** Has `coverage()`, `resourceScoped()`, `requiresRelationship()`, `relationshipClarification()`. Removed: `authorizationContract()`, `entityRegistry()`, `clarificationPriority()`.
- **Phase 12 test audit result:** All 44 Java tests pass, TypeScript compiles clean after Phase 12. Tests were updated in the same commit as the source change — no lag between source and test updates.
- **Conduit Workbench UI slice (2026-07-01):** Keep this in `admin-ui` React/Tailwind and add a separate `/gateway-api` proxy for gateway calls. Existing real gateway endpoints support trace SSE/health (`/trace/*`), domain registry (`/admin/domains`), agent registry (`/admin/agents`), and non-streaming chat completions (`/v1/chat/completions`). There is no per-agent liveness endpoint yet, so UI health should label registry/index status as real and liveness as placeholder.
- **Workbench React module structure (2026-07-01):** Workbench lives under `admin-ui/src/features/workbench/`; route wrapper is `pages/Workbench.tsx`. Trace streaming uses a fetch-based SSE reader so Authorization headers can be sent; native `EventSource` is not suitable for future authenticated trace endpoints. `VITE_GATEWAY_API_BASE` is a non-secret optional proxy/base-path override and needs `src/vite-env.d.ts`.

## Do-Not-Repeat

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
