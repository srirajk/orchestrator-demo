# anatomy.md

> Auto-maintained by OpenWolf. Last scanned: 2026-07-10T12:40:29.400Z
> Files: 953 tracked | Anatomy hits: 0 | Misses: 0

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/29f180d9-6150-4300-ae30-ee615cfcd441/scratchpad/

- `audit.py` — query, subst, bare_metric (~1488 tok)
- `V2__seed_demo_data.sql` — ============================================================================ (~4215 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/61462777-4366-4937-8cc5-c2b06d4bd1fe/scratchpad/

- `glassbox.mjs` — Read-only visual check: log into the real chat UI, send prompts, screenshot the live glass box. (~785 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/probe/

- `Probe.java` — Probe: main (~1124 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/gateway/src/main/java/ai/conduit/gateway/adapter/mcp/

- `McpAdapter.java` — ProtocolAdapter for MCP agents (Asset Servicing domain). (~8001 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/gateway/src/main/java/ai/conduit/gateway/registry/introspection/

- `AgentIntrospector.java` — Derives input/output schemas and resolved connection from agent specs. (~3605 tok)
- `McpToolIntrospector.java` — Fetches tool input/output schemas from an MCP server via JSON-RPC {@code tools/list}. (~4904 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/gateway/src/main/java/ai/conduit/gateway/registry/model/

- `AgentManifest.java` — Full stored manifest = what the domain team submitted + what the gateway derived. (~2826 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/gateway/src/main/resources/

- `application.yml` (~4151 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/mock-agents/servicing/

- `server.py` — JwtAuthMiddleware: get_custody_positions, get_settlements, get_corporate_actions, get_nav + 6 more (~3813 tok)

## ../../../../private/tmp/claude-501/-Users-srirajkadimisetty-projects-orchestrator-demo/ed1064b3-910e-402b-8a61-3b4abe474725/scratchpad/wt-mcp/registry/

- `agent-manifest.schema.json` — Declares used (~4183 tok)

## ../../../../tmp/

- `dag_drive.py` — Drive the DAG through the BFF as a real OIDC-authenticated user and capture the answer. (~411 tok)
- `f_identity_live_test.py` — One-off F-IDENTITY live verification: log in as rm_jane via the real BFF OIDC flow (~340 tok)
- `live_renewal_test.py` (~240 tok)
- `mcp_negative_test.py` — Negative test for servicing-mcp: confirm the introspection handshake (initialize, (~931 tok)
- `run_demo_catalog_supp.py` — Supplementary runs to fully characterize the asset-servicing settlement_risk gap (~645 tok)
- `run_demo_catalog.py` — Drive the 9 demo-catalog questions through the LIVE Conduit BFF, capture real (~1131 tok)

## ../../.claude/projects/-Users-srirajkadimisetty-projects-orchestrator-demo/memory/

- `feedback_eval_framework.md` (~487 tok)
- `feedback_gateway_package_structure.md` (~322 tok)
- `feedback_gateway_rules.md` (~526 tok)
- `feedback_no_sync_telemetry.md` (~506 tok)
- `feedback_observability_standard.md` (~364 tok)
- `feedback_verify_agent_base.md` (~394 tok)
- `feedback_workflow_parallelism.md` (~341 tok)
- `MEMORY.md` — Memory Index (~764 tok)
- `project_domain_manifest_strategy.md` — Agreed Architecture — Domain Manifest + Gateway Strategy (~958 tok)
- `project_gateway_auth_model.md` (~548 tok)
- `project_gold_class_overnight.md` (~611 tok)
- `project_langfuse_observability_model.md` (~462 tok)
- `project_meridian_gateway.md` — What this project is (~777 tok)
- `project_perf_livelock_rootcause.md` (~1023 tok)
- `project_production_grade_phase.md` — Declares build (~3048 tok)
- `project_repo_layout_worktrees.md` (~468 tok)
- `reference_prompt_framework.md` (~323 tok)

## ../orchestrator-chat/

- `docker-compose.perf.yml` — Performance-testing overlay — see docs/specs/PERF-TEST-HARNESS.md (~1514 tok)
- `docker-compose.yml` — Docker Compose services (~10663 tok)

## ../orchestrator-chat/.wolf/

- `anatomy.md` — anatomy.md (~20329 tok)
- `cerebrum.md` — Cerebrum (~11122 tok)
- `memory.md` — Memory (~45774 tok)

## ../orchestrator-chat/apps/chat/

- `Dockerfile` — Docker container definition (~391 tok)

## ../orchestrator-chat/apps/chat/bff/src/main/java/ai/conduit/chat/chat/

- `GatewayClient.java` — Client for the Conduit gateway's OpenAI-compatible {@code /v1/chat/completions} (~1667 tok)

## ../orchestrator-chat/apps/chat/bff/src/main/java/ai/conduit/chat/config/

- `AppProperties.java` — Strongly-typed configuration for the Conduit Chat BFF, bound from (~1248 tok)

## ../orchestrator-chat/apps/chat/bff/src/main/java/ai/conduit/chat/memory/

- `LlmSummaryService.java` — LLM-backed {@link SummaryService}. Regenerates a conversation's facts-free rolling (~2711 tok)

## ../orchestrator-chat/apps/chat/bff/src/main/resources/

- `application.yml` (~1432 tok)

## ../orchestrator-chat/apps/chat/bff/src/test/java/ai/conduit/chat/chat/

- `GatewayClientTransportTest.java` — Transport-level guards for {@link GatewayClient}. (~1426 tok)

## ../orchestrator-chat/docs/

- `AGENT-ONBOARDING-HANDBOOK.md` — The Conduit Agent Onboarding Handbook (~18821 tok)
- `AGENT-REGISTRATION-MODEL.md` — Agent Registration Model — what's declared, and why the domain + sub-domain + agent split (~2156 tok)
- `ANALYTICS-TIER-DRAFT.md` — Analytics Tier — Business DRAFT (PO / domain, via Fable) (~1181 tok)
- `DOMAIN-KNOWLEDGE-VERIFIED.md` — Domain Knowledge — VERIFIED (D3) (~2470 tok)
- `EVAL-AND-DASHBOARDS-MULTISTEP.md` — Eval + Dashboards for Multi-Step Orchestration (D8 / D9) (~980 tok)
- `GO-LIVE-RUNBOOK.md` — Go-Live Runbook — Multi-Step Orchestration (D11) (~1567 tok)
- `GOLD-CLASS-OVERNIGHT-GOAL.md` — GOLD-CLASS OVERNIGHT GOAL — locked 2026-07-07 (deliver by morning 2026-07-08) (~1986 tok)
- `ONBOARDING-AN-AGENT.md` — Onboarding an Agent into Conduit (~2742 tok)
- `ORCHESTRATION-DAG-SPEC.md` — Smart Orchestration — Dependent Multi-Step Plans (DAG) Spec (~3706 tok)
- `REPO-STRUCTURE-AND-NAMING.md` — Repo Structure + Naming — PROPOSAL (not executed; behind a hard test gate) (~1456 tok)
- `TEST-EVIDENCE.md` — Test Evidence — proving the orchestration build is solid (~1278 tok)

## ../orchestrator-chat/docs/orchestration-architecture/

- `CONTROL-FLOW-DESIGN-BASIS.md` — Control-Flow Design Basis — conditionals & iteration (foundation for T6) (~1113 tok)
- `DECISION-LOG.md` — Decision Log — Conduit multi-step orchestration (~2442 tok)
- `GURU-TEARDOWN-AND-FIXPLAN.md` — Adversarial Teardown + Fix Plan (~1641 tok)
- `README.md` — Project documentation (~471 tok)
- `SOLUTION-ARCHITECTURE.md` — Solution Architecture — Conduit multi-step orchestration (target design) (~1970 tok)

## ../orchestrator-chat/docs/perf/

- `RESULTS.md` — Perf results — Phase 1 (deterministic LLM) (~1306 tok)

## ../orchestrator-chat/docs/specs/

- `dashboards-multistep-D9.md` — Codex task spec — D9: Smart-Orchestration metric dashboards (~1430 tok)
- `FINANCE-FIX-concentration-denominator.md` — Codex task — FINANCE-FIX: concentration denominator + keep loss-ratio disclosures visible (~1061 tok)
- `GATEWAY-PACKAGE-STRUCTURE.md` — Gateway package structure — the convention of record (~1670 tok)
- `goal-pick-measurement-T1.5.md` — Codex task T1.5 — measure goal-pick accuracy on the SHIPPED embedding model (~1049 tok)
- `PERF-TEST-HARNESS.md` — PERF test harness — make the LLM a knob, then measure the gateway (~4652 tok)
- `PERF-trace-write-async.md` — Take the trace write off the request path (~1467 tok)
- `PERF-vt-carrier-pinning-FIX.md` — PERF FIX — gateway virtual-thread carrier pinning / concurrency livelock (~5028 tok)
- `PERF-vt-carrier-pinning.md` — PERF — gateway virtual-thread carrier pinning / concurrency livelock (SAVED, fix later) (~1146 tok)
- `PRODUCTION-GRADE-ROADMAP.md` — Codex roadmap — production-grade (PRESSURE-TESTED: Fable + Opus pre-flight applied) (~2974 tok)
- `rename-acme-to-meridian.md` — Codex task spec — rename agent-id namespace `acme.*` → `meridian.*`  ✅ T1 COMPLETE (0 authz flips ve (~1598 tok)
- `routing-hardening-T1.6.md` — Codex task T1.6 — routing hardening + honest goal-pick measurement (~1257 tok)
- `servicing-coverage-seeding-fix.md` — Codex task — fix servicing coverage/book seeding so settlement_risk demos LIVE (no admin bypass) (~1207 tok)
- `servicing-settlement_risk-vertical.md` — Codex task spec — Asset-Servicing `settlement_risk` analytics vertical (~1493 tok)
- `T-map-iteration.md` — Codex task — MAP / bounded iteration (declared, terminating, partial-tolerant, with a real harness) (~2284 tok)
- `T-multiturn-dag-backstop.md` — Codex task — multi-turn backstop for the DAG path (follow-up questions must keep working) (~1255 tok)
- `T-observability-e2e.md` — Codex task — complete e2e observability (make it real, and make it TESTABLE) (~1793 tok)
- `T-observability-metrics-dashboard.md` — Codex task — observability, the metrics pillar: gateway SLO metrics + operator dashboard + alerts (~1426 tok)
- `T-routing-measurement-gate.md` — Codex task — routing measurement-gate (a new agent can't silently poach a neighbor) (~1358 tok)
- `T-routing-reranker.md` — Codex task — LLM re-ranker for close/negation routing (the last routing-intelligence gap) (~1478 tok)
- `T2-per-hop-identity.md` — Codex task T2 — per-hop identity: zero-trust propagation + agent verification (fail-closed) (~1961 tok)
- `T3-translator-teeth.md` — Codex task T3 — the translator's TEETH: real output-schema introspection + boot-time select validati (~2538 tok)
- `T4-coverage-per-producer.md` — Codex task T4 — per-producer / row-level coverage, fail-closed, single-source book (the last securit (~2620 tok)
- `T5-grounding.md` — Codex task T5 — grounding: deterministic number rendering (the engine can never misstate a figure) (~1748 tok)
- `T6-conditional-edges.md` — Codex task T6 — conditional nodes (declared, deterministic branching) (~2114 tok)
- `T7-audit-replay.md` — T7 — Audit / Replay (design of record + phased build) — Fable-designed, Opus-verified (~2906 tok)

## ../orchestrator-chat/eval/goal-pick/

- `labeled_queries.json` (~3112 tok)
- `measure_goal_pick.py` — Measure live goal-pick accuracy without changing routing behavior. (~3803 tok)

## ../orchestrator-chat/gateway/

- `Dockerfile` — Docker container definition (~167 tok)
- `pom.xml` (~1592 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/adapter/

- `ProtocolAdapter.java` — Uniform interface over outbound agent protocols (HTTP/OpenAPI and MCP/SSE). (~554 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/adapter/http/

- `HttpAdapter.java` — ProtocolAdapter for HTTP / OpenAPI agents (Wealth domain). (~2483 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/adapter/mcp/

- `McpAdapter.java` — ProtocolAdapter for MCP/SSE agents (Asset Servicing domain). (~4887 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/api/v1/chat/

- `ChatCompletionsController.java` — End-to-end deadline for a single chat turn. Configuration, never a constant — the emitter (~4032 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/config/

- `AppConfig.java` — Synchronous client used by {@code EntityExtractor} (LLM) and {@code EntityResolver} (CRM). (~928 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/domain/chat/

- `ChatService.java` — How many recent user turns (including the current one) are concatenated into the routing (~28258 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/domain/intent/

- `IntentClassificationException.java` — Thrown when intent classification cannot be completed — the LLM is unreachable, rate limited, (~232 tok)
- `IntentClassifier.java` — Stage A of the request pipeline: classifies the user's intent before routing. (~9921 tok)
- `IntentResult.java` — Output of {@link IntentClassifier}. (~267 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/infrastructure/redis/

- `RedisConfig.java` — Redis connection pools. (~841 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/infrastructure/telemetry/

- `AsyncTraceWriter.java` — Flushes a request's trace events to storage <em>off the request thread</em>. (~1722 tok)
- `MdcPropagation.java` — Re-applies a captured MDC context map (requestId/conversationId/userId — set by (~534 tok)
- `RedisTraceStorageAdapter.java` — Redis-backed trace storage. (~1726 tok)
- `TraceEvent.java` — A single structured event emitted by the request pipeline to the glass-box panel. (~711 tok)
- `TraceEventPublisher.java` — In-memory pub/sub bus for glass-box trace events. (~2646 tok)
- `TraceStorageAdapter.java` — Storage contract for trace events — allows the glass-box panel to replay past requests. (~446 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/infrastructure/telemetry/event/

- `PlanGraphData.java` — Payload for the {@code plan_graph} trace event — the resolved multi-step execution graph, emitted (~354 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/orchestration/executor/

- `Blackboard.java` — Per-request working memory for multi-step ({@link ai.conduit.gateway.orchestration.model.Plan DAG}) (~3055 tok)
- `DagPlanExecutor.java` — Executes a dependency-wired {@link Plan} (populated {@code dependsOn} edges, topological order) (~3395 tok)
- `FlatPlanExecutor.java` — Flat plan executor: fans out all {@link PlanNode}s in parallel on virtual threads (~2347 tok)
- `InputContractValidator.java` — Structural validation of a bound wire input against a consumer's INTROSPECTED input schema (~1617 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/orchestration/harness/

- `AgentHarness.java` — Wraps each agent adapter call in a resilience harness: (~5112 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/orchestration/planner/

- `DagResolution.java` — Outcome of a {@link DagResolver} run. (~301 tok)
- `DagResolver.java` — Deterministic, domain-agnostic dependency-graph resolver for multi-step orchestration. (~2851 tok)
- `ResolutionError.java` — A single, machine-classified reason a {@link DagResolver} run could not produce a valid plan. (~463 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/registry/index/

- `RemoteEmbeddingClient.java` — EmbeddingClient that delegates to an OpenAI-compatible /v1/embeddings endpoint. (~1322 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/registry/introspection/

- `AgentIntrospector.java` — Derives input/output schemas and resolved connection from agent specs. (~2795 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/registry/loader/

- `RegistryBootstrapLoader.java` — Loads agent manifests from the external registry location at startup. (~1209 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/registry/model/

- `AgentManifest.java` — Full stored manifest = what the domain team submitted + what the gateway derived. (~1854 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/registry/service/

- `AgentRegistry.java` — Core registry service. (~1602 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/resolver/service/

- `AgentResolver.java` — Resolver — Stage A + Stage B from the spec. (~5588 tok)
- `LlmRoutingRerankerClient.java` — Service: LlmRoutingRerankerClient (~2096 tok)
- `RoutingRerankerClient.java` — Second-pass selector for ambiguous embedding results. (~572 tok)

## ../orchestrator-chat/gateway/src/main/java/ai/conduit/gateway/synthesis/answer/

- `AnswerSynthesizer.java` — Synthesizes a grounded, streamed answer from agent outputs using Z.AI GLM. (~12929 tok)

## ../orchestrator-chat/gateway/src/main/resources/

- `application.yml` (~3483 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/adapter/mcp/

- `McpAdapterResultTest.java` — MCP distinguishes two kinds of failure, and a client must branch on both. (~978 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/domain/intent/

- `IntentClassifierTest.java` — CLAUDE.md §5 — "Never return canned/fallback data when the LLM is unreachable; surface the error." (~858 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/infrastructure/telemetry/

- `AsyncTraceWriteTest.java` — Trace persistence must be batched, off the request path, and bounded. (~1585 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/orchestration/executor/

- `BlackboardTest.java` — Unit tests for the {@link Blackboard} binding convention. Type/entity/name symbols are generic (~3482 tok)
- `DagExecutorChaosTest.java` — TECHNIQUE 7 — Partial-failure / chaos (hard-rule d: a failed agent never cancels its siblings). (~2286 tok)
- `DagExecutorConcurrencyTest.java` — TECHNIQUE 6 — Concurrency / race (the important one). (~2756 tok)
- `DagPlanExecutorTest.java` — Hermetic tests for {@link DagPlanExecutor}. Uses the <b>real</b> {@link AgentHarness} (real (~3445 tok)
- `ExecutorTestSupport.java` — Shared, domain-free builders for the {@link DagPlanExecutor} concurrency/chaos battery. Uses the (~1296 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/orchestration/harness/

- `AgentHarnessResilienceIT.java` — Resilience integration test — verifies the harness's core invariant: (~2954 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/orchestration/planner/

- `DagGraphFixtures.java` — Shared, domain-free builders for the {@link DagResolver} test battery (property, determinism, (~1113 tok)
- `DagRealManifestTest.java` — Integration-style, hermetic (no docker) tests over the <b>real shipped manifests</b> in (~2599 tok)
- `DagResolverAdversarialTest.java` — TECHNIQUE 4 — Adversarial / negative testing. Hand-built pathological graphs that must produce a (~2092 tok)
- `DagResolverDeterminismTest.java` — TECHNIQUE 2 — Determinism / order-independence. (~1190 tok)
- `DagResolverMetamorphicTest.java` — TECHNIQUE 3 — Metamorphic testing. A metamorphic relation asserts how the OUTPUT must change (or (~1388 tok)
- `DagResolverPropertyTest.java` — TECHNIQUE 1 — Property-based / fuzz testing (seeded, reproducible). (~2180 tok)
- `DagResolverScaleTest.java` — TECHNIQUE 5 — Scale / load. Deep and wide graphs prove the resolver is iterative and (~1167 tok)
- `DagResolverTest.java` — Hermetic tests for {@link DagResolver}. Candidate manifests are synthetic and built in-test from (~3886 tok)

## ../orchestrator-chat/gateway/src/test/java/ai/conduit/gateway/resolver/service/

- `AgentResolverRerankerTest.java` — A re-ranker that declines to pick a single winner because the request needs SEVERAL capabilities (~2210 tok)

## ../orchestrator-chat/iam-service/

- `Dockerfile` — Docker container definition (~253 tok)

## ../orchestrator-chat/iam-service/src/main/java/com/openwolf/iam/config/

- `SecurityConfig.java` — Security configuration for the IAM service. (~6715 tok)

## ../orchestrator-chat/iam-service/src/main/java/com/openwolf/iam/service/

- `LlmPolicyGenerationService.java` — Generates Cerbos YAML policies from natural-language intent using Z.AI GLM. (~5832 tok)

## ../orchestrator-chat/infra/toxiproxy/

- `proxies.json` (~67 tok)

## ../orchestrator-chat/mock-agents/hr-policy/tests/

- `test_hr_policy.py` — TestHealth: test_health_ok, test_health_domain, test_openapi_json_served, test_openapi_has_get_opera (~2412 tok)

## ../orchestrator-chat/mock-agents/insurance/

- `main.py` — API: 1 endpoints (~904 tok)

## ../orchestrator-chat/mock-agents/insurance/renewal_risk/

- `compute.py` — RenewalRiskInputError: load_target_loss_ratio, compute_renewal_risk (~3518 tok)
- `handler.py` — API: 1 endpoints (~1244 tok)
- `test_compute.py` — test_loss_ratio_from_real_shapes_list_form, test_loss_ratio_from_real_shapes_single_form, test_flag_ (~2393 tok)

## ../orchestrator-chat/mock-agents/servicing/

- `server.py` — JwtAuthMiddleware: get_custody_positions, get_settlements, get_corporate_actions, get_nav + 4 more (~2809 tok)

## ../orchestrator-chat/mock-agents/servicing/shared/

- `error_schema.py` — AgentToolError: mcp_error_dict (~1094 tok)

## ../orchestrator-chat/mock-agents/stub-llm/

- `Dockerfile` — Docker container definition (~166 tok)
- `requirements.txt` — Python dependencies (~15 tok)
- `server.py` — API: 2 endpoints (~2428 tok)

## ../orchestrator-chat/mock-agents/tests/

- `test_agent_integration.py` — gets: test_no_auth_header_is_rejected, test_bearer_unused_is_rejected, test_empty_bearer_is_rejected (~4968 tok)
- `test_insurance.py` — TestInsuranceAuth: test_no_auth_header_is_rejected, test_bearer_unused_is_rejected, test_empty_beare (~4046 tok)
- `test_wealth.py` — TestHealth: test_health_ok, test_openapi_json_served, test_openapi_has_required_params, test_known_r (~1378 tok)

## ../orchestrator-chat/mock-agents/wealth-market-research/tests/

- `test_market_research.py` — TestHealth: test_health_ok, test_health_domain, test_openapi_json_served, test_openapi_has_get_opera (~2162 tok)

## ../orchestrator-chat/mock-agents/wealth/

- `main.py` — API: 1 endpoints (~948 tok)

## ../orchestrator-chat/mock-agents/wealth/concentration/

- `__init__.py` (~0 tok)
- `compute.py` — DEFAULT_SECTOR_THRESHOLD: load_thresholds, compute_concentration (~3251 tok)
- `handler.py` — API: 1 endpoints (~1140 tok)
- `test_compute_properties.py` — positions, portfolios, true_metrics, test_weights_in_unit_interval_and_sum_to_one (~7531 tok)
- `test_compute.py` — limit: test_weights_sum_to_one, test_top_single_name_correct, test_hhi_and_effective_positions, test (~1593 tok)

## ../orchestrator-chat/mock-agents/wealth/shared/

- `jwt_verify.py` — JWKSUnreachable: verify_bearer_token (~2040 tok)

## ../orchestrator-chat/registry/

- `agent-manifest.schema.json` — Declares used (~2813 tok)

## ../orchestrator-chat/registry/domains/hr/

- `hr-knowledge.json` (~157 tok)

## ../orchestrator-chat/registry/domains/wealth-management/

- `market-research.json` (~136 tok)

## ../orchestrator-chat/registry/manifests/

- `acme.insurance.claim_status.json` (~538 tok)
- `acme.insurance.policy_details.json` (~533 tok)
- `acme.servicing.cash_management.json` (~473 tok)
- `acme.servicing.corporate_actions.json` (~512 tok)
- `acme.servicing.custody_positions.json` (~488 tok)
- `acme.servicing.nav.json` (~492 tok)
- `acme.servicing.settlement_status.json` (~574 tok)
- `acme.wealth.concentration.json` — Declares concentration (~664 tok)
- `acme.wealth.goal_planning.json` (~494 tok)
- `acme.wealth.holdings.json` — Declares allocation (~542 tok)
- `acme.wealth.market_research.json` (~615 tok)
- `acme.wealth.performance.json` (~501 tok)
- `acme.wealth.risk_profile.json` (~488 tok)

## ../orchestrator-chat/registry/manifests/asset-servicing/

- `meridian.servicing.cash_management.json` (~512 tok)
- `meridian.servicing.corporate_actions.json` (~571 tok)
- `meridian.servicing.settlement_status.json` (~593 tok)

## ../orchestrator-chat/registry/manifests/hr/

- `acme.hr.policy_qa.json` (~655 tok)

## ../orchestrator-chat/registry/manifests/insurance/

- `acme.insurance.renewal_risk.json` (~860 tok)

## ../orchestrator-chat/registry/manifests/wealth-management/

- `acme.wealth.concentration.json` — Declares concentration (~726 tok)
- `acme.wealth.market_research.json` (~625 tok)
- `meridian.wealth.concentration.json` — Declares concentration (~811 tok)
- `meridian.wealth.goal_planning.json` (~514 tok)
- `meridian.wealth.holdings.json` — Declares allocation (~543 tok)
- `meridian.wealth.performance.json` (~536 tok)
- `meridian.wealth.risk_profile.json` (~574 tok)

## ../orchestrator-chat/scripts/

- `e2e-matrix.sh` — e2e-matrix.sh — full ABAC variation matrix through the running gateway/chat path. (~1307 tok)
- `integration-test.sh` — Curl-based integration tests against the running gateway. (~1300 tok)
- `perf-record-fixtures.sh` — Record AIMock fixtures once, so every later load run is free and deterministic. (~1625 tok)
- `perf-toxic.sh` — Toggle Toxiproxy toxics on the `llm` proxy — the latency knob and the only true hang. (~836 tok)

## ../orchestrator-chat/tests/e2e/security_harness/

- `__init__.py` (~0 tok)
- `conftest.py` — pytest_configure, jane_session, sam_session, carlos_session (~1679 tok)
- `run.sh` — Conduit security+correctness E2E acceptance gate. (~161 tok)
- `test_entitlement.py` — test_coverage_denial (~967 tok)
- `test_grounding.py` — test_grounding_no_fabrication (~1920 tok)
- `test_identity.py` — of: test_hop_identity_verified, test_no_token_rejected, test_tampered_signature_rejected (~3297 tok)
- `test_insurance_renewal_multistep.py` — test_insurance_renewal_multistep, test_insurance_out_of_book_policy_denied (~1540 tok)
- `test_positive_path.py` — test_multistep_concentration, test_single_step_holdings (~1167 tok)

## ../orchestrator-chat/tests/e2e/security_harness/lib/

- `__init__.py` (~0 tok)
- `bff_client.py` — class: login, create_conversation, send_message, ask (~1225 tok)
- `config.py` (~529 tok)
- `docker_logs.py` — Thin wrapper around `docker logs` for pulling evidence out of gateway/agent containers. (~341 tok)
- `evidence.py` — Small helper for printing diagnosable evidence blocks under `pytest -v -s`. (~223 tok)
- `ground_truth.py` — fetch_concentration_ground_truth, grounded_percentages, grounded_hhi (~776 tok)
- `iam_client.py` — get_jwt (~411 tok)
- `trace_client.py` — latest_request_id_for_conversation, events_for_request, trace_for_conversation, events_of_type (~589 tok)

## ../orchestrator-chat/tests/e2e/tests/

- `09-cerbos-authz.spec.ts` — Cerbos Authorization Matrix — direct PDP API tests (~6346 tok)

## ../orchestrator-chat/tests/load/

- `coldstart-load-test.js` — Conduit Gateway — Cold-start robustness load test (flat vs DAG path). (~1664 tok)

## ../orchestrator-chat/tests/schema/

- `test_registry_contracts.py` — load_json, validator, assert_valid, domain_paths (~2078 tok)

## ./

- `.DS_Store` (~1640 tok)
- `.gitignore` — Git ignore rules (~524 tok)
- `agent-manifest.schema.json` (~1558 tok)
- `BUILD_REPORT.md` — Build Report — Conduit AI Gateway (~1028 tok)
- `CLAUDE.md` — OpenWolf (~1698 tok)
- `docker-compose.yml` — Docker Compose services (~8697 tok)
- `MORNING-NOTES.md` — Morning notes — OIDC SSO is fixed ✅ (~1292 tok)
- `README.md` — Project documentation (~4394 tok)
- `TODO.md` — Conduit — Open TODO / Backlog (~823 tok)
- `z.ai-tiers.md` — Declares Model (~778 tok)

## .claude/

- `settings.json` (~441 tok)
- `settings.local.json` (~36 tok)

## .claude/rules/

- `openwolf.md` (~313 tok)
- `world-b.md` — World B — non-negotiable invariants for gateway code (~611 tok)

## .claude/skills/fastapi-pro/

- `SKILL.md` — Use this skill when (~1623 tok)

## .claude/skills/meridian-agent/

- `SKILL.md` — Conduit Agent Compliance Contract (~4793 tok)

## .claude/skills/meridian-agent/scripts/

- `verify.py` — Audit a Meridian agent directory for production compliance. (~511 tok)

## .claude/skills/openai-agents/

- `SKILL.md` — Scaffolding OpenAI Agents (~3394 tok)

## .claude/skills/openai-agents/scripts/

- `verify.py` — Verify scaffolding-openai-agents skill structure. (~331 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/gateway/src/test/resources/domains/

- `asset-servicing.json` (~50 tok)
- `wealth-management.json` (~147 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/gateway/src/test/resources/domains/asset-servicing/

- `cash-management.json` (~210 tok)
- `corporate-actions.json` (~246 tok)
- `custody-operations.json` (~240 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/gateway/src/test/resources/domains/wealth-management/

- `private-banking.json` (~378 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/registry/domains/

- `asset-servicing.json` (~50 tok)
- `wealth-management.json` (~147 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/registry/domains/asset-servicing/

- `cash-management.json` (~210 tok)
- `corporate-actions.json` (~246 tok)
- `custody-operations.json` (~240 tok)

## .claude/worktrees/agent-a1caf25dcb0ab3d39/registry/domains/wealth-management/

- `private-banking.json` (~378 tok)

## .claude/worktrees/agent-a1ff5915ba6b85292/gateway/

- `pom.xml` (~2265 tok)

## .claude/worktrees/agent-a1ff5915ba6b85292/gateway/src/main/resources/

- `application.yml` (~2021 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/

- `Dockerfile` — Docker container definition (~272 tok)
- `PACKAGE-STRUCTURE.md` — IAM Service — Package Structure (~1638 tok)
- `pom.xml` (~1326 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/main/java/com/openwolf/iam/auth/

- `JwtSigningKeys.java` — Resolves the RS256 signing key for the IAM service. (~1544 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/main/java/com/openwolf/iam/config/

- `SecurityConfig.java` — Security configuration for the IAM service. (~4386 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/main/java/com/openwolf/iam/service/

- `LlmPolicyGenerationService.java` — Generates Cerbos YAML policies from natural-language intent using Z.AI GLM. (~5852 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/main/resources/

- `application.yml` (~949 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/test/java/com/openwolf/iam/auth/

- `JwtSigningKeysTest.java` — Verifies the RS256 signing-key resolution contract: (~819 tok)

## .claude/worktrees/agent-a22a6475d6896c0d8/iam-service/src/test/java/com/openwolf/iam/service/

- `LlmPolicyGenerationTimeoutTest.java` — Regression test for the untimed-read bug class. (~889 tok)

## .claude/worktrees/agent-a32957dd9f9ee5dfd/e2e/tests/

- `10-coverage-flow.spec.ts` — Coverage-flow E2E tests (Phase 11+). (~2256 tok)

## .claude/worktrees/agent-a32957dd9f9ee5dfd/mock-agents/wealth-coverage/

- `data.py` — discover, check, resolve (~1200 tok)

## .claude/worktrees/agent-a3c0915360f71cbe6/

- `docker-compose.yml` — Docker Compose services (~6184 tok)

## .claude/worktrees/agent-a3c0915360f71cbe6/admin-ui/src/api/

- `client.ts` — Exports authApi, User, Team, TeamMember + 9 more (~1584 tok)

## .claude/worktrees/agent-a3c0915360f71cbe6/admin-ui/src/pages/

- `Policies.tsx` — ACTIONS_MAP (~3854 tok)

## .claude/worktrees/agent-a3c0915360f71cbe6/user-mgmt/

- `db.py` — get_db, init_db (~279 tok)
- `Dockerfile` — Docker container definition (~68 tok)
- `main.py` — get_redis (~19847 tok)
- `models.py` — Declares Base (~1336 tok)
- `pytest.ini` (~8 tok)
- `requirements.txt` — Python dependencies (~85 tok)

## .claude/worktrees/agent-a3c0915360f71cbe6/user-mgmt/tests/

- `conftest.py` — reset_db, app_client (~1052 tok)
- `test_integration.py` — TestBankScenario: test_rm_jane_exists_with_correct_book, test_rm_jane_has_wealth_segment, test_rm_ja (~6065 tok)
- `test_user_mgmt.py` — TestJWKS: test_jwks_has_correct_structure, test_jwks_e_is_65537, test_jwks_n_length, test_issue_toke (~7518 tok)

## .claude/worktrees/agent-a5f8f4277b6a7131d/mock-agents/insurance/claim_status/

- `handler.py` — Claim Status agent — GET /claim-status (~819 tok)

## .claude/worktrees/agent-a5f8f4277b6a7131d/mock-agents/insurance/policy_details/

- `handler.py` — Policy Details agent — GET /policy-details (~540 tok)

## .claude/worktrees/agent-a5f8f4277b6a7131d/mock-agents/insurance/shared/

- `validators.py` — validate_policy_id, validate_claim_id (~465 tok)

## .claude/worktrees/agent-a5f8f4277b6a7131d/mock-agents/tests/

- `test_insurance.py` — TestInsuranceAuth: test_no_auth_header_is_allowed, test_bearer_unused_is_allowed, test_empty_bearer_ (~3686 tok)

## .claude/worktrees/agent-acd21fbd8fbefdaf5/gateway/src/main/java/ai/meridian/gateway/domain/auth/

- `CerbosEntitlementAdapter.java` — Batch-calls the Cerbos PDP for relationship entitlement checks. (~3134 tok)
- `EntitlementService.java` — Enforces relationship-level entitlements by delegating to the Cerbos PDP. (~1278 tok)

## .claude/worktrees/agent-acd21fbd8fbefdaf5/gateway/src/main/resources/

- `application.yml` (~1359 tok)

## .claude/worktrees/agent-ae332f7ec09476468/.wolf/

- `anatomy.md` — anatomy.md (~16056 tok)

## .claude/worktrees/agent-ae332f7ec09476468/gateway/src/main/java/ai/conduit/gateway/adapter/mcp/

- `McpAdapter.java` — ProtocolAdapter for MCP agents (Asset Servicing domain). (~6921 tok)
- `McpTransportProperties.java` — MCP transport configuration — the negotiated protocol version and the default (~989 tok)

## .claude/worktrees/agent-ae332f7ec09476468/gateway/src/main/java/ai/conduit/gateway/registry/introspection/

- `AgentIntrospector.java` — Derives input/output schemas and resolved connection from agent specs. (~2010 tok)
- `McpToolIntrospector.java` — Fetches a tool's input schema from an MCP server via {@code tools/list}. (~4626 tok)

## .claude/worktrees/agent-ae332f7ec09476468/gateway/src/main/java/ai/conduit/gateway/registry/model/

- `AgentManifest.java` — Full stored manifest = what the domain team submitted + what the gateway derived. (~1003 tok)

## .claude/worktrees/agent-ae332f7ec09476468/gateway/src/main/resources/

- `application.yml` (~2061 tok)

## .claude/worktrees/agent-ae332f7ec09476468/gateway/src/test/java/ai/conduit/gateway/adapter/mcp/

- `McpAdapterResultTest.java` — Unit tests for {@link McpAdapter} response handling — no live server required. (~1485 tok)

## .claude/worktrees/agent-ae332f7ec09476468/mock-agents/servicing/

- `server.py` — JwtAuthMiddleware: get_custody_positions, get_settlements, get_corporate_actions, get_nav + 3 more (~2227 tok)

## .claude/worktrees/agent-ae332f7ec09476468/registry/

- `agent-manifest.schema.json` (~1903 tok)

## .claude/worktrees/wf_ba56dc76-986-1/mock-agents/wealth-coverage/

- `__init__.py` (~0 tok)
- `data.py` — discover, check, resolve (~944 tok)
- `Dockerfile` — Docker container definition (~55 tok)
- `main.py` — API: 4 endpoints (~745 tok)
- `requirements.txt` — Python dependencies (~22 tok)

## .claude/worktrees/wf_ba56dc76-986-1/mock-agents/wealth-coverage/tests/

- `__init__.py` (~0 tok)
- `test_coverage.py` — test_health, test_discover_rm_jane_returns_two_results, test_discover_rm_guest_returns_empty, test_d (~1392 tok)

## .claude/worktrees/wf_ba56dc76-986-2/

- `docker-compose.yml` — Docker Compose services (~6065 tok)

## .claude/worktrees/wf_ba56dc76-986-2/gateway/src/main/resources/

- `application.yml` (~1314 tok)

## .claude/worktrees/wf_ba56dc76-986-2/gateway/src/main/resources/domains/

- `asset-servicing.json` (~47 tok)
- `wealth-management.json` (~128 tok)

## .claude/worktrees/wf_ba56dc76-986-2/gateway/src/main/resources/domains/asset-servicing/

- `cash-management.json` (~55 tok)
- `corporate-actions.json` (~63 tok)
- `custody-operations.json` (~68 tok)

## .claude/worktrees/wf_ba56dc76-986-2/gateway/src/main/resources/domains/wealth-management/

- `private-banking.json` (~164 tok)

## .claude/worktrees/wf_ba56dc76-986-2/registry/manifests/

- `acme.servicing.nav.json` (~429 tok)

## .pytest_cache/

- `.gitignore` — Git ignore rules (~10 tok)
- `CACHEDIR.TAG` (~51 tok)
- `README.md` — Project documentation (~76 tok)

## .pytest_cache/v/cache/

- `lastfailed` (~29 tok)
- `nodeids` (~2952 tok)
- `stepwise` (~1 tok)

## .wolf/

- `anatomy.md` — Auto-maintained project file catalog with descriptions and token estimates. (~6443 tok)
- `buglog.json` — OpenWolf JSON bug log for errors, failures, root causes, and fixes. (~2124 tok)
- `cerebrum.md` — Long-term project learnings, user preferences, do-not-repeat notes, and decision log. (~6443 tok)
- `memory.md` — Chronological action log for session work and outcomes. (~2771 tok)
- `OPENWOLF.md` — OpenWolf operating protocol for navigation, memory, bug logging, and session-end updates. (~1675 tok)

## Glass-box authorization gate trace (2026-07-03)

- `apps/chat/web/src/components/TraceRail.tsx` — collapsible Decision-trace rail: intent→resolve→gate rows (✓/✗ + reason)→answer. (~1400 tok)
- `apps/chat/web/src/hooks/useTraceStream.ts` — conversation-scoped trace SSE hook + selectDenial(). (~900 tok)
- `apps/chat/web/src/lib/gatewayTrace.ts` — vendored mirror of @conduit/gateway-client SSE parse + trace types (Docker build-context safe). (~600 tok)
- `gateway/.../telemetry/event/GateData.java` — Trace payload for one authz gate decision: {gate, effect, reason, agent}; audience|segment|classification|coverage. (~350 tok)

## admin-ui/

- `.env.example` — Documents optional Vite gateway proxy override and admin JWT storage keys. (~55 tok)
- `DESIGN-SYSTEM.md` — Axiom Admin UI Design Direction (~251 tok)
- `Dockerfile` — Docker container definition (~103 tok)
- `index.html` — Meridian Admin (~236 tok)
- `nginx.conf` (~148 tok)
- `package.json` — Node.js package manifest (~192 tok)
- `postcss.config.js` (~23 tok)
- `tailwind.config.js` (~145 tok)
- `tsconfig.json` — TypeScript configuration (~162 tok)
- `tsconfig.node.json` (~61 tok)
- `vite.config.ts` (~96 tok)

## admin-ui/src/

- `App.tsx` — Protected (~423 tok)
- `index.css` — Styles: 5 rules (~228 tok)
- `main.tsx` — queryClient (~141 tok)
- `vite-env.d.ts` — Vite env typing for optional non-secret workbench gateway API base override (~65 tok)

## admin-ui/src/api/

- `client.ts` — Exports authApi, User, AuditEntry, PageResponse + 16 more (~1988 tok)

## admin-ui/src/auth/

- `tokenStorage.ts` — Canonical admin JWT localStorage helper with legacy key fallback (~120 tok)

## admin-ui/src/components/

- `ErrorBoundary.tsx` — Route-level React crash boundary with Axiom fallback and reload action. (~455 tok)
- `Layout.tsx` — Layout (~85 tok)
- `Sidebar.tsx` — nav (~774 tok)

## admin-ui/src/components/ui/

- `Badge.tsx` — colors (~346 tok)
- `Button.tsx` — Button (~464 tok)
- `Dialog.tsx` — Dialog (~543 tok)
- `EmptyState.tsx` — EmptyState (~224 tok)
- `Input.tsx` — Input (~823 tok)
- `Skeleton.tsx` — Skeleton (~83 tok)
- `Toast.tsx` — Ctx (~528 tok)

## admin-ui/src/features/workbench/

- `api.ts` — Typed gateway API boundary for trace health, domain/agent registry, auth-capable trace stream request metadata, and MVP non-streaming chat turns (~1070 tok)
- `types.ts` — Workbench chat, trace, domain manifest, agent manifest, and UI tone types (~980 tok)
- `WorkbenchPage.tsx` — Feature page composition for chat, trace, context ledger, coverage, and domain/agent health panels (~1230 tok)

## admin-ui/src/features/workbench/components/

- `ChatPanel.tsx` — Workbench chat composer/transcript panel labeled MVP non-streaming (~1450 tok)
- `ContextLedger.tsx` — Request context ledger derived from trace events (~1080 tok)
- `CoveragePanel.tsx` — Access check and manifest coverage status panel (~1260 tok)
- `HealthPanel.tsx` — Domain/agent registry and indexed-status panel; liveness remains explicit placeholder (~1390 tok)
- `Panel.tsx` — Shared workbench panel shell (~200 tok)
- `StatusPill.tsx` — Shared green/slate status pill (~190 tok)
- `TraceRail.tsx` — Live trace event rail renderer (~1120 tok)

## admin-ui/src/features/workbench/hooks/

- `useTraceStream.ts` — Fetch-based SSE reader with Authorization headers, parsing, reconnect, and request filtering (~1300 tok)
- `useWorkbenchChat.ts` — Workbench conversation state and MVP non-streaming chat submission hook (~860 tok)

## admin-ui/src/features/workbench/utils/

- `format.ts` — Workbench value/time/duration formatting helpers (~230 tok)
- `selectors.ts` — Domain, coverage, agent, and trace selector helpers (~430 tok)
- `traceEvents.ts` — Trace event tone/title/detail presentation helpers (~780 tok)

## admin-ui/src/hooks/

- `useAuth.tsx` — Ctx (~551 tok)

## admin-ui/src/pages/

- `AuditLog.tsx` — ACTION_COLORS — renders table (~3652 tok)
- `Dashboard.tsx` — StatCard (~2110 tok)
- `Login.tsx` — Login — renders form (~847 tok)
- `Policies.tsx` — RESOURCES (~3774 tok)
- `Roles.tsx` — EMPTY — renders table, modal (~3469 tok)
- `Teams.tsx` — EMPTY — renders modal (~3886 tok)
- `Users.tsx` — Users screen; per-segment "Segments & clearance" row editor (segment+tier rows, dup/required validation, live segments+classification-schema options), table shows per-segment chips, default chat_user role (~7200 tok)
- `Workbench.tsx` — Tiny route wrapper around features/workbench/WorkbenchPage (~30 tok)

## apps/admin/ (Axiom Admin Console — split from admin-ui)


## apps/insights/web/src/

- `App.tsx` — Conduit Insights React SPA (SSO login, 7 views). AnswerQualityView binds `grounding_distribution`→`<ScoreHistogram>` (vertical binned bars + 0→1 axis) and `grounding_by_model`→`<Bars unit="score">`. UserView binds `compaction`→`<CompactionStat>` (summary-attached bar + compactions/tokens-saved/avg-messages stat tiles). Unbuilt-endpoint placeholder panels have been removed (no stubs shown): TrustView dropped the 'Fabricated IDs' KPI card (trust-strip now 3 cols); AgentsView dropped 'Latency by stage' (Agent fleet now col12); UserView dropped 'Their conversations' + 'Entitlement decisions' (backend conversation-store adapter seam left intact — UI only). Awaiting-data panels (breakers, request volume, cost-over-time, bulkhead) kept. (~13000 tok)

## docs/

- `agent-catalog.md` — Agent Catalog — The 9 Demo Agents (Conduit demo) (~1479 tok)
- `agent-registration-schema-a2a-aligned.md` — Agent Registration Schema — Grounded in the A2A Agent Card Standard (~1795 tok)
- `agent-registry-demo-spec.md` — Agent Registry — Demo Spec (~2333 tok)
- `AGENTS.md` — Conduit AI — Agent Guide (~2838 tok)
- `authorization-abac-cerbos-deep-spec.md` — Authorization (ABAC + Cerbos) — Deep Spec (~2218 tok)
- `authorization-model.md` — Enterprise AI Gateway — Authorization Model (~5478 tok)
- `authz-architecture-brief.md` — Enterprise AI Gateway — Authorization & User Management Architectural Brief (~7031 tok)
- `clearance-tiers-and-agent-metadata.md` — Clearance Tiers — Tenant Schema, Agent Metadata & Policy (~2142 tok)
- `CONDUIT-WORKBENCH-PLAN.md` — Conduit Workbench + Axiom Control Plane Plan (~1861 tok)
- `DIAGRAM-PROMPTS.md` — Conduit — Diagram Prompts & Generation Guide (~4216 tok)
- `domain-manifest-and-memory.md` — Governed Memory + Context Envelope Architecture (~1745 tok)
- `domain-onboarding-standard.md` — Domain Onboarding Standard (~3140 tok)
- `EVAL-EXTRACTION.md` — Eval — Extraction Record (lift into its own project) (~1425 tok)
- `EVAL-FRAMEWORK.md` — Conduit Eval Framework — an agent-agnostic evaluation worker (~2269 tok)
- `EVAL-PRODUCT-VISION.md` — Eval — Product Vision (framework + app) (~1004 tok)
- `execution-orchestration-layer.md` — Execution / Orchestration Layer — Spec (~1323 tok)
- `gateway-domain-architecture.md` — Conduit AI Gateway — Domain Architecture (~7275 tok)
- `harness-and-telemetry-deep-spec.md` — Harness & Telemetry — Deep Spec (~2123 tok)
- `implementation-checklist.md` — Implementation Checklist — Pre-Build Validation Spec (~4638 tok)
- `input-synthesis-deep-spec.md` — Input Synthesis — Deep Spec (the one unproven piece) (~1951 tok)
- `master-build-plan-consolidated.md` — Enterprise Intelligence Platform — Master Build Plan (Meridian) (~3833 tok)
- `MODEL-SELECTION.md` — Model Selection Guide — Conduit AI Gateway (~2289 tok)
- `OPERATOR-RUNBOOK.md` — Conduit Gateway — Operator & Demo Runbook (~3446 tok)
- `platform-vision-and-maturity-path.md` — Enterprise Intelligence Platform — Vision & Maturity Path (~1324 tok)
- `PROJECT-OVERVIEW.md` — Conduit — Project Overview (~165 tok)
- `PROMPT-AUDIT.md` — Prompt Contract Audit — every production prompt vs the 9-element framework (~1931 tok)
- `QA-CODEX-PLAYBOOK.md` — Conduit — QA Playbook (for Codex / automated browser QA) (~4967 tok)
- `technical-architecture-clear-boundaries.md` — Technical Architecture — Clear Boundaries (~1990 tok)
- `WORLD-B-LOCKDOWN.md` — Conduit — World B Architecture Lockdown (~8940 tok)

## e2e/test-results/.playwright-artifacts-0/traces/

- `7a53b919140d28dd4a37-391c81cb85d1c98b3615.network` (~79237 tok)
- `7a53b919140d28dd4a37-391c81cb85d1c98b3615.trace` (~151811 tok)
- `7a53b919140d28dd4a37-558fe196c511fbdd0a8e-recording1-pwnetcopy-1.network` (~80232 tok)
- `7a53b919140d28dd4a37-558fe196c511fbdd0a8e-recording1.network` (~80232 tok)
- `7a53b919140d28dd4a37-558fe196c511fbdd0a8e-recording1.trace` (~90219 tok)

## e2e/test-results/.playwright-artifacts-0/traces/resources/

- `030f9585212bd4d4e8932faea87ca0c07d4c24db.js` (~592 tok)
- `0343e594c39bddf540cdc2b7f6df503cfe66f487.js` — o: s, n, r + 32 more (~20654 tok)
- `0653e2bfd3130722f3938eb6900e92206023ba53.json` (~136 tok)
- `070a2d08f2cdf956d7bf2abc21d2e08006237af2.js` — a: c, t, n + 38 more (~18270 tok)
- `07e31ee3a54cd6aa47b5c77c2a2194934c413fb9.mpga` (~9158 tok)
- `08063357155026a844d505103c2ba110f636ab87.js` — p: k, ee, ne + 8 more (~7574 tok)
- `0d61cc73fdf43c6da76578eba5888d1913ddecda.json` (~12 tok)
- `0e9bb1376132060f32f3e220ba63470a35f490ae.json` (~26 tok)
- `0f846cc9cad81e217a580fc15016263715113dbd.js` — oe: P, ce, le + 23 more (~131000 tok)
- `1131ab94b5bd1e9be6117cb9419426c4cca31219.js` — Declares __vite__mapDeps (~320 tok)
- `1168e7a447320dcfcd7074a37fca7bb28aa68982.json` (~25 tok)
- `1fba32ce49ceffcfe2f07b99a169e089e026102f.js` — Zustand store (~3997 tok)
- `28b487a65176965773d2ff284f80afeeaaf1d715.js` — Zustand store (~19989 tok)
- `29d062100d2b06a33b19ec8c1d06635835768e0a.js` — e: t, n, f + 32 more (~7015 tok)
- `2a54d3a858dab9852073d9b7a2719abba991e7e7.json` (~26 tok)
- `2a97fa1d6af0d95dada7a9efd53c709b9c91274f.json` (~216 tok)
- `2d5df1ca645a6befabf1b77886d4aa2a7c294d18.json` (~2511 tok)
- `34aa7ab290500956a57041d5e13379a189c01708.json` (~136 tok)
- `35eb6ebc791c6b5b9ca9f2194167b649bad7aa62.js` — u: p, m, b + 34 more (~35755 tok)
- `3878133610fdf85f30838a7c5f76ed542caa734e.js` — O: k, A, j + 22 more (~51111 tok)
- `3aaff721c193d48883d63a11f20c9bedaa0cb376.js` — API routes: GET, DELETE (4 endpoints) (~4271 tok)
- `3bc10f26e0353d74ea0e5b8b65d94d07866a75f2.json` (~27 tok)
- `411302380719ce66d634f88bf0bb2aecb1d4140c.js` — X: Re, ze, Be + 4 more (~13301 tok)
- `425e2537256d1f22529d69b841d581b6c6d65b04.json` (~10 tok)
- `43959990f5a42e47c1de0e123795bf62ea5a256f.js` — __vite__mapDeps: t, n (~228144 tok)
- `4454dc1fc51ece178fefeec725ca71c3ec6409cb.html` (~7 tok)
- `4642e87aecaf6e091fe96ccc06a3bebb7fce88d7.json` (~1747 tok)
- `4671466db3e3da41d7922794c5f1a9bf39ed465b.html` (~90 tok)
- `4885b2351b933169986c36026a3750148595d78b.js` (~39 tok)
- `48b5f2889b2812e61969a522ae13c6034bd4a00f.json` (~908 tok)
- `48ef67bb077c5f45ef6069d7c2c13a70dd9318b5.js` — i: c, f, p + 77 more (~174976 tok)
- `4cc190f9050014936991797d845444d66cb45c64.css` — Styles: 1 rules (~8069 tok)
- `516e8b7c1a6291851965bbc27c011f1f38f194b7.js` — API routes: GET (1 endpoints) (~734 tok)
- `55a978fe3153f45d5f38ee3b4160a53dcc3bfd95.js` — t: n, r, i + 28 more (~128078 tok)
- `6820547eda69254306e4603b940563d7833710cc.json` (~6 tok)
- `6fbc69b90b7c8ad57a25b2c0d3c5cafb39513de7.js` — n: i (~479 tok)
- `713c4ed0340326d49310a4f85181a5c9bd6a1a4a.json` (~36 tok)
- `770361375f3405c47f37962a0b5a280b26d42d6b.js` — n: r, i, s + 56 more (~26518 tok)
- `773d82cb42c5789514ed90d45b1decc286f4a638.json` (~36 tok)
- `7819fc28204f7aa7cf036aa58916cb4de1d90a96.js` — E: D, O, k + 12 more (~32435 tok)
- `7cb6efb98ba5972a9b5090dc2e517fe14d12cb04.json` (~2 tok)
- `7d2fd63aa61cbc2fc2394cc46ae98f3d28723ef4.json` (~6 tok)
- `7e8ca6b390cc3e7f89f3468a3f910db75197be38.js` — t: n, r, o + 19 more (~2317 tok)
- `819661f4176a424408fa58d3a9d14c131910df58.js` — p: _, v, y + 27 more (~59703 tok)
- `8475aea532424fd31b7bae82dd850351b4faef6a.html` — LibreChat (~2154 tok)
- `84773d5ae193daa1e445fa4113708f858631bee9.js` — xn: Sn, En, Dn, On, An (~29268 tok)
- `899d5679edd7269f047628bcb670c742b72d4f47.json` (~5 tok)
- `8bcfaf9f56810936711ac42b121badac09cb5547.json` (~324 tok)
- `976b01256050e3a79565bee7b938624b039f3a7d.css` — Styles: 1 rules, 48 vars, 7 media queries (~68627 tok)
- `97d170e1550eee4afc0af065b78cda302a97674c.json` (~1 tok)
- `9a69e180d4334913789416b15614aaea10d1aafb.json` (~14 tok)
- `9b7155d4563840103e2393f8f3dd9c3aec670a20.json` (~216 tok)
- `9ce3bd4224c8c1780db56b4125ecf3f24bf748b7.html` (~1 tok)
- `a7faca0fd091fdaa01341a8d3b9457fe04b0dfa6.js` — API routes: GET (1 endpoints) (~12884 tok)
- `acb4e7a182639fb37c957fb699feebdd5ab51e7c.js` — Declares qe (~10039 tok)
- `accb0f8d454871cac65377d3e4a8faebb1b51050.js` — C: o, re (~42708 tok)
- `ad3487c94038a0538b33f3dd5f78513b162975db.js` — o: s, c, l + 28 more (~19373 tok)
- `b0bb8441d385ca7137a8581049e36eb3af4b6bbc.js` (~236 tok)
- `b51c01321d39760aa28ce7944e143782ae6d4352.json` (~263 tok)
- `b736125f999f5e58a4df229845b352af14c8bd96.json` (~2411 tok)
- `bf21a9e8fbc5a3846fb05b4fa0859e0917b2202f.json` (~1 tok)
- `c1fc79b808fcda3c6824fbcbb6319b9e7b3415b4.js` — Zustand store (~1940 tok)
- `c6854507ffb14a0d0566c99e7d6b5a459a2f2c99.js` — _: b, x, C + 40 more (~25185 tok)
- `c82e7477009a02131f325a13621fc8ff580b6e82.json` (~367 tok)
- `cb1465592cd6e86710d51e13d430f11cbf8287b3.js` — e: t, t, n + 11 more (~16230 tok)
- `d61b510d6ec8dc9eb5ee0c325aa3b7506486fde2.js` — e: routes, h, p + 7 more (~6234 tok)
- `d981cc72bb5c8e65cbb5e191674d09192a840798.js` — s: d, f, p + 11 more (~3765 tok)
- `da1216592e0ca89841a3b583ed5e1866912a03eb.dat` (~646 tok)
- `da39a3ee5e6b4b0d3255bfef95601890afd80709.dat` (~0 tok)
- `da39a3ee5e6b4b0d3255bfef95601890afd80709.json` (~0 tok)
- `dad84ee1179552056016a99403ab54d69dd08254.js` — d: f (~6174 tok)
- `dd55ddbea8bae9ba0f9d636da9f2260cdaf9b98a.js` — Zustand store (~15516 tok)
- `e1eca1064f1fc0a147213ad976848ad5896e6c73.js` — PING_TYPE: pingClient, reloadUnresponsiveClients (~510 tok)
- `e256c337c93d89c74e302fcee475333e5d7247c1.json` (~200 tok)
- `e585cf56784ed0e7823df4954969f4ae12607e5e.webmanifest` (~155 tok)
- `ebe6dbbbd7bce07f192b092e44b475fc92f82504.json` (~130 tok)
- `ef7e8ca26a5b1210a3db5ed4323a1ef3cfa14966.json` (~20 tok)
- `f9f71fc267ebfc862fd34e38afb5091b4e95fcb8.json` (~37 tok)

## e2e/test-results/07-multi-turn-Multi-turn-c-f6fd6-ation-resets-client-context-chromium/

- `error-context.md` — Instructions (~136 tok)

## eval/

- `cerbos_golden_dataset.json` (~5722 tok)
- `cerbos_policy_eval.py` — check, run (~1465 tok)
- `continuous_loop.py` — main (~501 tok)
- `Dockerfile` — Docker container definition (~86 tok)
- `eval_deepeval.py` — PartialHonestyMetric: configure_judge_model, measure, a_measure, is_successful + 6 more (~5444 tok)
- `golden-prompts.json` (~3317 tok)
- `langfuse_continuous.py` — check_grounding, check_partial_honesty, llm_judge (~7457 tok)
- `langfuse_run_experiment.py` — Run a Langfuse experiment against the meridian-routing dataset. (~4132 tok)
- `langfuse_seed_datasets.py` — Seed Langfuse datasets from eval/golden-prompts.json. (~3565 tok)
- `multiturn-recency-insurance.json` — Cross-domain anaphor-recency regression guard (bug-234, uw_sam/insurance): establish policy A → switch B → re-name A → pronoun must bind to just-named A (POL-77002) not older B (POL-77001); asserted via coverage-gate entity in the decision trace. Proves the focal-recency fix is domain-generic. (~500 tok)
- `multiturn-routing.json` — Multi-turn context-aware routing regression guard (Calderon keyword-less-follow-up fix); expected domain/outcome + must_not_route per turn. (~600 tok)
- `requirements.txt` — Python dependencies (~41 tok)

## eval/prompts/

- `answer_synthesizer_contract.md` — Prompt contract v1.0: grounded answer synthesizer, agent outputs are ONLY truth, mandatory partial-honesty section, prompt injection via agent data addressed, 18 test cases (~2600 tok)
- `answer_synthesizer_contract.md` — Answer Synthesizer — Prompt Contract (~4679 tok)
- `entity_extractor_contract.md` — Prompt contract v1.0: entity extractor for relationship_ref/fund_ref/tickers, zero-fabrication hard bar, ambiguous→null, 18 test cases (~2200 tok)
- `entity_extractor_contract.md` — Entity Extractor — Prompt Contract (~4131 tok)
- `intent_classifier_contract.md` — Prompt contract v1.0: banking intent classifier, 5 intents (FETCH_DATA/FOLLOW_UP/CLARIFY/CHITCHAT/NAVIGATION), confidence<0.70→CLARIFY, 18 test cases (~2400 tok)
- `intent_classifier_contract.md` — Banking Intent Classifier — Prompt Contract (~3507 tok)
- `llm_judge_continuous_contract.md` — Prompt contract v1.0: live quality judge for Langfuse, scores relevance+safety only, grounding done separately, no expected answer required, 18 test cases (~2200 tok)
- `llm_judge_continuous_contract.md` — LLM Judge — Continuous Quality (Live Eval via Langfuse) — Prompt Contract (~4043 tok)
- `llm_judge_deepeval_contract.md` — Prompt contract v1.0: offline faithfulness judge for DeepEval, wrong number=-0.3 deduction, fabricated fact=-0.2, faithful threshold 0.70, 18 test cases (~2300 tok)
- `llm_judge_deepeval_contract.md` — LLM Judge — DeepEval Faithfulness (Offline Eval) — Prompt Contract (~3855 tok)

## feedback/

- `modifications-p.md` — , and TraceStreamController.java (line 50) returns persisted trace events by request ID. (~918 tok)

## gateway/

- `Dockerfile` — Docker container definition (~135 tok)
- `pom.xml` (~1494 tok)

## gateway/src/main/java/ai/conduit/gateway/api/v1/chat/

- `ChatCompletionsController.java` — Non-streaming ({@code stream:false}) — runs the same pipeline into a buffering emitter, (~2296 tok)

## gateway/src/main/java/ai/conduit/gateway/domain/chat/

- `ChatService.java` — Entry point from the controller — called on a virtual thread after the async boundary. Routes FETCH with bias-to-fetch (hasGroundedResolvableReference→resolveContextual entityKnown); FOLLOW_UP fallthrough to fetch when a grounded entity + confident route exist; routing-abstained FETCH degrades to grounded history synthesis when prior assistant data exists (hasPriorAssistantData). Deterministic identifier PRE-CHECK (identifyByIdPattern→resolve+CHECK→deny with the id's OWN domain copy before routing, bug-236); coverage else-branch named-entity backstop (resolveNamedReferenceBackstop/properNounPhrases: resolve typed proper-nouns principal-agnostically→CHECK→deny out-of-coverage NAMED entity instead of clarifying, bug-235); withheldDomains (structural-gate-pruned domains) threaded to the synthesizer for honest partial fulfillment (bug-237). Clarify copy aligned to capability (bug-239): buildDeterministicClarification lists candidates by NAME (+ id) with '- ' bullets (NO positional numbers) and invites "Reply with the <entityNoun> name or identifier" (entityNoun = manifest entity display, no hardcoded 'relationship ID'); buildClarificationQuestion hoists primaryResolvableEntity so the noun frames template + composed. No positional-index parsing anywhere — the resolver only honours name / manifest id_pattern. bug-233. setTraceOutput(rootSpan, answer): mirrors the synthesized answer onto the ROOT chat.handle span as langfuse.trace.output (blank skipped) at every answer path (synthesize, synthesizeFromHistory degrade, follow-up, chitchat) so Langfuse trace output populates and continuous eval scores it — clarify/deny paths (streamTextAndComplete) intentionally leave it empty and are filtered by the eval (bug-242). (~16600 tok)

## gateway/src/main/java/ai/conduit/gateway/domain/clarify/

- `ClarificationComposer.java` — The 4th grounded LLM call site (alongside IntentClassifier / EntityExtractor / AnswerSynthesizer). PHRASES a natural clarify question over a GROUNDED candidate set handed in as DELIMITED DATA; it never DECIDES to clarify (that stays deterministic in ChatService: extracted ∩ required = ∅) and never invents. Generic scaffold; entity noun + optional tone come from the manifest (World-B clean). compose() returns null (→ caller serves the deterministic template) when the LLM is unreachable OR validate() rejects: output blank / too long, contains any id_pattern token outside the candidate id set (core foreign-ID guard), or references no candidate. System prompt forbids positional numbers / 'reply with the number' — options are referred to by name + identifier only (bug-239); OPTIONS data list uses '- ' bullets not numbers. Non-streaming, single completion, own tight budget (config conduit.llm.clarification-composer.*, defaults inherit intent-classifier Z.AI/flash). (~2400 tok)

## gateway/src/main/java/ai/conduit/gateway/domain/intent/

- `IntentClassifier.java` — Stage A: combined intent+entity LLM (manifest-compiled prompt, temperature 0). Focal-entity rules (explicit name in latest msg supersedes history; pronoun→last focal; emit typed NAME not a recalled id; named entity→FETCH not CLARIFY) + deterministic deriveFocalReference() [id in latest msg → user-grounded ref → focalIdByNameMatch (proper-noun tokens vs transcript "Name (ID)") → lastFocalSingleId anaphora carry]. Extracts entities for FETCH_DATA AND FOLLOW_UP (bias-to-fetch fallthrough). bug-233. RECENCY (bug-234): deriveFocalReference() precedence reordered so an anaphoric turn that names no new entity binds to the MOST-RECENTLY-NAMED focal entity (recency carry) BEFORE the grounded-LLM-value fallback; a name shared with the latest message (sharesWord, ≥4 chars) counts as naming-this-turn and supersedes older focus. Fixes pronoun binding to a stale older entity. (~6500 tok)

## gateway/src/main/java/ai/conduit/gateway/insights/

- `BoardCatalog.java` — Declarative catalog of the 7 Insights boards + their panels (Prometheus PromQL or Langfuse). Board 7 (Cost & Quality) now also carries three answer-quality panels: `grounding_distribution` (bars histogram — grounding scores bucketed into SCORE_BINS=10 bins over [0,1] via `histogram()`), `grounding_by_model` (bars, score — avg grounding per generating model), and `compaction` (table — BFF compaction counters read from Prometheus: summary-attached %, tokens saved, compaction count, avg messages; range-invariant lifetime totals; gateway never calls the BFF). World-B clean (bins/labels/score-name are config/infra, no domain literals). (~4200 tok)
- `LangfuseMetricsSource.java` — MetricsSource over the Langfuse public API (cost/tokens/eval scores). Adds `groundingScores(limit)` (raw values of the configured grounding score → histogram input) and `groundingByModel(limit)` (joins each grounding score's traceId to that trace's generation model via `modelByTrace()` over GENERATION observations, then averages per model). Grounding score name is config `conduit.insights.grounding-score-name:grounding` (not a domain literal). (~5400 tok)

## gateway/src/main/java/ai/conduit/gateway/synthesis/answer/

- `AnswerSynthesizer.java` — Synthesizes a grounded, streamed answer from agent outputs using Z.AI GLM. System prompt forbids cross-entity aggregation/roll-ups (compute guardrail) and renders WITHHELD sections (structural-gate-denied domains) so mixed in/out-of-access asks fulfill the accessible part + state the withheld part; synthesizeFromHistory prompt also forbids computing/aggregating (bug-237). synthesize()/synthesizeFromHistory() now RETURN the accumulated answer text (String, was void) so ChatService can mirror it onto the ROOT chat.handle span as langfuse.trace.output — the former Span.current().setAttribute("langfuse.trace.output",…) here was unreliable (by synthesis time the active span is not guaranteed to be the root), so the trace-level output never landed and continuous eval scored an empty answer (bug-242). (~7600 tok)

## gateway/src/main/java/ai/meridian/gateway/

- `GatewayApplication.java` — GatewayApplication: main (~93 tok)

## gateway/src/main/java/ai/meridian/gateway/adapter/

- `ProtocolAdapter.java` — Uniform interface over outbound agent protocols (HTTP/OpenAPI and MCP/SSE). (~370 tok)

## gateway/src/main/java/ai/meridian/gateway/adapter/http/

- `HttpAdapter.java` — ProtocolAdapter for HTTP / OpenAPI agents (Wealth domain). (~2486 tok)

## gateway/src/main/java/ai/meridian/gateway/adapter/mcp/

- `McpAdapter.java` — ProtocolAdapter for MCP/SSE agents (Asset Servicing domain). (~4375 tok)

## gateway/src/main/java/ai/meridian/gateway/admin/

- `AgentRegistryController.java` — Agent registry management API. (~1122 tok)
- `DebugResolverController.java` — Debug endpoint for inspecting routing decisions without invoking any agents. (~638 tok)
- `DomainRegistryController.java` — RestController: DomainRegistryController (5 endpoints) (~488 tok)

## gateway/src/main/java/ai/meridian/gateway/api/v1/chat/

- `ChatCompletionsController.java` — RestController: ChatCompletionsController (2 endpoints) (~942 tok)

## gateway/src/main/java/ai/meridian/gateway/api/v1/chat/dto/

- `ChatRequest.java` — Subset of the OpenAI /v1/chat/completions request body. (~184 tok)
- `Message.java` — Class: Message (~65 tok)

## gateway/src/main/java/ai/meridian/gateway/api/v1/models/

- `ModelsController.java` — RestController: ModelsController (2 endpoints) (~216 tok)

## gateway/src/main/java/ai/meridian/gateway/api/v1/models/dto/

- `ModelsResponse.java` — ModelsResponse: Model (~108 tok)

## gateway/src/main/java/ai/meridian/gateway/api/v1/trace/

- `TraceStreamController.java` — Streams live glass-box trace events to connected clients. (~835 tok)

## gateway/src/main/java/ai/meridian/gateway/config/

- `AppConfig.java` — Shared RestTemplate used by outbound HTTP clients (EntityExtractor, (~296 tok)
- `SecurityConfig.java` — Spring Security resource server + role-based URL authorization. (~3489 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/auth/

- `AgentAuthorization.java` — Fine-grained (resource-level) authorization check for agent registration. (~1058 tok)
- `CerbosEntitlementAdapter.java` — Batch-calls the Cerbos PDP for relationship entitlement checks. (~3113 tok)
- `EntitlementService.java` — Enforces relationship-level entitlements by delegating to the Cerbos PDP. (~1801 tok)
- `JwksClient.java` — Fetches and caches the JWKS (public key set) from the user-mgmt service. (~879 tok)
- `Principal.java` — A caller's verified identity + structural attributes used for authorization checks. (~1053 tok)
- `PrincipalStore.java` — Loads principal attributes from Redis. (~788 tok)
- `RequestContext.java` — Per-request context stored in a ThreadLocal. (~279 tok)
- `RevocationChecker.java` — Returns true if the authorization for this principal+relationship has been revoked. (~520 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/chat/

- `ChatService.java` — Entry point from the controller — called on a virtual thread after the async boundary. (~14773 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/coverage/

- `CoverageCheckResult.java` — Factory: create a denied result with a machine-readable reason code. (~159 tok)
- `CoverageClient.java` — HTTP client for the DISCOVER / CHECK / RESOLVE coverage pipeline. (~1847 tok)
- `CoverageResolveResult.java` — True if multiple candidates matched and disambiguation is needed. (~240 tok)
- `CoverageResource.java` — Class: CoverageResource (~87 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/intent/

- `Intent.java` — Routing label produced by {@link IntentClassifier}. (~186 tok)
- `IntentClassifier.java` — Stage A of the request pipeline: classifies the user's intent before routing. (~4925 tok)
- `IntentResult.java` — Output of {@link IntentClassifier}. (~278 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/manifest/

- `ClarificationSchema.java` — Class: ClarificationSchema (~112 tok)
- `DomainManifest.java` — DomainManifest: Coverage, MemoryCompaction + clarify_style/clarify_tone (clarification WORDING policy; clarifyStyleOrDefault()→"template"). (~330 tok)
- `DomainManifestStore.java` — Resolves ${VAR_NAME} placeholders in all Coverage URL fields using Spring Environment. identifyByIdPattern(text)→IdentifiedReference: maps a typed id to its owning resource-scoped sub-domain + coverage via manifest id_pattern (bug-236, deterministic domain for a bare id). (~3700 tok)
- `DomainPrerequisiteValidator.java` — Service: DomainPrerequisiteValidator (~439 tok)
- `EffectiveManifest.java` — Merged domain+sub-domain view. requiresContext()/primaryRequiredKey()/clarificationFor(); carries clarifyStyle/clarifyTone from the domain manifest with clarifyComposed() helper (drives ChatService.buildClarificationQuestion's template|composed switch). (~760 tok)
- `EntityType.java` — A manifest-declared entity type. This is the load-bearing declaration that makes the (~528 tok)
- `SubDomainManifest.java` — Normalise missing optional collections so callers never NPE. (~650 tok)

## gateway/src/main/java/ai/meridian/gateway/domain/session/

- `ConversationSession.java` — Snapshot of conversation state persisted per {@code conversation_id} in Redis. (~1909 tok)
- `ConversationSessionStore.java` — Persists {@link ConversationSession} state in Redis. (~2863 tok)

## gateway/src/main/java/ai/meridian/gateway/infrastructure/identity/

- `IdentityExtractor.java` — Extracts the caller's user ID from the inbound HTTP request. (~477 tok)

## gateway/src/main/java/ai/meridian/gateway/infrastructure/redis/

- `RedisConfig.java` — Configuration: RedisConfig (~179 tok)

## gateway/src/main/java/ai/meridian/gateway/infrastructure/telemetry/

- `RedisTraceStorageAdapter.java` — Redis-backed trace storage. (~1073 tok)
- `RequestCorrelationFilter.java` — Sets MDC context keys on every inbound request so all log lines carry: (~1093 tok)
- `TraceEvent.java` — A single structured event emitted by the request pipeline to the glass-box panel. (~642 tok)
- `TraceEventPublisher.java` — In-memory pub/sub bus for glass-box trace events. (~1420 tok)
- `TraceStorageAdapter.java` — Storage contract for trace events — allows the glass-box panel to replay past requests. (~306 tok)

## gateway/src/main/java/ai/meridian/gateway/infrastructure/telemetry/event/

- `AgentCompleteData.java` — Class: AgentCompleteData (~47 tok)
- `AgentsResolvedData.java` — AgentsResolvedData: AgentRef, FilteredRef (~91 tok)
- `AgentStartData.java` — Class: AgentStartData (~36 tok)
- `EntitlementCheckData.java` — Class: EntitlementCheckData (~76 tok)
- `IntentClassifiedData.java` — Class: IntentClassifiedData (~44 tok)
- `RequestCompleteData.java` — Class: RequestCompleteData (~42 tok)
- `RequestStartData.java` — Class: RequestStartData (~36 tok)
- `SynthesisStartData.java` — Class: SynthesisStartData (~38 tok)

## gateway/src/main/java/ai/meridian/gateway/infrastructure/web/

- `BaggagePropagationFilter.java` — OncePerRequestFilter @Order(2). Extracts convId (X-Conversation-Id header or MDC fallback) and userId (JWT SecurityContext or MDC fallback), attaches both to OTel Baggage so the SDK injects W3C baggage header on outbound agent calls, and bridges to MDC keys convId/userId for local log enrichment. (~220 tok)
- `BaggagePropagationFilter.java` — Propagates convId and userId as W3C Baggage on every inbound request. (~1454 tok)

## gateway/src/main/java/ai/meridian/gateway/orchestration/executor/

- `FlatPlanExecutor.java` — Flat plan executor: fans out all {@link PlanNode}s in parallel on virtual threads (~1741 tok)

## gateway/src/main/java/ai/meridian/gateway/orchestration/harness/

- `AgentHarness.java` — Wraps each agent adapter call in a resilience harness. Now accepts MeterRegistry (3rd constructor param). Emits meridian.agent.calls counter and meridian.agent.latency timer at every exit path; registers meridian.circuit.breaker.state / meridian.bulkhead.executing / meridian.bulkhead.queued gauges once per agentId. QUEUE_FULL emits counter with status="QUEUE_FULL". (~5300 tok)

## gateway/src/main/java/ai/meridian/gateway/orchestration/model/

- `NodeResult.java` — The outcome of executing a single {@link PlanNode} through the agent harness. (~399 tok)
- `Plan.java` — An execution plan: an ordered (but for Phase 4, independent) list of {@link PlanNode}s (~80 tok)
- `PlanNode.java` — A single node in an execution {@link Plan}. (~190 tok)

## gateway/src/main/java/ai/meridian/gateway/registry/index/

- `EmbeddingClient.java` — Seam for the embedding model. (~168 tok)
- `HashEmbeddingClient.java` — Deterministic 384-dim embedding via SHA-256 hashing of token n-grams. (~1068 tok)
- `RemoteEmbeddingClient.java` — EmbeddingClient that delegates to an OpenAI-compatible /v1/embeddings endpoint. (~965 tok)
- `VectorIndex.java` — HNSW vector index over example-prompt embeddings. (~2390 tok)

## gateway/src/main/java/ai/meridian/gateway/registry/introspection/

- `AgentIntrospector.java` — Derives input/output schemas and resolved connection from agent specs. (~2007 tok)
- `McpToolIntrospector.java` — Fetches tool input schema from an MCP server via SSE + JSON-RPC. (~2675 tok)

## gateway/src/main/java/ai/meridian/gateway/registry/loader/

- `RegistryBootstrapLoader.java` — Loads agent manifests from the external registry location at startup. (~1210 tok)

## gateway/src/main/java/ai/meridian/gateway/registry/model/

- `AgentManifest.java` — Full stored manifest = what the domain team submitted + what the gateway derived. (~817 tok)
- `RoutingCandidate.java` — Result of a vector search hit — one candidate agent with its similarity score. (~65 tok)

## gateway/src/main/java/ai/meridian/gateway/registry/service/

- `AgentRegistry.java` — Core registry service. (~1584 tok)
- `ManifestValidator.java` — Validates a raw manifest JsonNode against the pinned agent-manifest.schema.json. (~552 tok)

## gateway/src/main/java/ai/meridian/gateway/resolver/model/

- `ResolverResult.java` — Output of the resolver — the full routing decision for one user prompt. (~233 tok)

## gateway/src/main/java/ai/meridian/gateway/resolver/service/

- `AgentResolver.java` — Resolver — Stage A+B. resolve(prompt,domain) for /debug; resolveContextual(routingText[,entityKnown]) for chat: conversation-enriched embedding + confidence/margin abstain (decisive-score OR domain-margin); entityKnown=true (turn carries an explicit grounded resolvable ref) relaxes the abstain gate so a terse id/name follow-up inherits the conversation facet and routes (bias-to-fetch, bug-233); no rigid single-domain scope so cross-domain fan-out is preserved. (~1500 tok)

## gateway/src/main/java/ai/meridian/gateway/synthesis/answer/

- `AnswerSynthesizer.java` — Synthesizes a grounded, streamed answer from agent outputs using Z.AI GLM. (~7346 tok)

## gateway/src/main/java/ai/meridian/gateway/synthesis/input/

- `EntityBag.java` — Generic, manifest-driven carrier for extracted and resolved entities. (~853 tok)
- `EntityExtractor.java` — Stage 1 — Extract. (~3484 tok)
- `EntityResolver.java` — Stage 2 — Resolve. (~1478 tok)
- `InputSynthesizer.java` — Input synthesis pipeline: Extract → Resolve → Bind. (~496 tok)
- `InputSynthesizerImpl.java` — Stage 3 — Bind, and orchestrates the full Extract → Resolve → Bind pipeline. (~2255 tok)

## gateway/src/main/resources/

- `agent-manifest.schema.json` (~1714 tok)
- `application.yml` (~1871 tok)

## gateway/src/main/resources/domains/

- `asset-servicing.json` (~56 tok)
- `wealth-management.json` (~147 tok)

## gateway/src/main/resources/domains/asset-servicing/

- `cash-management.json` (~65 tok)
- `corporate-actions.json` (~74 tok)
- `custody-operations.json` (~79 tok)

## gateway/src/main/resources/domains/wealth-management/

- `private-banking.json` (~203 tok)

## gateway/src/main/resources/manifests/

- `acme.servicing.cash_management.json` (~414 tok)
- `acme.servicing.corporate_actions.json` (~451 tok)
- `acme.servicing.custody_positions.json` (~427 tok)
- `acme.servicing.nav.json` (~438 tok)
- `acme.servicing.settlement_status.json` (~512 tok)
- `acme.wealth.goal_planning.json` (~434 tok)
- `acme.wealth.holdings.json` — Declares allocation (~422 tok)
- `acme.wealth.performance.json` (~428 tok)
- `acme.wealth.risk_profile.json` (~428 tok)

## gateway/src/test/java/ai/meridian/gateway/

- `GatewayApplicationTests.java` — Class: GatewayApplicationTests (~549 tok)

## gateway/src/test/java/ai/meridian/gateway/domain/auth/

- `AuthzFromMembershipTest.java` — Verifies JWT → Principal mapping and Cerbos-backed entitlement decisions. (~3778 tok)
- `RevocationCheckerTest.java` — Class: RevocationCheckerTest (~486 tok)
- `RoleAuthorizationTest.java` — Phase 10 — Role-based endpoint authorization. (~3391 tok)
- `SecurityRejectionIT.java` — M15 security requirement: every bad token must be rejected. (~3592 tok)

## gateway/src/test/java/ai/meridian/gateway/domain/manifest/

- `EffectiveManifestMergeTest.java` — Class: EffectiveManifestMergeTest (~1253 tok)
- `EffectiveManifestTest.java` — Targeted unit tests for {@link EffectiveManifest#merge} covering the three (~900 tok)

## gateway/src/test/java/ai/meridian/gateway/domain/session/

- `ConversationSessionTest.java` — Class: ConversationSessionTest (~766 tok)

## gateway/src/test/java/ai/meridian/gateway/orchestration/harness/

- `AgentHarnessResilienceIT.java` — Resilience integration test — verifies the harness's core invariant: (~2919 tok)

## gateway/src/test/java/ai/meridian/gateway/synthesis/input/

- `EntityBagTest.java` — Locks in the generic, manifest-keyed EntityBag contract that replaced the wealth-shaped record. (~838 tok)

## gateway/src/test/resources/domains/wealth-management/

- `private-banking.json` (~769 tok)

## htmls/

- `CONDUIT-LIVE-EXAMPLES.html` — Conduit — Live Answer Gallery (~4191 tok)
- `CONDUIT-ORCHESTRATION.html` — Conduit — Accountable Orchestration Across Governed Domains (~11331 tok)
- `CONDUIT-STORY.html` — Conduit — An Enterprise AI Gateway (~5398 tok)
- `THE-EMPTY-ROOM.html` — The Empty Room — How to Build a Machine an Enterprise Can Trust (~138834 tok)

## iam-service/

- `Dockerfile` — Docker container definition (~216 tok)
- `Dockerfile` — eclipse-temurin:21-jre-alpine; ZGC+ZGenerational; port 8084 (~40 tok)
- `pom.xml` (~1326 tok)
- `pom.xml` — Spring Boot 3.5.3 parent POM; Java 21; deps: web, data-jpa, security, oauth2-authorization-server, actuator, validation, postgresql, flyway, jackson-jsr310, cerbos-sdk-java:0.12.0, h2(test) (~220 tok)
- `README.md` — Project documentation (~2879 tok)
- `src/main/java/com/openwolf/iam/auth/` — CustomUserDetailsService (UserDetailsService using principal.id as sub), JwtClaimsCustomizer (OAuth2TokenCustomizer enriches ACCESS_TOKEN with roles/segments/classification/tenant_id/permissions) (~370 tok)
- `src/main/java/com/openwolf/iam/config/` — 5 configs: VirtualThreadConfig (Tomcat VT executor), SecurityConfig (Order-1 AS + Order-2 API, RSA-2048 JWK, 3 OAuth2 clients, BCrypt-12, CORS), JpaConfig (@EnableJpaAuditing), CerbosConfig (@ConditionalOnProperty), JacksonConfig (~650 tok)
- `src/main/java/com/openwolf/iam/controller/` — 10 controllers covering all 50+ endpoints: UserController, RoleController, TeamController, DomainController, PolicyController, AuditController, StatsController, AuthController (/auth/login + /auth/token), TenantController, HealthController (~1800 tok)
- `src/main/java/com/openwolf/iam/dto/` — 19 Java records: ErrorResponse (of() factory), PageResponse<T>, UserResponse, LoginResponse, TokenRequest + 14 more (~600 tok)
- `src/main/java/com/openwolf/iam/entity/` — 7 JPA entities: Tenant, Principal (@ManyToMany→roles), Role, Group (@ManyToMany→members), PersonalResource, Policy (@LastModifiedDate), AuditLog (occurredAt set explicitly) (~700 tok)
- `src/main/java/com/openwolf/iam/exception/` — 3 exceptions + GlobalExceptionHandler (all types → ErrorResponse, no stack traces, 4xx WARN, 5xx ERROR) (~450 tok)
- `src/main/java/com/openwolf/iam/IamApplication.java` — @SpringBootApplication + main (~30 tok)
- `src/main/java/com/openwolf/iam/repository/` — 7 JpaRepository interfaces; native JSONB query for admin_domains; JPQL JOIN for group members; paginated audit log (~350 tok)
- `src/main/java/com/openwolf/iam/service/` — 7 services: AuditService (fail-safe), CerbosAuthzService (fail-open on gRPC error), UserService (CRUD+book+roles+teams+domains), GroupService (CRUD+members+domain admins), RoleService, PolicyService (generate stub/validate/apply), DataSeeder (ApplicationRunner replaces SEED_REPLACE_ME) (~2200 tok)
- `src/main/resources/application.yml` — port 8084, virtual threads, datasource, JPA, Flyway, IAM + Cerbos props (~80 tok)
- `src/main/resources/db/migration/V1__init.sql` — Schema: tenants/principals/roles/principal_roles/groups/group_members/personal_resources/policies/audit_log; seeds 3 principals, 8 roles, 2 groups, rm_jane book (~350 tok)

## iam-service/src/main/java/com/openwolf/iam/

- `IamApplication.java` — OpenWolf IAM Service — OIDC provider, user management, entitlements. (~161 tok)

## iam-service/src/main/java/com/openwolf/iam/auth/

- `CustomUserDetailsService.java` — Loads {@link UserDetails} for Spring Security's {@link org.springframework.security.authentication.A (~674 tok)
- `JwtClaimsCustomizer.java` — Enriches OIDC access tokens with mandated claims ({@code tenant_id}, {@code roles}, (~681 tok)
- `OidcClaimEnricher.java` — Builds the enrichment claims for an access token <b>inside a read-only transaction</b>. (~1708 tok)

## iam-service/src/main/java/com/openwolf/iam/config/

- `CerbosConfig.java` — Creates a {@link CerbosBlockingClient} when {@code iam.cerbos.authz-enabled=true}. (~450 tok)
- `JacksonConfig.java` — Configures the primary {@link ObjectMapper}: (~297 tok)
- `JpaConfig.java` — Enables JPA auditing so that {@code @CreatedDate} and {@code @LastModifiedDate} (~143 tok)
- `SecurityConfig.java` — Security configuration for the IAM service. (~4558 tok)
- `VirtualThreadConfig.java` — Configures Tomcat to dispatch every request on a virtual thread. (~412 tok)

## iam-service/src/main/java/com/openwolf/iam/controller/

- `AuditController.java` — Paginated access to the audit log. All write operations in the IAM service (~705 tok)
- `AuthController.java` — Custom authentication endpoints — issues RS256 JWTs directly. (~2687 tok)
- `DomainController.java` — Manages domains — business capability areas that group principals and resources. (~1559 tok)
- `HealthController.java` — Simple health endpoint — permits all (no auth required). (~220 tok)
- `LoginController.java` — Serves the Axiom-branded OIDC login page. (~144 tok)
- `PolicyController.java` — Manages Cerbos YAML policies: CRUD + generate/validate/apply lifecycle. (~1362 tok)
- `RoleController.java` — RestController: RoleController (6 endpoints) (~669 tok)
- `StatsController.java` — Quick aggregate statistics — used by the admin-ui dashboard. (~467 tok)
- `TeamController.java` — Manages teams (groups in the /teams namespace). (~982 tok)
- `TenantController.java` — Tenant-scoped configuration endpoints. (~692 tok)
- `UserController.java` — RestController: UserController (15 endpoints) (~1653 tok)

## iam-service/src/main/java/com/openwolf/iam/dto/

- `AddMemberRequest.java` — Class: AddMemberRequest (~49 tok)
- `AssignRoleRequest.java` — Class: AssignRoleRequest (~49 tok)
- `AuditLogResponse.java` — Class: AuditLogResponse (~99 tok)
- `CreateGroupRequest.java` — Class: CreateGroupRequest (~96 tok)
- `CreatePolicyRequest.java` — Class: CreatePolicyRequest (~74 tok)
- `CreateRoleRequest.java` — Class: CreateRoleRequest (~71 tok)
- `CreateUserRequest.java` — Class: CreateUserRequest (~129 tok)
- `ErrorResponse.java` — Standard error response body. Every exception handler returns this shape. (~156 tok)
- `GroupResponse.java` — Class: GroupResponse (~95 tok)
- `ImpersonateRequest.java` — Admin-only impersonation token request DTO accepting userId/user_id. (~55 tok)
- `LoginResponse.java` — Class: LoginResponse (~46 tok)
- `PageResponse.java` — Generic paginated response wrapper. (~153 tok)
- `PatchBookRequest.java` — Adds and/or removes relationship IDs from a RM's book. (~75 tok)
- `PersonalResourceRequest.java` — Class: PersonalResourceRequest (~90 tok)
- `PolicyResponse.java` — Class: PolicyResponse (~76 tok)
- `RoleResponse.java` — Class: RoleResponse (~63 tok)
- `StatsResponse.java` — Class: StatsResponse (~45 tok)
- `TokenRequest.java` — Class: TokenRequest (~67 tok)
- `UpdateUserRequest.java` — All fields are nullable — only provided fields are updated (PATCH semantics via PUT). (~74 tok)
- `UserResponse.java` — Public user representation — password_hash is NEVER included. (~122 tok)

## iam-service/src/main/java/com/openwolf/iam/entity/

- `AuditLog.java` — Immutable audit record — every write operation in the IAM service emits one. (~952 tok)
- `Group.java` — A group represents either a team (organisational unit) or a domain (business capability area). (~1001 tok)
- `PersonalResource.java` — A resource personally assigned to a principal — e.g. a relationship in a RM's book. (~846 tok)
- `Policy.java` — A Cerbos authorization policy managed by the IAM service. (~794 tok)
- `Principal.java` — A principal is any authenticated entity in the system (user, service account). (~1207 tok)
- `Role.java` — JSONB array of permission strings, e.g. {@code ["users:read","relationships:read"]}. (~682 tok)
- `Tenant.java` — Entity: Tenant (~556 tok)

## iam-service/src/main/java/com/openwolf/iam/exception/

- `AuthzDeniedException.java` — Thrown by {@link com.openwolf.iam.service.CerbosAuthzService} when the Cerbos PDP (~125 tok)
- `EntityNotFoundException.java` — Thrown when a requested entity does not exist in the database. (~158 tok)
- `GlobalExceptionHandler.java` — Centralized exception handler — all exceptions map to {@link ErrorResponse}. (~1966 tok)
- `ResourceConflictException.java` — Thrown when a create/update operation would violate a uniqueness constraint. (~119 tok)

## iam-service/src/main/java/com/openwolf/iam/repository/

- `AuditLogRepository.java` — Repository: AuditLogRepository (~177 tok)
- `GroupRepository.java` — Finds all groups that contain the specified principal as a member. (~339 tok)
- `PersonalResourceRepository.java` — Repository: PersonalResourceRepository (~223 tok)
- `PolicyRepository.java` — Repository: PolicyRepository (~147 tok)
- `PrincipalRepository.java` — Finds principals where the JSONB attributes contain the given domain in admin_domains array. (~378 tok)
- `RoleRepository.java` — Repository: RoleRepository (~125 tok)
- `TenantRepository.java` — Repository: TenantRepository (~95 tok)

## iam-service/src/main/java/com/openwolf/iam/service/

- `AuditService.java` — Records immutable audit entries for every write operation in the IAM service. (~1268 tok)
- `CerbosAuthzService.java` — Wraps the Cerbos PDP for IAM-internal authorization checks. (~1368 tok)
- `DataSeeder.java` — Runs at startup to replace the {@code SEED_REPLACE_ME} placeholder passwords (~671 tok)
- `GroupService.java` — Updates the domain's relationship list — stored in the group's metadata JSON. (~3743 tok)
- `LlmPolicyGenerationService.java` — Generates Cerbos YAML policies from natural-language intent using Z.AI GLM. (~5559 tok)
- `PolicyService.java` — Transitions a policy status: draft → approved → deployed. (~3108 tok)
- `RoleService.java` — Service: RoleService (~1311 tok)
- `UserService.java` — Service: UserService (~4140 tok)

## iam-service/src/main/resources/

- `application.yml` (~706 tok)

## iam-service/src/main/resources/db/migration/

- `V1__init.sql` — ============================================================ (~2518 tok)
- `V2__seed_demo_data.sql` — ============================================================================ (~4218 tok)
- `V3__seed_e2e_users.sql` — ============================================================================ (~458 tok)
- `V4__reconcile_personas.sql` — reconciles personas (rm_guest, uw_sam insurance) (~500 tok)
- `V5__abac_segments_map_and_chat_user.sql` — flat segments array -> per-segment MAP; drop clearance; chat_user role; analyst_amy (~900 tok)
- `V6__abac_classification_ladder.sql` — tenant classification_schema -> ABAC ladder internal<confidential<confidential-pii (drops phantom restricted/public); live source for Users tier dropdown (~180 tok)

## iam-service/src/main/resources/static/css/

- `axiom-login.css` — Styles: 35 rules, 11 vars (~1691 tok)

## iam-service/src/main/resources/templates/

- `login.html` — Axiom — Meridian Identity Platform (~754 tok)

## iam-service/src/test/java/com/openwolf/iam/

- `IamApplicationTests.java` — Placeholder test class. (~197 tok)

## iam-service/src/test/java/com/openwolf/iam/auth/

- `JwtClaimsCustomizerTest.java` — Tests Axiom OIDC access-token gateway audience customization and ID-token identity-only behavior. (~779 tok)

## iam-service/src/test/resources/

- `application-test.yml` (~181 tok)

## infra/

- `otel-collector.yaml` (~869 tok)
- `prometheus.yml` (~129 tok)
- `tempo.yaml` (~97 tok)

## infra/cerbos/

- `config.yaml` (~94 tok)

## infra/cerbos/policies/

- `agent_resource.yaml` (~1189 tok)
- `domain_resource.yaml` (~358 tok)
- `iam_derived_roles.yaml` (~542 tok)
- `iam_resource.yaml` — Declares in (~1247 tok)
- `relationship_resource.yaml` (~193 tok)

## infra/clickhouse/

- `cluster.xml` (~440 tok)

## infra/grafana/provisioning/dashboards/

- `agent-health.json` — "Conduit — Agent Health" — SRE per-agent detail board (fleet stats, per-agent rows, CB history, bulkhead pressure). KEPT. (~6972 tok)
- `business-overview.json` — "Conduit — Business Overview". KEPT. (~8926 tok)
- `conversation-trace.json` — "Conduit — Conversation Trace Explorer". FIXED: entity_id var removed; Tempo panel re-keyed to `{ span.conversation.id = "$convId" }` (new gateway span attribute); Loki `|= "$convId"` panels kept. (~2953 tok)
- `dashboards.yaml` (~55 tok)

## infra/grafana/provisioning/datasources/

- `datasources.yaml` (~356 tok)

## infra/loki/

- `config.yaml` (~196 tok)
- `config.yaml` (~162 tok)

## infra/promtail/

- `promtail.yaml` (~419 tok)
- `promtail.yaml` (~218 tok)

## mock-agents/

- `Dockerfile` — Docker container definition (~55 tok)
- `placeholder.py` — API: GET (2 endpoints) (~154 tok)

## mock-agents/crm/

- `__init__.py` (~0 tok)
- `data.py` — resolve_entity, check_access (~674 tok)
- `Dockerfile` — Docker container definition (~53 tok)
- `main.py` — API: 3 endpoints (~272 tok)
- `requirements.txt` — Python dependencies (~13 tok)

## mock-agents/embeddings/

- `Dockerfile` — Docker container definition (~111 tok)
- `main.py` — API: GET, POST (2 endpoints) (~601 tok)
- `requirements.txt` — Python dependencies (~18 tok)

## mock-agents/insurance-coverage/

- `data.py` — discover, check, resolve (~1326 tok)
- `Dockerfile` — Docker container definition (~59 tok)
- `main.py` — API: 4 endpoints (~1078 tok)
- `requirements.txt` — Python dependencies (~28 tok)

## mock-agents/insurance-coverage/ (World B — DISCOVER/CHECK/RESOLVE, port 8088)


## mock-agents/insurance-coverage/tests/

- `test_coverage.py` — TestDiscover: test_uw_sam_sees_his_two_policies, test_uw_dana_sees_zenith, test_unknown_principal_ge (~1562 tok)

## mock-agents/insurance/

- `__init__.py` (~0 tok)
- `Dockerfile` — Docker container definition (~102 tok)
- `main.py` — API: 1 endpoints (~846 tok)
- `requirements.txt` — Python dependencies (~81 tok)

## mock-agents/insurance/ (World B — second domain, FastAPI, port 8087)


## mock-agents/insurance/claim_status/

- `__init__.py` (~0 tok)
- `handler.py` — Claim Status agent — GET /claim-status (~733 tok)

## mock-agents/insurance/policy_details/

- `__init__.py` (~0 tok)
- `handler.py` — Policy Details agent — GET /policy-details (~496 tok)

## mock-agents/insurance/shared/

- `__init__.py` (~0 tok)
- `canned_data.py` — claims_for_policy (~995 tok)
- `error_schema.py` — ErrorResponse: error_response (~294 tok)
- `fault_knobs.py` — fault_knob_middleware (~270 tok)
- `jwt_verify.py` — verify_bearer_token (~1103 tok)
- `telemetry.py` — setup_telemetry, agent_span (~1284 tok)

## mock-agents/servicing/

- `__init__.py` (~0 tok)
- `Dockerfile` — Docker container definition (~63 tok)
- `README.md` — Project documentation (~293 tok)
- `requirements.txt` — Python dependencies (~99 tok)
- `server.py` — JwtAuthMiddleware: get_custody_positions, get_settlements, get_corporate_actions, get_nav + 3 more (~2036 tok)

## mock-agents/servicing/cash/

- `__init__.py` (~1 tok)
- `README.md` — Project documentation (~157 tok)
- `tool.py` — Cash Management MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails. (~1062 tok)

## mock-agents/servicing/corporate_actions/

- `__init__.py` (~1 tok)
- `README.md` — Project documentation (~248 tok)
- `tool.py` — Corporate Actions MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails. (~1138 tok)

## mock-agents/servicing/corporate_actions/knowledge_base/

- `__init__.py` (~1 tok)
- `docs.py` — ISDA / Meridian Corporate Action Processing Rules — KB chunks. (~658 tok)
- `retriever.py` — Keyword retriever for Corporate Action Rules KB. (~226 tok)

## mock-agents/servicing/custody/

- `__init__.py` (~1 tok)
- `README.md` — Project documentation (~166 tok)
- `tool.py` — Custody Positions MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails. (~1124 tok)

## mock-agents/servicing/nav/

- `__init__.py` (~1 tok)
- `README.md` — Project documentation (~191 tok)
- `tool.py` — NAV MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails. (~1012 tok)

## mock-agents/servicing/settlements/

- `__init__.py` (~1 tok)
- `README.md` — Project documentation (~178 tok)
- `tool.py` — Settlements MCP tool — powered by OpenAI Agents SDK + Z.AI with guardrails. (~1121 tok)

## mock-agents/servicing/shared/

- `__init__.py` (~0 tok)
- `agent_client.py` — Z.AI client setup for servicing agents. (~632 tok)
- `canned_data.py` (~1799 tok)
- `error_schema.py` — mcp_error_dict, mcp_error_json (~521 tok)
- `fault_knobs.py` — maybe_fault (~246 tok)
- `guardrails.py` — injection_guardrail, relationship_id_guardrail, fund_id_guardrail, length_guardrail + 1 more (~1417 tok)
- `jwt_verify.py` — verify_bearer_token (~1103 tok)
- `telemetry.py` — agent_span (~591 tok)

## mock-agents/tests/

- `__init__.py` (~0 tok)
- `conftest.py` — whose: servicing_imports, mock_runner, suppress_otel_noise (~1027 tok)
- `test_agent_integration.py` — Tests: no_auth_header_is_allowed, bearer_unused_is_allowed, empty_bearer_is_allowed, malformed_token_too_many_dots_is_rejected + 16 more (~4637 tok)
- `test_concurrent_multiturn.py` — Test file (~3525 tok)
- `test_live.py` — TestLiveWealthHttp: test_health_endpoint, test_openapi_served, test_holdings_live, test_performance_ (~13357 tok)
- `test_servicing.py` — Tests: custody_positions_schema, settlements_schema, corporate_actions_schema, nav_schema + 6 more (~1385 tok)
- `test_wealth.py` — Tests: health_ok, openapi_json_served, openapi_has_required_params, known_relationship + 11 more (~1200 tok)

## mock-agents/wealth-coverage/

- `data.py` — discover, check, resolve (~1091 tok)
- `Dockerfile` — Docker container definition (~59 tok)
- `main.py` — API: 4 endpoints (~1047 tok)
- `requirements.txt` — Python dependencies (~28 tok)

## mock-agents/wealth-coverage/tests/

- `test_coverage.py` — TestDiscover: test_rm_jane_sees_her_two_relationships, test_rm_ken_sees_okafor, test_unknown_princip (~1514 tok)

## mock-agents/wealth/

- `__init__.py` (~0 tok)
- `Dockerfile` — Docker container definition (~94 tok)
- `main.py` — API: 1 endpoints (~913 tok)
- `README.md` — Project documentation (~372 tok)
- `requirements.txt` — Python dependencies (~108 tok)

## mock-agents/wealth/goal_planning/

- `__init__.py` (~1 tok)
- `handler.py` — Goal Planning agent — GET /goal-planning (~1049 tok)
- `README.md` — Project documentation (~168 tok)

## mock-agents/wealth/holdings/

- `__init__.py` (~1 tok)
- `handler.py` — Holdings agent — GET /holdings (~986 tok)
- `README.md` — Project documentation (~201 tok)

## mock-agents/wealth/performance/

- `__init__.py` (~1 tok)
- `handler.py` — Performance agent — GET /performance (~1040 tok)
- `README.md` — Project documentation (~157 tok)

## mock-agents/wealth/risk_profile/

- `__init__.py` (~1 tok)
- `handler.py` — API: 1 endpoints (~1271 tok)
- `README.md` — Project documentation (~351 tok)

## mock-agents/wealth/risk_profile/knowledge_base/

- `__init__.py` (~1 tok)
- `docs.py` (~866 tok)
- `retriever.py` — retrieve (~322 tok)

## mock-agents/wealth/shared/

- `__init__.py` (~0 tok)
- `agent_client.py` — Z.AI client setup for wealth agents. (~652 tok)
- `canned_data.py` (~1856 tok)
- `error_schema.py` — ErrorResponse: error_response (~293 tok)
- `fault_knobs.py` — fault_knob_middleware (~268 tok)
- `guardrails.py` — injection_guardrail, relationship_id_guardrail, length_guardrail, make_grounding_guardrail (~1598 tok)
- `jwt_verify.py` — verify_bearer_token (~1103 tok)
- `telemetry.py` — setup_telemetry, agent_span (~1312 tok)

## packages/ (monorepo shared libs — additive split)


## phases/

- `PHASE-1.md` — Phase 1 — Skeleton & First Streamed Reply (~466 tok)
- `PHASE-10.md` — Phase 10 — Role-Based Authorization (Spring Security) + Declarative Org Seed (~2005 tok)
- `PHASE-11.md` — Phase 11 — Verify, Enforce, Authorize (intent + proof, not mechanism) (~2183 tok)
- `PHASE-2.md` — Phase 2 — The Nine Agents Respond (~497 tok)
- `PHASE-3.md` — Phase 3 — Registration & Routing Decisions (~595 tok)
- `PHASE-4.md` — Phase 4 — The Real End-to-End Answer  ★ core demo (~690 tok)
- `PHASE-5.md` — Phase 5 — Governance & the Glass-Box (~599 tok)
- `PHASE-6.md` — Phase 6 — Demo Polish: Clarify, Resilience, Rebrand (~486 tok)
- `PHASE-7.md` — Phase 7 — Proof: Accuracy & Scale (~463 tok)
- `PHASE-8.md` — Phase 8 — Identity, Domains & End-to-End Authorization (~1741 tok)
- `PHASE-9.md` — Next Test Run — Verify Identity, Authorization & Correlation Are *Real* (~2254 tok)

## registry — insurance manifests (World B onboarding artifacts)


## registry/

- `agent-manifest.schema.json` (~1714 tok)
- `context-envelope.schema.json` — Context envelope returned by the external memory service (~1900 tok)
- `domain-manifest.schema.json` — Domain manifest contract with governed memory policy (~1200 tok)
- `memory-ledger-event.schema.json` — Append-only gateway/memory event shell schema (~750 tok)
- `README.md` — Registry onboarding and governed memory contract guide (~1160 tok)
- `sub-domain-manifest.schema.json` — Sub-domain manifest contract for entity context and agents (~1050 tok)

## registry/domains/

- `asset-servicing.json` — Asset-servicing domain with memory-service summary policy (~160 tok)
- `insurance.json` — Insurance domain with coverage URLs and memory-service summary policy (~210 tok)
- `wealth-management.json` — Wealth domain with coverage URLs and memory-service summary policy (~210 tok)

## registry/domains/asset-servicing/

- `cash-management.json` (~243 tok)
- `corporate-actions.json` (~281 tok)
- `custody-operations.json` (~274 tok)

## registry/domains/insurance/

- `claims-servicing.json` (~664 tok)

## registry/domains/wealth-management/

- `private-banking.json` (~769 tok)

## registry/manifests/

- `acme.insurance.claim_status.json` (~461 tok)
- `acme.insurance.policy_details.json` (~470 tok)
- `acme.servicing.cash_management.json` (~414 tok)
- `acme.servicing.corporate_actions.json` (~451 tok)
- `acme.servicing.custody_positions.json` (~427 tok)
- `acme.servicing.nav.json` (~438 tok)
- `acme.servicing.settlement_status.json` (~512 tok)
- `acme.wealth.goal_planning.json` (~434 tok)
- `acme.wealth.holdings.json` — Declares allocation (~422 tok)
- `acme.wealth.performance.json` (~428 tok)
- `acme.wealth.risk_profile.json` (~428 tok)

## scripts/

- `eval_agents.py` — TestHoldingsAgent: call_wealth, build_metrics, test_holdings_faithfulness, test_performance_faithful (~5311 tok)
- `eval-gate.sh` — Eval release gate — seeds Langfuse datasets, then runs the DeepEval routing (~753 tok)
- `eval-routing.py` — mint_admin_token, load_prompts, resolve, f1 + 1 more (~1675 tok)
- `integration-test.sh` — Curl-based integration tests against the running gateway. (~1352 tok)
- `probe-recency-insurance.py` — Cross-domain anaphor-recency BFF driver (uw_sam via OIDC): 5-turn insurance conversation proving the bug-234 focal-recency fix is domain-generic; pairs with eval/multiturn-recency-insurance.json. (~350 tok)
- `requirements-eval.txt` — Meridian eval dependencies (not runtime — install on dev/CI machine) (~33 tok)
- `run-integration-tests.sh` (~354 tok)
- `scenario-perf.py` — class: check_summary, mint_token, chat, resolve + 4 more (~14188 tok)
- `seed-all.sh` — THE single consolidated demo-data seeder (runs in conduit-seeder, once, after the whole stack is healthy). Ordered idempotent steps: (a) health waits, (b) principals→Redis via seed-users.sh, (c) Langfuse prices via seed-langfuse-models.py BEFORE traffic, (d) BFF conversations via seed-conversations-via-bff.py, (e) datasets via /eval/langfuse_seed_datasets.py, (f) dashboard via seed-langfuse-dashboard.sh. Env-only, no docker exec, no host dep. Per-step + summary logging; exit≠0 if any step fails. Replaced seed-users/seed-datasets/seed-langfuse-dashboard/seeder compose services. (~1400 tok)
- `seed-demo.py` — mint_token, chat, chat_multi, wait_for_gateway + 1 more (~2771 tok)
- `seed-demo.sh` — Meridian Gateway — Phoenix/Tempo demo seed (~176 tok)
- `seed-langfuse-dashboard.sh` — Idempotently seeds the "Conduit — LLM Quality & Cost" Langfuse dashboard by applying seed-data/langfuse-dashboard.sql to Langfuse Postgres. Env-driven (LANGFUSE_DB_*); uses psql (now in the seeder image) else `docker exec`. Called as step (f) of seed-all.sh. (~500 tok)
- `seed-users.sh` — Idempotently seed demo principals into Redis. PRINCIPALS ONLY now (price/convo/dataset/dashboard tails moved to seed-all.sh). Runs as seed-all.sh step (b) and standalone on host. (~700 tok)
- `smoke-ui.sh` — Tier-1 fast gate (invoked first by smoke.sh): 10 health URLs 200, CORS preflights per browser origin (chat→BFF :8099, admin→iam :5182→:8084), 4 personas mint JWT. No LLM/sleeps; exit=#failures. (~550 tok)
- `smoke.sh` — smoke.sh — full API/CLI smoke for Conduit. Run against a live stack (docker compose up). (~1581 tok)
- `verify-telemetry-e2e.sh` — ───────────────────────────────────────────────────────────────────────────── (~1151 tok)
- `verify.sh` — Full verification script — runs after each phase to confirm acceptance criteria. (~713 tok)
- `wait-for-healthy.sh` — Wait until all core docker-compose services report healthy, then exit 0. (~323 tok)
- `world-b-check.sh` — ───────────────────────────────────────────────────────────────────────────── (~1272 tok)

## scripts/eval-worker/

- `Dockerfile` — Docker container definition (~41 tok)
- `golden_datasets.py` (~1812 tok)
- `requirements.txt` — Python dependencies (~16 tok)
- `worker.py` — ZAIJudge: get_bearer_token, get_model_name, load_model, generate + 7 more (~3311 tok)

## scripts/seed-data/

- `langfuse-dashboard.sql` — Idempotent SQL that seeds the "Conduit — LLM Quality & Cost" Langfuse dashboard: 6 dashboard_widgets rows (cost-by-model, eval-scores, trace-volume, latency p50/p95, token-usage, score-histogram) + 1 dashboards row with the 12-col grid `definition`. Resolves project_id/owner by sub-query; all widgets min_version=1 (v1 views — v2 hits the v4-only events_core table). (~900 tok)
- `principals.json` — 3 demo principals (rm_jane, rm_carlos, rm_guest) matching PrincipalStore hash schema: id, roles/book/segments/domains/adminDomains as JSON arrays, clearance as int string (~200 tok)
- `principals.json` (~266 tok)

## test-results/

- `.last-run.json` (~13 tok)

## tests/

- `__init__.py` (~0 tok)
- `README.md` — Project documentation (~1979 tok)
- `README.md` — Project documentation (~1980 tok)

## tests/e2e/

- `package-lock.json` — npm lock file (~846 tok)
- `package.json` — Node.js package manifest (~121 tok)
- `playwright.config.ts` (~314 tok)
- `tsconfig.json` — TypeScript configuration (~99 tok)

## tests/e2e/tests/

- `00-login.spec.ts` — Login / registration flow. (~1152 tok)
- `02-hero-prompt.spec.ts` — Phase 4 / M6-M7 — End-to-end hero prompt. (~769 tok)
- `03-jwt-identity.spec.ts` — Phase 8 / M15 — RS256/JWKS identity. (~1993 tok)
- `04-entitlements.spec.ts` — Phase 5 / M8 — Cerbos ABAC entitlements. (~1738 tok)
- `05-resilience.spec.ts` — Phase 6 / M11 — Resilience beat. (~927 tok)
- `07-multi-turn.spec.ts` — Multi-turn conversation tests. (~2397 tok)
- `08-domain-authz.spec.ts` — Phase 11 — Domain-scoped ABAC (segment × agent-domain). (~3237 tok)
- `09-cerbos-authz.spec.ts` — Cerbos Authorization Matrix — direct PDP API tests (~5810 tok)
- `10-coverage-flow.spec.ts` — Coverage-flow E2E tests (Phase 11+). (~2278 tok)
- `11-screenshots.spec.ts` — Capture utility (and a smoke test): logs in, screenshots the branded home, sends the (~371 tok)
- `admin-ui.spec.ts` — Declares BASE (~960 tok)
- `helpers.ts` — Obtain a real RS256 JWT from the iam-service (~1961 tok)

## tests/integration/

- `__init__.py` (~0 tok)
- `requirements.txt` — Python dependencies (~13 tok)
- `test_gateway_coverage.py` — get_jwt, collect_sse_text, chat, test_6_gateway_health_check (~2949 tok)

## tests/load/

- `load-test-light.js` — Meridian Gateway — Lightweight k6 Load Test (demo / CI) (~701 tok)
- `load-test.js` — Meridian Gateway — k6 Phased Load Test (~1686 tok)
- `load-test.js` — Meridian Gateway — k6 Phased Load Test (~1687 tok)
- `scenario-test.js` — Meridian Gateway — Scenario Performance Test (~3794 tok)
- `scenario-test.js` — Meridian Gateway — Scenario Performance Test (~3794 tok)
- `smoke-test.js` — Meridian Gateway — Smoke Test (fast CI check) (~649 tok)
- `smoke-test.js` — Meridian Gateway — Smoke Test (fast CI check) (~649 tok)

## tests/schema/

- `requirements.txt` — Python dependencies for registry schema checks (~8 tok)
- `test_registry_contracts.py` — Validates registry schemas, manifests, cross-references, context envelope and ledger event examples (~1100 tok)

## user-mgmt/

- `Dockerfile` — Docker container definition (~66 tok)
- `main.py` — get_redis, get_user_domains, get_user_admin_domains, get_book_from_domains (~16280 tok)
- `policy_agent.py` — generate_policy, validate_policy_yaml, apply_policy, list_policies (~3689 tok)
- `requirements.txt` — Python dependencies (~49 tok)

## user-mgmt/.pytest_cache/

- `.gitignore` — Git ignore rules (~10 tok)
- `CACHEDIR.TAG` (~51 tok)
- `README.md` — Project documentation (~76 tok)

## user-mgmt/.pytest_cache/v/cache/

- `lastfailed` (~1 tok)
- `nodeids` (~1002 tok)
- `stepwise` (~1 tok)

## user-mgmt/seed/

- `org.yaml` — Meridian Bank — Declarative Org Seed (~811 tok)

## user-mgmt/tests/

- `test_user_mgmt.py` — Tests: jwks_has_correct_structure, jwks_e_is_65537, jwks_n_length, issue_token_returns_rs256_jwt + 20 more (~7203 tok)
