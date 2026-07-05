# Codex Brief — Fix the Access-Notice Flicker (frontend only, small + surgical)

Stop the access notice from flashing **red "Access denied"** during the pipeline before it settles to
**blue "Partial access"** once the answer lands. Show the notice only once the turn has resolved.

## The problem (observed live)
As `rm_carlos`, ask about **Sterling** (a query that succeeds but withholds asset-servicing):
- **During the ~7s pipeline:** a **red "Access denied — you don't have access to the asset-servicing
  data"** banner appears.
- **When the answer lands:** it flips to the correct **blue "Partial access"** notice.

The red-then-blue flip is jarring — a red "Access denied" on a query that actually succeeds reads as a
failure for a few seconds.

## Root cause (do NOT change the notice logic — only its visibility)
In `apps/chat/web/src/components/ChatPane.tsx`, `accessNotice = selectAccessNotice(traceEvents)` is
rendered as soon as it's non-null. During the pipeline, the withheld-domain gate-deny events arrive
**before** the answering agent completes, so `hasAnswer` is still false → `selectAccessNotice` returns
**full-denial (red)**. Once the wealth agent completes, `hasAnswer` becomes true → it returns
**partial-access (blue)**. So the notice is *correct*; it's just shown one stage too early.

## The fix
Gate the notice's **display** on the turn being resolved — i.e., the exact **complement** of the
pipeline-progress window. The pipeline line shows while `isStreaming && streamingContent === ''`; the
notice should show only when that is **false**:

```
const showAccessNotice = !!accessNotice && !(isStreaming && streamingContent === '')
```
Render the notice block on `showAccessNotice` instead of on `accessNotice` alone.

**Do NOT** modify `selectAccessNotice` / `useTraceStream.ts` — the red-vs-blue decision stays exactly
as-is. This is a **one-condition change in `ChatPane.tsx`** only. During the wait the progress line
owns the space; after the turn resolves the notice appears.

## Acceptance criteria (browser-verify all three — the Okafor case is the critical one)
1. **`rm_carlos` → Sterling:** during the pipeline the center shows only the progress line — **no red
   banner at any point**; when the answer appears, the **blue "Partial access"** notice shows. **No red
   flash, ever.**
2. **`rm_jane` → Whitman:** no notice at any point (unchanged).
3. **`rm_jane` → Okafor (CRITICAL — must still work):** during the pipeline, no notice; once the turn
   resolves, the **red "Access denied"** full-denial notice **still appears**. (It must — `hasAnswer`
   stays false on a full denial, so the red path is unchanged; the gate `!(isStreaming &&
   streamingContent === '')` is true both when a denial streams content AND when `isStreaming` simply
   ends, so the red notice surfaces either way.)

## Ground rules + completion contract
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat` (head
  `4a620d3`). Runtime: docker project `orchestrator-demo`, chat at http://localhost:8099. Rebuild:
  `docker compose -p orchestrator-demo build conduit-chat && docker compose -p orchestrator-demo up -d conduit-chat`.
- **Frontend only** (`apps/chat/web`), one condition in `ChatPane.tsx`. **DO NOT touch:** auth / the
  Axiom redirect; gateway / iam / authz / registry; `backend-*`; `selectAccessNotice`.
- **Everything inside the repo folder** — no files/folders elsewhere.
- **Finish it fully, don't leave it in pieces:** `npm run build` green, `scripts/world-b-check.sh`
  CRITICAL 0, committed on `feat/conduit-chat` (message ending **"Approved by Sriraj."**, no AI
  attribution), env healthy (`curl -s -o /dev/null -w '%{http_code}' http://localhost:8099` → 200).
- **Browser-verify all 3 acceptance cases** (especially Okafor→red) before declaring done; screenshot
  Sterling (blue, no red) + Okafor (red). If blocked, revert cleanly and report exactly what's left.
