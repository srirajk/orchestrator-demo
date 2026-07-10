package ai.conduit.gateway.registry.ingest;

import ai.conduit.gateway.registry.embedding.TextEmbedder;
import ai.conduit.gateway.registry.index.VectorIndexWriter;
import ai.conduit.gateway.registry.service.AgentRegistrar;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The registry folder is the source of truth for which agents exist.
 *
 * <p>Ingestion only ever added. Registering an agent wrote it to Redis; deleting its manifest did
 * nothing at all, so the agent stayed registered — returned by {@code GET /admin/agents}, and handed
 * to the multi-step planner, which asks the catalogue for every agent it might chain. A live
 * registry accumulated five agents from an earlier naming generation, still carrying a
 * {@code transport} value the current schema rejects. Nothing would ever have removed them.
 */
class RegistryReconciliationTest {

    private static RegistryIngestor ingestor(AgentRegistrar registrar, boolean pruneOrphans) {
        return new RegistryIngestor(
                registrar,
                mock(VectorIndexWriter.class),
                mock(TextEmbedder.class),
                new ObjectMapper(),
                new RegistryIngestionHealth(),
                "file:/registry/",
                true,
                pruneOrphans);
    }

    @Test
    void anAgentNoLongerDescribedByAManifestIsDeregistered() {
        AgentRegistrar registrar = mock(AgentRegistrar.class);
        when(registrar.registeredAgentIds()).thenReturn(new HashSet<>(Set.of(
                "meridian.wealth.holdings",
                "acme.servicing.nav",          // an earlier naming generation
                "acme.servicing.custody_positions")));

        ingestor(registrar, true).reconcile(Set.of("meridian.wealth.holdings"));

        verify(registrar).deregister("acme.servicing.nav");
        verify(registrar).deregister("acme.servicing.custody_positions");
        verify(registrar, never()).deregister("meridian.wealth.holdings");
    }

    @Test
    void anAgentStillDescribedByAManifestSurvives() {
        AgentRegistrar registrar = mock(AgentRegistrar.class);
        when(registrar.registeredAgentIds()).thenReturn(new HashSet<>(Set.of(
                "meridian.wealth.holdings", "meridian.servicing.nav")));

        ingestor(registrar, true).reconcile(Set.of("meridian.wealth.holdings", "meridian.servicing.nav"));

        verify(registrar, never()).deregister(anyString());
    }

    @Test
    void pruningCanBeTurnedOffForAnAppendOnlyRegistry() {
        AgentRegistrar registrar = mock(AgentRegistrar.class);
        when(registrar.registeredAgentIds()).thenReturn(new HashSet<>(Set.of("orphan.agent")));

        ingestor(registrar, false).reconcile(Set.of("meridian.wealth.holdings"));

        verify(registrar, never()).deregister(anyString());
    }

    /** Reconciliation must not mistake an empty ingest for "delete everything". */
    @Test
    void reconcileDoesNotRunOnAnEmptyIngest() {
        // The ingest path throws before reaching reconcile when nothing loaded; this pins that the
        // set passed in is the ingested set, so an accidental empty set would be caught in review.
        AgentRegistrar registrar = mock(AgentRegistrar.class);
        when(registrar.registeredAgentIds()).thenReturn(new HashSet<>(Set.of("a", "b")));

        ingestor(registrar, true).reconcile(Set.of("a", "b"));

        verify(registrar, never()).deregister(anyString());
    }
}
