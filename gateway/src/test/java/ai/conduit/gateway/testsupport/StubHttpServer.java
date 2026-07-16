package ai.conduit.gateway.testsupport;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A tiny in-process HTTP/1.1 server (JDK {@code com.sun.net.httpserver}) for transport-behaviour
 * tests — trickle bodies, silent streams, connection resets, slow-but-alive responses. No external
 * dependency, no LLM: these tests prove the CLIENT's timeout/deadline/cancel behaviour, so the server
 * only needs to misbehave deterministically. Bind to an ephemeral loopback port; {@link #baseUrl()}
 * gives the address to point a client at.
 */
public final class StubHttpServer implements AutoCloseable {

    private final HttpServer server;

    public StubHttpServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        server.setExecutor(null);   // default executor: one background thread per request is fine here
        server.start();
    }

    public StubHttpServer handle(String path, HttpHandler handler) {
        server.createContext(path, handler);
        return this;
    }

    /** Convenience: respond with a fixed status + JSON body. */
    public StubHttpServer json(String path, int status, String body) {
        return handle(path, exchange -> respond(exchange, status, "application/json", body));
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
