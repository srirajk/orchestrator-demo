# Agent Manifest Deep-Dive — Completeness, Candidate Additions, Hidden Coupling, Robustness

**Status:** audit (read-only) · **Date:** 2026-07-11 · **Auditor:** Fable
**Question:** Is the manifest solid and complete, and is anything the gateway holds today (generically) that SHOULD be manifest-declared?

**One-line verdict: the manifest surface is solid and near-complete — the domain-knowledge line is drawn in the right place — but three concrete changes are NEEDED: (1) fail-loud schema validation for domain/sub-domain manifests, (2) an enum on `figures[].format`, (3) moving the assistant "domain context" framing from env config into the domain manifest.**

The manifest is really THREE contracts, and this audit covers all of them because the gateway's
domain knowledge is spread across them by design:

| Contract | File | Schema | Schema enforced at runtime? |
|---|---|---|---|
| Agent manifest | `registry/manifests/<domain>/<id>.json` | `gateway/src/main/resources/agent-manifest.schema.json` (3 synced copies) | **Yes** — `ManifestValidator` (networknt, fail-loud) at registry ingestion (`gateway/src/main/java/ai/conduit/gateway/registry/service/ManifestValidator.java:38-46`) |
| Domain manifest | `registry/domains/<domain>.json` | `registry/domain-manifest.schema.json` | **No** — Jackson-parse only |
| Sub-domain manifest | `registry/domains/<domain>/<sub>.json` | `registry/sub-domain-manifest.schema.json` | **No** — Jackson-parse only, silent skip on failure |

---

## 1. Completeness — what a domain can declare today

### 1.1 Agent manifest (`agent-manifest.schema.json`, gateway classpath copy)

| Declaration | Schema ref | Consumed by (verified) |
|---|---|---|
| Identity: `agent_id`, `name`, `description`, `version`, `provider` | :10–:30 | routing payloads, registry |
| `domain` (open string — validated against loaded domain manifests, not an enum) | :31–:35 | authz, effective-manifest merge |
| `sub_domain` | :117 | effective-manifest merge (with reverse-map fallback, see §4.4) |
| `audience` = `segment` \| `enterprise` (segment gate skip) | :36–:40 | authz gates |
| `protocol` = http \| mcp \| a2a + per-protocol `connection` conditionals (incl. MCP `transport`, `protocol_version` override) | :41–:49, :270–:311 | ProtocolAdapters |
| `capabilities.streaming` | :50–:57 | harness |
| `skills[]`: id/name/description/`tags[]` (min 1)/`examples[]` (**min 3** — the routing corpus) | :62–:88 | HNSW embedding index (ingestion) AND reranker prompt (2/skill, `LlmRoutingRerankerClient.java:87-93`) |
| `constraints`: `access_mode` (read\|write), `data_classification` (4-level), `sla_timeout_ms`, `rate_limit` | :90–:115 | access_mode/classification → ABAC; sla → harness; **`rate_limit` consumed nowhere** (§1.3) |
| `max_response_tokens` (pre-synthesis truncation) | :121 | synthesis input |
| `output_schema` (fallback only; wire schemas otherwise derived by introspection) | :127 | registry pipeline |
| `io` dataflow contract: `consumes` (entity leaf \| `from` edge + JMESPath `select`), `produces` (name/type + `entities[]` id-selectors + `figures[]`), `condition`, `map` (over/item_select/caps) | :131–:268 | DagResolver, Blackboard, coverage filter, `GroundedFigureRenderer` |
| `io.produces[].figures[]`: `label` + `path` (JMESPath) + `format` | :205–:230 | `GroundedFigureRenderer.java:34-87`, `GroundedFigureValidator.java:89-94` |

### 1.2 Domain + sub-domain manifests

Domain (`registry/domain-manifest.schema.json`): `display_name`, `clarify_style`
(template\|composed, :16-:21), `clarify_tone` (:22-:26), `coverage` discover/check/resolve URLs +
TTL (:27-:54, env-var placeholders resolved at load — `DomainManifestStore.java:108-136`),
`memory_compaction` policy (:55-:114).

