package ai.meridian.gateway.registry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Validates a raw manifest JsonNode against the pinned agent-manifest.schema.json.
 * Rejects any manifest that doesn't satisfy the schema — invalid manifests are logged
 * and skipped at bootstrap, or rejected at the API boundary.
 */
@Component
public class ManifestValidator {

    private final JsonSchema schema;

    public ManifestValidator() {
        InputStream schemaStream = getClass().getResourceAsStream("/agent-manifest.schema.json");
        if (schemaStream == null) {
            throw new IllegalStateException(
                    "agent-manifest.schema.json not found in classpath. " +
                    "Ensure it is present in src/main/resources/.");
        }
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schemaStream);
    }

    /**
     * @throws ManifestValidationException if the manifest fails schema validation
     */
    public void validate(JsonNode manifest) {
        Set<ValidationMessage> errors = schema.validate(manifest);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new ManifestValidationException("Manifest schema validation failed: " + detail);
        }
    }

    public static class ManifestValidationException extends RuntimeException {
        public ManifestValidationException(String message) {
            super(message);
        }
    }
}
