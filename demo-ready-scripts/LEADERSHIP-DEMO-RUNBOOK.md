# Conduit — Leadership Demo Runbook
**Slot:** ~30–40 min (deep) · one client conversation → four lenses · **Date prepared:** 2026-07-13

---

## The business you're demoing (read this first)

**Meridian Bank** is a private / wealth-focused bank. Conduit is the AI layer that lets its staff
ask one plain-English question and get a grounded, permission-safe answer stitched across the
bank's back-office systems — without logging into five of them by hand.

**The business lines (each is a "domain" onboarded by manifest — no gateway code):**

| Domain | What it is | Who uses it |
|---|---|---|
| **Wealth Management / Private Banking** | Serves wealthy families, trusts and **family offices**. Portfolio holdings, performance, concentration risk, financial goals, house market view. | Relationship Managers (RMs) |
| **Asset Servicing** (the back office) | The operational plumbing behind holdings: **settlements, custody positions, cash, corporate actions** (dividends/splits), fund NAV, trade penalties. | RMs, operations |
| **Insurance** | Commercial insurance **policies**: policy details, claim status, renewal risk. | Underwriters |
| **HR** | Internal HR policy Q&A (leave, PTO, benefits). | Employees |

**The nouns (entities the system resolves — never guesses):**
- **Relationship `REL-xxxxx`** — one client household / trust / **family office**.
  *Whitman Family Office = REL-00042.* (A "family office" is a private firm managing one
  ultra-wealthy family's money — so it's a single, very large client relationship.)
- **Fund `FND-xxxx`** — a fund whose NAV / positions matter. **Policy `POL-xxxx`** — an insurance policy.

**The people (personas):**
- **Relationship Manager (RM)** — owns a **"book of business"**: the set of client relationships
  they're responsible for and allowed to see. `rm_jane` (senior; wealth **+** servicing access;
  covers Whitman), `rm_carlos` (wealth; covers **Sterling**, not Whitman), `rm_guest` (no book).
- **Underwriter** — insurance line (`uw_sam`, `uw_dana`). **Admin** — platform oversight (Insights).

**The problem it solves (say this out loud):** *"Today, an RM prepping for a client review logs into
five back-office systems — portfolio, custody, settlements, research — each with its own login,
format and permissions, and stitches the picture by hand. Conduit turns that into one question and
one grounded briefing in seconds — while never showing a client outside the RM's book, and with
every number traceable to a system of record. New business lines plug in as config, not a project."*

**Why "agents":** each back-office team exposes its system as an **agent** (an API the gateway
calls). The teams keep owning their systems and data; Conduit only **orchestrates across them**.

---

## 0. Credentials & surfaces (verified live 2026-07-13)

| Lens / tool | URL | Login |
|---|---|---|
| **Conduit Chat** (the product) | http://localhost:8099 | `rm_jane` / `Meridian@2024` |
| **Insights** (glass-box decision) | http://localhost:5175 | `admin` / `Meridian@2024` |
| **Langfuse** (AI trace / evals) | http://localhost:3030 | `admin@meridian.bank` / `changeme` |
| **Grafana** (traces + logs + SLO) | http://localhost:3000 | `admin` / `changeme` |
| Gateway API (OpenAI-compatible) | http://localhost:8080 | Bearer JWT from IAM |
| **Admin console** (users / roles / domains) | http://localhost:5180 | `admin` / `Meridian@2024` (role: `platform_admin`) |
| IAM / Axiom (identity **API**) | http://localhost:8084 | `admin` / `Meridian@2024` — it's an API: you authenticate as any persona to mint a JWT; there's no console at :8084 (use :5180) |

**Demo personas** (all share password `Meridian@2024`): `rm_jane`, `rm_carlos`, `rm_guest`
(wealth RMs) · `uw_sam`, `uw_dana` (insurance underwriters) · `admin`.

> Grafana's admin password is **its own** (`changeme`) — separate from the IAM `admin` persona
> used for Chat/Insights (`Meridian@2024`). Don't mix them up on stage.

---

## 1. Pre-demo checklist (run once, ~2 min before)

```bash
cd /Users/srirajkadimisetty/projects/orchestrator-demo

# a) all core containers healthy (expect no 'unhealthy' lines)
docker ps --format '{{.Names}}\t{{.Status}}' | grep -i unhealthy || echo "all healthy"

# b) Grafana is up (it is opt-in — this command brings it up if it isn't)
docker compose -p orchestrator-demo --profile observability up -d grafana

# c) routing brain sanity — 9 trace-truth checks, NO LLM cost
bash scripts/smoke-route.sh        # expect: 9/9 PASS

# d) WARM THE CONVERSATION so every backend has fresh data + a rehearsed run:
#    log into Chat (:8099 as rm_jane) and run all 4 turns of Lens 1 once.
#    Each turn's synthesis takes ~25–30s with the real agents — do this before they sit down.
```

