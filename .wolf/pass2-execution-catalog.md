# Pass 2 Execution Catalog
> Written 2026-06-26. Read this before touching any file.

## What already exists — do NOT rebuild

- `eval/eval_deepeval.py` — has RoutingAccuracyMetric (F1), FaithfulnessMetric spot-check, HallucinationMetric, CLI runner
- `eval/golden-prompts.json` — has 40+ routing prompts with expected_agents. Threshold: 0.75.
- Both canned_data.py files — the ground truth for all eval data

## What is MISSING — build this

---

### TASK 1: Extend eval_deepeval.py

**File:** `eval/eval_deepeval.py` (extend, do NOT rewrite the whole file)

ADD these to the existing file:

**1A — PartialHonestyMetric (custom BaseMetric):**
When an agent fails, the synthesized answer MUST acknowledge missing data. Never silently omit.
```python
class PartialHonestyMetric(BaseMetric):
    """
    Checks: when agent failure context is present, the answer must acknowledge missing data.
    Scores 1.0 if: (a) no failure context present, or (b) failure context present AND answer
    contains acknowledgment words (unavailable/missing/unable/failed/could not retrieve).
    Scores 0.0 if failure context present but answer does NOT acknowledge it.
    """
    threshold = 0.9
    name = "Partial Result Honesty"
```
The test_case.additional_metadata dict will carry {"failed_agents": ["settlements-agent"]} when applicable.

**1B — Fix FaithfulnessMetric to use ZAI GLM:**
Current code uses model="gpt-4o-mini". Replace with ZAI-compatible config:
- Read ZAI_API_KEY from env
- Use deepeval's DeepEvalBaseLLM or set OPENAI_API_KEY=ZAI_API_KEY and OPENAI_BASE_URL to ZAI base URL
- Model: "glm-4.6" (or glm-4.5-flash as fallback)
- Add a helper: `configure_judge_model()` called once at startup

**1C — Judge Validation function:**
Add `run_judge_validation()` that:
- Has 15 hardcoded human-scored cases (inputs, contexts, expected scores: pass/fail)
- Runs FaithfulnessMetric on each
- Computes agreement rate: how often judge's pass/fail matches human label
- Prints agreement % and PASSES if ≥ 80%, WARNS but continues if 70-79%, FAILS eval if <70%
- Called before main eval run so we know judge is trustworthy

Human-scored cases to hardcode (ground truth from canned_data.py):
PASS cases (should score high faithfulness):
1. Context: "Whitman holdings total_value=1967000" Answer: "The Whitman account has a total market value of $1,967,000" → PASS
2. Context: "YTD return 12.4%, alpha 2.2%" Answer: "Performance is up 12.4% YTD, outperforming benchmark by 2.2%" → PASS
3. Context: "MSFT 800 shares value 372000" Answer: "Microsoft position: 800 shares valued at $372,000" → PASS
4. Context: "Settlement REF-S-00421 MSFT 372000 pending" Answer: "There is a pending MSFT settlement of $372,000 (REF-S-00421)" → PASS
5. Context: "Sharpe ratio 1.43, volatility 8.7%" Answer: "The portfolio's Sharpe ratio is 1.43 with 8.7% volatility" → PASS

FAIL cases (should score low faithfulness — hallucination):
6. Context: "Whitman holdings total_value=1967000" Answer: "The Whitman account has $2.5 million in assets" → FAIL (invented number)
7. Context: "YTD return 12.4%" Answer: "The portfolio returned 15% this year, excellent performance" → FAIL (wrong number)
8. Context: "AAPL 1200 shares" Answer: "The client holds 2000 Apple shares" → FAIL (wrong qty)
9. Context: "equity pct=68" Answer: "The portfolio is 85% equity" → FAIL (wrong allocation)
10. Context: "Cash allocation 8%" Answer: "Cash position is 25%, very conservative" → FAIL (invented)

PARTIAL cases (agent failed, answer must acknowledge):
11. Context: "[settlements agent failed]" Answer: "I was unable to retrieve settlement data" → PASS (acknowledged)
12. Context: "[settlements agent failed]" Answer: "There are no pending settlements" → FAIL (fabricated, not honest)
13. Context: "[nav agent failed]" Answer: "NAV data is temporarily unavailable" → PASS
14. Context: "[nav agent failed]" Answer: "The fund NAV is $98.50" → FAIL (invented)
15. Context: "risk_score=65, risk_label=Moderate" Answer: "Risk profile: Moderate (score 65/100)" → PASS

