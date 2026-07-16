package ai.conduit.gateway.testsupport;

import ai.conduit.gateway.adapter.ProtocolAdapter;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Deterministic, fault-injectable {@link ProtocolAdapter} for the F5 test rig (spec §3b).
 *
 * <p>The production-shaped generalization of {@code ExecutorTestSupport.SleepyAdapter}: instead of
 * random sleeps, each agentId is bound to an explicit, reproducible behaviour —
 * {@code ok / slow(ms) / crash(exc) / hang()} — so a test can drive the real {@code AgentHarness}
 * into every terminal {@code NodeResult} status without docker or randomness. Every call is recorded
 * in an {@link Invocation} ledger {@code (agentId, input, bearerToken, ts)}, which underwrites both
 * the fault-injector equivalence proof and the reconciler correlation checks.
 *
 * <p>Thread-safe: the harness invokes this concurrently from virtual threads.
 */
public final class ScriptedAdapter implements ProtocolAdapter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One recorded call, in arrival order. {@code ts} is {@code System.nanoTime()} at entry. */
    public record Invocation(String agentId, JsonNode input, String bearerToken, long ts) {}

    /** The scripted behaviour for an agentId. */
    public sealed interface Behavior permits Ok, Slow, Crash, Hang {}

    /** Return a fixed payload immediately. */
    public record Ok(JsonNode payload) implements Behavior {}

    /** Sleep exactly {@code delayMs} (deterministic — not random), then return {@code payload}. */
    public record Slow(long delayMs, JsonNode payload) implements Behavior {}

    /** Throw a {@link RuntimeException} carrying {@code message} — maps to {@code NodeResult.FAILED}. */
    public record Crash(String message) implements Behavior {}

    /** Sleep effectively forever (1h) so the harness SLA cuts it — maps to {@code NodeResult.TIMEOUT}. */
    public record Hang() implements Behavior {}

    private final String protocol;
    private final Map<String, Behavior> scripts = new ConcurrentHashMap<>();
    private final Behavior defaultBehavior;
    private final List<Invocation> ledger = new CopyOnWriteArrayList<>();

    private ScriptedAdapter(String protocol, Behavior defaultBehavior) {
        this.protocol = protocol;
        this.defaultBehavior = defaultBehavior;
    }

    public static Builder builder() { return new Builder(); }

    /** A fast, deterministic adapter: every agentId returns {@code {"src":<id>}} immediately. */
    public static ScriptedAdapter fastOk() {
        return builder().build();
    }

    /** A fast, deterministic adapter with a fixed per-call delay (models a fast stub agent). */
    public static ScriptedAdapter fixedDelay(long delayMs) {
        return builder().defaultBehavior(new Slow(delayMs, null)).build();
    }

    @Override public String protocol() { return protocol; }

    @Override
    public JsonNode invoke(AgentManifest manifest, JsonNode input, String bearerToken) throws Exception {
        String agentId = manifest.agentId();
        ledger.add(new Invocation(agentId, input, bearerToken, System.nanoTime()));
        Behavior b = scripts.getOrDefault(agentId, defaultBehavior);
        return switch (b) {
            case Ok ok -> payloadOr(ok.payload(), agentId);
            case Slow slow -> { sleep(slow.delayMs()); yield payloadOr(slow.payload(), agentId); }
            case Crash crash -> throw new RuntimeException(crash.message());
            case Hang ignored -> { sleep(3_600_000L); yield payloadOr(null, agentId); }
        };
    }

    /** Immutable snapshot of the invocation ledger, in arrival order. */
    public List<Invocation> ledger() { return List.copyOf(ledger); }

    /** The exact healthy payload this adapter returns for {@code agentId} when none is scripted. */
    public static JsonNode defaultPayload(String agentId) {
        return MAPPER.createObjectNode().put("src", agentId);
    }

    private static JsonNode payloadOr(JsonNode explicit, String agentId) {
        return explicit != null ? explicit : defaultPayload(agentId);
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }

    public static final class Builder {
        private String protocol = "http";
        private Behavior defaultBehavior = new Ok(null);
        private final Map<String, Behavior> scripts = new ConcurrentHashMap<>();

        public Builder protocol(String p)          { this.protocol = p; return this; }
        public Builder defaultBehavior(Behavior b) { this.defaultBehavior = b; return this; }
        public Builder ok(String agentId, JsonNode payload)     { scripts.put(agentId, new Ok(payload)); return this; }
        public Builder slow(String agentId, long ms)            { scripts.put(agentId, new Slow(ms, null)); return this; }
        public Builder crash(String agentId, String message)    { scripts.put(agentId, new Crash(message)); return this; }
        public Builder hang(String agentId)                     { scripts.put(agentId, new Hang()); return this; }

        public ScriptedAdapter build() {
            ScriptedAdapter a = new ScriptedAdapter(protocol, defaultBehavior);
            a.scripts.putAll(scripts);
            return a;
        }
    }
}
