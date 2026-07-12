package ai.conduit.gateway.synthesis.input;

import ai.conduit.gateway.config.PromptLoader;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import ai.conduit.gateway.domain.manifest.EntityType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * When the extraction LLM is unreachable, {@link EntityExtractor} must degrade to a keyword parser
 * that recognises entity references <em>verbatim in the user's text</em> — and must never invent one.
 *
 * <p>The product rule: the LLM extracts human references, a deterministic lookup resolves them to
 * IDs, and an unresolved reference triggers a clarification — never a guess (CLAUDE.md §4b). The
 * failure path must not become a back door that fabricates an ID from a name, because a fabricated
 * reference would flow downstream as if the user had supplied it.
 *
 * <p>{@code IntentClassifier} had exactly this shape of bug — a canned value on LLM failure — so
 * these tests pin that the extractor's fallback stays honest.
 */
class EntityExtractorFallbackTest {

    private static final String ID_PATTERN = "REL-\\d+";

    private static EntityType resolvable(String key) {
        return new EntityType(key, key, "resolvable", key, ID_PATTERN, "relationship", false, null);
    }

    private static EntityExtractor extractorWithDeadLlm(List<EntityType> types) {
        RestTemplate restTemplate = mock(RestTemplate.class);
        // The LLM hop fails — every extraction must fall through to the keyword parser.
        when(restTemplate.exchange(any(String.class), any(), any(), any(Class.class)))
                .thenThrow(new RuntimeException("LLM unreachable"));

        DomainManifestStore store = mock(DomainManifestStore.class);
        when(store.entityTypes()).thenReturn(types);

        return new EntityExtractor(restTemplate, new ObjectMapper(), store, prompts());
    }

    /** Loads the real prompt resources from the test classpath (mirrors production wiring). */
    private static PromptLoader prompts() {
        try {
            return PromptLoader.forClasspath();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void aLiteralIdInThePromptIsRecognisedVerbatimWhenTheLlmIsDown() {
        EntityExtractor extractor = extractorWithDeadLlm(List.of(resolvable("relationship")));

        EntityBag bag = extractor.extract("show me holdings for REL-00042");

        assertThat(bag.reference("relationship"))
                .as("a literal id present in the text is matched verbatim, never altered")
                .isEqualTo("REL-00042");
    }

    @Test
    void aNameWithNoIdYieldsNoFabricatedReferenceWhenTheLlmIsDown() {
        EntityExtractor extractor = extractorWithDeadLlm(List.of(resolvable("relationship")));

        // The user named a client but supplied no id. The keyword fallback cannot know the id, and
        // must NOT invent one — downstream this becomes a clarification, not a guess.
        EntityBag bag = extractor.extract("show me holdings for the Whitman family office");

        assertThat(bag.reference("relationship"))
                .as("no id in the text → no reference; the fallback must never map a name to an id")
                .isNull();
    }

    @Test
    void theFallbackNeverThrows() {
        EntityExtractor extractor = extractorWithDeadLlm(List.of(resolvable("relationship")));
        // extract() is contractually best-effort; a dead LLM must not surface as an exception here.
        assertThat(extractor.extract("anything at all")).isNotNull();
    }
}
