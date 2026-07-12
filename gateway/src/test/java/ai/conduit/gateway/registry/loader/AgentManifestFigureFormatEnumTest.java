package ai.conduit.gateway.registry.loader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code io.produces[].figures[].format} is now an enum pinned to exactly the formats the gateway
 * renders (GroundedFigureRenderer) and gates on (GroundedFigureValidator). Before this, a typo'd
 * format passed ingestion, rendered via the plain fallthrough, and the currency/percent grounding
 * check silently degraded to "plain". This test proves a bogus value is rejected at manifest
 * validation — the same schema {@code ManifestValidator} enforces at runtime.
 */
class AgentManifestFigureFormatEnumTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The runtime schema copy the gateway actually validates against. */
    private static JsonSchema runtimeSchema() {
        InputStream in = AgentManifestFigureFormatEnumTest.class
                .getResourceAsStream("/agent-manifest.schema.json");
        assertThat(in).as("classpath agent-manifest.schema.json must exist").isNotNull();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    }

    /** gateway/src/test/java/... → repo root is one parent above the module dir. */
    private static Path repoRoot() {
        return Path.of(System.getProperty("user.dir")).getParent();
    }

    /** A real, schema-valid agent manifest that declares figures — the base for mutation. */
    private static JsonNode manifestWithFigures() throws Exception {
        Path p = repoRoot().resolve(
                "registry/manifests/asset-servicing/meridian.servicing.settlement_risk.json");
        return MAPPER.readTree(Files.readString(p));
    }

    private static void setEveryFigureFormat(JsonNode manifest, String format) {
        for (JsonNode produce : (ArrayNode) manifest.path("io").path("produces")) {
            JsonNode figures = produce.path("figures");
            if (figures.isArray()) {
                for (JsonNode figure : (ArrayNode) figures) {
                    ((ObjectNode) figure).put("format", format);
                }
            }
        }
    }

    @Test
    void aRealManifestWithSupportedFigureFormatsPassesValidation() throws Exception {
        JsonNode manifest = manifestWithFigures();
        assertThat(runtimeSchema().validate(manifest))
                .as("the unmodified fixture manifest must be schema-valid")
                .isEmpty();
    }

    @Test
    void aBogusFigureFormatIsRejectedAtValidation() throws Exception {
        JsonNode manifest = manifestWithFigures();
        setEveryFigureFormat(manifest, "dollars"); // not in the enum

        Set<ValidationMessage> errors = runtimeSchema().validate(manifest);

        assertThat(errors)
                .as("a figure format outside the enum must be rejected, not silently rendered as plain")
                .isNotEmpty();
        assertThat(errors.toString())
                .as("the rejection should be an enum violation on a figures[].format field")
                .contains("figures")
                .contains("format")
                .contains("enumeration");
    }

    @Test
    void everySupportedFormatIsAcceptedByTheEnum() throws Exception {
        for (String fmt : new String[]{
                "percent", "percent1", "percent2", "currency_usd", "count", "date", "plain"}) {
            JsonNode manifest = manifestWithFigures();
            setEveryFigureFormat(manifest, fmt);
            assertThat(runtimeSchema().validate(manifest))
                    .as("format '%s' is rendered by GroundedFigureRenderer and must pass the enum", fmt)
                    .isEmpty();
        }
    }
}