Sub-domain (`registry/sub-domain-manifest.schema.json`): `resource_scoped` (:29),
`entity_types[]` — key/`extract_as`/kind(resolvable\|literal\|list)/`display`/`id_pattern`/
`resolve_type`/`required`/`default` (:30-:47), `required_context[]` (drives the deterministic
CLARIFY, :48), `denial_messages` per reason-code + default (:52), `messages` (user-facing copy:
no_coverage, reference_not_found, needs_more_detail, specify_entity, missing_entity_question,
capability_unavailable…, :56), `clarification_schema` per entity (question/`options_source`/
priority/default, :60-:76), `agents[]` membership (:77-:84).

**Verified consumption:** entity_types drive prompt compilation (`IntentClassifier.buildSystemPrompt`,
`IntentClassifier.java:62-139`), extraction JSON keys, id-pattern grounding
(`DomainManifestStore.identifyAllByIdPattern` :371-:392), CLARIFY (`requiredContextKeys` :269-:275),
clarification wording (`ClarificationComposer` + `clarification_schema`), all user-facing copy
(`ChatService.msg`/`mapDenialReason` :1888-:1901 — every fallback literal in the gateway is
domain-neutral, verified at ChatService.java:535, 586, 757, 820, 886, 1595, 1932, 2163).

### 1.3 Completeness verdict

**A domain can express essentially everything it needs.** The 20 real manifests across 4 domains
use the surface non-trivially (multi-input fan-in with per-edge JMESPath projection —
`meridian.insurance.renewal_risk.json:93-105`; 3-producer DAG —
`meridian.servicing.settlement_risk.json:82-99`; MCP + streamable transport — same file :13-:18;
enterprise-audience knowledge agent with zero entity requirements — `meridian.hr.policy_qa.json`,
`registry/domains/hr/hr-knowledge.json:18`).

Things a domain **cannot** express today (assessed in §2/§3/§5): per-domain assistant framing
(lives in env config), non-USD currency figures, labeled intent-boundary examples, per-array-item
figures, write-side semantics beyond the `access_mode` enum.

**Dead declared surface** (declare-able but consumed by nothing — misleads domain teams):
- `constraints.rate_limit` (schema :106-:114) — no consumer; `AgentManifest.Constraints` has no
  rateLimit component (`AgentManifest.java:111-115`), so it is silently dropped by
  `@JsonIgnoreProperties(ignoreUnknown=true)`.
- `securitySchemes` (schema :58-:61) — zero references in gateway Java.
- `messages.followup_clarification` (declared in `private-banking.json:60`,
  `claims-servicing.json:52`) — no consumer; its `{entity}` placeholder has no substitution
  support (only `{reference}` is substituted, `ChatService.java:760`).

---

## 2. The two candidate additions — verdicts

### 2a. `routing_examples` manifest field — **NO** (redundant)

The manifest already carries the routing example corpus: `skills[].examples` with `minItems: 3`
(schema :79-:84), and it is consumed by BOTH routing mechanisms:

1. Embedded into the HNSW index at registry ingestion (`AgentManifest.allExamples()`,
   `AgentManifest.java:222-227`).
2. Injected into the LLM reranker's candidate payload — 2 examples per skill —
   `LlmRoutingRerankerClient.java:87-93`.

A separate `routing_examples` field would duplicate the same declaration with divergence risk and
no consumer that couldn't read `skills[].examples` instead. The abstract few-shots the
externalization work added to `intent-classifier.system.md:54-56` are placeholder-style
(`<entity name>`) and World-B clean.

**One conditional nuance:** what `skills[].examples` genuinely cannot express is **labeled
intent-boundary pairs** (query → FETCH_DATA/FOLLOW_UP/CLARIFY) — all skill examples are
FETCH_DATA-shaped. PROMPT-EXTERNALIZATION-DESIGN.md (I2) already gates this correctly: only if
DeepEval shows the abstract in-resource examples underperform should an optional, labeled
`intent_examples` block be added (sub-domain manifest). Until that evidence exists: **do not add
it.**

### 2b. Figure formats — **already manifest-declared; the NEED is an enum, not a new field**

The premise "if formats aren't fully manifest-declared, that's a gap" is satisfied: every figure
declares `label` + `path` + `format` in the manifest (schema :205-:230; e.g.
`meridian.wealth.concentration.json:177-198` declares percent1/count/plain per figure), and both
the renderer (`GroundedFigureRenderer.renderOne` reads `figure.format()`, :63/:70) and the
validator (`GroundedFigureValidator.formatCompatible`, :89-:94) read the manifest value. The
formatter registry itself (percent/percent1/percent2/currency_usd/count/date/plain,
`GroundedFigureRenderer.java:78-86`) is correctly gateway-owned generic machinery.

