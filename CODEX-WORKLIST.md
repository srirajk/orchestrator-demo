# Conduit Workbench UI — Full Review & Work List (handoff)

**Target:** `admin-ui/` — React 18 + Vite + TS + Tailwind + TanStack Query. It's the **Axiom (IAM)
admin console** + a **Conduit gateway Workbench** (chat + live glass-box trace rail).

**Overall:** strong foundation, right direction, clearly a big step up from LibreChat. Proper JWT
auth (Bearer, claims-decoded — designs out the old `X-User-Id`/Redis hole); correct use of the
gateway contracts (`stream:false` JSON for chat, SSE reserved for `/trace/stream`); a real Axiom
design system; good loading/empty-state hygiene on most surfaces. The items below are what to fix,
in priority order, followed by an exhaustive file-by-file catalog so nothing is lost.

Legend: **P0** correctness-critical · **P1** important · **P2** polish/robustness · **P3** nice-to-have.

---

## ⚡ STATUS UPDATE (deep review — 2026-07-02)

**The codebase has moved on — most of this doc's P1 code bugs are already fixed.** Verified against current source:
- `npx tsc --noEmit` → **clean (exit 0)**, `strict:true` passing.
- **All 6 P1 code bugs (the "Confirmed functional bugs" section) are FIXED** — Toast (`useRef`), AuditLog (errors surfaced + date filters applied), useTraceStream (exp backoff + attempt cap), useWorkbenchChat (AbortController + `resetConversation`), gatewayReq (object-`detail` handled). **Do NOT redo them** — they're kept below as a record only.
- **P0 persona is still open** (workbench still sends the admin token — `api.ts:17-19`).
- **New issues surfaced** — some are *regressions from the fixes*. Highest: **NEW-1** trace-scope leak, **NEW-2** audit cache-key collision, **NEW-3** rail not cleared on new conversation. See "NEW findings" below.

---

## P0 — User persona / impersonation (this makes it right or wrong)

**Problem.** The workbench chat sends the **logged-in admin's** JWT to the gateway
(`readAdminToken()` in `features/workbench/api.ts`). Every entitlement decision — resolution,
coverage check, allow/deny — therefore reflects the **admin's** book/segments/clearance, not the
end-user being demonstrated.

**Why it's make-or-break.** The product thesis is *"the same question yields different,
entitlement-scoped answers per user"* (`rm_jane` sees Whitman, is **denied** Okafor). Run as admin
and that story is false — the glass-box "Access denied" never fires for the right reason. **Persona
is the point of the workbench.**

**Build:**
1. A **persona selector** (dropdown from `usersApi.list()` — `rm_jane`, `rm_carlos`, `uw_sam`, …).
2. On select, obtain a **JWT scoped to that persona** carrying *their* claims (`sub`, `book`,
   `segments`, `clearance`, `roles`) — the shape the gateway's `Principal.fromJwt` consumes.
3. Send **that persona token** as `Bearer` to `/v1/chat/completions` **and** `/trace/stream` —
   never the admin token.
4. Propagate the persona to the trace rail header, ContextLedger, and CoveragePanel.
5. **Do NOT** use a trusted `X-Act-As-User`/`X-User-Id` header — that reopens the exact hole the
   JWT model closed. Impersonation must be a real, Axiom-minted, claims-bearing JWT.

**Suggested Axiom contract (admin-only token exchange):**
```
POST /api/auth/impersonate            # caller must hold platform_admin
Authorization: Bearer <admin-token>
{ "user_id": "rm_jane" }
→ 200 { "accessToken": "<persona-JWT>",   # sub + book/segments/clearance/roles of rm_jane
        "tokenType": "Bearer", "expiresIn": 900, "user": {…} }
```
RS256/JWKS-signed like a login token; short TTL (~15 min); **audit** every impersonation
(actor=admin, subject=persona).

**Done when:** persona=`rm_jane` → Whitman returns grounded data; Okafor returns explicit
**"Access denied"**; the trace rail's `entitlement_check` denial is keyed to `rm_jane` — without
touching the admin's own session.

---

## 🔴 NEW findings (deep code review — 2026-07-02) — this is the current work

