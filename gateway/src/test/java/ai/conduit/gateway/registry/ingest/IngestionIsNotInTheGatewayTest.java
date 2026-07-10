package ai.conduit.gateway.registry.ingest;

import ai.conduit.gateway.testsupport.RedisContainerTest;

import ai.conduit.gateway.registry.embedding.ManifestEmbedder;
import ai.conduit.gateway.registry.embedding.QueryEmbedder;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.service.AgentRegistrar;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway must be structurally incapable of ingesting the registry.
 *
 * <p>Ingestion — validating manifests, introspecting live agents, embedding the agent corpus, and
 * writing the vector index — is the registry ingestion job's work. It used to run inside the gateway
 * on {@code ApplicationReadyEvent}, so every gateway start re-embedded the whole corpus, and any
 * process that booted a gateway context rewrote live routing data. A test run once replaced the
 * eighteen registered agents with the five bundled test manifests.
 *
 * <p>Moving the code was not enough: as long as the beans exist in the gateway, a future call site
 * can reach them. So the guarantee is that they are <em>absent</em>, and this test is what makes
 * that a guarantee rather than an intention.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "conduit.registry.readiness.enabled=false",
        "conduit.embedding.provider=hash"
})
class IngestionIsNotInTheGatewayTest extends RedisContainerTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theGatewayHasNoCorpusEmbedder() {
        assertThat(context.getBeanNamesForType(ManifestEmbedder.class))
                .as("the gateway must not be able to embed the agent corpus — that is the ingestor's job")
                .isEmpty();
    }

    @Test
    void theGatewayHasNoVectorIndexWriter() {
        assertThat(context.getBeanNamesForType(VectorIndexWriter.class))
                .as("the gateway must have no code path that can create, overwrite, or drop the routing index")
                .isEmpty();
    }

    @Test
    void theGatewayHasNoRegistrar() {
        assertThat(context.getBeanNamesForType(AgentRegistrar.class))
                .as("the gateway reads the registry; it does not write it")
                .isEmpty();
    }

    @Test
    void theGatewayDoesHaveTheReadSideItNeedsToRoute() {
        assertThat(context.getBeanNamesForType(AgentRegistry.class))
                .as("the read-only catalog is what the resolver and synthesizer use")
                .isNotEmpty();
        assertThat(context.getBeanNamesForType(QueryEmbedder.class))
                .as("the query path still embeds the user's question on every request")
                .isNotEmpty();
    }
}
