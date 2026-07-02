# Conduit — GTM & Design Brief

> Purpose: everything needed to produce go-to-market surfaces (pitch site, one-pager, deck)
> for Conduit — plus a ready-to-paste prompt for Claude Design at the end. All claims below
> are grounded in what the repo actually proves today (see `README.md`, "What's proven today").

---

## 1. What this actually is (the honest read)

Most "enterprise AI" demos are a chatbot with a system prompt. Conduit is the opposite: the
chat box is the thinnest part, and the product is the **trust machinery underneath it** —
a single Java gateway that routes one plain-English question across specialist systems
(HTTP + MCP), enforces entitlements *before* any data moves, synthesizes one grounded answer,
and shows the entire decision live in a glass-box panel.

Four things here are genuinely differentiated — not demo polish, but architecture:

1. **Zero-hallucination by construction, not by prompt.** The LLM never produces an ID and
   never computes a number. Human references are extracted, then resolved deterministically;
   agent outputs are the only ground truth; unresolvable references trigger a scoped
   clarification decided *in code* (`extracted ∩ required_context = ∅`), not by an LLM's mood.
2. **Entitlement before fetch.** Structural authorization (Cerbos: role × resource) plus a
   data-aware check ("is this entity in your book?") prune the fan-out *before* any agent is
   called. Denied data is never fetched — a categorically stronger claim than "filtered from
   the answer."
3. **The glass box.** Every decision — intent, routing (selected *and rejected* agents),
   entitlement verdicts, per-agent latency, what's missing — renders live. This turns the
   compliance officer from the deal-blocker into the demo's best audience.
4. **Zero-code domain onboarding.** (Internally codenamed "World B" — keep that name out of
   external materials; use the benefit as the name.) The gateway carries zero domain
   knowledge; a new business line is onboarded with manifest JSON + a coverage-service URL,
   no gateway code. This is *proven in the repo*: insurance was bolted onto a
   wealth-and-servicing gateway by manifest alone, with a deterministic check
   (`world-b-check.sh` → CRITICAL 0) guarding the invariant. This is the line that turns
   "nice demo" into "platform with a cost curve" — domain #2 costs config, not a project.

