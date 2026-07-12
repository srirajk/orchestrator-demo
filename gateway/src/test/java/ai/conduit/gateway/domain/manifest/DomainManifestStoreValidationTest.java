package ai.conduit.gateway.domain.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain and sub-domain manifests are now schema-validated at load time, with the same fail-loud
 * contract agent manifests already have. Previously a malformed sub-domain file was silently dropped
 * ({@code loadSubDomains} caught + logged at debug), and domain files were validated nowhere — a typo
 * could remove a domain's entity types / CLARIFY gates / copy with no signal. This test proves a
 * malformed domain AND a malformed sub-domain each abort startup rather than being dropped.
 */
class DomainManifestStoreValidationTest {

    private static final String VALID_DOMAIN = """
        {
          "domain_id": "testdomain",
          "display_name": "Test Domain",
          "domain_context": "test coverage",
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
        """;

    private static final String VALID_SUBDOMAIN = """
        {
          "sub_domain_id": "test-sub",
          "display_name": "Test Sub",
          "parent_domain": "testdomain",
          "resource_scoped": false,
          "entity_types": [],
          "required_context": [],
          "agents": ["meridian.test.agent"]
        }
        """;

    private static DomainManifestStore storeAt(Path dir) {
        return new DomainManifestStore(new ObjectMapper(), new StandardEnvironment(),
                "file:" + dir.toAbsolutePath() + "/");
    }

    private static void write(Path dir, String relPath, String json) throws Exception {
        Path p = dir.resolve(relPath);
        Files.createDirectories(p.getParent());
        Files.writeString(p, json);
    }

    @Test
    void aValidDomainAndSubDomainLoadCleanly(@TempDir Path dir) throws Exception {
        write(dir, "domains/testdomain.json", VALID_DOMAIN);
        write(dir, "domains/testdomain/test-sub.json", VALID_SUBDOMAIN);

        DomainManifestStore store = storeAt(dir);
        assertThatCode(store::load).doesNotThrowAnyException();
        assertThat(store.getDomain("testdomain")).isPresent();
        assertThat(store.getSubDomain("test-sub")).isPresent();
    }

    @Test
    void aMalformedDomainManifestFailsLoud(@TempDir Path dir) throws Exception {
        // Missing the required memory_compaction block — schema-invalid.
        write(dir, "domains/testdomain.json", """
            { "domain_id": "testdomain", "display_name": "Test Domain" }
            """);

        DomainManifestStore store = storeAt(dir);
        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema validation");
    }

    @Test
    void aMalformedSubDomainManifestFailsLoud_notSilentlyDropped(@TempDir Path dir) throws Exception {
        write(dir, "domains/testdomain.json", VALID_DOMAIN);
        // Missing the required sub_domain_id — previously this was caught + dropped at debug.
        write(dir, "domains/testdomain/broken.json", """
            {
              "display_name": "Broken Sub",
              "parent_domain": "testdomain",
              "resource_scoped": false,
              "entity_types": [],
              "required_context": [],
              "agents": ["meridian.test.agent"]
            }
            """);

        DomainManifestStore store = storeAt(dir);
        assertThatThrownBy(store::load)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema validation");
    }
}
