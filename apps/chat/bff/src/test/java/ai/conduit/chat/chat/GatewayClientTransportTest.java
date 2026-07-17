package ai.conduit.chat.chat;

import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.web.GatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transport-level guards for {@link GatewayClient}.
 *
 * <p>Both properties asserted here are invisible in normal operation and expensive when lost:
 * an accidental HTTP/2 upgrade turns every gateway call into an unexplained 404, and an unbounded
 * read parks a request thread for the lifetime of the process. Neither shows up in a happy-path
 * test, so they are pinned explicitly.
 */
class GatewayClientTransportTest {

    private ServerSocket server;
    private ExecutorService executor;
    private final List<String> requestLines = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new ServerSocket(0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        executor.shutdownNow();
    }

    private GatewayClient clientFor(long requestTimeoutMs) {
        var gateway = new AppProperties.Gateway(
                "http://127.0.0.1:" + server.getLocalPort(),
                "test-model",
                1024,
                2000,
                requestTimeoutMs);
        var props = new AppProperties(gateway, null, null, null);
        return new GatewayClient(props, new ObjectMapper(), executor);
    }

    /**
     * The gateway is addressed over cleartext http://. A client that prefers HTTP/2 announces
     * {@code Upgrade: h2c} on its first request; a server that does not negotiate the upgrade
     * commonly answers 404 with an empty body. Pinning HTTP/1.1 means the header is never sent.
     */
    @Test
    void chatStreamNegotiatesHttp11AndNeverOffersAnH2cUpgrade() throws Exception {
        CountDownLatch served = new CountDownLatch(1);
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                var reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    synchronized (requestLines) {
                        requestLines.add(line);
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream\r\n"
                        + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                served.countDown();
            } catch (Exception ignored) {
                // the assertions below report the failure
            }
            return null;
        });

        GatewayStream stream = clientFor(5_000).openChatStream("token", "conv-1", List.of());

        assertThat(served.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stream.status()).isEqualTo(200);

        synchronized (requestLines) {
            assertThat(requestLines).isNotEmpty();
            assertThat(requestLines.get(0))
                    .as("request line must be HTTP/1.1, not HTTP/2 prior-knowledge")
                    .endsWith("HTTP/1.1");
            assertThat(requestLines)
                    .as("no h2c upgrade may be offered over cleartext")
                    .noneSatisfy(h -> assertThat(h.toLowerCase()).contains("upgrade"));
        }
    }

    /**
     * The Phase-2 RESUME overload must carry the structured-clarification carriers as headers so the
     * gateway can consume the outstanding descriptor and re-CHECK. An ordinary chat turn (the no-arg
     * overload) must send NEITHER header — the two paths stay distinguishable on the wire.
     */
    @Test
    void resumeOverloadSendsClarifyHeaders_ordinaryTurnSendsNone() throws Exception {
        CountDownLatch served = new CountDownLatch(1);
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                var reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    synchronized (requestLines) {
                        requestLines.add(line);
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream\r\n"
                        + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                served.countDown();
            } catch (Exception ignored) {
            }
            return null;
        });

        GatewayStream stream = clientFor(5_000)
                .openChatStream("token", "conv-9", List.of(), "nonce-abc", "REL-00042");

        assertThat(served.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stream.status()).isEqualTo(200);

        synchronized (requestLines) {
            assertThat(requestLines).anySatisfy(h ->
                    assertThat(h.toLowerCase()).contains("x-clarify-nonce: nonce-abc"));
            assertThat(requestLines).anySatisfy(h ->
                    assertThat(h.toLowerCase()).contains("x-clarify-selection: rel-00042"));
        }
    }

    @Test
    void ordinaryTurnSendsNoClarifyHeaders() throws Exception {
        CountDownLatch served = new CountDownLatch(1);
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                var reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    synchronized (requestLines) {
                        requestLines.add(line);
                    }
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/event-stream\r\n"
                        + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                served.countDown();
            } catch (Exception ignored) {
            }
            return null;
        });

        clientFor(5_000).openChatStream("token", "conv-9", List.of());

        assertThat(served.await(10, TimeUnit.SECONDS)).isTrue();
        synchronized (requestLines) {
            assertThat(requestLines).noneSatisfy(h ->
                    assertThat(h.toLowerCase()).contains("x-clarify-nonce"));
            assertThat(requestLines).noneSatisfy(h ->
                    assertThat(h.toLowerCase()).contains("x-clarify-selection"));
        }
    }

    /**
     * A peer that accepts the socket and then never writes a status line used to park the caller
     * forever, because {@code HttpClient} without a request timeout waits indefinitely for headers.
     */
    @Test
    void aPeerThatAcceptsAndStallsFailsWithinTheRequestTimeoutRatherThanHangingForever() {
        executor.submit(() -> {
            try (Socket socket = server.accept()) {
                // Accept, read nothing, answer nothing. Hold the connection open.
                Thread.sleep(30_000);
            } catch (Exception ignored) {
                // expected once the client gives up and the test tears the server down
            }
            return null;
        });

        GatewayClient client = clientFor(1_500);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.openChatStream("token", "conv-1", List.of()))
                .isInstanceOf(GatewayException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
                .as("must give up at the configured request timeout, not hang")
                .isLessThan(10_000);
    }
}
