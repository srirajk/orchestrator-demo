package ai.conduit.gateway.orchestration.harness;

import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.testsupport.HarnessTestSupport;
import ai.conduit.gateway.testsupport.ScriptedAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrent percentile latency harness (F5 spec §3d/AC3) — the in-JVM counterpart to
 * {@code tests/load/latency-harness.mjs}. It sweeps a target concurrency set {1,5,10,25} against a
 * deterministic fast {@link ScriptedAdapter} through the REAL {@link AgentHarness}, measures
 * p50/p95/p99 <b>under concurrency</b> (never concurrency=1 only), and exports a {@code sweep.json}
 * artifact. This underwrites the latency ACs the other stories cite.
 *
 * <p>Sanity floors (AC3): at c=1 and c=5, {@code error_rate == 0} and {@code p95} is finite and below
 * the stub SLA bound — a baseline whose low-concurrency floors fail is rejected. c=25 is captured as
 * characterization, not a gate (known contention territory owned by story #21).
 */
class LatencyHarnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int[] CONCURRENCY = {1, 5, 10, 25};
    private static final int REQUESTS_PER_WORKER = 20;
    private static final int STUB_DELAY_MS = 2;      // deterministic fast stub agent
    private static final int SLA_MS = 1_000;         // generous SLA → healthy stub never times out
    private static final String AGENT_ID = "stub-agent";

    @Test
    void sweep_reports_percentiles_under_concurrency_and_exports_artifact() throws Exception {
        // One fixed-delay stub adapter + a real harness with a bulkhead wide enough for max concurrency.
        ScriptedAdapter adapter = ScriptedAdapter.builder().slow(AGENT_ID, STUB_DELAY_MS).build();
        AgentHarness harness = HarnessTestSupport.harness(adapter, SLA_MS, 64, 512);

        ArrayNode sweep = MAPPER.createArrayNode();
        String gitSha = System.getProperty("conduit.git.sha", System.getenv().getOrDefault("GIT_SHA", "unknown"));

        for (int concurrency : CONCURRENCY) {
            ObjectNode step = runStep(harness, concurrency, gitSha);
            sweep.add(step);

            // Sanity floors: low-concurrency steps are gates.
            if (concurrency == 1 || concurrency == 5) {
                assertThat(step.get("error_rate").asDouble())
                        .as("c=%d must have zero errors on the stub stack", concurrency)
                        .isEqualTo(0.0);
                assertThat(step.get("p95").asLong())
                        .as("c=%d p95 must be finite and below the SLA bound", concurrency)
                        .isGreaterThanOrEqualTo(0L)
                        .isLessThan(SLA_MS);
            }
            // Every step must have recorded the full request count (harness didn't drop calls).
            assertThat(step.get("n").asInt()).isEqualTo(concurrency * REQUESTS_PER_WORKER);
        }

        Path out = Path.of("target", "evidence", "story-0", "latency-baseline", "sweep.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(sweep));

        // Artifact is valid JSON and covers every concurrency step.
        assertThat(MAPPER.readTree(Files.readString(out)).size()).isEqualTo(CONCURRENCY.length);
        System.out.println("[latency-harness] wrote " + out.toAbsolutePath());
    }

    private ObjectNode runStep(AgentHarness harness, int concurrency, String gitSha) throws InterruptedException {
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        Map<String, Integer> statuses = new TreeMap<>();
        ConcurrentLinkedQueue<String> statusStream = new ConcurrentLinkedQueue<>();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        List<Thread> workers = new ArrayList<>();

        for (int w = 0; w < concurrency; w++) {
            Thread t = Thread.ofVirtual().unstarted(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < REQUESTS_PER_WORKER; i++) {
                        NodeResult r = harness.execute(
                                HarnessTestSupport.node(AGENT_ID, "http", SLA_MS, null));
                        latencies.add(r.latencyMs());
                        statusStream.add(r.status().name());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            workers.add(t);
            t.start();
        }

        startGate.countDown();          // release all workers simultaneously
        done.await();

        for (String s : statusStream) statuses.merge(s, 1, Integer::sum);

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int n = sorted.size();
        long errors = statusStream.stream().filter(s -> !s.equals("OK")).count();

        ObjectNode step = MAPPER.createObjectNode();
        step.put("git_sha", gitSha);
        step.put("path_kind", "stub-agent");
        step.put("concurrency", concurrency);
        step.put("n", n);
        step.put("p50", percentile(sorted, 0.50));
        step.put("p95", percentile(sorted, 0.95));
        step.put("p99", percentile(sorted, 0.99));
        step.put("max", n == 0 ? 0 : sorted.get(n - 1));
        step.put("error_rate", n == 0 ? 0.0 : (double) errors / n);
        ObjectNode statusNode = step.putObject("statuses");
        statuses.forEach(statusNode::put);
        return step;
    }

    /** Nearest-rank percentile over a pre-sorted ascending list. */
    private static long percentile(List<Long> sortedAsc, double q) {
        if (sortedAsc.isEmpty()) return 0;
        int rank = (int) Math.ceil(q * sortedAsc.size());
        int idx = Math.min(Math.max(rank - 1, 0), sortedAsc.size() - 1);
        return sortedAsc.get(idx);
    }
}