### MAJOR
- **NEW-1 (correctness + possible cross-session trace leak)** — `useTraceStream.ts:128-134` now
  treats `!event.conversationId` as **always visible** (an overcorrection of the old empty-rail
  fix). If `/trace/stream` is a shared broadcast (frames with null `conversationId`), this admin's
  rail/ledger renders trace events from **other conversations / other principals**. Fix: the
  **gateway must round-trip `X-Conversation-Id` into every trace frame**, and the client must filter
  **strictly** on it — do not whitelist null. (Also unblocks P0: denials must be attributable to
  the selected persona's conversation.)
- **NEW-2 (react-query cache collision)** — `['audit', 0]` is shared by `Dashboard.tsx:110-113`
  (`auditApi.list(0, 5)`) and `AuditLog.tsx:146-150` (`list(page, 10)`) with **different `size`**.
  With `staleTime:30s` (`main.tsx:9`), page-1 audit rows + pagination math become **nav-order
  dependent** (5 vs 10 rows). Fix: key on `['audit', page, size]`.
- **NEW-3 (state desync on reset)** — `useWorkbenchChat.resetConversation` (`:22-29`) mints a new
  `conversationId`, but `useTraceStream` **never clears `events`/`trackedRequestIds`** (declared
  `:34-35`, only appended). Combined with NEW-1, "New conversation" leaves the prior turn's events
  in the rail/ledger/coverage/health panels. Clear them when `conversationId` changes.

### 🔴 BLOCKER (from screenshot review — verify live)
- **NEW-14 — Users page renders BLANK.** `screenshots/new-app/03-users.png` (added 2026-07-02) is
  an **empty canvas with the entire app shell — sidebar included — gone.** That is not an empty-list
  state; it's the signature of an **unhandled render crash unmounting the whole tree** (no
  `ErrorBoundary` → white screen; see NEW-12). Every other of the 8 screens renders correctly, so
  it's specific to the `/users` route. **If reproducible, the core user-directory page is down →
  blocker.** Verify: run the app, navigate to `/users`, read the console throw. (A capture-timing
  glitch is possible, but the missing shell argues against it.) The other 7 screens (login,
  dashboard, teams, roles, policies, audit, workbench) look genuinely top-notch — and the Workbench
  ledger visibly shows `PRINCIPAL: admin`, confirming P0.

### MINOR
- **NEW-4 (MINOR→MAJOR UX)** — `AuditLog.tsx:294-361`: pagination controls live inside the
  populated branch, so a filter that empties the current page **hides Next/Prev** — the user can't
  page forward to find matches (filtering is client-side, page-local). Keep pagination visible under
  an empty filter, or move filtering server-side.
- **NEW-5** — `ContextLedger.tsx:56-59` "Request" cell uses `events[0]` (**oldest**) while every
  other cell uses `latestOf` (newest) → stuck on the first turn. Use latest.
- **NEW-6** — timezone-naive date math: `AuditLog.tsx:178` `new Date(\`${dateFrom}T00:00:00\`)`
  (local) vs UTC `occurredAt` → boundary-day rows mis-included. Duplicate relative-time helpers at
  `AuditLog.tsx:39` and `Dashboard.tsx:73`.
- **NEW-7** — no cross-tab session sync (`useAuth` has no `storage` listener; logout in one tab
  leaves others authed); `user` shape **diverges pre/post reload** (`Login.tsx:23` stores API
  `res.user`; reload rebuilds from JWT claims with different defaults).
- **NEW-8** — `useAuth.tsx:18` bare `atob` isn't UTF-8-safe; a unicode claim (e.g. username) throws
  → `decodePayload` returns null → user appears logged-out after reload despite a valid token. Use
  a UTF-8-safe base64url decode.

### MAJOR (resilience & confidence — surfaced 2026-07-02)
- **NEW-12 (no crash safety net)** — there is **no React `ErrorBoundary`** in the app; one thrown
  render white-screens everything. Add a top-level boundary (and ideally a per-route one around the
  lazy `Workbench`) that shows a fallback + reload, so a single bad component/trace frame can't take
  down the console.
- **NEW-13 (no unit tests)** — **0 unit tests, no `test` script** in `admin-ui`. The trickiest,
  most regression-prone logic (`useTraceStream` SSE parse/abort/backoff, `useWorkbenchChat`
  abort/reset, `selectors`/`traceEvents` normalizers) is untested — and **NEW-1/NEW-3 are
  regressions in exactly that code.** Add Vitest + tests for the hooks/utils before the next round
  of stream changes. (A browser `tests/e2e/admin-ui.spec.ts` exists, but it can't pin hook-level
  behavior.)

