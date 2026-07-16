package ai.conduit.gateway.registry.readiness;

import ai.conduit.gateway.infrastructure.expression.ExpressionDialect;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndex;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Refuses to start the gateway unless the registry ingestion job has produced a routing index that
 * this gateway can actually search.
 *
 * <p>The gateway no longer builds the index, so it must not assume one is there. Three ways the
 * assumption fails, all of which were previously silent:
 *
 * <ul>
 *   <li><b>No index.</b> Every search returns nothing, every question falls through to a
 *       clarification, and the gateway reports itself healthy.</li>
 *   <li><b>No agents.</b> Same symptom, different cause: the index exists but ingestion never
 *       registered anything.</li>
 *   <li><b>A different model.</b> The index was built by one embedding model and this gateway
 *       embeds queries with another. Cosine similarity across two vector spaces is arithmetic
 *       without meaning: it returns confident numbers for unrelated comparisons, so routing degrades
 *       into plausible nonsense with no error anywhere.</li>
 * </ul>
 *
 * <p>A gateway that cannot route is not a degraded gateway; it is a gateway that answers the wrong
 * questions. Better to not start.
 */
@Component
@Profile("!registry")
@ConditionalOnProperty(name = "conduit.registry.readiness.enabled", havingValue = "true", matchIfMissing = true)
public class RegistryReadinessVerifier {

    private static final Logger log = LoggerFactory.getLogger(RegistryReadinessVerifier.class);

    private static final String REMEDY =
            "The registry service ingests the manifests and builds the index; wait for it to become "
            + "healthy (docker compose up -d registry-service) before starting the gateway.";

    private final VectorIndex vectorIndex;
    private final AgentRegistry registry;
    private final QueryEmbedder queryEmbedder;

    public RegistryReadinessVerifier(VectorIndex vectorIndex,
                                     AgentRegistry registry,
                                     QueryEmbedder queryEmbedder) {
        this.vectorIndex   = vectorIndex;
        this.registry      = registry;
        this.queryEmbedder = queryEmbedder;
    }

    @PostConstruct
    public void verify() {
        if (!vectorIndex.exists()) {
            throw new IllegalStateException(
                    "The routing vector index does not exist. The gateway does not build it. " + REMEDY);
        }

        long agents = registry.count();
        if (agents == 0) {
            throw new IllegalStateException(
                    "The routing vector index exists but no agents are registered. " + REMEDY);
        }

        String stamped = vectorIndex.stampedModelId();
        String current = queryEmbedder.modelId();
        if (stamped == null) {
            throw new IllegalStateException(
                    "The routing vector index carries no embedding-model stamp, so it cannot be shown "
                    + "to share a vector space with this gateway's query embedder (" + current + "). " + REMEDY);
        }
        if (!stamped.equals(current)) {
            throw new IllegalStateException(
                    "The routing vector index was built by embedding model '" + stamped
                    + "' but this gateway embeds queries with '" + current
                    + "'. Searching one vector space with another's vectors yields confident nonsense. "
                    + REMEDY);
        }

        verifyExprDialect();

        log.info("Registry ready — {} agent(s) indexed by model '{}', expression dialect '{}'",
                agents, stamped, ExpressionDialect.CURRENT);
    }

    /**
     * Refuse to start on a manifest-expression dialect skew. Manifest expressions are a language; if
     * the registry ingested them in one dialect and this gateway evaluates in another, every
     * expression mis-evaluates. Same failure mode as the embedding-model stamp, different axis.
     */
    private void verifyExprDialect() {
        String stampedDialect = vectorIndex.stampedExprDialect();
        String currentDialect = ExpressionDialect.CURRENT;
        if (stampedDialect == null) {
            throw new IllegalStateException(
                    "The routing index carries no expression-dialect stamp, so it cannot be shown to have "
                    + "been ingested in the dialect this gateway evaluates (" + currentDialect + "). " + REMEDY);
        }
        if (!stampedDialect.equals(currentDialect)) {
            throw new IllegalStateException(
                    "The routing index was ingested in expression dialect '" + stampedDialect
                    + "' but this gateway evaluates manifest expressions in '" + currentDialect
                    + "'. Every select/condition/map/figure expression would mis-evaluate. " + REMEDY);
        }
    }
}
