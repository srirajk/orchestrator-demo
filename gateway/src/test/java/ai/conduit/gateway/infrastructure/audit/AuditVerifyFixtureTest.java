package ai.conduit.gateway.infrastructure.audit;

import ai.conduit.gateway.infrastructure.telemetry.TraceEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The byte-match GATE for {@code scripts/audit-verify.py verify} (F5 spec §3f, harness item 7):
 * the REAL {@link AuditRecordAssembler} produces a record, it is written with the same mapper config
 * the production sink uses ({@code ObjectStoreAuditSink}: a plain ObjectMapper with
 * {@code ORDER_MAP_ENTRIES_BY_KEYS}), and the Python verifier must recompute the identical
 * {@code contentSha256} — proving the CLI reproduces Jackson's bytes before any AC cites it.
 *
 * <p>Runs the assembler directly (no Spring context, so no Redis needed). Skipped if {@code python3}
 * is unavailable — the parity claim is only asserted where it can actually be checked.
 *
 * <p>Fixture data is deliberately ASCII strings + longs and nested maps: the one shape where Python
 * and Jackson emit identical bytes. Doubles / non-ASCII (the F6 audit-golden shapes) are out of scope
 * for F5 and are that story's problem.
 */
class AuditVerifyFixtureTest {

    // Sink parity: same single feature as ObjectStoreAuditSink's mapper.
    private static final ObjectMapper SINK_MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    @Test
    void python_verifier_matches_jvm_hash() throws Exception {
        Path script = repoRoot().resolve("scripts/audit-verify.py");
        assumeTrue(Files.exists(script), "audit-verify.py not found");
        assumeTrue(pythonAvailable(), "python3 not available");

        // Nested map + string/long payloads — the byte-stable shape.
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("userId", "u-1");
        start.put("tenantId", "t-1");
        start.put("nested", Map.of("b", "2", "a", "1"));  // Jackson sorts these keys

        List<TraceEvent> events = List.of(
                TraceEvent.of("request_start", "req-1", start),
                TraceEvent.of("request_complete", "req-1",
                        Map.of("successCount", 2, "agentCount", 2)));

        AuditRecord record = new AuditRecordAssembler("0.1.0")
                .assemble(events, Instant.parse("2026-07-16T00:00:00Z"));

        assertThat(record.contentSha256()).as("hash must be captured").isNotBlank();

        Path recordFile = Files.createTempFile("audit-record", ".json");
        Files.write(recordFile, SINK_MAPPER.writeValueAsBytes(record));

        ProcessResult r = run(List.of("python3", script.toString(), "verify", recordFile.toString()));
        assertThat(r.exitCode())
                .as("python verifier must recompute the JVM contentSha256 exactly.\nstdout: %s\nstderr: %s",
                        r.stdout(), r.stderr())
                .isEqualTo(0);
        assertThat(r.stdout()).contains(record.contentSha256());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private record ProcessResult(int exitCode, String stdout, String stderr) {}

    private static ProcessResult run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        return new ProcessResult(code, out, err);
    }

    private static boolean pythonAvailable() {
        try {
            return run(List.of("python3", "--version")).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** cwd is the gateway module dir under surefire; walk up to the repo root holding scripts/. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("scripts/audit-verify.py"))) {
            dir = dir.getParent();
        }
        return dir != null ? dir : Path.of("").toAbsolutePath();
    }
}
