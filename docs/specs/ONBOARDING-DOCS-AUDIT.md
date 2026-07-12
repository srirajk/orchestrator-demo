# Onboarding Docs Audit — AGENT-ONBOARDING-HANDBOOK.md + domain-onboarding-standard.md

> Audit date: 2026-07-11. Auditor: Fable (read-only pass; every finding grounded in files read).
> Verdict up front: the **handbook is ~85% accurate** and needs a targeted staleness pass
> (registry-service split, capability-first routing, boot-log strings, `figures`). The
> **standard is ~30% accurate** — its agent-manifest section describes a schema that has never
> matched `agent-manifest.schema.json`, and a newcomer copying either of its example manifests
> fails validation on the first try. Recommendation at the end: **merge, keep the handbook.**

Legend: **STALE** = contradicts current code · **INCOMPLETE** = a newcomer-needed step/field is
missing · **UNVERIFIED** = a claim/command that does not work as written. Priority P0 = a
newcomer following the doc fails; P1 = succeeds but with wrong mental model or missing safety;
P2 = polish/accuracy.

---

## Part 1 — Findings: `docs/AGENT-ONBOARDING-HANDBOOK.md`

### H1 · P0 · STALE — The boot-success log line does not exist
- **Doc:** `docs/AGENT-ONBOARDING-HANDBOOK.md:918` and `:1216` — "The boot log reports
  `Registry bootstrap complete: N loaded, 0 failed`".
- **Code:** `gateway/src/main/java/ai/conduit/gateway/registry/ingest/RegistryIngestor.java:148`
  logs `"Registry ingestion complete: {} loaded, {} rejected"`. There is no "bootstrap complete"
  string anywhere in `gateway/src/main/java`.
- **Fix:** Replace both occurrences with the real line: `Registry ingestion complete: N loaded,
  0 rejected` plus `select validation: X validated, 0 UNVALIDATED (no output schema)`
  (`RegistryIngestor.java:146`). A newcomer greps logs for the doc's string and concludes boot
  failed.

### H2 · P0 · STALE — §4.1 and all of §8 say "the gateway" embeds/ingests at boot; ingestion moved to the registry-service
- **Doc:** `:363-364` "At boot, the gateway takes every string in every `skills[].examples`
  array and embeds it … behind `EmbeddingClient`"; §8 (`:903-923`) "When the gateway starts, for
  each manifest it runs a fixed pipeline… Validate → Introspect → Embed → Wire → Boot-validate →
  Register".
- **Code:** That pipeline runs in the **registry-service** (`registry` Spring profile), not the
  gateway: `RegistryIngestor.java:49` (`@Profile("registry")`), `ManifestEmbedder.java:39`,
  `AgentRegistrar.java:38`, `VectorIndexWriter.java:34` — all `@Profile("registry")`. The gateway
  cannot embed or write the index; it only verifies it exists, is non-empty, and was built by the
  same model (`registry/readiness/RegistryReadinessVerifier.java:36`, `@Profile("!registry")`,
  which refuses gateway startup otherwise). Compose: `docker-compose.yml:257-299`
  (`registry-service`, `SPRING_PROFILES_ACTIVE: registry`, gateway `depends_on` its healthcheck).
- Also: there is **no `EmbeddingClient` class**. The port is `TextEmbedder`
  (`gateway/src/main/java/ai/conduit/gateway/registry/embedding/TextEmbedder.java`), split into
  `ManifestEmbedder` (corpus, ingestion-only) and `QueryEmbedder` (request path), implemented by
  `RemoteEmbedder` calling the **Python sentence-transformers sidecar** over HTTP
  (`docker-compose.yml:269` `CONDUIT_EMBEDDING_REMOTE_URL: http://embeddings:8083/v1/embeddings`).
  The model/dim claims (all-MiniLM-L6-v2, 384-dim) are **correct** — good, no in-JVM-DJL drift here.
