package com.openwolf.iam.policystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Production manifest grounding for Axiom Policy Studio.
 *
 * <p>The base ceiling is typed here because it is the immutable Cerbos resource ceiling, not something
 * recoverable from a rendered tenant bundle. The vocabulary is enriched from registry domain/entity
 * manifests and agent manifests, mirroring the gateway's discipline of compiling prompts from manifest
 * facts instead of Java domain literals.
 */
@Component
public class ManifestBackedStudioGroundingProvider implements StudioGroundingProvider {

    private final ObjectMapper objectMapper;
    private final CanonicalPolicyWriter writer;
    private final Path registryRoot;

    public ManifestBackedStudioGroundingProvider(
            ObjectMapper objectMapper,
            CanonicalPolicyWriter writer,
            @Value("${iam.policy-studio.registry-root:registry}") String registryRoot) {
        this.objectMapper = objectMapper;
        this.writer = writer;
        Path configured = Path.of(registryRoot);
        this.registryRoot = Files.isDirectory(configured) ? configured : Path.of("..").resolve(registryRoot).normalize();
    }

    @Override
    public StudioGroundingSnapshot snapshot(String tenantId, String resourceKind) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be set");
        }
        if (!"agent".equals(resourceKind)) {
            throw new IllegalArgumentException("no manifest-grounded studio resource is configured for '" + resourceKind + "'");
        }
        ManifestFacts facts = loadFacts();
        ManifestVocabulary vocabulary = new ManifestVocabulary(
                "agent",
                Set.of("invoke", "invoke_membership", "register", "deregister"),
                facts.classifications(),
                facts.attributes(),
                Set.of("platform_admin", "domain_admin", "chat_user", "relationship_manager", "conduit_admin"),
                Set.of("business_derived_roles"));
        BaseCeiling ceiling = agentCeiling();
        ConsequenceFixtureMatrix matrix = matrixFor(tenantId, ceiling);
        return new StudioGroundingSnapshot(
                tenantId,
                vocabulary,
                ceiling,
                matrix,
                BundleSnapshot.of(null, ceiling, writer),
                facts.manifestRefs());
    }

    private BaseCeiling agentCeiling() {
        return new BaseCeiling(
                "agent",
                Set.of(
                        new BaseCeiling.Tuple("invoke", "platform_admin"),
                        new BaseCeiling.Tuple("invoke_membership", "platform_admin"),
                        new BaseCeiling.Tuple("register", "platform_admin"),
                        new BaseCeiling.Tuple("deregister", "platform_admin"),
                        new BaseCeiling.Tuple("invoke", "domain_admin"),
                        new BaseCeiling.Tuple("invoke_membership", "domain_admin"),
                        new BaseCeiling.Tuple("register", "domain_admin"),
                        new BaseCeiling.Tuple("deregister", "domain_admin"),
                        new BaseCeiling.Tuple("invoke", "chat_user"),
                        new BaseCeiling.Tuple("invoke", "relationship_manager"),
                        new BaseCeiling.Tuple("invoke_membership", "chat_user"),
                        new BaseCeiling.Tuple("invoke_membership", "relationship_manager")),
                true,
                Set.of("agent@"));
    }

    private ConsequenceFixtureMatrix matrixFor(String tenantId, BaseCeiling ceiling) {
        List<FixtureCell> cells = ceiling.tuples().stream()
                .sorted((a, b) -> (a.role() + ":" + a.action()).compareTo(b.role() + ":" + b.action()))
                .map(t -> new FixtureCell(
                        Set.of(t.role()),
                        tenantId,
                        java.util.Map.of(),
                        tenantId,
                        java.util.Map.of(),
                        t.action(),
                        t.role() + "-" + t.action() + "-same-tenant"))
                .toList();
        return ConsequenceFixtureMatrix.of(cells);
    }

    private ManifestFacts loadFacts() {
        LinkedHashSet<String> classifications = new LinkedHashSet<>();
        LinkedHashSet<String> attributes = new LinkedHashSet<>(List.of(
                "domain", "audience", "access_mode", "data_classification",
                "segments", "admin_domains", "domains", "tenant_id"));
        ArrayList<String> refs = new ArrayList<>();

        if (!Files.isDirectory(registryRoot)) {
            throw new IllegalStateException("registry root '" + registryRoot + "' is missing");
        }
        readJsonFiles(registryRoot.resolve("domains"), refs, node -> {
            addEntityAttributes(node, attributes);
            if (node.hasNonNull("domain_context")) {
                attributes.add("domain_context");
            }
        });
        readJsonFiles(registryRoot.resolve("manifests"), refs, node -> {
            JsonNode constraints = node.path("constraints");
            if (constraints.hasNonNull("data_classification")) {
                classifications.add(constraints.path("data_classification").asText());
            }
            if (constraints.hasNonNull("access_mode")) {
                attributes.add("access_mode");
            }
            addIoEntities(node.path("io").path("consumes"), attributes);
            addIoEntities(node.path("io").path("produces"), attributes);
        });

        if (classifications.isEmpty()) {
            throw new IllegalStateException("no data classifications were found under '" + registryRoot.resolve("manifests") + "'");
        }
        return new ManifestFacts(Set.copyOf(classifications), Set.copyOf(attributes), List.copyOf(refs));
    }

    private void readJsonFiles(Path dir, List<String> refs, JsonConsumer consumer) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("manifest directory '" + dir + "' is missing");
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            JsonNode node = objectMapper.readTree(path.toFile());
                            refs.add(registryRoot.relativize(path).toString().replace('\\', '/')
                                    + "#" + StudioHashing.sha256Hex(Files.readString(path)).substring(0, 16));
                            consumer.accept(node);
                        } catch (IOException e) {
                            throw new IllegalStateException("failed to read manifest '" + path + "'", e);
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("failed to scan manifest directory '" + dir + "'", e);
        }
    }

    private void addEntityAttributes(JsonNode node, Set<String> attributes) {
        JsonNode entityTypes = node.path("entity_types");
        if (entityTypes.isArray()) {
            for (JsonNode entity : entityTypes) {
                if (entity.hasNonNull("key")) {
                    attributes.add(entity.path("key").asText());
                }
                if (entity.hasNonNull("extract_as")) {
                    attributes.add(entity.path("extract_as").asText());
                }
            }
        }
    }

    private void addIoEntities(JsonNode entries, Set<String> attributes) {
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                if (entry.hasNonNull("entity")) {
                    attributes.add(entry.path("entity").asText());
                }
                if (entry.hasNonNull("name")) {
                    attributes.add(entry.path("name").asText());
                }
            }
        }
    }

    private record ManifestFacts(Set<String> classifications, Set<String> attributes, List<String> manifestRefs) {}

    @FunctionalInterface
    private interface JsonConsumer {
        void accept(JsonNode node);
    }
}
