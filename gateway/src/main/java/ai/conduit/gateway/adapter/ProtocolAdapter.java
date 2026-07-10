package ai.conduit.gateway.adapter;

import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Uniform interface over outbound agent protocols (HTTP/OpenAPI and MCP/SSE).
 *
 * <p>Implementations are pure functions: given a manifest and a typed input bag,
 * return the agent's JSON output.  All retry logic, circuit-breaking, and timeout
 * enforcement live in the harness layer above; adapters just do the wire call.
 *
 * <p>Thread safety: {@link #invoke} will be called concurrently from virtual
 * threads — implementations must be stateless or use thread-safe data structures.
 */
public interface ProtocolAdapter {

    /** Returns the protocol token this adapter handles, e.g. {@code "http"} or {@code "mcp"}. */
    String protocol();

    /**
     * Invoke the agent described by {@code manifest} with the given {@code input}.
     *
     * <p><b>F-IDENTITY:</b> {@code bearerToken} is the caller's identity, captured as request-scoped
     * DATA on the servlet thread (see {@code ChatCompletionsController}) and threaded explicitly
     * through the executors and harness — never read from {@code SecurityContextHolder}, which is
     * thread-local and is NOT propagated to the virtual threads this method runs on. Implementations
     * must send it as the outbound {@code Authorization} header and must refuse to call the agent
     * (throw) rather than silently omitting the header when it is null/blank.
     *
     * @param manifest    the agent's full manifest (connection details, constraints, …)
     * @param input       the per-agent input derived by the input-synthesis layer
     * @param bearerToken the calling principal's verified JWT, propagated to the agent for
     *                    hop-level authentication; must not be silently dropped
     * @return the agent's raw JSON response
     * @throws Exception propagated to the harness, which converts it to a failed node result
     */
    JsonNode invoke(AgentManifest manifest, JsonNode input, String bearerToken) throws Exception;
}