Supporting proof points worth quoting: partial-failure honesty (kill an agent mid-question,
the answer still arrives and states what's missing), multi-turn context carry-forward, k6 at
0% errors under 10 concurrent streams on virtual threads, continuous quality scoring
(grounding / honesty / relevance / safety) posted to Langfuse.

## 2. Positioning

- **Category:** Enterprise AI gateway — the trust and orchestration layer between a chat
  surface and a firm's systems of record. Explicitly *not* a chatbot, not RAG, not a copilot.
- **One-liner:** *One question. Every system. Every number real. Every decision visible.*
- **The wedge (GTM sequencing, from `docs/platform-vision-and-maturity-path.md`):** land with
  read-only Stage 1 — immediate "five portals → one question" ROI at near-zero operational
  risk — then expand to Stage 2 write actions (human-in-the-loop) on the same substrate. The
  read product earns the trust; the write product captures the workflow. Read-first is the
  strategy, not the caution.
- **Buyer / audience:** CTO / head of platform engineering at a bank or wealth manager
  (economic buyer), with CISO + compliance as the veto-holders the glass box is built to
  convert, and relationship managers / ops as the daily users.
- **The three fears it answers** (use verbatim — this framing tests well because it names
  the objection before the prospect does):
  | The bank's fear | Conduit's answer |
  |---|---|
  | "It'll make up a number." | Agent outputs are the only ground truth; the LLM never computes, recalls, or invents — and never produces an ID. |
  | "It'll leak data across books." | Entitlements checked before any data is fetched — structural and data-aware. |
  | "We can't audit it." | The glass box shows the whole decision, live, per request. |

## 3. Narrative spine (for any GTM surface)

1. **Hero moment:** a relationship manager asks *"Give me a complete overview of the Whitman
   relationship — holdings, performance, settlement status, and cash."* One question that
   today means five portals and two segments → one streamed, attributed answer.
2. **The problem named honestly:** generic chatbots fail banks three ways (the fears table).
3. **How it works:** the six stages — Intent → Route → Entitle → Fan-out → Synthesize →
   Observe — each with its guardrail.
4. **The trust surface:** the glass box, live, beside the chat.
5. **The platform economics:** zero-code domain onboarding — insurance added by manifest
   alone; no gateway code; deterministic proof.
6. **The trajectory:** the maturity ladder, read → write, same front door, growing verbs.
7. **Proof, not promises:** the four live demo beats (hero answer / kill an agent / denied
   entitlement / ambiguous → clarify).

## 4. Prompting philosophy (why the prompt below is shaped this way)

Design models produce their worst work when handed a section list — they comply, and the
result is a competent generic SaaS page. They produce their best work when they *understand*:
who is in the room, what those people have been burned by, what single idea the page must
land, and what real material they can render. So the prompt below is built as
**context → one big idea → non-negotiables → explicit creative freedom**, and it feeds
Claude Design a real request trace (actual IDs, dollar values, verdicts, plausible latencies)
so the mockups look alive instead of lorem-ipsum. Two deliberate "mind-blow" instructions:

- **The page IS the product.** Instead of screenshots, the hero is a working simulation of
  the glass box: the question types itself, six stages light up with latencies and verdicts,
  the answer streams with numbers visibly traced to source agents — and the visitor can
  *kill an agent* or *ask outside their book* and watch the machinery respond honestly.
- **The page practices what the product preaches.** Every stat on the page carries a small
  provenance mark — a marketing page with an audit trail. That's the brand in one gesture.

## 5. Architecture diagram — yes or no?

**Not the engineering one, not on the marketing page.** A component diagram (Redis, Cerbos,
collectors, vendor names) answers a question nobody on the page has asked yet, and reads as
complexity. The recommendation, encoded in the prompt below:

- **On the page:** the six-stage trace *is* the architecture visual — one question fanning
  out through a gate to specialist systems and returning as one attributed answer. If a
  diagram appears at all, it's that conceptual flow.
- **In the technical leave-behind / CISO brief (section 7 variants):** yes, a real component
  diagram belongs there — identity provider, policy engine, coverage services, protocol
  adapters, observability plane. Bank technical diligence will ask for it; give it to them
  at the evaluation stage, not the attraction stage.
- The prompt still *teaches* Claude Design the true topology (in one paragraph) so anything
  it draws is faithful — understanding the real system prevents decorative-but-wrong visuals.

## 6. Ready-to-paste Claude Design prompt

Paste everything inside the block below into Claude Design as-is. It's self-contained.
(The trace latencies and routing scores are representative values for the simulation —
swap in a captured trace later if you want them exact.)

```text
You are designing the launch surface for "Conduit — the Enterprise AI Gateway." Before any
layout work: understand the product and the room it has to win. Then design the page YOU
believe wins that room. The structure suggestions below are a starting proposal, not a cage
— if you have a stronger idea that keeps the non-negotiables true, take it.

THE ROOM YOU ARE DESIGNING FOR
A bank's innovation committee: the CTO / head of platform (economic buyer), the CISO and a
compliance officer (the veto), and two senior engineers who have seen everything. Every
vendor deck they see says "AI-powered." Every one of them has watched a chatbot demo invent
a client's portfolio value. In a bank, a number that is almost right is worse than no
answer — it is a mis-advised client, a fired banker, a regulator letter. These people do
not want magic; they want machinery. The page wins if the compliance officer — normally
the person who kills AI projects — leaves wanting to champion this one.

WHAT CONDUIT IS (understand it — don't just render it)
A relationship manager asks one plain-English question: "Give me a complete overview of
the Whitman relationship: holdings, performance, settlement status, and cash position."
Today that means logging into five portals across two business segments. Conduit is the
gateway between the chat box and the bank's specialist systems. Here is what actually
happens to that one question — this is real product behavior; use it as design material:

1. INTENT + ENTITIES — an LLM classifies the ask (fetch / follow-up / clarify) and
   extracts the human references: "the Whitman relationship." The LLM never produces an
   ID; a deterministic lookup resolves "Whitman" → REL-00042. An unresolvable reference
   triggers a scoped clarifying question — decided in code, never guessed.
2. ROUTE — the question is vector-matched against every registered agent's declared
   capabilities. Four agents selected (holdings 0.91 · performance 0.88 · settlement 0.84
   · cash 0.82) and five rejected, with scores shown for BOTH lists. Rejection
   transparency is a feature, not debug output.
3. ENTITLE — before any data moves, two checks: structural (does this role get this
   resource class — policy-as-code) and data-aware (is REL-00042 in rm_jane's book of
   business?). Verdict: ALLOWED. Had it been denied, the request dies here — the data is
   never fetched, not fetched-then-hidden.
4. FAN-OUT — the four agents are called in parallel over their native protocols (REST and
   MCP), each behind its own circuit breaker: holdings 240ms · performance 210ms ·
   settlement 310ms · cash 190ms. A failed agent never cancels its siblings.
5. SYNTHESIZE — an LLM merges the four payloads into one streamed answer. Agent outputs
   are the ONLY truth: the model never computes, recalls, or invents a number. Total
   portfolio value $1,967,000 — traceable to the holdings agent. If the settlement agent
   was down, the answer says so, plainly.
6. OBSERVE — all of the above renders live in the "glass box," an audit panel beside the
   chat: what was asked, how it routed, what it was allowed to see, who answered, how
   long each took, and what is missing.

THE ONE BIG IDEA (organize the entire page around this)
The answer and the reason for the answer, side by side. Every AI vendor shows the chat;
Conduit's product is the right-hand panel — the machinery of trust made visible. Tagline
direction: "One question. Every system. Every number real. Every decision visible."

MAKE THE PAGE *BE* THE PRODUCT
Do not use static screenshots. The hero is a working simulation: the Whitman question
types itself into a minimal chat input; beside it the six stages light up in sequence with
verdict chips and latencies (ALLOWED · 0.91 · 240ms); the answer streams in with each
number visibly traced to its source agent. Then hand the visitor the two killer
interactions as toggles on the simulation:
  • KILL AN AGENT — drop the settlement agent mid-request. The other three complete, the
    answer still arrives, and it honestly states what is missing. Partial-failure honesty,
    felt rather than claimed.
  • ASK OUTSIDE YOUR BOOK — switch the client to one this banker does not cover. The
    request dies at stage 3, DENIED, before any data is fetched. Show the stages after it
    never lighting up.
And let the page practice what the product preaches: every stat and claim on the page
carries a small provenance mark (what it traces to), mirroring the product's grounding
ethic. A marketing page with its own audit trail — that is the brand in one gesture.

WHAT MUST BE TRUE ON THE PAGE (non-negotiables — the rest is yours)
• The three fears, answered head-on, verbatim:
  – "It'll make up a number." → Agent outputs are the only ground truth. The LLM never
    computes, recalls, or invents — and never produces an ID.
  – "It'll leak data across books." → Entitlements are checked before any data is fetched
    — structural (role × resource) and data-aware (is this client in your book?).
  – "We can't audit it." → A live glass box shows the whole decision: what was asked, how
    it routed, what it was allowed to see, which systems answered, how long each took.
• Zero-code domain onboarding: a new business line is onboarded with configuration
  (domain manifests + one coverage-service URL) — no gateway code. Proof line: "Insurance
  was added to a wealth-and-servicing gateway by configuration alone; an automated check
  verifies zero domain coupling on every change." Cost-curve caption: "Domain #2 costs
  config, not a project."
• The maturity ladder: Stage 1 (today, read-only) — federated retrieval, five portals →
  one question, near-zero operational risk. Stage 2 (the expansion, same substrate) —
  write actions like resolving a settlement break, each gated by human-in-the-loop
  confirmation. Caption: "Read earns the trust; write captures the workflow. Same front
  door, growing verbs."
• Proof chips: "0% errors under concurrent load" · "3 business domains live" · "zero
  domain coupling, verified on every change" · "every answer scored for grounding,
  honesty, relevance, and safety."
• Standards footer (operability, not implementation tech): "Open protocols: MCP +
  OpenAPI" · "Policy-as-code authorization" · "OpenTelemetry-native observability" ·
  "OIDC identity, verified at every hop" · optional single reassurance line for platform
  teams: "Runs as one JVM service — the stack your bank already operates." No language
  or framework version numbers anywhere.
• CTA: "See the glass box live."

ARCHITECTURE DIAGRAM GUIDANCE
Do NOT put an engineering component diagram (databases, collectors, vendor boxes) on this
page. The six-stage trace IS the architecture visual: one question fanning out through a
gate to specialist systems and returning as one attributed answer — if you draw a diagram,
draw that flow. For your own understanding, so anything you draw is faithful: the true
topology is chat UI → gateway (intent → route → entitle → fan-out → synthesize → observe)
→ specialist agents over REST and MCP, with an OIDC identity provider verifying every hop,
coverage services answering "is this client in your book?", a policy engine for structural
authorization, and an observability plane scoring every answer for quality. A proper
component diagram exists but belongs in the technical appendix for the CISO, not here.

VISUAL DIRECTION (direction, not handcuffs)
Institutional confidence — the aesthetic of trading infrastructure, not AI-startup
sparkle. Dark, precise base (graphite / deep navy). One confident accent color, reserved
exclusively for verified/grounded moments so the eye learns that color = trust. Monospace
for anything that is data — REL-00042, $1,967,000, latencies, verdict chips ALLOWED /
DENIED / DEGRADED — with a subtle traced-to-source underline treatment. Motion is
meaning: things animate only when the machinery is doing something (stages lighting,
answers streaming) — never decoration. WCAG AA contrast throughout. No robots, no
sparkles, no gradient hype.

DELIVERABLES
1. The scrolling launch page with the working hero simulation described above.
2. A matching static one-pager layout (print-friendly leave-behind).
Where a structural suggestion above conflicts with a stronger design instinct, follow the
instinct and keep the non-negotiables.
```

## 7. Variants to ask Claude Design for after the first pass

- A 10–12 slide **pitch deck** using the same narrative spine (slide 1 = hero split-screen,
  slide for each fear, zero-code domain onboarding as the "why we win" slide, ladder as the
  "why now / roadmap").
- A one-page **compliance brief** aimed at the CISO: the six guardrails, the glass box, the
  entitlement-before-fetch sequence — **this is where the real component diagram goes**
  (identity provider, policy engine, coverage services, protocol adapters, observability
  plane).
- A **demo-day leave-behind**: the four beats as a card, QR to the runbook.
