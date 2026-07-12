package ai.conduit.gateway.synthesis.answer;

import ai.conduit.gateway.orchestration.model.NodeResult;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.synthesis.answer.AnswerSynthesizer.WithheldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-entity COMPARE synthesis attribution: two calls to the SAME agent for two clients must produce
 * DISTINCT, entity-qualified DATA headers and figure sources, and an uncovered client must appear ONLY as
 * a WITHHELD ENTITY block quoting the user's own words + the manifest denial copy — never the resolver's
 * canonical name/id. Exercises the pure renderers ({@code renderUserContent}, {@code GroundedFigureRenderer})
 * with no LLM/HTTP. World-B: fixture literals live in TEST only.
 */
class AnswerSynthesizerCompareTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GroundedFigureRenderer renderer = new GroundedFigureRenderer(MAPPER);

    private static NodeResult ok(String nodeId, String agentId, String json) throws Exception {
        return new NodeResult(nodeId, agentId, "http", NodeResult.Status.OK, MAPPER.readTree(json), 5L, null);
    }

    @Test
    void sameAgentTwoEntities_produceDistinctEntityQualifiedDataHeaders() throws Exception {
        String agent = "meridian.wealth.concentration";
        NodeResult whitman = ok(agent + "#REL-00042", agent, "{\"top5\":0.38}");
        NodeResult calderon = ok(agent + "#REL-00099", agent, "{\"top5\":0.22}");
        Map<String, String> labels = Map.of(
                agent + "#REL-00042", "REL-00042 \"Whitman Family Office\"",
                agent + "#REL-00099", "REL-00099 \"Calderon Trust\"");

        String content = AnswerSynthesizer.renderUserContent(MAPPER, "", List.of(whitman, calderon),
                List.of(), List.of(), labels, List.of(), null);

        assertThat(content).contains("[entity: REL-00042 \"Whitman Family Office\"]");
        assertThat(content).contains("[entity: REL-00099 \"Calderon Trust\"]");
        // Both blocks name the same agent but are disambiguated by their entity label.
        assertThat(content.split("--- DATA: " + java.util.regex.Pattern.quote(agent))).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void withheldEntity_usesUserVerbatimAndManifestCopy_neverCanonicalName() throws Exception {
        // The withheld (uncovered) client: attribution is the user's own words + the standard coverage copy.
        WithheldEntity okafor = new WithheldEntity("the Okafor account",
                "That relationship is not in your coverage.");
        String agent = "meridian.wealth.concentration";
        NodeResult whitman = ok(agent + "#REL-00042", agent, "{\"top5\":0.38}");

        String content = AnswerSynthesizer.renderUserContent(MAPPER, "", List.of(whitman),
                List.of(), List.of(), Map.of(agent + "#REL-00042", "REL-00042 \"Whitman Family Office\""),
                List.of(okafor), null);

        assertThat(content).contains("--- WITHHELD ENTITY: \"the Okafor account\" ---");
        assertThat(content).contains("That relationship is not in your coverage.");
        // No-leak: the uncovered client's resolved id/name never appears (the resolver knows REL-00188/"Okafor",
        // but only the user's own words are disclosed).
        assertThat(content).doesNotContain("REL-00188");
        assertThat(content).doesNotContain("Okafor Holdings");
    }

    @Test
    void singleEntity_headerIsByteIdentical_noEntityAnnotation() throws Exception {
        String agent = "meridian.wealth.holdings";
        NodeResult only = ok(agent, agent, "{\"v\":1}");   // nodeId == agentId (single-entity path)

        String content = AnswerSynthesizer.renderUserContent(MAPPER, "", List.of(only),
                List.of(), List.of(), Map.of(), List.of(), null);

        assertThat(content).contains("--- DATA: " + agent + " (http) ---");
        assertThat(content).doesNotContain("[entity:");
    }

    @Test
    void cappedNote_isSurfacedInPrompt() throws Exception {
        String agent = "meridian.wealth.concentration";
        NodeResult only = ok(agent + "#REL-00042", agent, "{\"v\":1}");
        String content = AnswerSynthesizer.renderUserContent(MAPPER, "", List.of(only),
                List.of(), List.of(), Map.of(agent + "#REL-00042", "REL-00042"), List.of(),
                "Only the first 2 were compared.");
        assertThat(content).contains("--- NOTE --- Only the first 2 were compared.");
    }

    @Test
    void figureSourceAgent_isNodeId_soTwoClientsFiguresAreDistinct() throws Exception {
        String agent = "meridian.wealth.concentration";
        AgentManifest manifest = withFigure(agent, "Top-5 concentration", "top5", "percent1");
        NodeResult whitman = ok(agent + "#REL-00042", agent, "{\"top5\":0.38}");
        NodeResult calderon = ok(agent + "#REL-00099", agent, "{\"top5\":0.22}");

        List<GroundedFigure> figures = renderer.render(List.of(whitman, calderon),
                id -> id.equals(agent) ? Optional.of(manifest) : Optional.empty());

        assertThat(figures).hasSize(2);
        assertThat(figures).extracting(GroundedFigure::sourceAgent)
                .containsExactly(agent + "#REL-00042", agent + "#REL-00099");   // per-client attribution
    }

    private static AgentManifest withFigure(String id, String label, String path, String format) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "s", "s", "d", List.of(), List.of("d"), List.of("text"), List.of("json"));
        AgentManifest.Io io = new AgentManifest.Io(List.of(), List.of(
                new AgentManifest.Produce(null, null, List.of(),
                        List.of(new AgentManifest.ProducedFigure(label, path, format)))));
        return new AgentManifest(id, id, "d", "1.0.0", null, "wealth-management", null, "private-banking",
                null, "http", null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000), io, null, null, null, true, null);
    }
}
