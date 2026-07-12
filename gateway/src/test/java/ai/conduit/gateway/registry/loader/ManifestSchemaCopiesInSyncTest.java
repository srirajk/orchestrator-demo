package ai.conduit.gateway.registry.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agent manifest schema exists in three places: the repo root, {@code registry/}, and the
 * gateway's classpath resources. Only the classpath copy is loaded by {@code ManifestValidator} at
 * runtime, so the other two can drift without any test noticing.
 *
 * <p>They did. The MCP Streamable HTTP migration added {@code "streamable"} to the {@code transport}
 * enum in {@code registry/} only. The unit suite stayed green and World B stayed clean, while the
 * running gateway rejected all seven asset-servicing manifests — "does not have a value in the
 * enumeration [sse, stdio]" — and booted with 11 loaded, 7 failed.
 *
 * <p>A schema that governs registration is a contract. Three copies of a contract is one contract
 * and two liabilities; this test makes the drift fail the build instead of the deployment.
 */
class ManifestSchemaCopiesInSyncTest {

    private static final String SCHEMA = "agent-manifest.schema.json";

    /** gateway/src/test/java/... → repo root is four parents up from the module dir. */
    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }

    private static JsonNode read(Path path) throws Exception {
        assertThat(Files.exists(path)).as("schema copy must exist: %s", path).isTrue();
        return new ObjectMapper().readTree(Files.readString(path));
    }

    @Test
    void allThreeCopiesOfTheManifestSchemaAreSemanticallyIdentical() throws Exception {
        Path root = repoRoot();
        JsonNode runtime = read(root.resolve("gateway/src/main/resources/" + SCHEMA));
        JsonNode registry = read(root.resolve("registry/" + SCHEMA));
        JsonNode top = read(root.resolve(SCHEMA));

        assertThat(registry)
                .as("registry/%s has drifted from the runtime copy the gateway actually validates against", SCHEMA)
                .isEqualTo(runtime);
        assertThat(top)
                .as("root %s has drifted from the runtime copy", SCHEMA)
                .isEqualTo(runtime);
    }

    /**
     * The domain + sub-domain schemas are now loaded and enforced by the gateway ({@code
     * DomainManifestStore}) from its classpath copy, while the registry service owns the authoritative
     * copy under {@code registry/}. Same contract-in-two-places liability as the agent schema — guard it.
     */
    @Test
    void bothCopiesOfTheDomainSchemasAreSemanticallyIdentical() throws Exception {
        Path root = repoRoot();
        for (String schema : new String[]{"domain-manifest.schema.json", "sub-domain-manifest.schema.json"}) {
            JsonNode runtime = read(root.resolve("gateway/src/main/resources/" + schema));
            JsonNode registry = read(root.resolve("registry/" + schema));
            assertThat(registry)
                    .as("registry/%s has drifted from the gateway classpath copy it is validated against", schema)
                    .isEqualTo(runtime);
        }
    }
}
