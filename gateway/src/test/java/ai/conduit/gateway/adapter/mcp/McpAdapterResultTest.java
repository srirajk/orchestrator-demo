package ai.conduit.gateway.adapter.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP distinguishes two kinds of failure, and a client must branch on both.
 *
 * <ul>
 *   <li><b>Protocol error</b> — the JSON-RPC {@code error} member (unknown tool, bad arguments).</li>
 *   <li><b>Tool execution error</b> — a <em>successful</em> JSON-RPC response whose
 *       {@code result.isError} is true. Unlike HTTP there is no status code, so this flag is the
 *       only signal that the tool ran and failed.</li>
 * </ul>
 *
 * <p>The gateway used to check only the first. An agent reporting {@code llm_unavailable} therefore
 * looked identical to one that answered: its error envelope was parsed as data, the node counted
 * toward {@code okCount}, the request was recorded ANSWERED, and the synthesizer received
 * {@code {"error": ...}} as ground truth (hard rule c). Measured: 29 failed agent calls, zero
 * reported by the gateway.
 */
class McpAdapterResultTest {

    private final McpAdapter adapter = new McpAdapter(new ObjectMapper());

    @Test
    void toolExecutionErrorIsAFailure_notData() throws Exception {
        // What FastMCP emits when a tool raises: a normal JSON-RPC result, isError=true.
        String raw = """
            {"jsonrpc":"2.0","id":"2","result":{
               "content":[{"type":"text","text":"Error executing tool get_nav: {\\"error\\":\\"llm_unavailable: TimeoutError\\",\\"agent_id\\":\\"meridian.servicing.nav\\",\\"status_code\\":503}"}],
               "isError":true}}
            """;

        assertThatThrownBy(() -> adapter.extractResult(raw))
                .as("an agent that ran and failed must never be parsed as data")
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP tool error")
                .hasMessageContaining("llm_unavailable");
    }

    @Test
    void protocolErrorIsAFailure() {
        String raw = """
            {"jsonrpc":"2.0","id":"2","error":{"code":-32602,"message":"Unknown tool: get_navv"}}
            """;

        assertThatThrownBy(() -> adapter.extractResult(raw))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MCP JSON-RPC error")
                .hasMessageContaining("Unknown tool");
    }

    @Test
    void successfulToolResultIsParsedAsData() throws Exception {
        String raw = """
            {"jsonrpc":"2.0","id":"2","result":{
               "content":[{"type":"text","text":"{\\"nav\\":128.45,\\"currency\\":\\"USD\\"}"}]}}
            """;

        JsonNode data = adapter.extractResult(raw);

        assertThat(data.path("nav").asDouble()).isEqualTo(128.45);
        assertThat(data.path("currency").asText()).isEqualTo("USD");
    }

    @Test
    void absentIsErrorMeansSuccess() throws Exception {
        ObjectMapper m = new ObjectMapper();
        assertThat(McpAdapter.isToolError(m.readTree("{\"content\":[]}"))).isFalse();
        assertThat(McpAdapter.isToolError(m.readTree("{\"isError\":false}"))).isFalse();
        assertThat(McpAdapter.isToolError(m.readTree("{\"isError\":true}"))).isTrue();
    }

    @Test
    void nonJsonToolTextIsWrappedRatherThanLost() throws Exception {
        String raw = """
            {"jsonrpc":"2.0","id":"2","result":{"content":[{"type":"text","text":"plain text"}]}}
            """;

        assertThat(adapter.extractResult(raw).path("text").asText()).isEqualTo("plain text");
    }
}
