# Build Report — Conduit AI Gateway

> Status & verification record: **what is built, what is proven, and how to re-verify it.**
> For what the product is, see [`docs/PROJECT-OVERVIEW.md`](docs/PROJECT-OVERVIEW.md); to run it,
> [`docs/OPERATOR-RUNBOOK.md`](docs/OPERATOR-RUNBOOK.md).

**Bottom line:** the build is complete and GTM-demo ready. World B is achieved (the gateway
carries zero domain knowledge), all demo beats pass end-to-end, and the observability + eval
stack is live.

---

## 1. Status at a glance

| Capability | Status | Evidence |
|---|---|---|
| **World B** — zero domain knowledge in gateway | ✅ | `scripts/world-b-check.sh` → CRITICAL 0 |
| **Multi-domain** — 3 domains, 11 agents | ✅ | 4 wealth (HTTP) + 5 asset-servicing (MCP) + 2 insurance (HTTP) load at boot |
| **Domain-by-manifest** — onboard with no gateway code | ✅ | Insurance added by manifest + coverage service only |
| **Hero** — cross-protocol fan-out + grounded answer | ✅ | Whitman overview spans HTTP + MCP; numbers trace to agent outputs |
| **Multi-turn** — session carry-forward | ✅ | Follow-up answered from session, no entity restated |
| **Resilience** — agent kill → honest partial | ✅ | MCP killed → 3/5 answer, states missing data |
| **Entitlement** — out-of-book denial | ✅ | rm_jane→Okafor and uw_sam→POL-88003 both denied (2 domains) |
| **Clarification** — ambiguous → scoped question | ✅ | Deterministic `extracted ∩ required = ∅` |
| **Glass-box** — live decision trace | ✅ | 6 stages + per-agent latency, HTTP/MCP; renders Connected |
| **Langfuse** — trace per turn, session per conversation | ✅ | Input **+** output captured; grouped by session |
| **Continuous eval** — async quality scoring | ✅ | `eval` profile worker; grounding/honesty/relevance/safety, dedup+sampling |
| **Release gate** — routing accuracy + faithfulness | ✅ | DeepEval (offline / CI) |
| **Scale** — concurrent streaming | ✅ | k6: 0% errors at 10 VUs, virtual threads, TTFT p95 7.5s |
| **Identity** — OIDC, RS256/JWKS, verified each hop | ✅ | IAM service; book-of-business in coverage services, not the token |

## 2. How to re-verify

```bash
# Full pipeline (build → up → smoke → e2e → eval; world-b-check is a hard gate)
./scripts/verify.sh

# Or piecewise:
bash scripts/world-b-check.sh                 # CRITICAL must be 0
docker compose up -d && bash scripts/seed-users.sh
docker compose --profile eval up -d eval-worker   # continuous scoring
```

The four demo beats and their exact prompts are in
[`docs/OPERATOR-RUNBOOK.md` §5](docs/OPERATOR-RUNBOOK.md).

## 3. Test coverage

- **Gateway:** JUnit 5 + Spring Boot Test + Testcontainers (Redis, Cerbos) — resolver routing,
  input synthesis (zero fabricated IDs), harness (breaker/partial join), registry introspection,
  Cerbos allow/deny.
- **Agents:** pytest + FastAPI TestClient + MCP smoke — schema-valid canned data, fault knobs,
  entity-ID validation.
- **E2E:** Playwright (`tests/e2e/`) — hero, glass-box, resilience, entitlement, clarification.
- **Eval:** DeepEval routing-accuracy + faithfulness (`scripts/eval-gate.sh`).
- **Load:** k6 (`tests/load/`).

## 4. Known limitations (none block the demo)

- **Glass-box deny rendering:** on a full entitlement denial the Entitlement Gate panel shows
  "WAITING" rather than an explicit "DENIED" (the outcome is still clear — 0 agents ran). Cosmetic.
- **Grounding on aggregates:** data turns can score <1.0 when the answer includes computed
  totals/projections not verbatim in any single agent output — a real signal, not hallucination.
- **cAdvisor on Docker Desktop macOS:** limited container labels; deferred to k8s.
- **GLM judge:** the async eval judge runs on OpenAI (the Z.AI account was out of balance);
  flip `JUDGE_*` back to Z.AI to use GLM.
- **Single environment:** this stack is "prod" only — no separate staging/test env tagging.

## 5. Build history

The phase-by-phase build log lived in `phases/` and an earlier long-form version of this report.
Those have been retired now that the build is complete; the git history retains them. This report
is the living status record going forward.