**The real gap is validation looseness — NEED:** the schema types `format` as any non-empty
string (:223-:227, the known ids appear only in a description). A typo (`curency_usd`) passes
ingestion, falls through the renderer's `default -> scalarText` branch
(`GroundedFigureRenderer.java:85`), and the validator then treats the figure as `plain`
(`GroundedFigureValidator.java:90`) — so a mislabeled currency figure loses its `$`-format gate
and the whole grounded-figure protocol silently weakens. Proposed change (one line, schema only,
all three copies):

```json
"format": {
  "type": "string",
  "enum": ["percent", "percent1", "percent2", "currency_usd", "count", "date", "plain"]
}
```

**Related NICE-TO-HAVE (roadmap):** the format vocabulary is USD-only. The validator's numeral
regex recognizes only `$` (`GroundedFigureValidator.java:16`) and the only currency format is
`currency_usd`. An international domain (EUR/GBP books) cannot declare its figures. When such a
domain onboards, extend generically (e.g. `"format": "currency", "currency_code": "EUR"` with a
symbol table in the renderer/validator) — do not pre-build it now (CLAUDE.md rule g).

---

## 3. Hidden domain coupling — what should move to the manifest

`scripts/world-b-check.sh` greps for known literals (domains, names, `REL-`/`FND-`, entity field
names — script :74-:84) and reports CRITICAL 0; the items below are semantic coupling it cannot
see.

### 3.1 `conduit.assistant.domain-context` — domain framing in config, not manifest — **NEED**

The IntentClassifier's prompt opener ("You are a routing classifier and entity extractor for
{{domain_context}}", `intent-classifier.system.md:1`) and the history-synthesis prompt take their
domain framing from a single env property with a committed, wealth-flavored default:

- `IntentClassifier.java:173` / `AnswerSynthesizer.java:102`:
  `@Value("${conduit.assistant.domain-context:an enterprise data assistant for relationship managers}")`
- `application.yml:79` — same default.
- `docker-compose.yml:198` — the demo overrides it with a **hand-maintained cross-domain summary**:
  "client financial data, market research, and internal HR policies".

That compose string is literally a prose index of the loaded domains. Onboarding a new domain
(the §6 workflow: manifest + coverage URL, no gateway change) leaves this framing stale unless
someone edits platform deploy config — which is exactly the coupling World-B exists to remove. It
is also a single global string: with multiple domains loaded, one domain's framing serves all.

**Proposed change:** add an optional `domain_context` (string) to `domain-manifest.schema.json`
(e.g. `"wealth-management": "client relationships, portfolios, and holdings for relationship
managers"`), and have the gateway compose the opener generically from the loaded domain manifests
(join of display_name/domain_context), keeping `conduit.assistant.domain-context` only as a
deployment override. The composition loop is generic interpreter code; the copy becomes a domain
declaration. This also fixes the same coupling in the classifier prompt for every future domain
with zero config edits.

### 3.2 `conduit.assistant.display-name:Meridian` — committed brand default — minor, config not manifest