- **Fix:** Retitle §8 "What happens at ingestion (the registry-service)"; rewrite steps to name
  the registry-service; add "re-ingest = `docker compose -p orchestrator-demo restart
  registry-service`" (or `up -d registry-service`); add the gateway-side readiness story (empty
  index / zero agents / model-id mismatch → gateway refuses to start, with the remedy text in
  `RegistryReadinessVerifier.java:44-46`). Fix `EmbeddingClient` → `TextEmbedder` (+ sidecar) at
  `:364`.

### H3 · P0 · STALE — §8 understates the failure mode: an invalid manifest fails the WHOLE ingestion, not just your manifest
- **Doc:** `:908` "(Malformed manifest → rejected.)" and `:917` "your manifest fails to load" —
  reads as a per-manifest skip.
- **Code:** default `conduit.registry.ingest.fail-on-invalid=true`
  (`RegistryIngestor.java:74`, `docker-compose.yml:273`): any rejected manifest throws
  (`RegistryIngestor.java:150-156`) and the registry container **exits 1**
  (`RegistryIngestor.java:97-101`), blocking the gateway. Rationale documented in the class
  javadoc (`:42-46`): "A registry that does not fully load is not a degraded registry, it is an
  unknown one."
- Also missing from §10 (offboarding, `:980-982`): removal is enforced by **orphan reconciliation**
  — the manifest folder is the source of truth and agents with no manifest are pruned on the next
  ingest (`RegistryIngestor.java:178-197`, `conduit.registry.ingest.prune-orphans=true`).
- **Fix:** State plainly: "a broken manifest stops the whole registry from going healthy — your
  onboarding failure is loud and blocks everyone's boot, by design"; add the prune-orphans note to
  §10.

### H4 · P1 · STALE — §4 routing story predates capability-first routing (de-entitied corpus + query masking)
Two halves:
- **(a) The corpus is de-entitied; the handbook's worked examples still contain client names.**
  Doc `:1018` quotes holdings example `"current holdings for the Whitman relationship"` and
  `:1062` quotes concentration `"how concentrated is the Whitman relationship"`. The actual
  manifests were de-entitied: `registry/manifests/wealth-management/meridian.wealth.holdings.json`
  says `"current holdings for this relationship"`;
  `…/meridian.wealth.concentration.json` says `"how concentrated is this relationship"`. The
  handbook is actively teaching newcomers to put entity names into examples — now an anti-pattern.
- **(b) Query-side entity masking is not mentioned at all.** Resolved entity spans are blanked
  from the routing text with a neutral deictic before the router sees the query
  (`conduit.routing.entity-mask-token: "the subject"`,
  `gateway/src/main/resources/application.yml:~168-186` "Piece 3";
  `gateway/src/main/java/ai/conduit/gateway/domain/chat/RoutePreparationPolicy.java`,
  `config/RoutePreparationConfig.java:22`), with `residual-stopwords` near-empty relaxation,
  `min-residual-content-tokens`, and a `preparation-version` stamp (`route-prep-v2`). Consequence
  for manifest authors: **an example keyed to an entity name can never match a masked query** —
  routing keys on the capability asked, not the entity named.
- **Fix:** Add a §4.x "Capability-first: write examples about the capability, never the client"
  covering (a)+(b); scrub "Whitman" from the §11.1/§11.2 example listings to match disk. (§4.4's
  trade_penalty list at `:439-448` matches disk exactly — keep.)

### H5 · P1 · INCOMPLETE/UNVERIFIED — The goal-pick measurement gate is never named as a runnable command
- **Doc:** §4.6 (`:481-498`) and §9 (`:948-958`) describe the harness and its gate semantics but
  give no command, path, or prerequisite. A newcomer cannot run it.
- **Code:** `scripts/routing-measurement-gate.sh` → `eval/goal-pick/measure_goal_pick.py`, dataset
  `eval/goal-pick/labeled_queries.json`, baselines `eval/goal-pick/baselines/`. It drives the
  **live gateway's** `POST /debug/route`, which is config-gated OFF by default
  (`conduit.debug.route-decision.enabled`, `application.yml:~192-204`) and enabled for the demo
  via compose — so the stack must be up, users seeded (`scripts/seed-users.sh`), and the debug
  endpoint enabled.
- **Fix:** Name the command + prerequisites in §4.6 and the §9 checklist.

### H6 · P2 · UNVERIFIED — "~93%" routing accuracy is a floating number
- **Doc:** `:493` "The current harness measures domain-level routing in the **~93%** range".
- **Repo:** not reproducible from the doc; baselines live in `eval/goal-pick/baselines/` (with
  `REBASELINE.md`). Historical note in project memory says domain routing measured ~85% after the
  T1.5 metric fix — the 93% claim may be post-hardening, but the doc gives no provenance.
- **Fix:** Cite the baseline file (or say "see `eval/goal-pick/baselines/` for the current
  number") instead of hardcoding a percentage that will drift.

### H7 · P1 · INCOMPLETE — `io.produces[].figures[]` is entirely undocumented
- **Doc:** §5 never mentions figures; the §13 appendix row for `io.produces[]` (`:1212`) lists
  `{ name*, type*, entities? }` only.
- **Code:** schema `gateway/src/main/resources/agent-manifest.schema.json:205-230` — `figures[]`
  with required `label`, `path` (JMESPath), `format` (currently free-string: "percent, percent1,
  currency_usd, count, plain, date"); Java `AgentManifest.ProducedFigure`
  (`registry/model/AgentManifest.java:219`); boot-validated per producer
  (`registry/service/SelectContractValidator.java:184` "figure path validation … UNVALIDATED").
  This is the deterministic figure-rendering / answer-attribution machinery ("grounded figures",
  which the handbook *names* at `:200` but never teaches).
- **Fix:** Add a §5.x "Declaring load-bearing figures" (label/path/format semantics + boot
  validation) and the appendix row. **This is a prerequisite for incoming change #2** (the format
  enum) — see Part 4.

### H8 · P1 · STALE — MCP transport documented wrong; Streamable HTTP + spec version never mentioned
- **Doc:** `:1202` appendix — MCP connection "`{ server_url*, tool*, transport? }`
  (`sse`\|`stdio`)". §6's MCP section (`:819-825`) says nothing about transport or protocol
  version.
- **Code:** schema `agent-manifest.schema.json:294` — `transport` enum is
  `["streamable", "sse", "stdio"]` with **default `streamable`** ("'sse' = legacy, deprecated
  HTTP+SSE"), plus optional `protocol_version` per-agent override; adapter defaults come from
  `conduit.mcp.protocol-version: 2025-11-25` / `legacy-protocol-version: 2024-11-05`
  (`application.yml:93-94`); `AgentManifest.Connection` (`AgentManifest.java:74-99`,
  `isLegacySse()`). The real MCP manifest uses
  `"server_url": "http://servicing-mcp:8082/mcp", "transport": "streamable"`
  (`registry/manifests/asset-servicing/meridian.servicing.settlement_status.json`).
- **Fix:** Appendix row → `transport? (streamable|sse|stdio, default streamable)` +
  `protocol_version?`; add two sentences to §6: Streamable HTTP (single `/mcp` endpoint, spec
  `2025-11-25`) is the default; `sse` exists only as a deprecated legacy escape hatch.

### H9 · P2 · STALE — "three live domains and sixteen agents"
- **Doc:** `:122`. **Disk:** four domains (`registry/domains/`: wealth-management,
  asset-servicing, insurance, hr) and **18** agent manifests (`registry/manifests/*/`: 7 servicing,
  7 wealth, 3 insurance, 1 hr).
- **Fix:** "four live domains and eighteen agents" — or drop the count so it can't rot.

### H10 · P1 · INCOMPLETE — The sub-domain manifest's `entity_types` anatomy is never documented
- **Doc:** §3 (`:318`) says entity types "live" in the sub-domain manifest and §5.1 (`:618-620`)
  says `consumes.entity` references "a sub-domain entity key" — but nowhere is the entity-type
  declaration itself specified. A newcomer whose agent needs a **new** entity type (the common
  case for a new domain) has no reference for `key`, `extract_as`,
  `kind: resolvable|literal|list`, `display`, `id_pattern`, `resolve_type`, `required`, `default`.
- **Code:** `registry/sub-domain-manifest.schema.json:30-47` (required: key, extract_as, kind,
  display, required); `gateway/src/main/java/ai/conduit/gateway/domain/manifest/EntityType.java`
  (javadoc explains each field's runtime role — extraction, resolver short-circuit via
  `id_pattern`, prompt compilation via `display`). Live example:
  `registry/domains/wealth-management/private-banking.json:6-42`.
- Same gap for the sub-domain's **user-facing copy contract**: `messages` (incl. the
  `capability_unavailable` key the gateway reads — `domain/chat/ChatService.java:585-586,1595`;
  `DomainManifestStore.message(key, subDomainId)`), `denial_messages` (reason-code → copy,
  `DomainManifestStore.denialMessage`), and `clarification_schema.options_source` value
  `principal_book` (`sub-domain-manifest.schema.json:70`). And the **domain** manifest's
  `clarify_style`/`clarify_tone` (`registry/domain-manifest.schema.json:16-26`,
  `DomainManifest.java:17-18`; live: `registry/domains/wealth-management.json` uses `composed`).
- **Fix:** Add a "The sub-domain manifest — entity types and user-facing copy" section (or fold
  the standard's Level-1/Level-2 material in here, corrected — see Part 5).

### H11 · P2 · INCOMPLETE — The three-copies schema rule is not stated
- **Doc:** `:294`, `:1188` point at `registry/agent-manifest.schema.json`.
- **Code:** the copy actually enforced at ingestion is the **gateway classpath** one
  (`registry/service/ManifestValidator.java:25` loads `/agent-manifest.schema.json`); a third
  copy sits at repo root. CLAUDE.md §5: all three must stay identical
  (`ManifestSchemaCopiesInSyncTest` fails the build on drift).
- **Fix:** One-line note wherever the schema is referenced: "three synced copies; the classpath
  copy is loaded at runtime; edit all three."

### H12 · P2 · STALE — §11.4 example list drifted from disk (and from §4.4 of the same doc)
- **Doc:** `:1122-1129` lists 6 trade_penalty examples; the actual manifest
  (`registry/manifests/asset-servicing/meridian.servicing.trade_penalty.json`) has 8 — including
  `"show skipped failed trade rows when the map cap is reached"` and
  `"itemize per-failed-trade penalty details"`, which §4.4 (`:439-448`) correctly includes.
- **Fix:** Make §11.4 match disk (or explicitly say "abridged").

### H13 · P2 · INCOMPLETE — Appendix omissions (minor)
- `securitySchemes` (schema `:58-61`) absent from §13.
- `io.consumes[]` entity items may also carry `required` (schema puts `required` on the item
  regardless of variant; live use: `meridian.wealth.holdings.json` io
  `{"entity": "relationship_id", "required": true}`) — the appendix (`:1211`) shows `required`
  only on the `from` variant.

### Verified-accurate (do not "fix" these)
- Abstain-floor keys `conduit.routing.min-score` (0.40) / `min-margin` (0.005) —
  `application.yml:146,150`. ✔
- Map global ceilings `conduit.orchestration.map.max-items` / `max-concurrency` —
  `application.yml:267-268`. ✔
- Runtime `checkComposable` backstop — `orchestration/executor/Blackboard.java:50`. ✔
- Select/condition/map boot validation with precise errors —
  `registry/service/SelectContractValidator.java` (incl. `:467` error text naming agentId). ✔
- `select validation: X validated, Y UNVALIDATED (no output schema)` line — exact
  (`RegistryIngestor.java:146`). ✔
- LLM re-ranker for close calls exists — `resolver/service/LlmRoutingRerankerClient.java`
  (near-tie `margin-threshold` 0.13 + `abstain-adjacent-band`, `application.yml:151-158`). ✔
- Embeddings are the Python sidecar, all-MiniLM-L6-v2, 384-dim — no DJL drift. ✔
- Schema facts: examples minItems 3, agent_id pattern, semver pattern, constraints enums/ranges,
  a2a `agent_card_url`, oneOf entity/from — all match `agent-manifest.schema.json`. ✔
- Manifest path `registry/manifests/<domain>/<agent_id>.json` (`:293`) matches disk and the
  ingest glob `manifests/**/*.json` (`RegistryIngestor.java:108`). ✔ (The standard's flat path is
  wrong — see S1.)

---

## Part 2 — Findings: `docs/domain-onboarding-standard.md`

This doc's header says "Locked — agreed 2026-06-28" (`:4`) but its Level-3 section was never
trued up against the schema that actually shipped. It reads as the pre-implementation spec.

### S1 · P0 · STALE — The entire Level-3 agent-manifest section describes a schema that does not exist
- **Doc:** `:211-267`. Both example JSONs and the field table use: `connection.url` (+ env
  substitution), top-level `tool_name`, `capabilities` as a **keyword array**, `example_prompts`,
  and `max_response_tokens` marked **required** ("Agent is never told"). It also claims
  `example_prompts`: "More = better routing" (`:265`) — the direct opposite of the handbook's
  (correct) §4.2 quality-over-quantity rule.
- **Code:** `gateway/src/main/resources/agent-manifest.schema.json` — required top-level is
  `agent_id, name, description, version, provider, domain, audience, protocol, connection,
  capabilities, skills, constraints` (`:8`); connection is `{openapi_url, operation_id}` (http) /
  `{server_url, tool, transport?, protocol_version?}` (mcp) (`:270-311`); `capabilities` is an
  object requiring `streaming` (`:50-57`); routing fuel is `skills[].examples` (minItems 3,
  `:79-84`); `max_response_tokens` is optional (`:121-126`). The MCP example's
  `"url": "${SERVICING_MCP_URL}/sse"` (`:240`) additionally teaches the **deprecated HTTP+SSE**
  transport. Path is wrong too: `registry/manifests/{agent-id}.json` (`:206`) vs actual
  `registry/manifests/{domain}/{agent-id}.json`.
- **Fix:** Delete/replace the whole Level-3 section. A newcomer copying either example fails
  schema validation on ~8 independent counts. Point at the handbook §3/§13 + a real manifest
  (`registry/manifests/wealth-management/meridian.wealth.holdings.json`).

### S2 · P0 · STALE — Sub-domain section omits `entity_types`, which the schema REQUIRES
- **Doc:** `:129-201`. Neither example JSON (`:136-185`) nor the field table (`:187-200`)
  mentions `entity_types` — yet it is in the schema's required list
  (`registry/sub-domain-manifest.schema.json:8-16`). Both example JSONs in the doc are
  schema-invalid. (Today they'd load anyway because sub-domain loading is lenient — see S6 — but
  incoming change #1 makes this fail loudly, at which point the doc's examples break the boot.)
- Also missing: `denial_messages`, `messages` (incl. the load-bearing `capability_unavailable`
  key — `ChatService.java:585`), and `options_source: principal_book`
  (`sub-domain-manifest.schema.json:70`; doc `:197` lists only discover/agent_derived/none).
- **Fix:** Rebuild the section from `registry/domains/wealth-management/private-banking.json`
  (the canonical live example) + the schema.

### S3 · P1 · STALE — Domain-manifest section: missing fields, misstated requiredness, runtime-ignored fields presented as behavior
- **Doc:** `:68-126`.
- Missing from table/example: `clarify_style` / `clarify_tone`
  (`registry/domain-manifest.schema.json:16-26`; live: `wealth-management.json` sets
  `clarify_style: composed`).
- Coverage requiredness misstated: the schema requires **all three** URLs whenever a `coverage`
  object is present (`domain-manifest.schema.json:30`); the *runtime* check is narrower — a
  `resource_scoped` sub-domain requires its parent's `coverage.discover_url`
  (`DomainManifestStore.java:176-186`). The doc's per-URL "if resource_scoped sub-domains exist"
  matches neither exactly.
- `memory_compaction` is schema-required always (`domain-manifest.schema.json:8`) — even the
  role-less `hr.json` carries it — but the doc's "No coverage block needed for role-based
  domains" note (`:125`) leaves the impression the whole governance block is conditional.
- **Fix:** Correct the table; and see S4 for how to frame memory_compaction honestly.

### S4 · P1 · UNVERIFIED — The external memory service does not exist
- **Doc:** `:86-104` (summary_policy semantics), `:374-376` ("gateway calls the external memory
  service"), `:418-421` ("Memory service owns compaction → ledger append, summaries, envelope
  watermark"), boot check `:440`.
- **Code:** no memory service in `docker-compose.yml`; no gateway client for one. The gateway
  Java parses only `must_preserve`/`can_drop`
  (`domain/manifest/DomainManifest.java:32-36` — `summary_policy` is not even bound). The
  `summary_policy` block is required boilerplate validated by the JSON schema, ignored at
  runtime.
- **Fix:** Mark the memory/compaction narrative explicitly as "declared now, executed by a future
  memory service — today the block is schema-required boilerplate; copy it verbatim from an
  existing domain."

### S5 · P1 · STALE — "vector + BM25 search" — there is no BM25
- **Doc:** `:379` "Agents resolved → vector + BM25 search against example_prompts +
  capabilities".
- **Code:** zero BM25 hits in `gateway/src/main/java`. Routing = HNSW cosine over embedded
  `skills[].examples` + abstain floor + LLM reranker (`LlmRoutingRerankerClient.java`) + query
  masking (H4). `capabilities` is a transport-feature object, not a routing signal.
- The rest of the `:369-424` flow is broadly right (intent enum `FETCH_DATA / FOLLOW_UP /
  CLARIFY / CHITCHAT` verified at `domain/intent/Intent.java:16-`), but rewrite the routing line.

### S6 · P1 · STALE — "Validation at Boot" checklist is largely aspirational
- **Doc:** `:430-441` lists 8 fail-fast checks.
- **Code:** `DomainManifestStore.validate()` (`DomainManifestStore.java:170-189`) enforces
  exactly **two**: sub_domain.parent_domain resolves, and resource_scoped ⇒ parent has
  `coverage.discover_url`. NOT enforced today: agent.domain / agent.sub_domain resolution,
  sub_domain.agents ⇒ loaded agent manifest, "unknown capability types" (the concept doesn't
  exist), memory_compaction owner. Worse, the opposite of fail-fast holds for malformed
  sub-domain files: they are **silently skipped** at `DomainManifestStore.java:164-166`
  (`log.debug("Skipping non-subdomain file …")`) because the domain glob and sub-domain glob
  overlap. (Domain-level parse failures do throw — `:100`.) This silent skip is exactly what
  incoming change #1 fixes — rewrite this section when it lands (Part 4).

### S7 · P2 · STALE — "Current Domains" tables contradict disk
- **Doc:** `:481-500`. Wealth sub-domains listed as private-banking + **institutional** —
  institutional does not exist; actual: `private-banking` + `market-research`
  (`registry/domains/wealth-management/`), with goal_planning in private-banking
  (`private-banking.json:76-81`). Asset-servicing: custody-operations listed as
  `resource_scoped: false` with "No coverage service. Cerbos structural check only" — actually
  `resource_scoped: true` with settlement_risk added
  (`registry/domains/asset-servicing/custody-operations.json`), and `asset-servicing.json` now
  declares a coverage block. The **insurance** and **hr** domains are missing entirely. The doc's
  "role-based sub-domain" example (`:168-185`) uses custody-operations as its `false` example —
  now wrong on disk.
- **Fix:** Regenerate the tables from `registry/domains/` — or better, delete them and point at
  the folder (tables of live state in a doc are guaranteed to rot).

### S8 · P2 · STALE — The "Open Question" (tenant_id in JWT) is answered
- **Doc:** `:468-477`.
- **Code:** IAM enriches tokens with `tenant_id`
  (`iam-service/src/main/java/com/openwolf/iam/auth/OidcClaimEnricher.java:93`,
  `JwtClaimsCustomizer.java:23`); the gateway extracts it
  (`config/SecurityConfig.java:244`) and sends `X-Tenant-Id` on every coverage call
  (`domain/coverage/CoverageClient.java:60,92,137`).
- **Fix:** Replace the section with one line stating it's resolved.

### S9 · P2 · STALE — Coverage auth header: it's the caller's token, not a "gateway-service-token"
- **Doc:** `:282`, `:313` — `Authorization: Bearer {gateway-service-token}`.
- **Code:** `CoverageClient.applyBearer` (`CoverageClient.java:182-187`) forwards the bearer it
  was handed — the end-user's token from the request (`ChatService.java:427` passes
  `callerToken`). This matters: it's the per-hop-identity story the handbook §7 tells correctly.
- **Fix:** `Bearer {end-user JWT, forwarded}`. (The `X-Tenant-Id` header and the
  DISCOVER/CHECK/RESOLVE shapes, reason codes, and RESOLVE-is-principal-agnostic rule all match
  the code — this contract section is the standard's best, most-still-true content. Keep it.)

### S10 · P2 · STALE — "CI lint rule enforces the first two"
- **Doc:** `:464`. It's `scripts/world-b-check.sh` (wired as a hard gate in
  `scripts/verify.sh:54`). Name it, so a newcomer can run it.

---

## Part 3 — Gaps to add for "super super solid"

1. **A dead-simple copy-paste quickstart (top of the handbook).** Today there is no command
   sequence anywhere in either doc. Target 10 steps: copy a minimal HTTP manifest into
   `registry/manifests/<domain>/`, `docker compose -p orchestrator-demo up -d` (core set),
   `bash scripts/seed-users.sh`, restart `registry-service`, watch for
   `Registry ingestion complete: N loaded, 0 rejected` + `0 UNVALIDATED`, then see it route via
   `scripts/smoke-route.sh` / `POST /debug/route` (with the debug flag caveat), then
   `scripts/routing-measurement-gate.sh`, then `scripts/world-b-check.sh` and
   `scripts/verify.sh`. Note: the gateway reads the index from Redis, so a re-ingest does not
   require a gateway rebuild.
2. **A complete three-level field-reference table** (domain / sub-domain / agent) generated
   against the actual schema files — including `entity_types` (all 8 fields), `messages` +
   `denial_messages` key catalog (incl. `capability_unavailable`), `clarify_style`/`clarify_tone`,
   `figures`, `transport`/`protocol_version`, `securitySchemes`. The handbook's §13 covers only
   the agent level.
3. **A failure-mode catalog** (symptom → cause → fix), all strings verifiable in code:
   - `Rejected N manifest(s). A partially-loaded registry routes silently…` + container exit 1 →
     schema-invalid manifest (`RegistryIngestor.java:153`).
   - `No manifests found at …` → wrong path/mount (`RegistryIngestor.java:112`).
   - `select validation failed: agentId=…` → `select` references a field the producer doesn't
     emit (`SelectContractValidator.java:467`).
   - `… UNVALIDATED (no output schema …)` warning → producer lacks introspectable/declared
     output schema (`SelectContractValidator.java:80`).
   - `Embedding service never became ready after 15 attempts` → sidecar down
     (`RegistryIngestor.java:225`).
   - `Embedding service returned N-dim vectors but conduit.embedding.dimension is …` →
     model/config mismatch (`RegistryIngestor.java:207`).
   - Gateway refuses to start: no index / no agents / model-id mismatch →
     `RegistryReadinessVerifier` (start `registry-service` first).
   - `Sub-domain 'X' references unknown parent domain` / `… resource_scoped=true but … no
     coverage.discover_url` → `DomainManifestStore.java:173,182`.
   - `Pruning N orphaned agent(s)` → manifest deleted, agent deregistered on next ingest.
   - Malformed sub-domain manifest → **today silently skipped** (debug log only) — becomes loud
     with incoming change #1.
4. **The Cerbos step for a new domain.** Handbook §7 mentions it in passing (`:854-856`); neither
   doc lists "add the segment→domain mapping in `infra/cerbos/policies/`" as a numbered onboarding
   step with a file example. CLAUDE.md §6 step 4 has the canonical wording.
5. **Entity-type authoring guide** (H10) — the single biggest conceptual gap for a *new domain*
   (vs a new agent in an existing domain).
6. **How routing measurement is run and gated** (H5) — command, dataset, baselines, rebaseline
   procedure (`eval/goal-pick/REBASELINE.md`).
7. **The registry write control plane** — `POST/PUT/DELETE /admin/agents` lives on the
   registry-service, gateway keeps `GET /admin/agents` only (CLAUDE.md §3a;
   `registry/api/AgentRegistrationController.java:45` is `@Profile("registry")`). Neither doc
   mentions the API path exists as an alternative to folder-drop + restart.

---

## Part 4 — Required doc updates for the 3 in-flight manifest changes

### Change 1 — fail-loud schema validation of domain + sub-domain manifests
(Today: sub-domain parse failures silently skipped `DomainManifestStore.java:164-166`; no JSON-
schema validation at all for either level — only Jackson binding with `ignoreUnknown`.)
- `domain-onboarding-standard.md` **"Validation at Boot"** (`:430-443`): rewrite to describe the
  new behavior — every file under `registry/domains/` validated against
  `registry/domain-manifest.schema.json` / `registry/sub-domain-manifest.schema.json`, malformed
  → startup refusal with the file named. Delete the four checklist lines that aren't real (S6).
- `domain-onboarding-standard.md` **Level-2 examples** (`:136-185`): must gain `entity_types` or
  they will now fail the boot they're meant to teach (S2).
- Handbook **§8** pipeline: add step 0 "domain + sub-domain manifests are schema-validated;
  malformed = refused startup", and add the new error string to the failure-mode catalog
  (Part 3 §3).
- Handbook **§3** "Placement in the domain" table: note that `domain`/`sub_domain` resolution is
  now backed by validated manifests.

### Change 2 — `enum` on `io.produces[].figures[].format`
- Handbook: first **document `figures` at all** (H7 — new §5.x + §13 appendix row), then state
  the enum values as the closed set (replacing today's free-string description
  `agent-manifest.schema.json:223-227` "such as percent, percent1, currency_usd, count, plain,
  or date").
- Failure-mode catalog: new entry — unknown `format` now fails schema validation at ingestion
  (previously accepted silently).
- Reminder in both docs: the agent schema exists in **three synced copies** (H11) — the enum edit
  touches all three.

### Change 3 — optional `domain_context` on the DOMAIN manifest
(Replaces the hand-edited env string `conduit.assistant.domain-context` /
`CONDUIT_ASSISTANT_DOMAIN_CONTEXT` — `application.yml:79`, `docker-compose.yml:198` — consumed by
`IntentClassifier.java:173` and `AnswerSynthesizer.java:102` to compile prompts.)
- `domain-onboarding-standard.md` **Level-1 field table + example** (`:75-124`): add
  `domain_context` (optional string; what it feeds — the intent-classifier and synthesizer
  prompt skeletons' `{{domain_context}}` slot).
- Handbook **§3** (domain placement) and **§1** ("every user-facing phrase … lives in data you
  supply"): note that the assistant's self-description is now also manifest-declared — this
  closes the last hand-edited gateway config for onboarding, making the "zero gateway edits"
  claim literally complete.
- Handbook **§8/§6-workflow**: remove/replace any implied need to set the env var for a new
  domain; document precedence (manifest value vs env default) once the implementation lands.
- CLAUDE.md §6 workflow (out of scope for these two docs but same change): step 1 should mention
  `domain_context`.

---

## Part 5 — Separate or merge?

**Merge. Keep the handbook as the single onboarding document; retire the standard.**

- The standard's unique still-true content is small and specific: the coverage-service contract
  (DISCOVER/CHECK/RESOLVE shapes + reason codes + rules, `:275-364` — verified against
  `CoverageClient.java` modulo S9), the clarification/denial-copy philosophy, and the Level-1/2
  field tables (which need rebuilding anyway per S2/S3). Fold those into the handbook as a new
  "§7.5 The coverage service you must build" and an expanded domain/sub-domain reference, and the
  standard has nothing left that isn't stale or duplicated.
- Two overlapping docs are this repo's known failure mode (the in-JVM-DJL CLAUDE.md incident):
  the handbook and the standard already disagree on the same facts (examples quality-vs-quantity,
  MCP transport, manifest fields, fail-fast behavior). One doc, one truth.
- Replace `domain-onboarding-standard.md` with a 5-line pointer stub (or delete it and fix
  inbound references) so stale copies stop being findable.
