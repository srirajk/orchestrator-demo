# Research — E2E tracing when the gateway invokes Microsoft Copilot agents over HTTP (2026-07)

**Bottom line:** For **Copilot Studio** agents, the distributed trace **stops at the HTTP boundary.** Plan
for a **CLIENT boundary span (L1) + correlation-ID stitching into App Insights (L2).** True span-level e2e
into our Tempo/Langfuse is **not** achievable for Copilot Studio — it's Application Insights-locked.

## Why (cited)
- **No inbound `traceparent` honoring:** Direct Line 3.0 documents only an `Authorization` header — no trace
  headers. Power Platform's own distributed-tracing guide only gets continuity via a Dataverse-plugin
  workaround (redundant if Copilot propagated context natively). Correlation model is
  `conversationId`/`activityId`, not W3C.
- **No OTLP export to your backend:** the only telemetry sink is an **App Insights connection string**
  (agent Settings → Advanced). No "bring your own OTLP endpoint." Copilot Studio *does* generate real OTel
  GenAI-semconv spans when "OTel tracing" is enabled — but they go to **App Insights**, rendered in Azure
  Monitor's new **Agents (Preview)** view. Ingestion-only; no re-export to self-hosted Tempo.

## The strategic insight — tracing depth is a function of the agent RUNTIME, not the protocol
| Agent runtime | OTLP export to our backend? | Depth |
|---|---|---|
| **Copilot Studio** | ❌ App Insights-locked | L1/L2 (boundary + correlation) |
| **Microsoft Agent Framework** (GA ~Apr 2026) | ✅ reads `OTEL_EXPORTER_OTLP_*`; **documented with Langfuse** | **L3 true e2e** |
| **Azure AI Foundry Agent Service** | ✅ OTLP via Microsoft OpenTelemetry Distro (Datadog/Grafana/any OTLP) | **L3 true e2e** |

→ If e2e tracing matters for a given agent, **build it on Agent Framework / Foundry, not Copilot Studio.**
Caveat: the `CopilotStudioAgent` wrapper in SK/Agent Framework is only a *client* that calls out to a
published Copilot Studio agent — Copilot doesn't run *inside* Agent Framework, so that OTel portability does
NOT flow into Copilot Studio.

## Invocation gotcha (server-to-server)
- **M365 Agents SDK (Copilot Studio Client)** = recommended path, BUT currently **delegated (user) auth
  only — no app-only / service-principal** (roadmap). Blocker for a pure server-to-server gateway.
- **Direct Line API** = NOT deprecated (what retired was the legacy Bot Framework SDK, Dec 2025). **Use
  Direct Line for server-to-server Copilot invocation today.**
- A2A (GA Apr 2026): Copilot Studio is the A2A *client*, not an inbound server — wrong direction. A2A only
  *recommends* (SHOULD) W3C trace-context propagation, not mandated.

## Recommended pattern for the gateway
1. **L1:** wrap the Copilot call as `SpanKind.CLIENT`, tag `gen_ai.system`, agent id, `conversationId`,
   latency/error → lands in Tempo + Langfuse. Ceiling on *our* backend.
2. **L2 (recommended):** put a **correlation id** (our traceId, or the Copilot `conversationId`) on the
   boundary span AND pass it in as a conversation variable so Copilot logs it to App Insights
   `customDimensions`. Investigation = two-console join (Grafana/Langfuse ↔ App Insights) by the shared id.
   Optionally add an OTel **Span Link** for causal documentation.
3. **L3:** only by choosing Agent Framework / Foundry for that agent.
- **Do NOT propagate context/PII to Copilot** — it's external; OTel itself advises against propagating to
  external endpoints. (Matches our Option-B manifest boundary policy.)

## Manifest tie-in (World-B)
Let the **agent manifest declare its runtime + tracing tier** (L1/L2 Copilot vs L3 Agent-Framework/Foundry)
so the gateway knows what depth to expect and how to stitch.

## Honesty flags
Copilot Studio's "OTel tracing enabled" span scope is under-documented (verify in Advanced settings UI);
M365 Agents SDK app-only auth is a live gap; this whole space (Agents view, A2A GA, Agent Framework GA)
shipped Apr–Jun 2026 — **re-verify before committing architecture.**
