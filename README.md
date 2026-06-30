# Conduit — Enterprise AI Gateway

> One plain-English question → the right specialist systems, across HTTP and MCP → one grounded,
> attributed answer, with every routing and access decision visible live.

**Conduit is an enterprise AI gateway for a bank.** A relationship manager asks a question in
plain English; the gateway figures out which specialist systems hold the answer, checks the user
is entitled to that data, calls those systems in parallel, and streams back **one synthesized,
grounded answer** — while showing the entire decision live in a glass-box panel.

What makes it different is **"World B": the gateway carries zero domain knowledge.** A new
business line is onboarded by adding manifest files + a coverage-service URL — **no gateway code
changes.** (We prove it here: insurance was added to a wealth-and-servicing gateway by manifest
alone.) See [`docs/PROJECT-OVERVIEW.md`](docs/PROJECT-OVERVIEW.md) for the full story and module
map.

<details>
<summary><b>📸 Screenshots — what it looks like when it comes up</b> (click to expand)</summary>

<br>

**The Conduit-branded chat (home):**

![Conduit — branded LibreChat home](docs/images/librechat-home.png)

**A grounded answer to the hero prompt** — one synthesized reply, every number traceable to a
source system (relationship `REL-00042`, `$1,967,000`, the actual positions):

![Conduit — grounded Whitman portfolio answer](docs/images/librechat-answer.png)

> These are captured live by `tests/tests/e2e/tests/11-screenshots.spec.ts` — run it to regenerate
> them, so the README never goes stale.

</details>

---

## What you can do with this demo

Four things, all live, all on the same stack:

1. **Ask the hero question** and get one grounded answer fanned out across HTTP + MCP agents.
2. **Kill an agent mid-question** and watch the answer still come back — honestly stating what's
   missing.
3. **Ask about a client you don't cover** and watch it get denied *before* any data is fetched.
4. **Ask something ambiguous** and get a scoped clarifying question instead of a hallucination.

Every one of these is **visible end-to-end** — in the chat, in the live glass-box, in the traces,
and in the quality scores.

---

## Spin it up

```bash
cp .env.example .env          # then set CONDUIT_LLM_SYNTHESIZER_API_KEY (an OpenAI key)
docker compose up -d          # core stack
bash scripts/seed-users.sh    # demo identities (rm_jane, uw_sam, …)
docker compose --profile eval up -d eval-worker   # optional: continuous quality scoring
```

Then take the guided tour below. (Full reference — every port, login, and the exact demo
prompts — is in [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md).)

---

## The guided tour — open these, here's what to look at

### 1. The chat — LibreChat → http://localhost:3080
*Log in as `rm_jane` / `Meridian@2024`.* This is what the banker uses.
**Try:** *"Give me a complete overview of the Whitman relationship: holdings, performance,
settlement status, and cash position."*
**Look for:** one streamed answer where every number (portfolio value, allocations, the
settlement, cash) is real — pulled from the agents, nothing invented. Then ask a follow-up
("*which holding is largest, and how much cash is unsettled?*") — it answers from memory without
you restating the client.

### 2. The decision — Glass-Box → http://localhost:4000
Open it beside the chat; confirm the top-right says **"Connected"**, then send a prompt.
**Look for** the six stages light up live:
1. **Request Received** — the raw question
2. **Intent Classification** — fetch-data, with confidence
3. **Agent Routing** — which agents were selected (with scores) **and which were not**
4. **Entitlement Gate** — ALLOWED / DENIED, with the resolved entity
5. **Agent Fan-out** — each agent, **HTTP or MCP**, with its own latency, green ✓ or red ✗
6. **Answer Synthesis** — streamed to the client
…ending in a summary (total latency, *N/M agents succeeded*). This is the trust surface — the
"why did it answer that" story for compliance.

### 3. The traces & quality — Langfuse → http://localhost:3030
*Log in as `admin@meridian.bank` / `changeme`.*
- **Tracing → Sessions:** your conversation is one **session**; each turn is a **trace** under it
  (1 conversation → many traces). Open a trace to see the prompt (input) **and** the answer
  (output), plus the child spans for each agent call (HTTP + MCP) with timings and token usage.
- **Scores (on each trace):** if the eval worker is running, you'll see **grounding**,
  **partial_honesty**, **relevance**, and **safety** — posted automatically every few minutes by
  the continuous evaluator. This is "is the answer actually good?", measured, not assumed.
- **Datasets / Experiments:** the release gate (DeepEval, run via `scripts/eval-gate.sh`) scores
  routing accuracy + faithfulness against golden datasets — the pre-ship certification, separate
  from the always-on scoring.

### 4. The metrics, logs & distributed traces — Grafana → http://localhost:3000
*Log in as `admin` / `changeme`.* Dashboards (left nav → Dashboards):
| Dashboard | What it shows |
|---|---|
| **Conduit — Live Demo View** | the at-a-glance demo panel |
| **Conduit Gateway — Performance** | request rate, success rate, **p50/p95/p99 latency**, outbound agent-call latency |
| **Conduit — Agent Health** | per-agent success/error rates and latency (spot a failing agent) |
| **Conduit — Business Overview** | intents, domains, cost-by-domain |
| **Conduit — Conversation Trace Explorer** | drill a single conversation across the stack |
| **Conduit — Resource Usage** | JVM CPU / memory |

- **Logs (Loki):** Explore → Loki → query `{container="conduit-gateway"}`. The gateway logs the
  conversation id it derived (`conv-…`); grab it and filter by it to follow one conversation's
  logs. *(Tip: click "Run query" twice on first load if a panel says "No data".)*
- **Distributed traces (Tempo):** Explore → Tempo → search recent traces to see the same request
  as a span waterfall (gateway → each agent), complementary to the glass-box.

---

## How the end-to-end works (in one breath)

`question → intent + entity extraction (no invented IDs) → semantic route → entitlement prune
(structural + book-of-business) → parallel fan-out over HTTP + MCP (virtual threads, circuit
breakers) → grounded synthesis → streamed answer`, with OTel spans + glass-box events emitted
throughout and quality scored asynchronously. The full lifecycle is in
[`docs/PROJECT-OVERVIEW.md` §3](docs/PROJECT-OVERVIEW.md).

---

## Onboard a new business

The whole point of World B: add a domain with **no gateway code**. The step-by-step (with file
templates) is in [`registry/README.md`](registry/README.md).

---

## Documentation map

| Doc | Read it for |
|---|---|
| [`docs/PROJECT-OVERVIEW.md`](docs/PROJECT-OVERVIEW.md) | **What the project is** — the GTM story + module-by-module map |
| [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md) | **Run & demo** — every URL/port/login, the four-beat script, troubleshooting |
| [`registry/README.md`](registry/README.md) | **Onboard a new business** — the manifest structure + checklist |
| [`docs/WORLD-B-LOCKDOWN.md`](docs/WORLD-B-LOCKDOWN.md) | The deep product/architecture spec |
| [`docs/MODEL-SELECTION.md`](docs/MODEL-SELECTION.md) | Model / provider strategy |
| [`BUILD_REPORT.md`](BUILD_REPORT.md) | Build status & verification record |
| [`CLAUDE.md`](CLAUDE.md) | How an AI agent should work in this repo (invariants) |

---

## Verify everything

```bash
./scripts/verify.sh            # build → up → smoke → e2e → eval (world-b-check is a hard gate)
bash scripts/world-b-check.sh  # the "no domain knowledge in the gateway" gate → CRITICAL must be 0
```