Tabs to pre-open (in click order): Chat · Insights · Langfuse · Grafana · a terminal.

---

## 2. The narrative in one breath (cold open — 2 min)

Put the **Control Plane in Layers** artifact on screen.

> "Most 'AI gateways' are a proxy with a prompt. We built a **control plane**. The request path
> is deliberately **thin and domain-blind** — it carries zero knowledge of any bank domain.
> Everything that makes it behave — **identity, policy, routing, safety, evaluation** — lives in
> **governed layers around it**. Adding a new business domain is **configuration, not a code
> release**. Let me walk you through a *real client conversation*, then show you what happened
> underneath it — through four lenses."

---

## 3. The flow — one query, four lenses

### Lens 1 — The conversation (8 min) · Chat `:8099` as `rm_jane`
Not one question — a **realistic RM briefing**. Each turn lights up a *different* specialist, and
the client context carries forward without you re-naming it. Send these **in one conversation**, in
order:

> **Use these exact phrasings — all four verified end-to-end on 2026-07-13.** They were tuned:
> turn 2 leans on context on purpose; turns 3–4 name the subject explicitly, because a vague
> follow-up ("any fails I should flag?", "our house view?") makes the classifier ask *which client?*
> instead of answering. Say it the way it's written and it flows.

| # | Say this (verbatim) | What happens under the hood | The point |
|---|---|---|---|
| **1** | *"Give me a summary of the Whitman Family Office holdings."* | Wealth **holdings** agent → grounded portfolio summary + provenance footer | The answer, grounded. |
| **2** | *"What's the concentration risk **there**?"* | Wealth **concentration** agent (fan-in analytics on the holdings) — 25.4% top name, 6 breaches, HHI | **Context carries** — "there" = Whitman, never re-typed. ✅ verified |
| **3** | *"Show me pending settlements and custody positions **for the Whitman Family Office**."* | **Asset-Servicing** agents (settlement status + custody) over **MCP** — a different domain and protocol | **Cross-domain, cross-protocol** fan-out. ✅ verified |
| **4** | *"What is **Meridian's house view** on equities this quarter?"* | Wealth **market-research** agent — **not client-specific**, so no coverage gate | Not everything is entity-scoped; routing is capability-first. ✅ verified |

**What to point at while it runs:**
- Turn 1's **"Grounded figures from the source data"** footer.
  > "Every load-bearing number is auditable back to a specialist agent. The model **summarizes** —
  > it never computes or invents. If a number can't be traced, the answer is **refused, not faked**."
  > *(the grounding guard we hardened on 2026-07-13.)*
- Turn 2: you said **"there"**, not "Whitman."
  > "It remembered the client — the relationship ID is preserved across turns; the model never
  > re-guesses who we're talking about."
- Turn 3: a **different specialist team** answered — custody/settlements, a separate system on a
  separate protocol — same chat.
  > "One conversation just spanned the front office and the back office. To the RM it's one chat;
  > underneath it's several systems, orchestrated and entitlement-checked per call."

> ✅ **Turn 1 reliability (fixed 2026-07-13):** an earlier ~1-in-4 quirk — the summary occasionally
> came back as a bare *"the grounded figures are: …"* list because the model tried to *compute* a
> derived stat ("effective number of positions") that the guard then refused — is **fixed** (synth
> prompt hardened; **verified 0 fallbacks in 5 consecutive runs**). If you ever still see the terse
> list, it's the safety guard working, not a break: **resend once** and it returns clean prose — or
> narrate it as a feature: *"it just refused to show a number it couldn't prove."*

### Lens 1.5 — **Swap the user** (5 min) — the entitlement proof · Chat `:8099`
*This is the beat that lands "identity + entitlement are real." Verified live 2026-07-13.*

1. Still in Chat, **log out** and **log back in as `rm_carlos`** (`Meridian@2024`) — an **equally
   valid wealth RM**, same domain as Jane.
2. Ask the **exact same question**: *"give me a summary of the Whitman Family Office holdings"*
3. Result (confirmed): **"That client is not in your coverage."** — a clean, human denial. No error,
   no stack trace, **and zero data leak** — the same question that gave Jane a full summary gives
   Carlos one sentence.

> "Same role. Same domain. Same question. But **Whitman is Jane's client, not Carlos's.** The gate
> isn't the role — it's the **data-aware book of business**, checked per principal on every single
> request. And notice: I didn't change a line of code or a line of config to enforce that."

