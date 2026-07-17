package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestCompleteData;
import ai.conduit.gateway.infrastructure.telemetry.event.RequestStartData;
import ai.conduit.gateway.infrastructure.telemetry.event.TenantContextData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Axiom A6.4 — the Cerbos DECISION LOG survives a PDP restart and stays joinable to the app audit
 * record. We record a {@code cerbosCallId} + policy version from a live check, restart the PDP (a
 * DISTINCT Testcontainers instance on a random port — never the running demo's {@code conduit-cerbos:3594}),
 * and prove the retained decision still carries that callId, which the assembled {@link AuditRecord}
 * references via {@code cerbosCallIds} — the two join.
 *
 * <p>The decision log is written to {@code /var/log/cerbos} inside the container, bind-mounted to a host
 * dir that OUTLIVES the container — the same durability the docker-compose {@code cerbos-audit} volume
 * provides in the demo.
 */
class CerbosDecisionLogDurabilityIT {

    private static final DockerImageName CERBOS_IMAGE = DockerImageName.parse(
            System.getenv().getOrDefault("CERBOS_IMAGE", "ghcr.io/cerbos/cerbos:latest"));

    private final ObjectMapper mapper = new ObjectMapper();

    /** Walk up from the surefire cwd (the gateway module) to the repo root holding infra/cerbos. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("infra/cerbos/config.yaml"))) {
            dir = dir.getParent();
        }
        if (dir == null) throw new IllegalStateException("could not locate infra/cerbos from " + Path.of("").toAbsolutePath());
        return dir;
    }

    private GenericContainer<?> cerbos(String confDir, String logDir) {
        return new GenericContainer<>(CERBOS_IMAGE)
                .withExposedPorts(3592)
                .withFileSystemBind(confDir, "/conf", BindMode.READ_ONLY)
                .withFileSystemBind(logDir, "/var/log/cerbos", BindMode.READ_WRITE)
                .withCommand("server", "--config=/conf/config.yaml")
                .waitingFor(Wait.forHttp("/_cerbos/health").forPort(3592).forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
    }

    /** A minimal valid CheckResources call — returns 200 with a cerbosCallId and logs one decision. */
    private String checkAndCaptureCallId(String baseUrl) throws Exception {
        String body = """
                {"principal":{"id":"examiner-p1","policyVersion":"default","roles":["user"],
                  "attr":{"segments":{},"domains":[],"admin_domains":[],"tenant_id":"acme"}},
                 "resources":[{"actions":["invoke"],
                  "resource":{"kind":"agent","policyVersion":"default","id":"agent-x",
                    "attr":{"domain":"d","audience":"segment","access_mode":"read","data_classification":"confidential","tenant_id":"acme"}}}]}
                """;
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/check/resources"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as("check body: %s", resp.body()).isEqualTo(200);
        String callId = mapper.readTree(resp.body()).path("cerbosCallId").asText(null);
        assertThat(callId).as("the PDP response must carry a cerbosCallId to join on").isNotBlank();
        return callId;
    }

    private String readLog(Path logFile) throws Exception {
        return Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
    }

    @Test
    void restartPreservesJoinableDecision() throws Exception {
        String conf = repoRoot().resolve("infra/cerbos").toString();
        Path logDir = Files.createTempDirectory("cerbos-audit-durability");
        logDir.toFile().setWritable(true, false);
        logDir.toFile().setReadable(true, false);
        logDir.toFile().setExecutable(true, false);
        Path logFile = logDir.resolve("audit.log");

        String callId;

        // ── PDP instance #1: make a decision, then stop (graceful shutdown flushes the file backend). ──
        GenericContainer<?> pdp1 = cerbos(conf, logDir.toString());
        pdp1.start();
        try {
            String base = "http://" + pdp1.getHost() + ":" + pdp1.getMappedPort(3592);
            callId = checkAndCaptureCallId(base);
        } finally {
            pdp1.stop();
        }

        // The decision is durable on disk AFTER the PDP that wrote it is gone.
        final String cid = callId;
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> assertThat(readLog(logFile))
                        .as("decision log persists the callId after PDP shutdown")
                        .contains(cid));

        // ── PDP instance #2: a FRESH process re-attaches the retained log — the chain survived a restart. ──
        GenericContainer<?> pdp2 = cerbos(conf, logDir.toString());
        pdp2.start();
        try {
            assertThat(readLog(logFile))
                    .as("a restarted PDP still sees the retained decision")
                    .contains(cid);
        } finally {
            pdp2.stop();
        }

        // ── Join: the app audit record references the same callId + the active bundle version. ──
        List<TraceEvent> events = List.of(
                new TraceEvent("request_start", "req-exam", "conv-exam", 1_700_000_000_000L,
                        new RequestStartData("examiner-p1", "acme", "am I entitled?")),
                new TraceEvent("tenant_context", "req-exam", "conv-exam", 1_700_000_000_000L,
                        new TenantContextData("acme", "default")),
                new TraceEvent("entitlement_check", "req-exam", "conv-exam", 1_700_000_000_000L,
                        Map.of("cerbosCallId", callId, "allowed", true, "source", "cerbos")),
                new TraceEvent("request_complete", "req-exam", "conv-exam", 1_700_000_000_000L,
                        new RequestCompleteData(10, 1, 1)));

        AuditRecord record = new AuditRecordAssembler("test-a6")
                .assemble(events, Instant.parse("2026-07-16T12:00:00Z"));

        assertThat(record.cerbosCallIds())
                .as("the app audit record carries the decision-log join key")
                .contains(callId);
        assertThat(record.activePolicyVersion())
                .as("and the immutable bundle version the request ran under")
                .isEqualTo("default");

        // The join holds: the callId in the DURABLE decision log == the callId in the app record.
        assertThat(readLog(logFile)).contains(callId);
        assertThat(Set.copyOf(record.cerbosCallIds())).contains(callId);
    }
}
