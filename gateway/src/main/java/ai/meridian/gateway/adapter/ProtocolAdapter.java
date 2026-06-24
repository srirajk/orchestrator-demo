package ai.meridian.gateway.adapter;

import ai.meridian.gateway.registry.model.AgentManifest;
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
     * @param manifest the agent's full manifest (connection details, constraints, …)
     * @param input    the per-agent input derived by the input-synthesis layer
     * @return the agent's raw JSON response
     * @throws Exception propagated to the harness, which converts it to a failed node result
     */
    JsonNode invoke(AgentManifest manifest, JsonNode input) throws Exception;
}
