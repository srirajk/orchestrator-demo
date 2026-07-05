# Codex Brief — Conduit Chat Demo-Polish Pass (frontend only)

Make the Conduit Chat UI **demo-rock-solid**. Five items, ranked. All work is in `apps/chat/web`
(one tiny optional touch in `apps/chat/bff` only if needed for TTFT timing). **Browser-verify every
fix — the whole point is that it looks flawless live.**

## Ground rules (read first)
- **Repo:** `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`.
- **Runtime:** docker compose project **`orchestrator-demo`**; chat served at **http://localhost:8099**.
  Rebuild after changes: `docker compose -p orchestrator-demo build conduit-chat && docker compose -p orchestrator-demo up -d conduit-chat`.
- **Logins** (all password `Meridian@2024`): `rm_jane` (wealth+servicing), `rm_carlos` (wealth only),
  `analyst_amy` (wealth, no PII), `uw_sam` (insurance).
- **DO NOT touch:** auth / login / the Axiom OIDC redirect (a Conduit login page is a SEPARATE later
  task — leave the redirect exactly as-is); gateway / iam / authz / registry; `backend-*` containers.
- **Stay in bounds:** do ALL work **inside the project folder** — do not create any files or folders
  outside the repo. No scratch dirs elsewhere.
- **Gate:** run `scripts/world-b-check.sh` (CRITICAL must stay 0). Commit on `feat/conduit-chat`,
  message ending **"Approved by Sriraj."**, NO AI attribution.
- **Deliverable:** per item — root cause, the fix, and a browser **screenshot** proving it.

## Completion contract (non-negotiable — do NOT hand back partial work)
1. **Finish all five items** (P0 → P2). Do not stop halfway, do not hand back a partial pass. If you
   run low on room, prioritize P0 then P1, but see rule 5 — never leave a fix *half-applied*.
2. **Leave everything working.** Before you're done: `npm run build` green, `scripts/world-b-check.sh`
   CRITICAL 0, all changes committed on `feat/conduit-chat` (nothing uncommitted, no broken build),
   and the running env healthy (`conduit-chat` rebuilt + `up` + healthy — verify `curl -s -o /dev/null
   -w '%{http_code}' http://localhost:8099` returns 200). Never leave the repo or the env in a broken
   or half-edited state.
3. **Browser-verify each fix actually works** before marking it done — a code change that isn't
   confirmed live in the browser does NOT count as complete.
4. **Everything inside this repo folder.** Create no files or folders outside
   `/Users/srirajkadimisetty/projects/orchestrator-chat`. Delete any temp files you create. No scratch
   dirs anywhere else on the machine.
5. **No silent skips.** If you genuinely cannot complete an item, finish the others fully, then clearly
   report in your handback: which item is incomplete, exactly why, what's left to do, and confirm you
   reverted (not left dangling) any half-applied change for that one item so the build still passes.
