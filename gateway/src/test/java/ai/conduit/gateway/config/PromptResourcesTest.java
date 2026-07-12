package ai.conduit.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drift guard for the externalized LLM prompt resources ({@code src/main/resources/prompts}),
 * mirroring {@code ManifestSchemaCopiesInSyncTest}'s plain-JUnit style (no Spring context).
 *
 * <p>Pins four properties that a silent edit would otherwise slip past:
 * <ol>
 *   <li>every expected resource exists and is non-blank;</li>
 *   <li>each resource's {@code {{placeholder}}} inventory is exactly what Java fills — an orphaned
 *       placeholder crashes startup, a silently-added one goes unfilled to the model;</li>
 *   <li>no resource carries domain knowledge (same CRITICAL classes as {@code world-b-check.sh});</li>
 *   <li>no resource contains a literal {@code {{figure_} token — that syntax is owned by the
 *       runtime data path ({@code GroundedFigureRenderer}) and must never collide with a template.</li>
 * </ol>
 * Plus a {@link PromptLoader} construct-and-render smoke over the real resources.
 */
class PromptResourcesTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z0-9_]+)}}");

    /** name (path under prompts/, no .md) → expected placeholder key set. */
    private static final Map<String, Set<String>> EXPECTED = new LinkedHashMap<>();
    static {
        EXPECTED.put("fragments/instruction-hierarchy", Set.of("surface"));
        EXPECTED.put("intent-classifier.system", Set.of(
                "domain_context", "entity_json_fields", "entity_extraction_rules",
                "entity_field_list", "instruction_hierarchy", "clarify_rule"));
        EXPECTED.put("intent-classifier.clarify-rule", Set.of());
        EXPECTED.put("entity-extractor.system", Set.of("entity_field_rules", "instruction_hierarchy"));
        EXPECTED.put("answer-synthesizer.system", Set.of("display_name", "instruction_hierarchy"));
        EXPECTED.put("answer-synthesizer.figures-block", Set.of());
        EXPECTED.put("answer-synthesizer-history.system", Set.of("domain_context", "instruction_hierarchy"));
        EXPECTED.put("clarification-composer.system", Set.of("display_name"));
        EXPECTED.put("clarification-composer.default-question", Set.of("entity_noun"));
        EXPECTED.put("routing-reranker.system", Set.of());
    }

    /** CRITICAL domain-knowledge patterns — kept in lockstep with scripts/world-b-check.sh. */
    private static final Pattern[] WORLD_B_CRITICAL = {
            Pattern.compile("wealth-management|asset-servicing|private-banking|custody-operations"
                    + "|corporate-actions|cash-management|institutional"),
            Pattern.compile("[Ww]hitman|[Cc]alderon|[Oo]kafor|[Aa]ndersen"),
            Pattern.compile("REL-[0-9]|FND-[A-Za-z0-9]"),
            Pattern.compile("\"relationship_id\"|\"fund_id\"|\"relationship_reference\""
                    + "|\"fund_reference\"|\"ticker_references\""),
            Pattern.compile("[Bb]anking AI|client relationship|which client|in your coverage"
                    + "|mention the client|client name"),
            Pattern.compile("\"QTD\""),
    };

    private static Map<String, String> loadResources() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:prompts/**/*.md");
        for (Resource r : resources) {
            String url = r.getURL().toString();
            String rel = url.substring(url.lastIndexOf("/prompts/") + "/prompts/".length());
            if (rel.endsWith(".md")) rel = rel.substring(0, rel.length() - 3);
            out.put(rel, r.getContentAsString(StandardCharsets.UTF_8));
        }
        return out;
    }

    private static Set<String> placeholdersIn(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    @Test
    void everyExpectedResourceExistsAndIsNonBlank() throws Exception {
        Map<String, String> resources = loadResources();
        for (String name : EXPECTED.keySet()) {
            assertThat(resources).as("missing prompt resource: %s.md", name).containsKey(name);
            assertThat(resources.get(name)).as("prompt resource is blank: %s.md", name).isNotBlank();
        }
        // No stray resources the test does not know about (a new prompt needs a pinned inventory).
        assertThat(resources.keySet())
                .as("an unexpected prompt resource was added without a pinned placeholder inventory")
                .isEqualTo(EXPECTED.keySet());
    }

    @Test
    void eachResourcePlaceholderInventoryIsPinned() throws Exception {
        Map<String, String> resources = loadResources();
        for (Map.Entry<String, Set<String>> e : EXPECTED.entrySet()) {
            Set<String> actual = placeholdersIn(resources.get(e.getKey()));
            assertThat(actual)
                    .as("placeholder drift in %s.md — Java fills a fixed set", e.getKey())
                    .isEqualTo(new TreeSet<>(e.getValue()));
        }
    }

    @Test
    void noResourceCarriesDomainKnowledge() throws Exception {
        Map<String, String> resources = loadResources();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            for (Pattern p : WORLD_B_CRITICAL) {
                assertThat(p.matcher(e.getValue()).find())
                        .as("World-B violation: %s.md matches CRITICAL pattern /%s/", e.getKey(), p.pattern())
                        .isFalse();
            }
        }
    }

    @Test
    void noResourceCollidesWithTheFigurePlaceholderSyntax() throws Exception {
        Map<String, String> resources = loadResources();
        for (Map.Entry<String, String> e : resources.entrySet()) {
            assertThat(e.getValue())
                    .as("%s.md must not contain a literal {{figure_ token (owned by the data path)", e.getKey())
                    .doesNotContain("{{figure_");
        }
    }

    @Test
    void promptLoaderConstructsAndStrictlyRendersEveryTemplate() throws Exception {
        PromptLoader loader = PromptLoader.forClasspath();

        String hierarchy = loader.render("fragments/instruction-hierarchy", Map.of("surface", "X"));
        assertThat(hierarchy).isNotBlank().doesNotContain("{{");

        assertThatCode(() -> {
            loader.render("intent-classifier.system", Map.of(
                    "domain_context", "ctx", "entity_json_fields", "", "entity_extraction_rules", "",
                    "entity_field_list", "", "instruction_hierarchy", "H", "clarify_rule", ""));
            loader.render("entity-extractor.system", Map.of(
                    "entity_field_rules", "- f: v\n", "instruction_hierarchy", "H"));
            loader.render("answer-synthesizer.system", Map.of(
                    "display_name", "Meridian", "instruction_hierarchy", "H"));
            loader.render("answer-synthesizer-history.system", Map.of(
                    "domain_context", "ctx", "instruction_hierarchy", "H"));
            loader.render("clarification-composer.system", Map.of("display_name", "Meridian"));
            loader.render("clarification-composer.default-question", Map.of("entity_noun", "account"));
            loader.prompt("intent-classifier.clarify-rule");
            loader.prompt("answer-synthesizer.figures-block");
            loader.prompt("routing-reranker.system");
        }).doesNotThrowAnyException();
    }

    @Test
    void strictRenderRejectsAnUnfilledPlaceholder() throws Exception {
        PromptLoader loader = PromptLoader.forClasspath();
        // display_name provided, instruction_hierarchy deliberately omitted → must throw.
        assertThatThrownBy(() -> loader.render("answer-synthesizer.system", Map.of("display_name", "X")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved placeholder");
    }
}
