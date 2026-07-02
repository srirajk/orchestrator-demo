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

## 4. Ready-to-paste Claude Design prompt

Paste everything inside the block below into Claude Design as-is. It's self-contained.

```text
Design a product marketing site (single scrolling page + matching one-pager layout) for
"Conduit — the Enterprise AI Gateway for banks."

WHAT THE PRODUCT IS
Conduit sits between a chat box and a bank's specialist systems. A relationship manager asks
one plain-English question — "Give me a complete overview of the Whitman relationship:
holdings, performance, settlement status, and cash position." Conduit classifies the intent,
extracts the human references (never inventing IDs), routes semantically to the right
specialist agents across HTTP and MCP protocols, checks entitlements BEFORE fetching any
data (role-based + "is this client in your book?"), fans out in parallel, and streams back
one synthesized answer where every number traces to a source system. The entire decision
renders live in a "glass-box" audit panel: intent, chosen AND rejected routes, entitlement
verdicts, per-agent latency, and what's missing if a system was down.

POSITIONING
Category: enterprise AI gateway — trust infrastructure, NOT a chatbot, NOT RAG.
Tagline direction: "One question. Every system. Every number real. Every decision visible."
Audience: bank CTOs / heads of platform; compliance and CISO as the skeptics the design must
win over. Tone: institutional confidence — precise, calm, engineered. Think "trading-floor
infrastructure," not "AI startup gradient hype."

PAGE STRUCTURE
1. HERO — the one-liner, the hero question typed into a minimal chat input, and beside it a
   stylized glass-box panel lighting up six stages: Intent → Route → Entitle → Fan-out →
   Synthesize → Observe. The split-screen "answer + why" is the signature visual; make the
   glass box the hero, not the chat.
2. THE THREE FEARS — a three-column objection/answer section, verbatim:
   • "It'll make up a number." → Agent outputs are the only ground truth. The LLM never
     computes, recalls, or invents — and never produces an ID.
   • "It'll leak data across books." → Entitlements are checked before any data is fetched —
     structural (role × resource) and data-aware (is this entity in your book?).
   • "We can't audit it." → A live glass box shows the whole decision: what was asked, how it
     routed, what it was allowed to see, which systems answered, how long each took.
3. HOW IT WORKS — the six-stage pipeline as a horizontal flow, each stage paired with its
   guardrail (e.g. Fan-out: "a failed agent never cancels its siblings; the answer states
   what's missing").
4. THE PLATFORM CLAIM — ZERO-CODE DOMAIN ONBOARDING. The gateway carries zero domain
   knowledge. A new business line is onboarded with configuration (domain manifests + one
   coverage-service URL) — no gateway code. Proof line: "Insurance was added to a
   wealth-and-servicing gateway by configuration alone. An automated check verifies zero
   domain coupling on every change." Visualize as: domain manifests plugging into an
   unchanged core. This is the cost-curve story: domain #2 costs config, not a project.
5. MATURITY LADDER — Stage 1 (today, read-only): federated retrieval, "five portals → one
   question," near-zero operational risk. Stage 2 (the expansion, same substrate): write
   actions — resolve a settlement break, initiate a rebalance — each gated by human-in-the-
   loop confirmation. Caption: "Same front door. Growing power behind it. Read earns the
   trust; write captures the workflow."
6. PROOF STRIP — four live demo beats as compact cards:
   • Hero question → one grounded answer across HTTP + MCP agents, follow-ups answered from
     conversation memory.
   • Kill an agent mid-question → the answer still arrives and honestly states what's missing.
   • Ask about a client outside your book → denied before any data is fetched.
   • Ask something ambiguous → a scoped clarifying question, never a guess.
   Plus stat chips: "0% errors under concurrent load" · "3 business domains live" · "zero
   domain coupling, verified on every change" · "every answer scored for grounding, honesty,
   relevance, and safety."
7. INTEGRATION & OPERATIONS FOOTER — quiet labels about standards and operability, not
   implementation tech: "Open protocols: MCP + OpenAPI" · "Policy-as-code authorization" ·
   "OpenTelemetry-native observability" · "OIDC identity, verified at every hop" · optional
   single reassurance line for platform teams: "Runs as one JVM service — the stack your
   bank already operates." No language/framework version numbers.
8. CTA — "See the glass box live" (demo request).

VISUAL DIRECTION
Dark, precise, auditable. The glass-box motif should drive the system: thin luminous stage
indicators, monospaced trace details (timestamps, latencies, verdict chips like ALLOWED /
DENIED / DEGRADED), against a restrained institutional base — deep navy/graphite, one
confident accent for "verified/grounded" moments. Data elements (numbers, IDs like
REL-00042) render in mono with a subtle "traced to source" underline treatment. No generic
robot/sparkle AI clichés. Accessibility: WCAG AA contrast throughout.
```

## 5. Variants to ask Claude Design for after the first pass

- A 10–12 slide **pitch deck** using the same narrative spine (slide 1 = hero split-screen,
  slide for each fear, zero-code domain onboarding as the "why we win" slide, ladder as the
  "why now / roadmap").
- A one-page **compliance brief** aimed at the CISO: the six guardrails, the glass box, and
  the entitlement-before-fetch sequence diagram.
- A **demo-day leave-behind**: the four beats as a card, QR to the runbook.
