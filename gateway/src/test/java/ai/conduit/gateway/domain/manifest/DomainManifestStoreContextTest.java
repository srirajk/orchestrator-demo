package ai.conduit.gateway.domain.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code domain_context} moves the classifier/synthesizer domain framing OUT of a hand-maintained env
 * string (with a wealth-flavored default) and INTO the domain manifests. The gateway now COMPOSES the
 * framing deterministically from the declared phrases — World B: no domain literal in Java, and no
 * onboarding edit outside a manifest. This verifies the join is deterministic (sorted by domain_id)
 * and falls back to a domain-NEUTRAL default when no domain declares a phrase.
 */
class DomainManifestStoreContextTest {

    @Test
    void composeReturnsTheNeutralDefaultWhenNoDomainDeclaresAContext() {
        assertThat(DomainManifestStore.composeDomainContext(List.of()))
                .isEqualTo("an enterprise data assistant");
    }

    @Test
    void composeJoinsASingleContextWithoutAConjunction() {
        assertThat(DomainManifestStore.composeDomainContext(List.of("client financial data")))
                .isEqualTo("an enterprise data assistant covering client financial data");
    }

    @Test
    void composeJoinsMultipleContextsDeterministically() {
        assertThat(DomainManifestStore.composeDomainContext(
                List.of("asset servicing operations", "insurance policies and claims", "client financial data")))
                .isEqualTo("an enterprise data assistant covering asset servicing operations, "
                        + "insurance policies and claims and client financial data");
    }

    @Test
    void composedDomainContextFromLoadedManifestsIsOrderedByDomainId() throws Exception {
        // Loads the real test-resource domain manifests (asset-servicing, insurance, wealth-management),
        // each of which declares a domain_context. The composed framing must be in domain_id order,
        // regardless of the HashMap iteration order the store stores them in.
        DomainManifestStore store = new DomainManifestStore(
                new ObjectMapper(), new StandardEnvironment(), "classpath:");
        store.load();

        assertThat(store.composedDomainContext())
                .isEqualTo("an enterprise data assistant covering asset servicing operations, "
                        + "insurance policies and claims and client financial data");
    }

    @Test
    void composedDomainContextFallsBackToNeutralWhenNoDomainDeclaresOne(@TempDir Path dir) throws Exception {
        // A schema-valid domain with NO domain_context → the composed framing is the neutral default.
        Path p = dir.resolve("domains/plain.json");
        Files.createDirectories(p.getParent());
        Files.writeString(p, """
            {
              "domain_id": "plain",
              "display_name": "Plain",
              "memory_compaction": {
                "envelope_version": "context-envelope.v1",
                "must_preserve": ["domain"],
                "summary_policy": {
                  "owner": "memory-service",
                  "max_summary_tokens": 600,
                  "ledger_retention_days": 90,
                  "include_runtime_events": ["gateway.response_completed"]
                }
              }
            }
            """);

        DomainManifestStore store = new DomainManifestStore(
                new ObjectMapper(), new StandardEnvironment(), "file:" + dir.toAbsolutePath() + "/");
        store.load();

        assertThat(store.composedDomainContext()).isEqualTo("an enterprise data assistant");
    }
}
