package ai.conduit.gateway.registry.readiness;

import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The gateway no longer builds the routing index, so it must not assume one is there.
 *
 * <p>Each failure below used to be silent. A gateway with no index, no agents, or an index built by
 * a different embedding model starts cleanly, reports itself healthy, finds no routing candidates
 * for anything, and answers every question with a request for clarification. That is not a degraded
 * gateway — it is a gateway that quietly stopped working.
 */
class RegistryReadinessVerifierTest {

    private static final String MODEL = "remote:all-MiniLM-L6-v2:384";

    private static RegistryReadinessVerifier verifier(boolean indexExists, String stamp, long agents, String queryModel) {
        VectorIndex index = mock(VectorIndex.class);
        when(index.exists()).thenReturn(indexExists);
        when(index.stampedModelId()).thenReturn(stamp);

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.count()).thenReturn(agents);

        QueryEmbedder query = mock(QueryEmbedder.class);
        when(query.modelId()).thenReturn(queryModel);

        return new RegistryReadinessVerifier(index, registry, query);
    }

    @Test
    void anIngestedRegistryLetsTheGatewayStart() {
        assertThatCode(verifier(true, MODEL, 18, MODEL)::verify).doesNotThrowAnyException();
    }

    @Test
    void aMissingIndexRefusesTheStart() {
        assertThatThrownBy(verifier(false, null, 0, MODEL)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void anIndexWithNoRegisteredAgentsRefusesTheStart() {
        assertThatThrownBy(verifier(true, MODEL, 0, MODEL)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no agents are registered");
    }

    @Test
    void anUnstampedIndexRefusesTheStartBecauseItsVectorSpaceCannotBeShown() {
        assertThatThrownBy(verifier(true, null, 18, MODEL)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no embedding-model stamp");
    }

    /**
     * The dangerous one. Both sides work; they simply do not mean the same thing. Cosine similarity
     * across two vector spaces returns confident numbers for unrelated comparisons.
     */
    @Test
    void anIndexBuiltByADifferentModelRefusesTheStart() {
        assertThatThrownBy(verifier(true, "hash:sha256-ngram:384", 18, MODEL)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confident nonsense");
    }
}