### NIT
- **NEW-9** — auth context value not memoized (`useAuth.tsx:60`) — latent re-render foot-gun.
- **NEW-10** — `token()` read from localStorage twice per request (`api/client.ts:28`).
- **NEW-11** — casts / non-null a stricter lint would flag (`Teams.tsx:67,81`; `Input.tsx:71`;
  pervasive `!`; `types.ts:24` `Record<string,unknown>`). `tsconfig` has `noUnusedLocals:false`, so
  dead locals (e.g. `Dialog.tsx:15` unused `ref`) are hidden.

### Still open from the original doc (re-confirmed present, not re-derived)
- **Policies** (`Policies.tsx:34-37`) — 4 list queries swallow errors (failure reads as "No
  policies"); `refetch()` unhandled (`:63`); CEL free-text unvalidated. **(P1)**
- **No global 401→logout / no expiry gate** — `App.tsx:16-19`. **(P2)**
- **No delete confirmations** — `Users.tsx:290`, `Teams.tsx:186`, `Roles.tsx:217`. **(P2)**
- **Dialog a11y** — no `role`/`aria`/focus-trap/initial-focus; unused `ref`; close `X` no
  `aria-label`. **(P2)**
- **Off-brand `index.html`** (title "Meridian Admin", favicon `#2563eb`); **Badge dup tones**; raw
  `<input>/<select>` bypass primitives; **no `.env.example`**. **(P2/P3)**

---

## ✅ P1 — Functional bugs (ALL FIXED as of 2026-07-02 — record only, do NOT redo)

> Verified fixed against current source: Toast (`useRef`), AuditLog (errors surfaced + date filters
> applied), useTraceStream (exp backoff + cap), useWorkbenchChat (AbortController + reset),
> gatewayReq (object-`detail` handled). Kept below for history; the trace-filter fix introduced
> **NEW-1** above.

1. **`components/ui/Toast.tsx` — broken id generator.** `let seq = 0` is declared **inside the
   component body** (line ~14), so `const id = ++seq` (line ~17) resets to `1` every render.
   Result: duplicate React `key`s, and the auto-dismiss `setTimeout(... filter(t => t.id !== id))`
   can remove the **wrong** toast. **Fix:** `const seq = useRef(0)` (or a module-level counter).
   Also add `role="status"`/`aria-live` to the container and `aria-label` to the close button.

2. **`pages/AuditLog.tsx` — swallowed errors + dead filters.**
   - The query uses `retry:false` and **never reads `isError`/`error`**; there's a local
     `error`/`setError` state and a full red retry banner (~203-217) but **`setError` is never
     called** — the banner is unreachable. On a failed audit fetch, `data` is `undefined` →
     `filtered` is `[]` → the UI shows the neutral "No audit entries found" empty state. **An API
     failure is silently presented as "no data."** Wire the query's `isError`/`error` to the banner.
   - **`dateFrom`/`dateTo` inputs are captured but never applied** — `filtered` (~171-178) only
     filters by `actor`/`action`. Date filtering is non-functional.
   - Client-side filtering only sees the current 10-row page (misleading "filter by actor").
   - `formatTime` duplicates `features/workbench/utils/format.ts` (divergent impls).

3. **`features/workbench/hooks/useTraceStream.ts` — filtering can hide all events.**
   `visibleEvents`/`trackedRequestIds` only include an event if `event.conversationId ===
   conversationId` **or** its `requestId` is already tracked; tracking only *starts* on a
   `conversationId` match (~76-81). **If the gateway emits trace frames with null/absent
   `conversationId` (a shared broadcast stream), nothing is ever tracked → the rail/ledger stay
   empty despite a live "SSE connected" pill.** This is the most likely "why is my trace rail
   empty" foot-gun. Requires the client `X-Conversation-Id` to round-trip into trace events; verify
   that, or relax the filter. Also:
   - Fixed 2.5s reconnect, **no exponential backoff, no attempt cap** → reconnect storm if the
     gateway is down.
   - A single bad frame calls `setError('Trace event parse failed')` while `connected` stays true;
     `error` is only cleared on the *next* reconnect → "Live" pill can show with a lingering error.
   - `trackedRequestIds` grows unbounded (events capped at 160, tracked-ids never trimmed).
   - (Cleanup/abort is otherwise solid — AbortError handled, `cancelled` guards reconnect.)

4. **`features/workbench/hooks/useWorkbenchChat.ts` — no AbortController + off-pattern.**
   Navigating away mid-request doesn't cancel the fetch → `setMessages` on an unmounted component
   (leak/act-warning). Inconsistent with `useTraceStream` (which aborts) and with the rest of the
   app (every other page uses react-query mutations w/ retry+toast; chat uses a bare `async` fn).
   Also: `conversationId` is generated once client-side and **never reset** — no "new
   conversation" affordance; message IDs aren't server-correlated (can't line up with trace
   `requestId`).

