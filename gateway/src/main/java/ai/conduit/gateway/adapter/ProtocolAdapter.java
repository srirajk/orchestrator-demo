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

    /**
     * The provenance-carrying invocation (F4). Returns the agent output wrapped in a
     * {@link PayloadHandle}: {@link PayloadHandle.Inline} for a tree carried in memory, or a
     * content-addressed {@link PayloadHandle.Ref} when the adapter spilled the canonical bytes to the
     * claim-check store. In v1 the handle ALWAYS carries the stamped tree ({@link PayloadHandle#tree()}),
     * so the DAG re-fetches nothing.
     *
     * <p><b>Funnel invariant (F1).</b> {@code invoke}/{@code invokeHandle} are implementation methods;
     * the ONLY permitted caller is the harness, which is itself dispatched solely by the
     * {@code GovernedInvoker} checkpoint. Do not open a second door — no executor, controller, or service
     * may call an adapter directly.
     *
     * <p>The default returns {@code null} to signal "no claim-check semantics" — the harness then falls
     * back to {@link #invoke} and wraps its output in an adapter-provenance {@code Inline}. This keeps the
     * single {@link #invoke} call INSIDE the harness funnel (the architecture rule that only the harness
     * calls {@code ProtocolAdapter.invoke} stays true) instead of the interface opening a second door.
     * A test double or a spill-agnostic adapter thus inherits correct behaviour for free.
     * {@code HttpAdapter} (threshold spill) and {@code McpAdapter} (eager {@code resource_link} fetch)
     * override this to return a real handle.
     *
     * <p><b>A2A contract (adapter does not exist yet):</b> an artifact {@code FilePart.file.uri} maps to
     * a {@link PayloadHandle.Ref} and a {@code DataPart} maps to a {@link PayloadHandle.Inline} — the same
     * two-arm shape as HTTP/MCP.
     *
     * @return a {@link PayloadHandle} whose {@link PayloadHandle#tree()} is the stamped output tree, or
     *         {@code null} to have the harness fall back to {@link #invoke} + inline-wrap
     * @throws Exception propagated to the harness, which converts it to a failed node result
     */
    default PayloadHandle invokeHandle(AgentManifest manifest, JsonNode input, String bearerToken)
            throws Exception {
        return null;   // harness falls back to invoke() so the sole invoke() call stays inside the funnel
    }
}