**Optional add-ons (pick one if time):**
- Log in as `rm_carlos` and ask for **"Sterling Capital Partners holdings"** (*his* client) → he
  gets a full answer. The book cuts both ways.
- Stay as `rm_jane` and ask for **"the Okafor relationship, REL-00188"** (outside her book) → same
  clean denial. *Even the hero RM has boundaries.*

### Lens 2 — The decision, glass-box (7 min) · Insights `:5175` → **Orchestration** / **Decision replay**
- Show: routing (which agents, and *why*), the **three authorization gates**, agents that
  fired / failed / were denied.
- Tie it back to the swap: the `rm_carlos` denial you just saw appears here as a **coverage
  decision** in the decision trace — the glass box shows *why* it was refused.
  > "Entitlement is enforced on the **data-aware book**, not just a role claim. Right role,
  > wrong book → denied — and every refusal is auditable."

### Lens 3 — The trace, two backends (10 min) — **THE punchline** · Langfuse `:3030` + Grafana `:3000`
1. **Langfuse:** open the `chat-turn` trace — `user = rm_jane`, a `conv-…` session.
   > "Clean AI view: sessions, token cost, inline evals."
2. **Grafana → Explore → data source: Tempo**, paste this TraceQL:
   ```
   { span.openinference.span.kind != "" }
   ```
   Open the `POST /v1/chat/completions` trace.
   > "Same OTLP stream, a **different backend** — full distributed picture."