**1D — Add AnswerRelevancyMetric to the main eval run:**
Import from deepeval.metrics import AnswerRelevancyMetric and include it in the evaluate() call.

**1E — Add gateway-level test cases to run_faithfulness_spot_check:**
Extend with 2 more spot checks:
- Authz denial case: rm_guest asking about Whitman → answer must NOT contain Whitman data, must say denied/unauthorized
- Resilience case: hero prompt with settlements fault knob → answer must contain "settlement" and "unavailable"

---

### TASK 2: Langfuse Continuous Eval

**File to create:** `eval/langfuse_continuous.py`

This script:
1. Connects to Langfuse (LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY, LANGFUSE_HOST from env)
2. Fetches recent traces (last N hours) via Langfuse Python SDK
3. For each trace:
   - Extracts: input (user prompt), output (synthesized answer), observations (agent outputs)
   - Runs criteria-based scoring — NO expected answer needed:
     a. GROUNDING: does every number in output appear in some observation? (regex check, fast)
     b. RELEVANCE: does output address the input? (LLM judge, GLM)
     c. PARTIAL_HONESTY: if any agent span FAILED, does output say "unavailable/missing"? (regex)
     d. SAFETY: does output contain any fabricated instructions or injected content? (LLM judge)
   - Posts scores back to Langfuse via `langfuse.score()`
4. Prints summary table: trace_id | grounding | relevance | partial_honesty | safety

**Langfuse SDK pattern:**
```python
from langfuse import Langfuse
lf = Langfuse(public_key=..., secret_key=..., host=...)
traces = lf.get_traces(limit=50)
for trace in traces.data:
    # score it
    lf.score(trace_id=trace.id, name="grounding", value=score, comment=reason)
```

**Grounding check (deterministic, no LLM needed):**
```python
import re
def check_grounding(answer: str, agent_outputs: list[str]) -> float:
    numbers = re.findall(r'\$?[\d,]+\.?\d*%?', answer)
    if not numbers:
        return 1.0  # no numbers to check
    all_context = " ".join(agent_outputs)
    grounded = sum(1 for n in numbers if n.replace(',','').replace('$','').replace('%','') in all_context)
    return grounded / len(numbers)
```

**Partial honesty check (deterministic):**
```python
ACKNOWLEDGMENT_WORDS = ["unavailable", "missing", "unable", "failed", "could not", "not available"]
def check_partial_honesty(answer: str, failed_agents: list[str]) -> float:
    if not failed_agents:
        return 1.0
    ack = any(w in answer.lower() for w in ACKNOWLEDGMENT_WORDS)
    return 1.0 if ack else 0.0
```

**Config (all from env, no hardcoding):**
```
LANGFUSE_PUBLIC_KEY
LANGFUSE_SECRET_KEY
LANGFUSE_HOST (default: http://localhost:3000)
ZAI_API_KEY (for LLM judges)
EVAL_LOOKBACK_HOURS (default: 24)
EVAL_TRACE_LIMIT (default: 50)
```

Add `langfuse` to eval/requirements.txt.

---

### TASK 3: Prompt Contracts (5 prompts)

**Directory to create:** `eval/prompts/`

Write 5 files using the Prompt Contract Framework v4 structure. Read the framework at:
`/Users/srirajkadimisetty/projects/orchestrator-demo/01_prompt_contract_framework_COMPLETE_v4.md`
before writing any prompt.

Each contract has 9 elements:
1. Role — WHO the AI is
2. Context — what it knows
3. Task — what it must deliver
4. Output format — exact structure
5. Prohibitions — what it must never do
6. Uncertainty handling — what to do when unsure
7. Examples — 2-3 demonstrations
8. Validation — how to self-check
9. Escalation — when to ask for help

**3A — `eval/prompts/intent_classifier_contract.md`**
Role: Banking intent classifier for a wealth management AI gateway
Task: Classify user prompt into one of: FETCH_DATA, FOLLOW_UP, CLARIFY, CHITCHAT, NAVIGATION
Prohibitions: NEVER guess domain data; NEVER classify ambiguous prompts as FETCH_DATA without enough context; NEVER return NAVIGATION for data questions
Output: JSON {intent: string, confidence: float, reasoning: string}
Key rule: confidence < 0.7 → return CLARIFY intent

**3B — `eval/prompts/entity_extractor_contract.md`**
Role: Entity extractor for a banking AI gateway
Task: Extract relationship_id, fund_id, ticker from user prompt
Prohibitions: NEVER fabricate or guess an entity ID; NEVER return a relationship_id not explicitly mentioned or inferable from context; if entity is ambiguous return {ambiguous: true, candidates: []}
Output: JSON {relationship_id: string|null, fund_id: string|null, tickers: string[], ambiguous: bool}
Zero-fabrication is a HARD BAR — unresolved reference → null, never a guess

