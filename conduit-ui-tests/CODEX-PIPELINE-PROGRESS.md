# Codex Brief — Live Pipeline-Progress Line in the Chat Center (frontend only)

Turn the dead "waiting" spinner into a **live, animated status line that narrates the pipeline** while
the answer is being produced — driven entirely by trace events we already stream. This is a
perceived-latency + demo-wow feature. **No backend change.**

## Why
In this router/orchestrator design the answer streams only once the **synthesizer** runs — after
intent → route → gates → agent fan-out. That's ~7s where today the center shows just a spinner. We
fill that time with what the system is actually doing, so the wait feels intelligent, not slow.

## Ground rules
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`
  (current head `cecd826`). Runtime: docker project **`orchestrator-demo`**, chat at
  **http://localhost:8099**. Rebuild: `docker compose -p orchestrator-demo build conduit-chat &&
  docker compose -p orchestrator-demo up -d conduit-chat`.
- **Login:** `rm_jane` / `Meridian@2024` (also `rm_carlos`, `analyst_amy`, `uw_sam`).
- **DO NOT touch:** auth / the Axiom redirect; gateway / iam / authz / registry; `backend-*`.
- **Frontend only:** `apps/chat/web`. All work **inside the repo folder** — no files/folders elsewhere.
- **Gate + commit:** `scripts/world-b-check.sh` CRITICAL 0; commit on `feat/conduit-chat`, message
  ending **"Approved by Sriraj."**, no AI attribution.

## What to build
While a turn is in flight and **no answer tokens have arrived yet**, replace the center spinner with a
status line that reflects the **current pipeline stage**, derived from the live trace events
(`traceEvents` from `useTraceStream`, already available in `ChatPane`). As soon as the first content
token streams, hide the status line and show the streaming answer.

### Stage mapping (conceptual — wire to the ACTUAL event types)
Read the real event type names from `apps/chat/web/src/lib/gatewayTrace.ts` (the `TraceEvent` shape)
and how `TraceRail` renders them, then map furthest-progressed event → label:

| Pipeline signal (confirm exact type names in code) | Center label |
|---|---|
| stream started / `request_start` / no stage events yet | `Understanding your question…` |
| intent classified | `Understanding your question…` |
| agents resolved (has a count `N`) | `Routing to {N} specialist agents…` |
| authorization gate events (segment / classification / coverage) | `Checking your access…` |
| agent start / agent complete (fan-out running) | `Gathering the data…` |
| synthesis start | `Composing your answer…` |
| first content delta (`streamingContent` non-empty) | *(hide status → show the streaming answer)* |

### Behavior
- **Monotonic within a turn** — the stage only moves forward; it must not flicker backwards as events
  interleave. (Compute the max stage index reached from the events seen so far.)
- **Dynamic count** — "Routing to N specialists" takes N from the resolved-agents event, not hardcoded.
- **Subtle animation** — a tasteful pulsing/shimmer on the active line (Tailwind `animate-pulse` on a
  trailing dot group, or a soft shimmer). Not gaudy.
- **Graceful on denial** — if the turn ends with no answer (a gate deny → red notice, e.g. `rm_jane` →
  Okafor), the status line must **stop cleanly**, not hang on "Checking your access…". Tie its
  visibility to `isStreaming` and clear it when streaming ends.
- **Keep labels generic** (no client/entity names); the count is the only dynamic value.

### The details that make it AMAZING (do NOT skip — this is the difference between "nice" and "gold")
- **Minimum dwell per stage (~500ms) — this is the single most important detail.** Several stages
  complete in <100ms, so without a floor they flash by invisibly and the whole thing looks like a
  glitch. Queue the target stage and advance the *displayed* stage no faster than ~500ms/step until it
  catches up — so the narration always reads as a smooth, deliberate step-by-step, even when the real
  events arrive in a burst. A viewer must be able to actually READ each stage.
- **Smooth transition to the answer.** When the first token arrives, crossfade (~150ms) the status
  line out and the answer in — never a hard swap.
- **A small evolving icon per stage** (lucide — already a dependency), e.g.
  `Sparkles → GitBranch → ShieldCheck → Database → PenLine`, with the active line showing
  icon + label + a soft pulsing dot group.
- **Accessibility:** the status line is `aria-live="polite"` so each stage is announced.
- **Exact copy (warm, confident, enterprise — tune only lightly):**
  - `Understanding your question…`
  - `Routing to {N} specialist agents…`
  - `Checking your access…`
  - `Gathering the data…`
  - `Composing your answer…`

### Edge cases (handle all — a stuck status line ruins the effect)
- **CLARIFY turns** (system asks a clarifying question instead of fetching): the pipeline stops early —
  end the status line cleanly when the clarification/answer arrives; don't hang on an early stage.
- **Denied turns:** end cleanly at the red notice (also stated above).
- **Very fast turns:** min-dwell still applies so it never flickers; if a token somehow arrives before
  the first stage renders, go straight to the answer.
- **Errors / aborted stream:** clear the status line immediately; never leave it spinning forever.

### Implementation sketch
- Add `selectPipelineStage(events: TraceEvent[]): { label: string } | null` (in `useTraceStream.ts`
  or a small helper) returning the current stage label, or `null` when there's nothing to show / a
  token has already arrived.
- In `ChatPane`, when `isStreaming && !streamingContent`, pass the stage to `MessageList` and render
  the status line where the "assistant is working" spinner is today; once `streamingContent` is
  non-empty, render the answer as now.
- **Optional wow (only if quick & clean):** show completed stages as a faint mini-checklist above the
  active line (✓ Understood → ✓ Routed to 5 → ⏳ Checking access…). Skip if it adds risk.

## Completion contract (don't hand back partial)
1. Finish it fully; leave `npm run build` green, world-b 0, committed on `feat/conduit-chat`, env
   healthy (`curl -s -o /dev/null -w '%{http_code}' http://localhost:8099` → 200).
2. **Browser-verify:** as `rm_jane`, send Whitman → during the wait the center shows the stages
   advancing (Understanding → Routing to N → Checking access → Gathering → Writing), THEN the answer
   streams and the status line disappears. Send Okafor → status line stops cleanly at the red denial,
   no hang. Screenshot mid-pipeline (showing a stage) + final answer.
3. Everything inside the repo folder; no silent skips; if blocked, revert cleanly and report exactly
   what's left.