6. **Final handback** must state, explicitly: all 5 done (or which aren't per rule 5), build green,
   world-b 0, committed, env healthy at :8099, and a screenshot per fix.

---

## P0 — THE DEMO-KILLER: "trace finishes but no answer appears"  ⭐ fix first, make bulletproof
**Symptom:** you ask a question, the right-hand Decision trace completes, but the answer never lands
in the center (blank / "Start a conversation"). Intermittent. This is the single most important fix.

**Root cause:** in `apps/chat/web/src/components/ChatPane.tsx` the clear-optimistic effect (~lines
121–126) wipes `localMessages` as soon as `serverMessages.length > sendBaseCountRef.current`. But the
BFF persists the **user** message synchronously and the **assistant** message on a background thread
*after* the stream ends. So the moment the user row alone lands server-side, the condition is true and
the local assistant bubble is cleared — while the server has no assistant row yet. The answer falls
through the gap. The id-based merge can't save it (`local-*` ids never match server UUIDs).

**Fix direction:** only clear the optimistic messages once the server has caught up to include BOTH
the user AND the assistant row for this turn — e.g. require `serverMessages.length >=
sendBaseCountRef.current + <messages added this turn>`, or keep the finalized assistant bubble until
its server twin is present. Never allow a state where the turn produced an answer but nothing shows.

**Verify:** as `rm_jane`, send `Give me a summary of the Whitman Family Office holdings` **5+ times**
(new chat each). The answer must render AND stay every single time. Then refresh — still there.
Zero "trace done, center blank" occurrences.

---

## P1a — Composer doesn't clear after sending
**Symptom (confirmed):** after you send, the prompt text is still sitting in the input box.

**Root cause:** the pending-send restore effect (`ChatPane.tsx` ~lines 90–95) re-populates the composer
from the just-saved pending send, because `clearPendingSend` runs only after `apiStream` resolves —
too late; the restore effect fires first on the id/isNew change.

**Fix direction:** clear the composer immediately on send (and don't restore the draft for content that
was just sent). After Enter, the input must be empty.

**Verify:** send a message → the input box is empty right away.

---

## P1b — User message renders twice
**Symptom (confirmed, esp. rm_jane):** the question bubble shows twice.

**Root cause:** the merge in `ChatPane.tsx` (~lines 132–135) shows both the optimistic `local-*` user
bubble and the server UUID user bubble, because dedup keys on id and the two ids never match — so both
render until the local copy is cleared.

**Fix direction:** ensure exactly one user bubble ever shows — e.g. drop the local user message once its
server twin arrives (match on role+content, or clear on server confirmation), so there's never a
double.

**Verify:** send a message → exactly one user bubble. Re-open the conversation → exactly one.

---

## P1c — False-positive "Partial access" notice
**Symptom (confirmed, run2-2):** as `rm_jane`, asking about **Whitman (wealth)** showed a blue
"Partial access — you don't have access to the **insurance** data" notice. She never asked about
insurance; the router speculatively pulled an insurance agent and the notice fired on it.

**Root cause:** `apps/chat/web/src/hooks/useTraceStream.ts` `selectAccessNotice` raises a partial notice
for ANY denied gate, including speculatively-routed cross-segment agents unrelated to the query.

**Fix direction:** only surface a partial-access notice when the withheld data is genuinely relevant to
what the user asked — e.g. suppress notices for agents outside the query's domain/intent, or only
report a withheld domain that shares the answered entity/domain. It must NOT cry wolf on a fully
successful in-domain query. (Note: a registry-side routing tighten is coming separately to reduce these
speculative selections; the UI should still not raise a false notice regardless.)

**Verify:** `rm_jane` → Whitman = **no** notice. `rm_carlos` → Sterling = still the correct blue
"asset-servicing" notice. `rm_jane` → Okafor = still the red full-denial.

---

## P2 — TTFT chip (the "wow", and it kills the dead-gap feeling)
**Goal:** show real time-to-first-token so the answer visibly starts fast and technical viewers see the
latency. Once P0 is fixed the answer streams token-by-token; add a small latency chip on the assistant
message, e.g. **"first token 0.8s · 3.2s total"**.

**Fix direction:** on send, record a timestamp; on the first streamed content delta, compute TTFT; on
`[DONE]`, compute total. Render a subtle chip under/next to the assistant message. Prefer client-side
timing (no BFF change). Ensure streaming content renders progressively so there is never a blank gap
between send and first token.

**Verify:** ask a question → you see it start streaming quickly and the chip shows a realistic TTFT +
total.

---

## Final check before you hand back
- `rm_jane` Whitman ×5 → answer always renders + persists (P0).
- composer empties on send (P1a); one user bubble (P1b); no false notice on Whitman, correct notice on
  Sterling, red on Okafor (P1c); TTFT chip visible (P2).
- `scripts/world-b-check.sh` CRITICAL 0. Build green. Committed on `feat/conduit-chat`, "Approved by
  Sriraj.", no AI attribution. All work inside the repo folder.
