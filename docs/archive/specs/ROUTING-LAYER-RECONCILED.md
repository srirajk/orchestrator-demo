# Routing layer — reconciled review (Fable + Codex-SOL + Opus)

**Both independent reviews reached the same verdict: do NOT implement the spec as written.** They agree on
essentially every blocker. The core reconciled finding: **the spec treated grounding / masking / continuity
as fields added to existing records; both reviews prove they are CONTROL-FLOW changes.** This is an
architectural refactor, not a fix-the-spec-and-build.

## Where BOTH agree (settled — high confidence)
1. **Scalar `EntityBag` can't carry multi-reference.** One string per `extract_as`; the intent classifier
   returns one scalar per type. "Compare Whitman and Calderon" grounds one reference. → needs an **extractor
   mention/provenance model** (list of mention records: verbatim text, message identity, offsets), not a
   list-shaped `GroundingResult` over a scalar bag.
2. **Removing syntactic `entityKnown` regresses the FOLLOW_UP path.** `handleFollowUp:1152-1167` uses the
   syntactic helper as its admission gate + runs an unmasked probe *before* grounding. → needs a **single
   shared pre-routing preparation** (window→ground→mask→residual→relaxation-signal) used by FETCH_DATA,
   FOLLOW_UP, and the eval endpoint.
3. **Do NOT ship the v1 domain circuit-breaker.** It denies a legitimate mixed request when the pruned
   domain leads (violates the partial-fulfilment DoD and the shipped bug-260 behavior), AND misses
   same-domain substitution. → build **requested-capability groups** first.
4. **Request-global "deny on any interpretation" conflicts with partial fulfilment.** Verdicts must attach
   to **requested groups**, not the whole request. Retain `UNAVAILABLE` (fail-closed) + `denialReason` —
   the spec dropped both.
5. **The reranker can't derive `{primary, requestedGroups, dagGoal}`.** Output is one-id/`multiple`/`abstain`;
   `multiple` keeps all noisy candidates. → **expand the bounded reranker contract** (`multiple` returns
   explicit shortlist ids) — shared by Stage 3 and Stage 5. Do not infer groups from every floor-passed candidate.
6. **Spans are in the wrong coordinate space.** Grounder sees `latestPrompt`; the routing query is a
   trimmed, reversed, newline-joined multi-turn string. → keep spans **message-local**, mask each selected
   message before join; normalize/merge overlapping+duplicate spans; a carried focal reference has no
   latest-turn span (mask its prior mention if that turn is in-window).
7. **Fail-open-on-alignment-miss must NOT get the trusted relaxation.** Mark masking incomplete → keep
   normal score/margin abstention; prefer clarify/abstain over trusting the raw entity-dominated route.
8. **Coverage checks only the FIRST resource-scoped survivor** (`findFirst`, one effective manifest, one
   bag id). A real multi-domain request can't be correctly checked/bound. → **per-group coverage**.
9. **DAG producer prune silently flat-fallbacks** (`:1043-1053`) — loses that the asked capability can't be
   fulfilled and runs adjacent survivors. → derive goal+producer closure before disposition; authz prune ≠
   technical miss.
10. **Eval:** the `/debug` flag / pre-masking is a test-only fork; extract the shared production preparation
    behind a decision endpoint; the current gate doesn't gate exact-capability accuracy and excludes
    confusers; **96.3% was never a chat-path baseline** → A/B/C frozen measurements + joint threshold
    grid-search with grouped held-out split.
11. **World-B:** no Java precedence table / domain enum / type-word mask token (ban `EntityType.display/
    resolveType/key` as the token); denial copy via a new generic manifest `messages` key; hoist per-call
    `Pattern.compile`; bound the N×M coverage-call latency (VT history).

## Disagreements (minor — both essentially aligned)
- v1 breaker: Fable "fix-the-signature or go durable"; Codex "remove it, build groups first." → **Resolution:
  don't ship v1; go straight to the requested-group model.** (Both agree v1-as-written is unsafe.)
- Otherwise no contradictions — remarkable convergence.

## The TRUE scope (control-flow, not fields) — 5 architectural pieces
1. **Extractor mention/provenance model** (multi-mention, offsets, message identity) — replaces scalar bag.
2. **Shared pre-routing preparation pipeline** (window→ground→mask→residual→relaxation) for FETCH/FOLLOW_UP/eval.
3. **`RequestedPlan(groups[])` model** with per-group coverage/auth/binding/withhold — replaces first-survivor
   pipeline AND the v1 breaker; also fixes DAG goal+producer continuity.
4. **Expanded bounded reranker contract** (explicit ids for `multiple`) shared by continuity + conflict trigger.
5. **Production-path eval** (shared preparation behind a decision endpoint) + real gates + A/B/C re-baseline.

## Interim value already banked (Stage 0, local, verified)
De-entiting the corpus alone fixed bug-261's actual mis-route (`settlements for Continental` → settlement_status
0.637, no insurance). The recall dip is masking-dependent; a threshold re-baseline recovers *most* of it (some
overlap remains without masking). So Stage 0 + re-baseline is a real, shippable interim improvement; the full
SOTA (pieces 1–5) is a dedicated refactor.
