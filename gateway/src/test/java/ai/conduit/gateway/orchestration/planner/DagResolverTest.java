package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hermetic tests for {@link DagResolver}. Candidate manifests are synthetic and built in-test from
 * minimal JSON (which also exercises the real Jackson {@code io} deserialization path); the core
 * cases never depend on the shipped 13 manifests. One integration-style test loads the real wealth
 * manifests to prove entity-only capabilities resolve as single-node plans.
 *
 * <p>Type/entity symbols here are deliberately generic placeholders ("typeA", "entE", "cap.*") — the
 * resolver is a graph algorithm over manifest strings and carries no domain knowledge.
 */
class DagResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final DagResolver resolver = new DagResolver();

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    /** Deserialize a minimal manifest JSON into an {@link AgentManifest} (unset fields stay null). */
    private static AgentManifest manifest(String json) {
        try {
            return MAPPER.readValue(json, AgentManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("bad test manifest: " + json, e);
        }
    }

    /** node id → its dependsOn list, for compact assertions. */
    private static Map<String, List<String>> edges(Plan plan) {
        return plan.nodes().stream()
                .collect(Collectors.toMap(PlanNode::nodeId, PlanNode::dependsOn));
    }

    private static List<String> order(Plan plan) {
        return plan.nodes().stream().map(PlanNode::nodeId).toList();
    }

    /** Assert a appears before b in the topological order. */
    private static void assertBefore(Plan plan, String a, String b) {
        List<String> o = order(plan);
        assertThat(o.indexOf(a)).as("%s before %s in %s", a, b, o).isLessThan(o.indexOf(b));
    }

    // ── core cases ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("2-hop derive: producer→analytics edge, correct topo order")
    void twoHopDerive() {
        var data = manifest("""
            {"agent_id":"cap.data",
             "io":{"consumes":[{"entity":"entE","required":true}],
                   "produces":[{"name":"d","type":"typeA"}]}}""");
        var analytics = manifest("""
            {"agent_id":"cap.analytics",
             "io":{"consumes":[{"from":"typeA","required":true}],
                   "produces":[{"name":"a","type":"typeOut"}]}}""");

        DagResolution r = resolver.resolve("cap.analytics", List.of(data, analytics), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        assertThat(r.errors()).isEmpty();
        assertThat(order(r.plan())).containsExactly("cap.data", "cap.analytics");
        assertThat(edges(r.plan()))
                .containsEntry("cap.data", List.of())
                .containsEntry("cap.analytics", List.of("cap.data"));
    }

    @Test
    @DisplayName("fan-in: two independent producers, consumer depends on both, both parallel")
    void fanIn() {
        var pA = manifest("""
            {"agent_id":"cap.pa",
             "io":{"consumes":[{"entity":"entE","required":true}],
                   "produces":[{"name":"a","type":"typeA"}]}}""");
        var pB = manifest("""
            {"agent_id":"cap.pb",
             "io":{"consumes":[{"entity":"entE","required":true}],
                   "produces":[{"name":"b","type":"typeB"}]}}""");
        var analytics = manifest("""
            {"agent_id":"cap.join",
             "io":{"consumes":[{"from":"typeA"},{"from":"typeB"}]}}""");

        DagResolution r = resolver.resolve("cap.join", List.of(pA, pB, analytics), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        assertThat(edges(r.plan()))
                .containsEntry("cap.pa", List.of())
                .containsEntry("cap.pb", List.of())
                .containsEntry("cap.join", List.of("cap.pa", "cap.pb"));   // sorted, deterministic
        // producers have no interdependency; both precede the join
        assertBefore(r.plan(), "cap.pa", "cap.join");
        assertBefore(r.plan(), "cap.pb", "cap.join");
    }

    @Test
    @DisplayName("diamond: a shared producer appears exactly once and precedes both consumers")
    void sharedProducerDedup() {
        var base = manifest("""
            {"agent_id":"cap.base",
             "io":{"consumes":[{"entity":"entE","required":true}],
                   "produces":[{"name":"c","type":"typeC"}]}}""");
        var mid1 = manifest("""
            {"agent_id":"cap.mid1",
             "io":{"consumes":[{"from":"typeC"}],"produces":[{"name":"a","type":"typeA"}]}}""");
        var mid2 = manifest("""
            {"agent_id":"cap.mid2",
             "io":{"consumes":[{"from":"typeC"}],"produces":[{"name":"b","type":"typeB"}]}}""");
        var top = manifest("""
            {"agent_id":"cap.top",
             "io":{"consumes":[{"from":"typeA"},{"from":"typeB"}]}}""");

        DagResolution r = resolver.resolve("cap.top", List.of(base, mid1, mid2, top), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        assertThat(order(r.plan())).hasSize(4);
        assertThat(order(r.plan())).filteredOn("cap.base"::equals).hasSize(1);   // deduped
        assertThat(edges(r.plan()))
                .containsEntry("cap.base", List.of())
                .containsEntry("cap.mid1", List.of("cap.base"))
                .containsEntry("cap.mid2", List.of("cap.base"))
                .containsEntry("cap.top", List.of("cap.mid1", "cap.mid2"));
        assertBefore(r.plan(), "cap.base", "cap.mid1");
        assertBefore(r.plan(), "cap.base", "cap.mid2");
        assertBefore(r.plan(), "cap.mid1", "cap.top");
    }

    @Test
    @DisplayName("single node: entity-only consumes → one node, empty dependsOn")
    void singleNodeEntityOnly() {
        var solo = manifest("""
            {"agent_id":"cap.solo",
             "io":{"consumes":[{"entity":"entE","required":true}],
                   "produces":[{"name":"s","type":"typeS"}]}}""");

        DagResolution r = resolver.resolve("cap.solo", List.of(solo), Set.of("entE"));

        assertThat(r.ok()).isTrue();
        assertThat(r.plan().nodes()).singleElement()
                .satisfies(n -> {
                    assertThat(n.nodeId()).isEqualTo("cap.solo");
                    assertThat(n.dependsOn()).isEmpty();
                    assertThat(n.agent()).isSameAs(solo);
                });
    }

    @Test
    @DisplayName("single node: absent io block → one node, empty dependsOn")
    void singleNodeNoIo() {
        var solo = manifest("{\"agent_id\":\"cap.plain\"}");

        DagResolution r = resolver.resolve("cap.plain", List.of(solo), Set.of());

        assertThat(r.ok()).isTrue();
        assertThat(order(r.plan())).containsExactly("cap.plain");
        assertThat(r.plan().nodes().get(0).dependsOn()).isEmpty();
    }

    // ── rejection cases ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reject CYCLE: A←→B mutual from-dependency")
    void rejectCycle() {
        var a = manifest("""
            {"agent_id":"cap.a",
             "io":{"consumes":[{"from":"typeB"}],"produces":[{"name":"a","type":"typeA"}]}}""");
        var b = manifest("""
            {"agent_id":"cap.b",
             "io":{"consumes":[{"from":"typeA"}],"produces":[{"name":"b","type":"typeB"}]}}""");

        DagResolution r = resolver.resolve("cap.a", List.of(a, b), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.plan()).isNull();
        assertThat(r.hasError(ResolutionError.Code.CYCLE)).isTrue();
        ResolutionError cycle = r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.CYCLE).findFirst().orElseThrow();
        assertThat(cycle.details()).contains("cap.a", "cap.b");
    }

    @Test
    @DisplayName("reject MISSING_PRODUCER: no capability produces the required type")
    void rejectMissingProducer() {
        var goal = manifest("""
            {"agent_id":"cap.goal","io":{"consumes":[{"from":"typeZ"}]}}""");

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.MISSING_PRODUCER)).isTrue();
        ResolutionError err = r.errors().get(0);
        assertThat(err.details()).contains("cap.goal", "typeZ");
    }

    @Test
    @DisplayName("reject AMBIGUOUS_PRODUCER: two candidates produce the same type; both listed")
    void rejectAmbiguousProducer() {
        var p1 = manifest("""
            {"agent_id":"cap.p1","io":{"produces":[{"name":"x","type":"typeA"}]}}""");
        var p2 = manifest("""
            {"agent_id":"cap.p2","io":{"produces":[{"name":"y","type":"typeA"}]}}""");
        var goal = manifest("""
            {"agent_id":"cap.goal","io":{"consumes":[{"from":"typeA"}]}}""");

        DagResolution r = resolver.resolve("cap.goal", List.of(p1, p2, goal), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.AMBIGUOUS_PRODUCER)).isTrue();
        ResolutionError err = r.errors().stream()
                .filter(e -> e.code() == ResolutionError.Code.AMBIGUOUS_PRODUCER).findFirst().orElseThrow();
        assertThat(err.details()).contains("cap.p1", "cap.p2");   // does NOT silently pick one
    }

    @Test
    @DisplayName("reject UNMET_REQUIRED_INPUT: required entity not in available set")
    void rejectUnmetRequiredInput() {
        var goal = manifest("""
            {"agent_id":"cap.goal",
             "io":{"consumes":[{"entity":"entE","required":true}]}}""");

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of());   // entE missing

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNMET_REQUIRED_INPUT)).isTrue();
        assertThat(r.errors().get(0).details()).contains("cap.goal", "entE");
    }

    @Test
    @DisplayName("optional entity absent → no error, single node")
    void optionalEntityAbsentIsFine() {
        var goal = manifest("""
            {"agent_id":"cap.goal",
             "io":{"consumes":[{"entity":"entE","required":true},{"entity":"entOpt","required":false}]}}""");

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of("entE"));   // entOpt absent

        assertThat(r.ok()).isTrue();
        assertThat(order(r.plan())).containsExactly("cap.goal");
    }

    @Test
    @DisplayName("reject UNKNOWN_NODE: goal id not among candidates")
    void rejectUnknownGoal() {
        var other = manifest("{\"agent_id\":\"cap.other\"}");

        DagResolution r = resolver.resolve("cap.absent", List.of(other), Set.of());

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNKNOWN_NODE)).isTrue();
        assertThat(r.errors().get(0).details()).contains("cap.absent");
    }

    @Test
    @DisplayName("resolver reports all errors it can find, not just the first")
    void reportsMultipleErrors() {
        var goal = manifest("""
            {"agent_id":"cap.goal",
             "io":{"consumes":[{"entity":"entE","required":true},{"from":"typeZ"}]}}""");

        DagResolution r = resolver.resolve("cap.goal", List.of(goal), Set.of());   // entE missing + typeZ unproduced

        assertThat(r.ok()).isFalse();
        assertThat(r.hasError(ResolutionError.Code.UNMET_REQUIRED_INPUT)).isTrue();
        assertThat(r.hasError(ResolutionError.Code.MISSING_PRODUCER)).isTrue();
    }

    // ── integration: real shipped manifests ──────────────────────────────────────────────────

    @Test
    @DisplayName("integration: real wealth manifests (holdings, performance) resolve as single-node plans")
    void realWealthManifestsAreSingleNode() {
        AgentManifest holdings = fromClasspath("/dag/holdings.json");
        AgentManifest performance = fromClasspath("/dag/performance.json");
        List<AgentManifest> registry = List.of(holdings, performance);

        // The only leaf entity these capabilities require is available; period (on performance)
        // is optional. Neither declares a `from`, so neither creates an edge.
        Set<String> available = Set.of(entityKeyOf(holdings));

        DagResolution h = resolver.resolve(holdings.agentId(), registry, available);
        assertThat(h.ok()).as("holdings resolution: %s", h.errors()).isTrue();
        assertThat(h.plan().nodes()).singleElement()
                .satisfies(n -> assertThat(n.dependsOn()).isEmpty());

        DagResolution p = resolver.resolve(performance.agentId(), registry, available);
        assertThat(p.ok()).as("performance resolution: %s", p.errors()).isTrue();
        assertThat(p.plan().nodes()).singleElement()
                .satisfies(n -> assertThat(n.dependsOn()).isEmpty());

        // Sanity: io actually deserialized off the real manifest (produces exists, from-less consumes).
        assertThat(holdings.io()).isNotNull();
        assertThat(holdings.io().produces()).isNotEmpty();
    }

    private static AgentManifest fromClasspath(String resource) {
        try (InputStream in = DagResolverTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("resource %s on test classpath", resource).isNotNull();
            return MAPPER.readValue(in, AgentManifest.class);
        } catch (Exception e) {
            throw new UncheckedIOException("cannot load " + resource,
                    e instanceof java.io.IOException io ? io : new java.io.IOException(e));
        }
    }

    /** The first required entity key a manifest consumes (read from manifest data, not hardcoded). */
    private static String entityKeyOf(AgentManifest m) {
        return m.io().consumes().stream()
                .filter(AgentManifest.Consume::isEntityRef)
                .filter(AgentManifest.Consume::isRequired)
                .map(AgentManifest.Consume::entity)
                .findFirst().orElseThrow();
    }
}
