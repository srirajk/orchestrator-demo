# T7 — Audit / Replay (design of record + phased build) — Fable-designed, Opus-verified

> GATEWAY code, regulator-facing. **World-B applies** (the record/replay/store machinery is generic; no
> domain literals). Run `scripts/world-b-check.sh` before/after, CRITICAL 0. Do NOT commit (reviewer
> commits). Repo `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Big
> task — **built in phases T7a → T7b → T7c, one session each, verified/committed between.** If ambiguous,
> STOP and report.

## The honest thesis (what "replay" means here — say this to a validator)
Every decision is either **re-derived** in front of the reviewer from frozen inputs, or is a **hash-attested**
recorded input/output pair of a non-deterministic component (LLM) or external system (agent/coverage). Nothing
is unexplained. Because the middle is deterministic (LLM only picks the goal + phrases the answer), the
**re-derivable fraction is unusually large** — routing thresholds, planning, authorization, dataflow, control
flow, and grounding all re-execute; the LLM contributes one goal label, optional rank adjustments, and prose,
which are attested (hosted LLM inference is NOT bit-reproducible even at temperature 0 — do not claim you can
re-run it). Framing: the store is **17a-4-*shaped*** (append-only + audit-trail alternative prong, per the SEC
2022 amendment that ended WORM-only), NOT "compliant" — it's a bank-prospect system, not a books-and-records
system.

## Verified starting reality (from code)
- Trace foundation is rich: `infrastructure/telemetry/TraceEvent.java` + `event/*` emit 14 event types
  (request_start, intent_classified, agents_resolved [candidates+scores+filtered-with-reasons], gate,
  entitlement_check, check_denied, agent_start/complete, node_condition, map_iteration, grounded_figures,
  synthesis_start, plan_graph, request_complete). Reuse this as the record body.
- **`DagResolver.resolve` is pure/deterministic/order-stable** (the replay keystone) — re-derivation works
  IFF the snapshot identity is captured correctly.
- Gaps (all confirmed): Redis trace has a 24h TTL + best-effort `save()` that swallows errors; agent output is
  an **80-char preview** (`ChatService.java:~828`) — full payload captured nowhere; DAG-abandon is `log +
  Micrometer counter` (`emitDagFallback`), **not a per-request trace event** (no `dag_abandoned` type exists);
  **no content-hashing anywhere** in the gateway (the whole hash/snapshot stack is greenfield).
- **No `LLMClient` interface** — 5 call sites (AnswerSynthesizer, IntentClassifier, EntityExtractor,
  ClarificationComposer, LlmRoutingRerankerClient) each build their own HttpClient. The re-ranker alone sits
  behind `RoutingRerankerClient`.
- The **final answer is not captured** (RequestCompleteData = totalMs/agentCount/successCount only; the answer
  streams via SSE and only reaches an OTel span attribute).
- Identity (`ProtocolAdapter.invoke(...,bearerToken)`, fail-closed jwt_verify) + per-producer coverage
  (`DagPlanExecutor.evaluateNodeCoverage`/`applyProducedEntityCoverage`, `AgentManifest.Produce.entities`) NOW
  EXIST to record — the old F1/F2 caveat is resolved.

## The Decision Record (schema) — one per chat turn
- **Envelope:** record_id, request_id, conversation_id, schema_version, occurred_at(start/end),
  **principal {user_id, segment, audience, access_mode, token_claims_digest, jwks_key_id, AUTH_ASSURANCE:
  jwt | x-user-id-fallback}** — the weak `x-user-id` path is the **LibreChat-only** fallback (a known auth
  hole), NOT the eval; recording assurance lets the store distinguish strongly- from weakly-authenticated
  principals. **origin {channel: librechat|api|...}** — normal provenance. **Eval correction:** the
  production continuous-eval OBSERVES real recorded traces via Langfuse, scores them (DeepEval), and posts
  scores back — it issues NO gateway requests and creates NO audit records; the golden-dataset *driver*,
  when run, authenticates with a real JWT. Neither is "synthetic flooding" (an earlier review
  mischaracterized a trace-observer as a traffic generator) — so no special eval-flood mitigation is needed;
  an `eval` origin tag is fine as ordinary provenance if the driver runs. prev_record_hash, record_hash,
  outcome {ANSWERED|CLARIFIED|DENIED|PARTIAL|ERROR}+reason.
- **Snapshot references (by content hash — the greenfield keystone):** registry_snapshot_id (SHA-256 over
  canonicalized manifests + domain manifests), policy_bundle_hash (Cerbos), prompt_template_hashes (compiled
  per call site), config_hash (routing thresholds/rerank config/deadlines/model names), embedding_model_id,
  gateway_build (git sha). Snapshots stored ONCE content-addressed (`snapshot/{hash}`); records carry refs.
- **Decision body:** the existing trace events, upgraded — full LLM request/response per call site,
  re-ranker fired+in/out, resolver inputs, **`dag_abandoned` as a real per-request trace event** (reason-coded),
  **full agent payloads** (alongside the preview), grounded-figure validator verdicts, coverage/identity
  decisions (now real), and the **final answer text** (accumulated from the SSE stream).

## Phase T7a — snapshot identity + record schema (THE KEYSTONE; do first)
1. **Content-hashing infra (greenfield):** canonical JSON (RFC-8785-style) + SHA-256 helper; on registry
   load/reload (`RegistryBootstrapLoader`/`AgentRegistry`) compute per-artifact digests + a combined
   `registry_snapshot_id`; store snapshots content-addressed; expose the current snapshot_id.
2. **Record envelope + snapshot refs** as above — including **AUTH_ASSURANCE** (jwt vs the LibreChat-only
   x-user-id fallback — derive from how the principal was actually authenticated; this is the real weak-auth
   case, not the eval) and **origin** (channel provenance). Thread the snapshot_id into the per-request
   context so every event/record carries it.
3. **Gate:** every request produces an envelope with a correct snapshot_id, auth-assurance, and origin;
   changing a manifest changes the snapshot_id (test); a request on the x-user-id path is recorded with weaker
   assurance than a JWT request; World-B 0; mvn green. (No store/replay yet — this phase just makes every
   decision pin *what produced it* and *how strongly the caller was authenticated*.)

## Phase T7b — close the capture gaps
Add the missing captures (behind config; keep the 80-char preview for the live panel):
- **Full agent payloads** (the F3-relevant gap — a truncated payload hides a confident-wrong-number).
- **`dag_abandoned`** as a real reason-coded per-request trace event (not just the counter) — the S-fallback
  visibility fix; a silent flat fallback must be auditable.
- **Re-ranker** fired + full in/out at the `RoutingRerankerClient` seam (clean today).
- **Grounded-figure validator verdicts** per figure (attribution pass/fail).
- **LLM-call capture:** extract a minimal **`LLMClient` seam** across the 5 sites (or a per-site capture hook)
  — budget this refactor explicitly; capture {compiled prompt, model/provider/params, full response, tokens}.
  (This is the one genuinely under-costed piece — do the seam, don't hand-wave "one decorator".)
- **Final answer capture over SSE:** accumulate the streamed deltas server-side to record the exact bytes the
  user saw, WITHOUT perturbing byte-correct SSE timing (hard-rule a) — net-new, treat as a real task.
- **Gate:** a request's decision body now contains full agent payloads, the reason-coded abandon events, the
  re-ranker in/out, the LLM in/out at every site, the grounded verdicts, and the final answer text; SSE byte-
  shape unchanged (assert); mvn green; World-B 0.

## Phase T7c — AuditRecordStore + replay verifier + access
1. **`AuditRecordStore` interface** (mirrors `TraceStorageAdapter`): `append(record)`, `getChain(range)`,
   `checkpoint()`. First impl: hash-chained NDJSON segments + content-addressed snapshot blobs on a dedicated
   volume (or MinIO). **Per-request/per-shard chains checkpointed into a spine — NOT one global chain** (a
   global chain is a serialization point that fights the virtual-thread fan-out; this is the real perf risk,
   not SHA-256 cost). `strict|degraded` write mode (`conduit.audit.mode`): strict fails the request if the
   record can't persist; demo runs degraded but escalates loudly (no silent swallow for audit records).
   Signed checkpoints anchored to a second trust domain (e.g. the OTel/Langfuse store or a separate bucket).
2. **Replay verifier** (`scripts/audit-replay.sh <requestId>` + an admin endpoint feeding the glass-box panel):
   (0) integrity — recompute record hash + chain link + checkpoint → INTACT/TAMPERED; (1) **re-derive** —
   `DagResolver.resolve` over the snapshot must equal the recorded plan; re-evaluate Cerbos gates over recorded
   principal attrs; re-run translators/predicates/grounding over recorded agent outputs; CLARIFY set-logic →
   MATCH/MISMATCH (a mismatch surfaces a gateway_build delta = detected behavioral drift); (2) **attest** — LLM
   calls + agent/coverage responses shown in/out with versions, and the *parse/interpretation* re-derived
   (recorded LLM response → parsed goal id matches; recorded coverage response entails the recorded decision).
3. **Access:** an admin `/audit/records/...` API gated by a **Cerbos `audit.read` action + `audit_reader`
   audience** (reuse the 3-gate machinery, World-B clean); **every audit read is itself an audit event**
   (reader, record, timestamp); no direct human store access. Records inherit the most-restrictive
   classification of any data that crossed them (tag in envelope).
4. **Gate:** a completed request writes an intact chained record; the replay verifier shows per-stage
   MATCH/ATTESTED/INTACT on a real request; the **demo money-shot** — change a manifest, the old record still
   replays MATCH against its frozen snapshot while the live system behaves differently; a DENIED/CLARIFIED/
   fallback request is also recorded; audit reads require `audit.read` + emit an audit-of-audit event;
   concurrency: N concurrent requests all get intact records with no global-lock regression (a load check);
   mvn green; World-B 0.

## Deferred to the seam (designed, NOT built now — do not over-build)
Field-level encryption + crypto-shredding (90-day demo, no real PII — put the confidential-field boundary +
key-id column behind the interface, defer the KMS/shred machinery); certified WORM backend (S3 Object Lock
compliance-mode / 17a-4-assessed vendor behind `AuditRecordStore`); Merkle inclusion/consistency proofs
(single-writer log — a signed external checkpoint suffices); multi-instance chain-sharding automation;
retention/legal-hold automation (demo 90d, documented production target 7yr per MiFID II); a formal GDPR Art.
15(1)(h) narrative generator (the record has the data; the prose renderer is product work).

## Constraints / anti-gaming (all phases)
- Replay must actually RE-EXECUTE the deterministic stages (not compare recorded-to-recorded); a MISMATCH/
  TAMPERED must genuinely fail. The hash chain must be tamper-evident (a flipped byte fails integrity — test
  it). Audit-write in strict mode must fail-closed, never silently drop. World-B clean; do NOT commit.

## Report (per phase)
Files; the schema/hashing/store/replay as built; the four corrected items (LLMClient seam, dag_abandoned as
event, SSE final-answer capture, auth-assurance+eval-origin in envelope); the gate evidence (esp. the
change-a-manifest-still-replays money-shot and the tamper-fails-integrity test); mvn/World-B/perf results.
STOP and report anything unanticipated.