`ClarificationComposer.java:88` and `AnswerSynthesizer.java:101` default the assistant identity
to `Meridian` (a client/brand literal) in Java `@Value` defaults and `application.yml:80`. This is
*platform branding*, not per-domain knowledge, so the domain manifest is NOT its home — but a
committed brand default in gateway source sits badly next to CLAUDE.md §5 ("no client-specific
data in the gateway"). Recommendation: keep it config, move the default value out of Java/
application.yml into `docker-compose.yml` like the secrets pattern (`${VAR:-Meridian}`), so
gateway source carries no brand literal. Not a manifest change.

### 3.3 Cross-domain message fallback — borrowed copy on missing keys — minor

`DomainManifestStore.message(key, subDomainId)` falls back to an unscoped all-sub-domain search
(:298-:306), and HR/asset-servicing manifests declare few or no `messages`
(`hr-knowledge.json` has none; `corporate-actions.json:39-41` only `capability_unavailable`). An
HR request that needs `needs_more_detail` today surfaces **wealth** copy ("If you mention the
client…", `private-banking.json:57`) via HashMap-order luck. The scoped-first lookups (bug 238
fix, :326-:341) fixed denials; the residual unscoped fallback should prefer the gateway's
domain-neutral literal (already passed at every call site) over another domain's copy.
Gateway-logic tweak + manifest hygiene (declare the keys per sub-domain); no schema change.

### 3.4 Confirmed NOT coupling (correctly gateway-owned — do not move)

- The 4-intent taxonomy, focal/anaphora rules, and abstract few-shots
  (`intent-classifier.system.md`) — generic reasoning skeleton; all entity JSON keys, rules, and
  id-pattern hints compiled from `entity_types` per request (`IntentClassifier.java:62-103`).
- The reranker single/multiple/abstain contract (`routing-reranker.system.md`) — fully static,
  zero domain tokens; candidates arrive as manifest data.
- The grounded-figure placeholder protocol + validator/renderer machinery.
- The anaphora token list (`IntentClassifier.java:174`) — language function-words, configurable.
- The disposition machinery, coverage RESOLVE/CHECK lattice, stable interpretation ordering
  (`DomainManifestStore.java:358-:392`) — generic over manifest declarations.

---

## 4. Schema robustness — would a bad manifest fail loudly?

### 4.1 Agent manifest — **good**

`additionalProperties: false` at the top level (schema :7), required core fields (:8), semver +
id patterns, protocol conditionals, `minItems: 3` on examples. Ingestion validates against the
schema and **throws** (`ManifestValidator.java:38-46`). The 3-copy sync is build-gated
(`ManifestSchemaCopiesInSyncTest`). Residual softness: `connection` itself is a free-form object
(only the conditionals constrain it, no additionalProperties:false, :46-:49); `capabilities` is
`additionalProperties: true` (:52); `figures[].format` free string (§2b).

### 4.2 Domain/sub-domain manifests — **the biggest hole: silent degradation — NEED**

`registry/domain-manifest.schema.json` and `registry/sub-domain-manifest.schema.json` exist but
are **enforced nowhere** — no Java, script, or test references them (repo-wide grep). At gateway
boot:

- Domain files: Jackson-parse failure throws (`DomainManifestStore.java:100`) — loud. But field
  content is unvalidated: a missing `coverage.resolve_url` or a bad `clarify_style` value loads
  fine and misbehaves later.
- **Sub-domain files: any parse problem is `log.debug("Skipping non-subdomain file …")` and the
  file is dropped** (`DomainManifestStore.java:164-166`) — the loader must tolerate domain-level
  files matched by the same `domains/**/*.json` glob (:146), so a genuinely malformed sub-domain
  manifest is indistinguishable from a domain file and vanishes silently. Downstream, `EntityType`
  is `@JsonIgnoreProperties(ignoreUnknown=true)` (`EntityType.java:24`) — a misspelled
  `id_patern` yields a null pattern and quietly disables deterministic id grounding for that
  entity; a dropped sub-domain removes its `required_context` and the CLARIFY gate for the whole
  domain.

**Proposed change (registry/gateway load path, no schema-content change):** validate every
`domains/*.json` against `domain-manifest.schema.json` and every `domains/*/*.json` against
`sub-domain-manifest.schema.json` at load (networknt is already on the classpath via
`ManifestValidator`); distinguish the two by path depth instead of parse-success; refuse startup
on any failure — mirroring the agent-manifest behavior and the existing fail-fast precedents
(resource_scoped-without-coverage already throws, `DomainManifestStore.java:177-186`; missing
prompt resources fail startup, `IntentClassifier.java:210`). Also bring both schemas under the
same 3-copy/classpath discipline as the agent schema.

### 4.3 Versioning — NICE-TO-HAVE

Agents carry their own semver (:17-:21) but no manifest kind carries a **schema** version; the
memory envelope already models this correctly (`envelope_version: context-envelope.v1`,
domain schema :60-:63). An optional `manifest_version` const/enum on all three kinds would make
future schema evolution detectable at ingestion instead of by Jackson accident. Cheap; not urgent
while all manifests are in-repo.

### 4.4 Known deserialization wart — fix in gateway, not manifest

`AgentManifest.subDomain` sometimes deserializes null (Jackson/record quirk), compensated by an
agentId→subDomain reverse map built from sub-domain `agents[]` lists
(`DomainManifestStore.java:34-36, :205-212`). The workaround is correct but the quirk should be
root-caused; it is exactly the class of silent field-drop §4.2 warns about.

---

## 5. Extensibility for the roadmap — can the next domain fit with zero gateway Java?

| Next-domain shape | Expressible today? | Assessment |
|---|---|---|
| New read domain, HTTP or MCP agents, own coverage service, own entity types/copy | **Yes, fully** — the 4 live domains prove the whole surface incl. enterprise-audience, non-resource-scoped, and MCP transport/version overrides | — |
| Multi-figure attribution (many figures per output, multi-producer fan-in) | **Yes** — settlement_risk declares 3 consumed producers + 5 figures | — |
| Per-array-item figures ("each position's weight") | **No** — `figures[].path` must select a scalar; arrays degrade to `scalarText`/no numeric values (`GroundedFigureRenderer.java:89-90, :136-141`) | NICE-TO-HAVE when a domain actually needs it (e.g. `each_of` array path + item label template). Don't pre-build |
| Non-USD currency figures | **No** (§2b) | NICE-TO-HAVE, generic `currency` + code |
| Write/action capability | **Seam exists** — `access_mode: "write"` is in the enum (:96-:100) and resolver-enforced read-only per Phase 1; no confirmation/idempotency/side-effect declarations yet | Correct per rule g (build the seam, not the feature). When write phases in, additions are schema-only |
| Different clarification style/tone per domain | **Yes** — `clarify_style`/`clarify_tone` (domain schema :16-:26) flow through `EffectiveManifest` (:58-:59) to the composer (`ChatService.java:1972`) | — |
| Multiple domains loaded at once | Manifest-side yes; gateway-side the prompt entity union (`DomainManifestStore.entityTypes()` :223-:232) and the global `domain_context` (§3.1) are single-domain seams, explicitly documented in code | §3.1 is the manifest half; the union-scoping is gateway work, out of scope here |

---

## 6. Decision summary

**NEED (do these):**
1. **Fail-loud validation of domain + sub-domain manifests** against their existing schemas at
   registry/gateway load; kill the silent-skip in `DomainManifestStore.loadSubDomains`
   (`:164-166`). Biggest robustness hole; schemas already written, validator lib already present.
2. **Enum `io.produces[].figures[].format`** in `agent-manifest.schema.json` (all 3 copies):
   `percent|percent1|percent2|currency_usd|count|date|plain`. Closes the silent-degrade path
   through `GroundedFigureRenderer.java:85` / `GroundedFigureValidator.java:89-94`.
3. **Add optional `domain_context` to the domain manifest** and compose the assistant's domain
   framing from loaded domains; demote `conduit.assistant.domain-context` to an override. Removes
   the last domain-shaped copy that must be hand-edited outside a manifest when onboarding
   (`IntentClassifier.java:173`, `application.yml:79`, `docker-compose.yml:198`).

**NICE-TO-HAVE (backlog, in priority order):**
- Prune or wire the dead declared surface: `constraints.rate_limit`, `securitySchemes`,
  `messages.followup_clarification` (+ `{entity}` substitution if kept).
- `manifest_version` on all three manifest kinds; bring domain/sub-domain schemas under the same
  copy-sync/classpath discipline as the agent schema.
- Generic currency support (`currency` + ISO code) when a non-USD domain onboards.
- Move the `Meridian` display-name default out of gateway source into compose (config hygiene,
  not a manifest field).
- Labeled `intent_examples` (sub-domain manifest) **only if** DeepEval shows the abstract
  in-resource few-shots underperform (per PROMPT-EXTERNALIZATION-DESIGN.md I2).
- Per-array-item figures, write-capability declarations — build when a real domain demands them.

**NO (rejected):**
- `routing_examples` manifest field — redundant with `skills[].examples`, which already feeds both
  the HNSW index and the reranker prompt (`LlmRoutingRerankerClient.java:87-93`).
- Moving any prompt skeleton, validator logic, formatter registry, disposition machinery, or
  anaphora handling into the manifest — all generic interpreter machinery, correctly gateway-owned.