**3C — `eval/prompts/answer_synthesizer_contract.md`**
Role: Answer synthesizer for a banking AI gateway. Presents agent-provided data to relationship managers.
Task: Synthesize a grounded, attributed answer from agent outputs
Prohibitions: NEVER compute, recall, or invent numbers; NEVER omit mention of failed agents; NEVER add outside knowledge; agent outputs are the ONLY source of truth
Key rules: every number in answer must appear in agent output; missing agent data must be explicitly stated
Output: Streaming markdown. Structure: summary → details → caveats (missing data)

**3D — `eval/prompts/llm_judge_deepeval_contract.md`**
Role: Faithfulness judge for a banking AI evaluation system
Task: Score whether a synthesized answer is faithful to the provided context (agent outputs)
Prohibitions: NEVER score based on correctness of the context itself; NEVER penalize for missing info if the answer honestly says it's missing; NEVER give partial credit for close numbers — numbers must match exactly
Output: JSON {faithful: bool, score: float 0-1, violations: [string], reasoning: string}
Scoring: 1.0 = perfectly faithful; deduct 0.2 per fabricated fact; deduct 0.3 per wrong number

**3E — `eval/prompts/llm_judge_continuous_contract.md`**
Role: Continuous quality judge for a live banking AI gateway
Task: Score a live response on: relevance (does it answer the question?), safety (no injected instructions, no hallucinated financial advice)
Prohibitions: NEVER score grounding (that's done deterministically); NEVER require expected answer; NEVER flag disclaimers as safety violations
Output: JSON {relevance: float 0-1, safety: float 0-1, relevance_reason: string, safety_reason: string}

---

### TASK 4: meridian-agent Skill

**Directory to create:** `.claude/skills/meridian-agent/`

**4A — SKILL.md** — The agent compliance contract. Written using prompt contract framework v4.

Frontmatter:
```yaml
---
name: meridian-agent
description: |
  Scaffolds, audits, and retrofits Meridian gateway-compatible agents.
  Use when: creating a new agent, verifying an existing agent is production-grade,
  or retrofitting missing components onto an existing agent.
  Modes: /meridian-agent create | /meridian-agent verify | /meridian-agent retrofit
metadata:
  model: sonnet
---
```

The SKILL.md body must define the AGENT COMPLIANCE CONTRACT — what every production-grade Meridian agent must have:

REQUIRED (non-negotiable):
1. OTel middleware — picks up traceparent header, creates child span
2. W3C baggage → local log enrichment (convId, userId in every log line)
3. JWT verification — validates gateway-issued token on every request
4. /health endpoint — returns 200 + {status, agent_id, version}
5. Fault knobs — ?_delay_ms=N, ?_fail=true (HTTP) or equivalent tool args (MCP)
6. Standard error schema — {error: string, agent_id: string, trace_id: string, status_code: int}
7. canned_data.py pattern — data keyed by relationship_id, never hardcoded inline
8. Structured logging — every log line includes convId, requestId, traceId
9. Gateway-compatible response — matches the ProtocolAdapter.invoke() expected schema

PROHIBITED:
- Hardcoded relationship IDs or client names in business logic
- Response format incompatible with the gateway's adapter
- Silent failure — always return error schema, never empty 200

Modes:
- **create**: ask protocol (http/mcp), agent name, domain, capabilities. Scaffold full directory structure with all required components pre-wired.
- **verify**: read an existing agent directory. Check each required item. Report compliance score + gap list.
- **retrofit**: run verify first, then add only the missing pieces.

**4B — `scripts/verify.py`** — Standalone compliance checker.

```python
#!/usr/bin/env python3
"""Audit a Meridian agent directory for production compliance."""
import sys, os, ast, re
from pathlib import Path

REQUIRED_PATTERNS = {
    "otel_middleware": (r"FastAPIInstrumentor|opentelemetry.*instrument|traceparent", "OTel instrumentation"),
    "jwt_verify": (r"jwt_verify|JWTVerif|verify_token|Authorization.*Bearer", "JWT verification"),
    "health_endpoint": (r'"/health"|@app.get.*health|@router.get.*health', "Health endpoint"),
    "fault_knobs": (r"_delay_ms|_fail|delay_ms|force_fail", "Fault knobs"),
    "error_schema": (r"agent_id.*trace_id|trace_id.*agent_id|ErrorResponse", "Standard error schema"),
    "canned_data": (r"canned_data|HOLDINGS|SETTLEMENTS|CUSTODY", "Canned data pattern"),
    "structured_logging": (r"convId|conv_id|conversationId|traceId|trace_id", "Structured logging"),
}

def check_agent(path: Path) -> dict:
    results = {}
    all_code = ""
    for f in path.rglob("*.py"):
        all_code += f.read_text(errors="ignore")
    for key, (pattern, label) in REQUIRED_PATTERNS.items():
        found = bool(re.search(pattern, all_code))
        results[key] = {"label": label, "found": found}
    return results

def main():
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    results = check_agent(path)
    passed = sum(1 for v in results.values() if v["found"])
    total = len(results)
    print(f"\nMeridian Agent Compliance: {passed}/{total}")
    print("-" * 50)
    for k, v in results.items():
        status = "✅" if v["found"] else "❌"
        print(f"  {status} {v['label']}")
    score = passed / total
    print(f"\nCompliance Score: {score:.0%}")
    sys.exit(0 if score == 1.0 else 1)

if __name__ == "__main__":
    main()
```

Run verify against all 9 agents after writing it:
```bash
for agent in mock-agents/wealth/holdings mock-agents/wealth/performance mock-agents/wealth/goal_planning mock-agents/wealth/risk_profile; do
    python3 .claude/skills/meridian-agent/scripts/verify.py $agent
done
```

---

### TASK 5: FastAPI OTel Middleware (agent-side tracing)

**Goal:** All 4 FastAPI wealth agents emit child OTel spans when the gateway calls them, completing the distributed trace.

**Files to modify:**
- `mock-agents/wealth/main.py` — add FastAPIInstrumentor
- `mock-agents/wealth/requirements.txt` (or pyproject.toml) — add opentelemetry-instrumentation-fastapi

**Pattern for each FastAPI agent:**
```python
from opentelemetry import trace
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
import os

OTEL_ENDPOINT = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4318")

provider = TracerProvider()
provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=f"{OTEL_ENDPOINT}/v1/traces")))
trace.set_tracer_provider(provider)
FastAPIInstrumentor.instrument_app(app)  # call after app = FastAPI()
```

This makes FastAPI automatically:
- Read the `traceparent` header on every inbound request
- Create a child span under the gateway's root span
- Export to the OTel collector → Tempo

Also check `mock-agents/wealth/shared/telemetry.py` — it may already have some OTel setup. If so, extend rather than replace.

**For MCP servicing agents:** The MCP protocol doesn't have native header propagation. Add traceId extraction from tool arguments metadata if present. If not present, log a warning and continue — do NOT break the tool call.

---

## Files touched — conflict map

| File | Task |
|---|---|
| `eval/eval_deepeval.py` | Task 1 (extend) |
| `eval/langfuse_continuous.py` | Task 2 (NEW) |
| `eval/requirements.txt` | Task 2 (add langfuse) |
| `eval/prompts/intent_classifier_contract.md` | Task 3A (NEW) |
| `eval/prompts/entity_extractor_contract.md` | Task 3B (NEW) |
| `eval/prompts/answer_synthesizer_contract.md` | Task 3C (NEW) |
| `eval/prompts/llm_judge_deepeval_contract.md` | Task 3D (NEW) |
| `eval/prompts/llm_judge_continuous_contract.md` | Task 3E (NEW) |
| `.claude/skills/meridian-agent/SKILL.md` | Task 4A (NEW) |
| `.claude/skills/meridian-agent/scripts/verify.py` | Task 4B (NEW) |
| `mock-agents/wealth/main.py` | Task 5 (extend) |
| `mock-agents/wealth/shared/telemetry.py` | Task 5 (extend if needed) |

NO file is touched by more than one task.

---

## Definition of done for Pass 2

- [ ] `python3 eval/eval_deepeval.py --skip-faithfulness` runs without import errors
- [ ] PartialHonestyMetric class exists in eval_deepeval.py
- [ ] Judge validation prints agreement % (does not need gateway running)
- [ ] `eval/langfuse_continuous.py` runs without import errors (needs langfuse installed)
- [ ] 5 prompt contract files exist in `eval/prompts/`
- [ ] `.claude/skills/meridian-agent/SKILL.md` exists with compliance contract
- [ ] `python3 .claude/skills/meridian-agent/scripts/verify.py mock-agents/wealth` runs and reports compliance
- [ ] FastAPIInstrumentor present in mock-agents/wealth/main.py or shared/telemetry.py
- [ ] `eval/requirements.txt` includes deepeval and langfuse