5. **`features/workbench/api.ts` — `[object Object]` errors.** `gatewayReq` error extraction is
   `err.detail?.message || err.detail || err.error || res.statusText`; if `detail` is an object
   without `.message`, the thrown `Error.message` becomes `"[object Object]"`. Guard for object
   `detail`.

---

## P1 — Streaming chat composer

Chat is MVP non-streaming (`stream:false` → single `chat.completion` JSON). Fine to ship, but the
production feel needs token streaming. When you add it:
- `stream:true`; the gateway SSE is **byte-exact OpenAI**: `data: {json}` deltas, terminal
  `data: [DONE]`, `finish_reason:"stop"`. Standard SSE reader works — **key "done" detection on the
  exact `data: [DONE]` sentinel** (that's what hung LibreChat; fixed gateway-side, just consume it).
  Reuse the fetch-stream approach from `useTraceStream` (so you can send the `Authorization` header).
- Keep non-streaming as a fallback toggle. Also make **Enter submit** in `ChatPanel` (currently
  the textarea has no `onKeyDown`; must click Send) and give the textarea an `aria-label`.

---

## P1 — Error-state & no-data discipline (finish what's started)

The **no-data** story is already good — Dashboard/Users/Teams/Roles/AuditLog use neutral
`EmptyState`; `StatusPill`, `ContextLedger`, `TraceRail`, `CoveragePanel`/`HealthPanel` empties are
neutral (not red). Two gaps remain:
- **Silent error swallowing:** `pages/Policies.tsx` (all four list queries — `policies`, `roles`,
  `policy-resources`, `segments` — have no `isError`/error UI; a failed fetch looks like "no
  policies"), and `HealthPanel` ignores domains load/error (the "Domains" metric shows `0` on a
  failed fetch, indistinguishable from zero domains). Surface these.
- **Red-on-error panels:** `CoveragePanel` shows a red `Unavailable` badge and `HealthPanel` a red
  "Registry unavailable" banner on **fetch failure**. These are genuine errors (not no-data, so
  they don't violate the empty≠red rule), but if you want a softer "degraded" treatment, use amber
  + a retry affordance rather than alarm-red.

---

## P2 — Accessibility (systemic — worth one focused pass)

- **Icon-only action buttons** across `Users`/`Teams`/`Roles`/`Sidebar`/`Dialog` lack `aria-label`
  (some have `title`, `Teams` card buttons have neither). Most are `opacity-0 group-hover:opacity-100`
  → **not keyboard-focusable/visible on focus.** Add `aria-label` + a `focus-within`/`focus-visible`
  reveal.
- **`Dialog.tsx`:** no `role="dialog"`/`aria-modal`/`aria-labelledby`, **no focus trap, no initial
  focus** (keyboard/SR users stranded behind the backdrop); close `X` has no `aria-label`; the
  declared `ref` (lines ~15/30) is unused; backdrop isn't keyboard-dismissable.
- **`Input.tsx`:** `Textarea` and `Select` labels have **no `htmlFor`/`id`** (only `Input` is
  wired); auto-id-from-label collides if two fields share a label; no `aria-invalid`/`aria-describedby`
  on error.
- **`Sidebar` `.sidebar-link`** has hover but no visible `focus-visible` ring on the dark rail.
- **`Button.tsx`:** when `loading`, the spinner renders *alongside* the original label+icon (no
  swap) — cosmetic but shows "spinner + Send icon + text" together.

## P2 — Destructive actions need confirmation

`Users`/`Teams`/`Roles` delete is a bare `onClick={() => deleteMut.mutate(id)}` — **no confirm
dialog.** Route through the existing `Dialog`. Also several mutations
(`assignRole`/`removeRole`/`removeMember`) toast on error but give **no success feedback**.

## P2 — Auth robustness

- `App.tsx` `Protected` only checks `token` truthiness — **no expiry/validity check**, and there's
  **no global 401 → logout**. A stale token renders the app until an API call 401s (and nothing
  handles it). Add a 401 interceptor in both `api/client.ts` and `features/workbench/api.ts` that
  clears the token and routes to `/login`; have `Protected` reject expired tokens (reuse
  `decodePayload`'s `exp` check).
- Token is in `localStorage` (XSS-exposed) — standard tradeoff; note it, consider httpOnly cookie
  later.

## P2 — Design-system consistency (migration is half-done)

- **Legacy `brand-*` tokens still pervasive** in `Users`/`Teams`/`Roles`/`Policies`
  (`brand-50/500/600/700`) while the shell + workbench use `axiom-*`/`gold-*`. Finish migrating IAM
  pages to `axiom-*` per `DESIGN-SYSTEM.md`.
- **Off-brand `index.html`:** title "Meridian Admin" and favicon fill `#2563eb` (generic blue) —
  the exact thing `DESIGN-SYSTEM.md` warns against. Swap to an `axiom`/`gold` mark.
- **Duplicate tokens:** `gold`≡`yellow` and `navy`≡`indigo` in `Badge.tsx` (identical class
  strings); `EventTone`/`StatusTone` in workbench `types.ts` re-declare a subset of Badge's
  `Color`. Consolidate to one source of truth.
- Several raw `<input>`/`<select>` in `Roles`/`Teams`/`Policies` bypass the `Input`/`Select`
  components — route through the primitives.

## P3 — Config / docs

- **No `.env.example`.** Only `VITE_GATEWAY_API_BASE` is declared (`vite-env.d.ts`); the IAM base
  is hardcoded `/api` (no `VITE_API_*`). Add a `.env.example` documenting `VITE_GATEWAY_API_BASE`
  and the token keys (`meridian_admin_token`, legacy `conduit_admin_token`).
- **SSE proxy:** dev (`vite.config.ts`, port **5174**) proxies `/api`→`8084` and
  `/gateway-api`→`8080` (both `changeOrigin`, prefix-stripped) with no SSE tuning (Vite streams by
  default). Prod (`nginx.conf`) sets `proxy_buffering off; proxy_read_timeout 150s;` on the gateway
  route — **required for SSE**; keep that in sync if the trace stream's max duration changes.

---

# Exhaustive file-by-file catalog

**Shell / bootstrap**
- `main.tsx` — React root; single `QueryClient` (`retry:1`, `staleTime:30s`); `StrictMode`
  (double-invokes effects in dev → SSE opens/aborts twice, benign).
- `App.tsx` — Providers `Auth > Toast > Router`; pages eager except `Workbench` (`lazy`+`Suspense`).
  `Protected` gates `Layout`. Issues: token-truthiness-only guard (no expiry, no 401 handling);
  `RouteFallback` uses raw `slate-200` instead of tokens.
- `vite-env.d.ts` — declares only `VITE_GATEWAY_API_BASE?`.
- `auth/tokenStorage.ts` — localStorage token; primary `meridian_admin_token`, legacy
  `conduit_admin_token`; migrates/clears both. Shared by IAM (`useAuth`) and gateway (`api.ts`).
- `hooks/useAuth.tsx` — JWT decoded client-side (sub/roles/book/segments/clearance/classification/
  adminDomains), `exp` checked on decode; `isAdmin = roles.includes('platform_admin')`.

**Layout / nav**
- `components/Layout.tsx` — fixed Sidebar + scrollable `<main><Outlet/>`. No skip-link.
- `components/Sidebar.tsx` — navy rail, static nav, active gold inset rail. Logout icon-only,
  `title` but no `aria-label`; no visible focus ring.

**UI kit (`components/ui/`)**
- `Badge.tsx` — 9-tone pill + `RoleBadge`. Dup tones (`yellow`≡`gold`, `indigo`≡`navy`).
- `Button.tsx` — variants + `loading` spinner (renders alongside children); good `focus-visible`.
- `Dialog.tsx` — backdrop + Esc-close; **no role/aria/focus-trap/initial-focus**, unused `ref`,
  close `X` no `aria-label`.
- `EmptyState.tsx` — neutral empty primitive (icon `axiom-300`, no red). The intended pattern.
- `Input.tsx` — `Input`/`Textarea`/`Select`; only `Input` wires `htmlFor`/`id`; auto-id collision
  risk; no `aria-invalid`.
- `Skeleton.tsx` — `animate-pulse`, `aria-hidden`. Used consistently.
- `Toast.tsx` — context, 4s auto-dismiss. **BUG: `let seq=0` in render body** (see P1-1); no
  `aria-live`.

**Pages (`pages/`)**
- `Dashboard.tsx` — stats + recent audit; Skeleton loading, neutral `EmptyState`; premium
  StatCards. (No no-data issues.)
- `Login.tsx` — split navy/gold sign-in; red error banner appropriate (real auth failure); no
  `role="alert"`, minimal validation.
- `Users.tsx` — directory CRUD + roles + drawer; 3 queries / 5 mutations; SkeletonRow + neutral
  empties (distinguishes no-users vs no-match). Issues: **no delete confirm**, icon buttons
  `title`-only + hover-hidden, silent success on role assign, inline classification-color logic
  duplicates the top-of-file map, mixes `brand-*`.
- `Teams.tsx` — team cards + member drawer; SkeletonCard + neutral empties. Issues: card buttons
  **no title/aria**, **no delete confirm**, `as Omit<…>` double-cast smell, raw `<select>` (`brand-500`).
- `Roles.tsx` — role table + permission chips + clearance slider; SkeletonRow + neutral empty.
  Issues: **no delete confirm**, icon buttons no aria, raw perm `<input>`, comma-operator Enter
  handler.
- `Policies.tsx` — Cerbos browser + AI policy generator (generate→validate→apply, Apply gated on
  `valid`). Issues: **all four list queries swallow errors** (failure looks like "no policies"), no
  list skeleton, unhandled `refetch()` promise, free-text CEL unvalidated, `brand-*` throughout.
- `AuditLog.tsx` — paginated audit + expandable before/after + export. **BUGS (P1-2):** dead error
  banner / swallowed errors; date filters wired but unused; page-local filtering; duplicate
  `formatTime`.

**Workbench (`features/workbench/`)**
- `Workbench.tsx` — thin re-export of `WorkbenchPage`.
- `WorkbenchPage.tsx` — wires `useWorkbenchChat`→`useTraceStream(conversationId)`; `trace-health`
  polled 10s, `domains`/`agents` queries; passes filtered `visibleEvents` to panels. Passes
  domains loading/error to CoveragePanel but **not** HealthPanel.
- `api.ts` — `gatewayBase()` (`VITE_GATEWAY_API_BASE` or `/gateway-api`); Bearer from tokenStorage;
  `sendChatTurnMvp` (`stream:false`, `X-Conversation-Id`); `traceStreamRequest`. Error-extraction
  `[object Object]` risk (P1-5).
- `types.ts` — dual snake/camel DTOs (defensive vs unstable contract); `TraceEventData extends
  Record<string,unknown>` (any-ish); `EventTone`/`StatusTone` dup Badge colors.
- `hooks/useTraceStream.ts` — **fetch-stream SSE** (not EventSource, to send auth); manual `\n\n`
  framing; cap 160. **Issues P1-3** (conversation filtering, reconnect storm, transient error).
- `hooks/useWorkbenchChat.ts` — draft/messages/isSending; optimistic user msg; `stream:false` turn.
  **Issues P1-4** (no abort, off-pattern, no reset).
- `utils/format.ts` — coercion + `formatTime`/`formatDuration` (`Pending`/ms/s, neutral). Clean.
- `utils/selectors.ts` — normalizers over dual-cased manifests; `latestOf`. Pure, solid.
- `utils/traceEvents.ts` — substring→tone mapping; title/detail per type (substring matching is
  order-dependent).
- `components/Panel.tsx` — shared card w/ icon+title+action. Good primitive.
- `components/StatusPill.tsx` — boolean pill; **false = neutral slate** (correct, not red).
- `components/ChatPanel.tsx` — transcript + composer; "MVP non-streaming" badge; auto-scroll;
  neutral empty; error bubbles red (appropriate). Issues: **Enter doesn't submit**, textarea no label.
- `components/TraceRail.tsx` — live list, per-type icon+tone; neutral empty (reuses error string as
  calm gray subline). `key` includes array index (minor).
- `components/ContextLedger.tsx` — 8-cell summary from latest events; all placeholders neutral
  "Pending"; **Access red only when `allowed===false`** (correct).
- `components/CoveragePanel.tsx` — last access-check + coverage services; Skeleton/neutral empty;
  **red `Unavailable` badge on fetch error** (real error).
- `components/HealthPanel.tsx` — domain/agent metrics + registry; Skeleton/neutral empty; **red
  "Registry unavailable" banner on agents error**; **ignores domains load/error** (shows 0).

# Design tokens (reference)
- Fonts: Inter. Flat: `canvas #f5f7fa`, `panel #fff`, `line #d8dee8`. `ink` 900/700/500.
- `axiom` navy scale 50→950; `gold` accent 50→900; `brand` = legacy navy alias (being retired).
- Shadows: `enterprise`, `gold-focus`.
- CSS classes (`index.css`): `page-shell`, `page-kicker`, `surface-card`, `surface-panel`,
  `section-heading`, `muted-copy`, `axiom-mark`, `sidebar-link[.active]` (gold inset rail), custom
  scrollbar, gold `::selection`.

# Proxy / config (reference)
- Dev `vite.config.ts` (port **5174**): `/api`→`localhost:8084` (IAM), `/gateway-api`→`localhost:8080`
  (gateway); both `changeOrigin`, prefix-stripped; no SSE tuning (Vite streams by default).
- Prod `nginx.conf`: same routes to `iam-service:8084` / `gateway:8080`; gateway location sets
  `proxy_buffering off; proxy_cache off; proxy_read_timeout 150s;` — **required for SSE**.
- Env: `VITE_GATEWAY_API_BASE` (only declared); IAM base hardcoded `/api`. No `.env.example`.

---

### Headline for Codex (updated 2026-07-02)
The 6 original P1 code bugs are **fixed** and `tsc` is **clean** — do not redo them. Verdict:
**not production-ready yet, but close.**

**⚠️ FIRST: verify NEW-14 — the Users page screenshot is fully blank (likely a render crash). If it
reproduces live, it's a blocker above everything below.** Then, in order:

0. **P0 persona/impersonation** — still open; the product-defining gap (workbench runs as the admin
   token). Must be an Axiom-minted, claims-bearing token for a *selected end-user*.
1. **NEW-1 — trace-event scoping.** Gateway round-trips `X-Conversation-Id` into every frame;
   client filters strictly; stop whitelisting null. Correctness now + cross-session leak risk; also
   unblocks P0.
2. **NEW-3 — clear rail/ledger/panels on conversation reset** so "New conversation" actually resets.
3. **NEW-2 — disambiguate the `['audit', 0]` query key** (add `size`) to stop the Dashboard↔AuditLog
   cache collision.
4. **Policies error surfacing + global 401→logout** (doc P1/P2).
5. **NEW-4 — keep AuditLog pagination visible under an empty filter** (or filter server-side).

Fast-follow (same sprint): delete confirmations, `Dialog` focus-trap/aria, `index.html` re-brand,
ContextLedger `events[0]`→latest (NEW-5), auth `useMemo` + cross-tab sync (NEW-7/9).

---

## What this review did NOT cover (scope — read before trusting completeness)

This is a **static, read-only code review** (`tsc --noEmit` + source reading). It does **not** cover:
- **Live / visual UX** — the app was never run. Rendered polish, motion, responsive breakpoints,
  the trace rail's real streaming feel, and the chat experience are **unassessed**. A "top-notch"
  verdict needs a design-QC pass (open in Chrome + screenshot Dashboard / Workbench / admin pages).
- **The IAM-side of P0** — the `/auth/impersonate` token-exchange endpoint is speced (contract
  above) but must be **built on `iam-service`**, not `admin-ui`; that work and its tests are out of
  scope here.
- **Runtime behavior against a live gateway** — e.g. NEW-1 (null-`conversationId` trace leak) is
  *inferred from code*; confirm it against a running `/trace/stream` under two concurrent sessions.
- **Performance** — bundle size / code-splitting (only `Workbench` is lazy), re-render profiling.
- **Security testing** — this flags code-level auth issues (admin-token principal, `localStorage`,
  `atob`), not a pen-test.
