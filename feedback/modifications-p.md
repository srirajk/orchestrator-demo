Findings

[P1] Downstream JWT propagation likely breaks across the async fan-out.

ChatCompletionsController.java (line 50) correctly notes that thread locals do not cross the async boundary and captures Principal, but the adapters later read the bearer token from SecurityContextHolder inside execution threads.

See:

* HttpAdapter.java (line 202)
* McpAdapter.java (line 99)

The gateway may authorize using the JWT principal, while agents receive no token and reject, or tests may hide this if mocks are permissive.

Fix

Carry the raw bearer token in an explicit request context or PlanNode / RequestContext, then pass it into adapters.

⸻

[P1] Agent policy fail-open ignores the configured Cerbos fail mode.

CerbosEntitlementAdapter.java (line 119) always falls back to allowing non-mutating agents when Cerbos agent checks fail, while relationship checks honor fail_mode at CerbosEntitlementAdapter.java (line 212).

For a bank demo, this is an easy critique:

“PDP down means agent-domain policy opens.”

Make agent checks fail closed by default too, with demo-only local fallback clearly configured.

⸻

[P1] Trace endpoints are public and expose stored request evidence.

SecurityConfig.java (line 68) permits all /trace/**, and TraceStreamController.java (line 50) returns persisted trace events by request ID.

Those events include agent result previews from ChatService.java (line 329).

For local demo this is fine, but for credibility add a switch:

* public in demo mode
* admin-only in production
* or signed glass-box session in serious mode

⸻

[P1] Live registration can fetch arbitrary URLs during introspection.

The manifest schema accepts openapi_url and server_url as plain strings at agent-manifest.schema.json (line 118), and introspection fetches them directly at:

* AgentIntrospector.java (line 57)
* McpToolIntrospector.java (line 50)

Add:

* allowlisted host patterns
* scheme validation
* private IP blocking
* short read timeouts

Otherwise an admin endpoint becomes an SSRF path.

⸻

[P2] Registry update can create or overwrite a different agent than the path ID.

AgentRegistry.java (line 98) checks that the path agentId exists, then calls:

register(submissionNode)

without verifying

submission.agent_id == agentId.

A PUT to

/admin/agents/a

with body

agent_id=b

can register b.

Reject mismatched IDs.

⸻

[P2] MCP introspection silently fabricates a schema if tool discovery fails.

McpToolIntrospector.java (line 66) falls back to a guessed schema at McpToolIntrospector.java (line 218).

That undercuts the “derive contracts from the agent” story.

For bootstrap demo, fallback is convenient.

For live registration, fail closed unless an explicit allow_fallback_schema demo flag is on.

⸻

[P2] /v1/chat/completions claims OpenAI compatibility but ignores stream=false.

ChatRequest.java (line 16) carries stream, but ChatCompletionsController.java (line 40) always returns SSE.

Fine for LibreChat, weaker for an “OpenAI-compatible API.”

Either:

* document “streaming only”, or
* return a normal JSON chat completion when stream=false.

⸻

[P2] Follow-up cache can bypass fresh entitlement and route checks.

ChatService.java (line 360) reuses cached agent results for follow-up synthesis.

If user permissions change mid-session, cached confidential data can still be reused until TTL.

For demo this is acceptable.

For strong governance:

* re-check relationship entitlement before cached synthesis, or
* bind cached results to the principal and policy decision ID.

⸻

[P3] Numeric grounding check is diagnostic only.

AnswerSynthesizer.java (line 444) logs unsupported numbers but does not alter the response.