3. **Deliver the line** (point at the artifact's observability rail):
   > "The gateway emits **one** OpenTelemetry stream. The collector fans it to Tempo, Langfuse,
   > Prometheus, Loki. The AI spans use **OpenInference** — the *same standard Arize reads today*.
   > Adding Arize is **one line in a collector config**: zero gateway change, zero
   > re-instrumentation. **We instrument to the standard, not to a vendor.**"

### Lens 4 — The evidence (8 min) · Grafana `:3000` dashboards
- **Gateway Health / SLO** → P95, in-flight, request outcomes.
- **Agent Health** → per-agent latency, circuit-breaker state.
- **Smart Orchestration** → routing / DAG behavior.
  > "We measure answer **quality**, not just HTTP 200 — DeepEval scores every turn continuously
  > and gates releases." *(the continuous-eval worker is running.)*

---

## 4. Back-pocket depth (5–8 min — only if they lean in)
Proves **config, not code**:
- `bash scripts/world-b-check.sh` live → **CRITICAL: 0**
  > "If domain knowledge ever leaks into the gateway, the build fails."
- Open one `registry/manifests/*.json` → "This — not Java — is how a domain exists."
- Grafana: click a trace's **traceID → jump straight to its Loki logs** (correlation is wired).

---

## 5. Close (2 min)
Back to **layer 7** on the artifact:
> "The last layer is **Onboarding Studio**: business teams onboard an agent by evidence and
> approval, never touching a manifest. That's the roadmap — the control plane governing its
> own growth."

---

## 6. Hard-questions Q&A

**"Isn't this just a wrapper around OpenAI?"**
No. The LLM only classifies intent and phrases the final summary. It **never produces an ID**
(deterministic resolution) and **never computes a number** (grounded-figure validation). The
gateway carries zero domain knowledge — proven by an automated gate on every build.

**"What if an agent lies or fails?"**
Agent outputs are untrusted **data**, never instructions. A failed agent never cancels its
siblings — we join to a deadline, synthesize from survivors, and state what's missing.

**"You're locked into Langfuse."**
No — that's Lens 3. One OTLP stream, OpenInference spans; Langfuse, Tempo, Arize are
interchangeable exporters. Swap = one collector line.

**"How does a new domain get added — and how long?"**
A manifest JSON plus a coverage URL, then re-run registry ingestion. No gateway code, no
request-path redeploy.

**"Entitlements — role-based only?"**
Three gates: structural policy (Cerbos) **plus** the data-aware book of business. Right role,
wrong book → still denied.

**"Can you prove it doesn't hallucinate numbers?"**
Structurally: every numeral in the answer must equal a real fetched figure, or the answer is
refused. We hardened exactly this guard today.

**"Performance at scale?"**
Virtual threads, telemetry async and off the hot path, HNSW vector routing. The known scale
item — moving the query-embedding hop in-JVM — is scoped, not hand-waved.

---

## 7. If something breaks mid-demo
- **Chat answer looks robotic / lists figures with no prose** → grounding fallback fired; pick a
  simpler single-figure question and move on (don't debug live).
- **Grafana Tempo panel empty** → data source dropdown may be on Prometheus; switch to **Tempo**
  and re-run the TraceQL. Health-probe traces are noise — the `openinference.span.kind` filter
  hides them.
- **A persona can't log in** → `bash scripts/seed-users.sh` re-seeds IAM principals into Redis.
- **Grafana down** → `docker compose -p orchestrator-demo --profile observability up -d grafana`.

---

## Appendix A — Verified persona books (ground truth, 2026-07-13)

Queried live from the wealth-coverage service (`GET /coverage/{principal}` and
`/coverage/{principal}/resources/{id}`). **Deterministic, no LLM involved** — this *is* the gate.

| Persona | Role / scope | Book of business (clients) |
|---|---|---|
| `rm_jane` | wealth + servicing, domain `wealth-private-banking` | **Whitman Family Office** (REL-00042), Calderon Trust (REL-00099), Rivera Diversified Trust (REL-00333) |
| `rm_carlos` | wealth only | **Sterling Capital Partners** (REL-00201) |
| `rm_guest` | wealth segment, no domain membership | *(empty book)* |

Coverage-check results (`allowed` / `reason`):

| Principal | Resource | Result |
|---|---|---|
| rm_jane | REL-00042 (Whitman) | ✅ `allowed:true` · `in-book` |
| rm_jane | REL-00188 (Okafor) | 🔒 `allowed:false` · `not-in-book` |
| rm_carlos | REL-00042 (Whitman) | 🔒 `allowed:false` · `not-in-book` |
| rm_carlos | REL-00201 (Sterling) | ✅ (his client) |
| rm_guest | REL-00042 (Whitman) | 🔒 `allowed:false` · `not-in-book` |

**Chat denial text (confirmed):** `rm_carlos` asking for Whitman → *"That client is not in your coverage."*

> Note the architecture: routing (`/debug/route`) is **principal-agnostic** — it resolves the same
> way for everyone. The **coverage CHECK is the only gate**, applied per principal during execution.
> That's a deliberate invariant (never filter entity resolution by the principal's book), and it's
> why the swap is a clean data-boundary demo rather than a routing quirk.

---

## Appendix B — The agents in your system (18 across 4 domains)

Each agent is an external specialist system the gateway calls — the gateway owns none of this data.
**Protocols:** Wealth/Insurance/HR over **HTTP**, Asset-Servicing over **MCP** (Streamable HTTP).
**Fan-in analytics** = agents that consume *other* agents' outputs (the "smart orchestration" DAG).

### Wealth Management — HTTP (7)
| Agent | What it returns |
|---|---|
| `meridian.wealth.holdings` | Current portfolio positions + asset-class allocation |
| `meridian.wealth.concentration` | **fan-in** — HHI, top single-name %, breach flags (from holdings) |
| `meridian.wealth.concentration_review` | **fan-in** — conditional firm-policy concentration review |
| `meridian.wealth.performance` | Total return %, P&L |
| `meridian.wealth.risk_profile` | Risk-tolerance assessment + concentration flags |
| `meridian.wealth.goal_planning` | Financial-goal status / on-track assessment |
| `meridian.wealth.market_research` | Meridian house view, sector outlooks, macro |

### Asset Servicing — MCP (7)
| Agent | What it returns |
|---|---|
| `meridian.servicing.settlement_status` | Pending & failed settlement records |
| `meridian.servicing.settlement_risk` | **fan-in** — consumes settlement + custody + cash |
| `meridian.servicing.custody_positions` | Custody holdings broken down by custodian |
| `meridian.servicing.cash_management` | Cash balances + projected cash positions |
| `meridian.servicing.corporate_actions` | Upcoming dividends, splits, elections |
| `meridian.servicing.nav` | Fund NAV + as-of date (keyed by **fund_id**, not relationship) |
| `meridian.servicing.trade_penalty` | CSDR-style penalty aging on failed settlements |

### Insurance — HTTP (3)
| Agent | What it returns |
|---|---|
| `meridian.insurance.policy_details` | Commercial policy record (line of business, premium, coverage) |
| `meridian.insurance.claim_status` | Claim status, amount, incident date, adjuster |
| `meridian.insurance.renewal_risk` | **fan-in** — consumes policy_details + claim_status |

### HR — HTTP (1)
| Agent | What it returns |
|---|---|
| `meridian.hr.policy_qa` | HR policy Q&A (parental leave, PTO, benefits, conduct) |

> **Talking point:** onboarding a 19th agent (or a whole new domain) is a **manifest + coverage URL**
> and a re-ingest — no gateway code. The 4 fan-in agents are why routing isn't a flat lookup: the
> gateway builds a small dependency plan (holdings → concentration; settlement/custody/cash →
> settlement_risk) and runs the tiers in order.
