package ai.conduit.gateway.orchestration.planner;

import ai.conduit.gateway.orchestration.model.Plan;
import ai.conduit.gateway.orchestration.model.PlanNode;
import ai.conduit.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style, hermetic (no docker) tests over the <b>real shipped manifests</b> in
 * {@code registry/manifests/}. Two things are proven here:
 *
 * <ol>
 *   <li><b>{@code io} preservation (step 1 regression guard).</b> The multi-step {@code io} contract
 *       must survive both Jackson binding <i>and</i> the registry's re-stamp round-trip (serialize →
 *       persist → deserialize). If a future change reintroduces the old-arity {@code AgentManifest}
 *       constructor that drops {@code io}, this fails.</li>
 *   <li><b>Resolver over real manifests.</b> With the two real wealth manifests as candidates, the
 *       {@link DagResolver} derives the flagship two-step plan: holdings → concentration.</li>
 * </ol>
 *
 * <p>The manifest ids/types read here are data from the registry files, not gateway constants — the
 * test locates and loads them at runtime; the gateway source embeds none of these symbols.
 */
class DagRealManifestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private final DagResolver resolver = new DagResolver();

    // The flagship fan-in pair, by their real registry ids.
    private static final String CONCENTRATION_ID = "meridian.wealth.concentration";
    private static final String HOLDINGS_ID       = "meridian.wealth.holdings";

    // ── manifest loading ───────────────────────────────────────────────────────────────────────

    /** Walk up from the module working dir to find the shipped registry/manifests directory. */
    private static Path registryDir() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("registry").resolve("manifests");
            if (Files.isDirectory(candidate)) return candidate;
            dir = dir.getParent();
        }
        throw new IllegalStateException("registry/manifests not found upward from "
                + Paths.get("").toAbsolutePath());
    }

    private static AgentManifest load(String agentId) {
        // Resolve by filename recursively — manifests are organised into per-domain subfolders.
        try (var files = Files.walk(registryDir())) {
            Path file = files
                    .filter(p -> p.getFileName().toString().equals(agentId + ".json"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("manifest not found: " + agentId));
            return MAPPER.readValue(Files.readAllBytes(file), AgentManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("could not load real manifest " + agentId, e);
        }
    }

    /** Mimic the registry re-stamp: derive-then-persist round-trip through the full canonical ctor. */
    private static AgentManifest restampRoundTrip(AgentManifest m) {
        // Re-stamp via the all-component constructor exactly as AgentRegistry.stampAndStore now does,
        // then JSON round-trip exactly as Redis persist → find() does.
        AgentManifest stamped = new AgentManifest(
                m.agentId(), m.name(), m.description(), m.version(), m.provider(),
                m.domain(), m.audience(), m.subDomain(), m.maxResponseTokens(), m.protocol(),
                m.connection(), m.capabilities(), m.skills(), m.constraints(),
                m.io(),
                m.inputSchema(), m.outputSchema(), m.resolvedConnection(), true, null);
        try {
            String json = MAPPER.writeValueAsString(stamped);
            return MAPPER.readValue(json, AgentManifest.class);
        } catch (Exception e) {
            throw new RuntimeException("restamp round-trip failed", e);
        }
    }

    // ── step 1: io survives load + registry re-stamp ─────────────────────────────────────────────

    @Test
    @DisplayName("real manifests keep io through Jackson load AND the registry re-stamp round-trip")
    void ioSurvivesRegistryRestamp() {
        AgentManifest concentration = restampRoundTrip(load(CONCENTRATION_ID));
        AgentManifest holdings      = restampRoundTrip(load(HOLDINGS_ID));

        // concentration: fan-in — consumes an upstream produced type, produces its own type.
        assertThat(concentration.io()).as("concentration io must survive re-stamp").isNotNull();
        assertThat(concentration.io().produces()).isNotEmpty();
        assertThat(concentration.io().consumes())
                .anyMatch(c -> c != null && c.isProducedRef() && c.isRequired());

        // holdings: leaf — consumes a required entity, produces the type concentration wants.
        assertThat(holdings.io()).as("holdings io must survive re-stamp").isNotNull();
        assertThat(holdings.io().produces()).isNotEmpty();
        assertThat(holdings.io().consumes()).anyMatch(c -> c != null && c.isEntityRef());

        // The producer/consumer type symbols actually line up (proves the wiring is real, not vacuous).
        String consumedType = concentration.io().consumes().stream()
                .filter(c -> c != null && c.isProducedRef() && c.isRequired())
                .findFirst().orElseThrow().from();
        assertThat(holdings.io().produces())
                .anyMatch(p -> consumedType.equals(p.type()));
    }

    @Test
    @DisplayName("the old-arity AgentManifest constructor drops io (documents the fixed footgun)")
    void oldArityConstructorDropsIo() {
        AgentManifest real = load(CONCENTRATION_ID);
        assertThat(real.io()).isNotNull();

        // The 19-arg delegating constructor sets io=null — this is exactly why the registry re-stamp
        // sites had to switch to the full canonical constructor. Guard against a silent regression.
        AgentManifest viaOldArity = new AgentManifest(
                real.agentId(), real.name(), real.description(), real.version(), real.provider(),
                real.domain(), real.audience(), real.subDomain(), real.maxResponseTokens(), real.protocol(),
                real.connection(), real.capabilities(), real.skills(), real.constraints(),
                real.inputSchema(), real.outputSchema(), real.resolvedConnection(), true, null);
        assertThat(viaOldArity.io()).isNull();
    }

    // ── resolver derives the flagship plan from the real manifests ───────────────────────────────

    /** Load every shipped manifest (the whole registry snapshot), re-stamped as the registry would. */
    private static List<AgentManifest> loadAllRestamped() {
        try (var files = Files.walk(registryDir())) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> {
                        try {
                            return restampRoundTrip(MAPPER.readValue(Files.readAllBytes(p), AgentManifest.class));
                        } catch (Exception e) {
                            return null;   // skip anything unparseable — mirrors the loader's tolerance
                        }
                    })
                    .filter(m -> m != null && m.agentId() != null)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("could not enumerate registry manifests", e);
        }
    }

    @Test
    @DisplayName("resolver derives holdings → concentration from the whole real registry snapshot")
    void resolvesHoldingsThenConcentration() {
        // Candidates = the entire real registry (as the gateway sees it after bootstrap).
        List<AgentManifest> candidates = loadAllRestamped();

        // The entity that the leaf producers consume is available (resolved upstream).
        String entityKey = load(HOLDINGS_ID).io().consumes().stream()
                .filter(c -> c != null && c.isEntityRef())
                .findFirst().orElseThrow().entity();

        DagResolution res = resolver.resolve(CONCENTRATION_ID, candidates, Set.of(entityKey));
        assertThat(res.ok()).as("errors: %s", res.errors()).isTrue();

        Plan plan = res.plan();
        Map<String, List<String>> edges = plan.nodes().stream()
                .collect(Collectors.toMap(PlanNode::nodeId, PlanNode::dependsOn));

        // holdings is a leaf; concentration depends (at least) on holdings.
        assertThat(edges).containsKeys(HOLDINGS_ID, CONCENTRATION_ID);
        assertThat(edges.get(HOLDINGS_ID)).as("holdings is a leaf").isEmpty();
        assertThat(edges.get(CONCENTRATION_ID)).as("concentration depends on holdings").contains(HOLDINGS_ID);

        // Only concentration + its transitive producers are pulled in — a tight, multi-node plan.
        assertThat(plan.nodes().size()).as("plan is multi-node").isGreaterThanOrEqualTo(2);

        List<String> order = plan.nodes().stream().map(PlanNode::nodeId).toList();
        assertThat(order.indexOf(HOLDINGS_ID))
                .as("topo order: holdings before concentration in %s", order)
                .isLessThan(order.indexOf(CONCENTRATION_ID));
        assertThat(order.get(order.size() - 1))
                .as("concentration (the goal/sink) is last")
                .isEqualTo(CONCENTRATION_ID);
    }
}
