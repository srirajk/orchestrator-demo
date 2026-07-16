package ai.conduit.gateway.adapter.mcp;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.model.AgentManifest.Connection;
import ai.conduit.gateway.registry.model.AgentManifest.Constraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSE silent-stream fix (F3): once the SSE response has arrived, {@code sseConn.cancel(true)} is a
 * no-op — cancelling an already-completed future does NOT close the socket. So a legacy-SSE server
 * that opens the stream (sends the endpoint event) then goes SILENT past the deadline would leave the
 * reader VT parked on readLine() and the socket open forever. The adapter now closes the live body
 * stream on deadline expiry: the invoke fails inside the SLA AND the server sees the TCP connection
 * close (proving no leaked reader thread / socket).
 *
 * <p>Uses a RAW {@link ServerSocket} (not {@code HttpServer}) so the close is detected deterministically
 * by {@code read() == -1} on the accepted socket — write-based broken-pipe detection is unreliable
 * under TCP half-close on loopback.
 */
class McpSseSilentStreamTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void silentSseStreamFailsInsideSlaAndClosesTheStream() throws Exception {
        int slaMs = 800;
        CountDownLatch sseClosed = new CountDownLatch(1);

        try (ServerSocket server = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            int port = server.getLocalPort();

            // Accept loop: one handler per connection, routed by request line.
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        Thread.ofVirtual().start(() -> handle(socket, sseClosed));
                    } catch (IOException e) {
                        return;   // server closed
                    }
                }
            });

            McpAdapter adapter = new McpAdapter(mapper, "2025-11-25", "2024-11-05", 10000, 10000);
            AgentManifest manifest = sseAgent("http://127.0.0.1:" + port + "/sse", slaMs);

            long t0 = System.nanoTime();
            assertThatThrownBy(() -> adapter.invoke(manifest, mapper.createObjectNode(), "tok"))
                    .as("a silent SSE stream past the deadline must fail the invoke");
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

            assertThat(elapsedMs).as("fails inside SLA + 1s slack").isLessThan(slaMs + 1000);
            assertThat(sseClosed.await(4, TimeUnit.SECONDS))
                    .as("adapter must close the live SSE stream on deadline (no leaked reader/socket)")
                    .isTrue();
        }
    }

    /** Raw HTTP/1.1 handler: GET /sse → endpoint event then silence (detect close); POST → 202. */
    private static void handle(Socket socket, CountDownLatch sseClosed) {
        try (socket) {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();

            String requestLine = in.readLine();
            if (requestLine == null) return;
            int contentLength = 0;
            String h;
            while ((h = in.readLine()) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    contentLength = Integer.parseInt(h.substring(c + 1).trim());
                }
            }

            if (requestLine.startsWith("GET /sse")) {
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write("event: endpoint\ndata: /messages\n\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                // Now SILENT — never a message event. Block reading; the client's close (abortSse →
                // body.close()) sends FIN and read() returns -1. THAT is the no-leak proof.
                while (in.read() != -1) { /* discard */ }
                sseClosed.countDown();
            } else {
                // Session-path POST (initialize / tools/call): drain body, ack 202.
                for (int i = 0; i < contentLength; i++) in.read();
                out.write("HTTP/1.1 202 Accepted\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        } catch (IOException e) {
            // GET /sse connection dropped from the read side counts as closed too.
            sseClosed.countDown();
        }
    }

    static AgentManifest sseAgent(String serverUrl, int slaMs) {
        return new AgentManifest("a1", "a1", null, null, null, null, null, null, null, "mcp",
                new Connection(null, null, serverUrl, "the_tool", "sse", null),
                null, null, new Constraints("read", "internal", slaMs),
                null, null, null, null, true, null);
    }
}
