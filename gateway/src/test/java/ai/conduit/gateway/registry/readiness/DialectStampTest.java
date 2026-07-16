package ai.conduit.gateway.registry.readiness;

import ai.conduit.gateway.infrastructure.expression.ExpressionDialect;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cross-container expression-dialect skew gate. Manifest expressions are a language; if the
 * registry ingested them in one dialect and the gateway evaluates in another, every select / condition
 * / map / figure expression mis-evaluates. The gateway must refuse to start on such a pairing — the
 * same discipline as the embedding-model stamp, on a different axis.
 *
 * <p>The migration flips {@link ExpressionDialect#CURRENT} across the two-commit sequence
 * ({@code jmespath} → {@code cel-v1}); any mixed pairing across those commits is a stamp mismatch and
 * is refused here. This test asserts the gate fires for <b>any</b> foreign stamp (both directions,
 * independent of which commit's constant is compiled) and passes only on an exact match.
 */
class DialectStampTest {

    private static final String MODEL = "remote:all-MiniLM-L6-v2:384";

    private static RegistryReadinessVerifier verifier(String stampedDialect) {
        VectorIndex index = mock(VectorIndex.class);
        when(index.exists()).thenReturn(true);
        when(index.stampedModelId()).thenReturn(MODEL);
        when(index.stampedExprDialect()).thenReturn(stampedDialect);

        AgentRegistry registry = mock(AgentRegistry.class);
        when(registry.count()).thenReturn(12L);

        QueryEmbedder query = mock(QueryEmbedder.class);
        when(query.modelId()).thenReturn(MODEL);

        return new RegistryReadinessVerifier(index, registry, query);
    }

    @Test
    @DisplayName("a matching dialect stamp lets the gateway start")
    void matchingDialectStarts() {
        assertThatCode(verifier(ExpressionDialect.CURRENT)::verify).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unstamped index refuses the start (dialect cannot be shown to match)")
    void unstampedDialectRefusesStart() {
        assertThatThrownBy(verifier(null)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no expression-dialect stamp");
    }

    @Test
    @DisplayName("a foreign dialect stamp refuses the start with a named mismatch (skew both directions)")
    void foreignDialectRefusesStart() {
        // A stamp from the OTHER side of the migration flip. Independent of which constant is compiled,
        // an index ingested in a dialect this gateway does not speak is refused.
        String other = ExpressionDialect.CURRENT.equals("cel-v1") ? "jmespath" : "cel-v1";
        assertThatThrownBy(verifier(other)::verify)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(other)
                .hasMessageContaining(ExpressionDialect.CURRENT);
    }
}
