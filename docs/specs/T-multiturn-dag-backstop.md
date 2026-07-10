# Codex task — multi-turn backstop for the DAG path (follow-up questions must keep working)

> GATEWAY code — **World-B applies** (no domain/client/entity literals in Java; carried context is data). Run
> `scripts/world-b-check.sh` before/after; CRITICAL 0. Do NOT commit (reviewer commits). Repo
> `/Users/srirajkadimisetty/projects/orchestrator-chat`, branch `feat/conduit-chat`. Stack: docker compose
> `orchestrator-demo`; BFF :8099. **If ambiguous, STOP and report.**

## Why (a real, user-visible gap)
Real conversations are multi-turn. A user asks "what's the concentration for the Whitman Family Office?"
then follows up "**and their settlement risk?**" — the follow-up never restates "Whitman". The **flat
(single-agent) path already has a backstop** for this: when the current turn's entity extraction comes up
empty, it falls back to the conversation's **carried entity context** (the entity resolved on a prior turn;
conversationId = session; there's existing anaphora handling — `conduit.chat.anaphora`). **The multi-step
(`tryDag`) path does NOT have this backstop** — so a follow-up that relies on carried context fails to
resolve the required entity, and the multi-step feature silently degrades (no DAG, or a clarify that
shouldn't be needed). Multi-step is the flagship; it must survive a follow-up.

## Scope — extend the EXISTING backstop to the DAG path (reuse, don't reinvent)
Study how the flat path carries and reuses conversation entity context (the anaphora / extractor-drop
backstop and the conversation state where resolved entities live). **Extend that same mechanism to
`tryDag`'s entity resolution**: when the current turn's extraction does not yield a required entity for a
DAG goal, resolve it from the conversation's carried context, then build and run the DAG. Do NOT build a
second, parallel context store — thread the same carried context the flat path uses into the DAG resolver's
entity resolution.

## Guardrails (these are the correctness/security of the feature)
1. **New entity overrides carried.** If the follow-up explicitly names a DIFFERENT client ("what about the
   Calderon Trust?"), use the newly-named entity — never stick to the stale carried one. Current-turn
   extraction wins; carried context is only the fallback when the current turn is silent.
2. **Entitlement is RE-CHECKED on the carried entity — no bypass.** A carried entity is NOT pre-authorized;
   it goes through the same coverage/classification gates as a freshly-named one (this is exactly T4's S16:
   coverage is re-checked per turn, never cached). A user who could see Whitman on turn 1 but not some other
   carried entity must still be denied.
3. **Honest clarify when there's nothing to carry.** If neither the current turn nor the carried context
   yields the required entity, ask a clarifying question — never guess an entity.
4. **Determinism / no leakage.** Carried context is per-conversation (session-scoped); it must not bleed
   across conversations or principals.

## HARNESS (first-class — live multi-turn, extend `tests/e2e/security_harness/`)
A `test_multiturn_dag_backstop` (and unit coverage where it fits):
- **Backstop fires:** turn 1 "concentration for the Whitman Family Office" → the concentration DAG runs;
  turn 2 "and their settlement risk?" (no entity named) → the settlement_risk **fan-in DAG runs for the
  carried Whitman relationship** (plan_graph shows the multi-step plan, not a clarify/flat degrade).
- **New entity overrides:** turn 3 "what about the Calderon Trust?" → switches to Calderon (carried context
  updated), the DAG runs for Calderon, not Whitman.
- **Entitlement re-checked on carried context:** a carried entity the principal does NOT cover → denied
  (compose with T4 — carried context is not a coverage bypass).
- **Honest clarify:** a follow-up with no current entity and no carried context → a clarify, not a guess.
- Regression: single-turn multi-step (the 3 verticals) unchanged; conditionals + map + identity + coverage
  all still pass.

## GATE
- The harness scenarios above pass live (backstop fires, override works, entitlement re-checked, honest
  clarify), on a clean rebuild.
- `cd gateway && mvn test` green; `scripts/world-b-check.sh` CRITICAL 0; full live regression bundle passes.

## Constraints / anti-gaming
- REUSE the flat path's carried-context mechanism; do not fork a second context store.
- Carried context does NOT bypass entitlement — re-check every gate on the carried entity. Do NOT weaken a
  gate to make a follow-up pass. World-B clean (carried entities are data; the resolver is generic).
- Do NOT commit.

## Report
Files changed; how the flat-path carried context was threaded into `tryDag`'s entity resolution (the reused
mechanism, not a new store); the guardrail handling (new-entity override, entitlement re-check on carried
entity, honest clarify); the harness evidence for all four scenarios (with request ids); mvn / World-B /
regression results. STOP and report anything the spec didn't anticipate.
