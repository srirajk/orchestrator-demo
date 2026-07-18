package com.openwolf.iam.policystudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.policystudio.lifecycle.BundleCanonicalizer;
import com.openwolf.iam.policystudio.lifecycle.BundleContentReader;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundleRecord;
import com.openwolf.iam.policystudio.lifecycle.PolicyBundleRepository;
import com.openwolf.iam.tenancy.ActiveTenantDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Production manifest grounding for Axiom Policy Studio.
 *
 * <p>S5 (lifecycle-spine hardening): the grounding vocabulary — resource kind, actions, roles, approved
 * imports — and the immutable base ceiling are derived from the <b>tenant-effective Cerbos base policy
 * bundle</b> via {@link BaseBundleGrounding}, not from Java literals. Data classifications and condition
 * attributes come from the registry domain/agent manifests (the same facts the gateway compiles prompts
 * from). Adding an action, role, or ceiling tuple is therefore a policy edit; adding a classification or
 * entity attribute is a manifest edit — never a code edit.
 */
@Component
public class ManifestBackedStudioGroundingProvider implements StudioGroundingProvider {

    private static final Logger log = LoggerFactory.getLogger(ManifestBackedStudioGroundingProvider.class);

    private final ObjectMapper objectMapper;
    private final CanonicalPolicyWriter writer;
    private final PolicyYamlParser parser;
    private final ActiveTenantDirectory directory;
    private final PolicyBundleRepository bundles;
    private final Path registryRoot;
    private final Path baseBundleDir;
    private final Path tenantDeploymentRoot;

    public ManifestBackedStudioGroundingProvider(
            ObjectMapper objectMapper,
            CanonicalPolicyWriter writer,
            PolicyYamlParser parser,
            ActiveTenantDirectory directory,
            PolicyBundleRepository bundles,
            @Value("${iam.policy-studio.registry-root:registry}") String registryRoot,
            @Value("${iam.policy-studio.base-bundle-dir:infra/cerbos/policies}") String baseBundleDir,
            @Value("${iam.policy-studio.tenant-deployment-root:infra/cerbos/tenants}") String tenantDeploymentRoot) {
        this.objectMapper = objectMapper;
        this.writer = writer;
        this.parser = parser;
        this.directory = directory;
        this.bundles = bundles;
        this.registryRoot = resolveExisting(registryRoot);
        this.baseBundleDir = resolveExisting(baseBundleDir);
        this.tenantDeploymentRoot = resolveExisting(tenantDeploymentRoot);
    }

    /** Resolve a configured relative path from the process cwd, with a {@code ..} fallback (module-cwd runs). */
    private static Path resolveExisting(String configured) {
        Path direct = Path.of(configured);
        return Files.isDirectory(direct) ? direct : Path.of("..").resolve(configured).normalize();
    }

    @Override
    public StudioGroundingSnapshot snapshot(String tenantId, String resourceKind) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must be set");
        }
        // Vocabulary + ceiling are derived from the base Cerbos policy bundle for this kind (no Java literals);
        // a kind with no root policy in the base bundle is not a manifest-grounded studio resource.
        BaseBundleGrounding.Facts base = BaseBundleGrounding.read(baseBundleDir, resourceKind);
        ManifestFacts facts = loadFacts();
        ManifestVocabulary vocabulary = new ManifestVocabulary(
                base.resourceKind(),
                base.actions(),
                facts.classifications(),
                facts.attributes(),
                base.roles(),
                base.approvedImports());
        BaseCeiling ceiling = base.ceiling();
        ConsequenceFixtureMatrix matrix = matrixFor(tenantId, base, facts);
        return new StudioGroundingSnapshot(
                tenantId,
                vocabulary,
                ceiling,
                matrix,
                currentBundle(tenantId, ceiling),
                facts.manifestRefs());
    }

    /**
     * The tenant's <b>actual current bundle</b> as the review baseline (S3, Bug A). Once a tenant has a
     * promoted active version, a second promotion must review against that live bundle — not a synthetic
     * base-only snapshot — or the consequence diff is wrong and the CAS from the real active id fails.
     *
     * <p>Resolution: read the live pointer from the {@link ActiveTenantDirectory}; if it names a promoted
     * bundle in the immutable {@link PolicyBundleRepository store}, materialise that record's tenant
     * restriction child as {@code current} (its {@code bundleId} is exactly the live pointer, so the
     * promotion CAS aligns). A tenant still on the inherited base — no pointer, or a pointer that is the
     * base baseline registered by {@code StudioBaselineActivationService} (no bundle record) — falls back
     * to the deterministic base-only snapshot, whose id reproduces that baseline exactly.
     */
    private BundleSnapshot currentBundle(String tenantId, BaseCeiling ceiling) {
        Optional<String> activeVersion = directory.find(tenantId);
        if (activeVersion.isPresent()) {
            Optional<PolicyBundleRecord> record = bundles.findById(activeVersion.get());
            if (record.isPresent()) {
                return materializeCurrent(activeVersion.get(), record.get(), ceiling);
            }
            log.debug("Tenant '{}' active version '{}' has no bundle record — treating as inherited base baseline",
                    tenantId, activeVersion.get());
        }
        // Base-only, deterministic: reproduces any base baseline id already registered for the tenant.
        return BundleSnapshot.of(null, ceiling, writer);
    }

    /**
     * Materialise the {@code current} snapshot from a promoted bundle's immutable record. The recovered
     * tenant child is stored in version-sentinel form; it is lowered back to the {@code default} version the
     * consequence-diff PDP probe evaluates at, then parsed into the typed IR. The snapshot id is pinned to
     * the live active version so the promotion compare-and-set observes an exact match.
     */
    private BundleSnapshot materializeCurrent(String activeVersion, PolicyBundleRecord record, BaseCeiling ceiling) {
        String canonicalContent = record.getCanonicalContent();
        Optional<String> childYaml = BundleContentReader.tenantChildYaml(
                canonicalContent, ceiling.resourceKind(), record.getTenantId());
        if (childYaml.isEmpty()) {
            // A promoted bundle with no tenant restriction child inherits the base ceiling directly.
            return new BundleSnapshot(activeVersion, null, ceiling, canonicalContent);
        }
        String evaluable = childYaml.get().replace(BundleCanonicalizer.BUNDLE_VERSION_SENTINEL, "default");
        PolicyIR childIr = parser.parse(evaluable);
        return new BundleSnapshot(activeVersion, childIr, ceiling, canonicalContent);
    }

    // ── consequence fixture matrix ────────────────────────────────────────────────────────────────

    /**
     * The consequence-diff evaluation matrix (S5). It is <b>more than same-tenant positives with empty
     * attributes</b>: for the condition-gated front-door role it also carries, per relevant action, an
     * <b>attribute-removed</b>, a <b>cross-tenant</b>, a <b>missing-attribute</b>, and a
     * <b>wrong-segment</b> cell. So the real-PDP consequence diff evaluates those negative classes and the
     * over-permission alarm can catch a candidate that widens any of them (a base-only current denies them;
     * a structurally-broken candidate that allowed one would surface as a DENY→ALLOW delta).
     *
     * <p>Every attribute value is manifest/config-derived: the domain, audience, access-mode and
     * data-classification come from an agent manifest that declares them; the segment key and the
     * classification ladder come from the tenant deployment's domain→segment map. Nothing here is a domain
     * literal in Java.
     */
    private ConsequenceFixtureMatrix matrixFor(String tenantId, BaseBundleGrounding.Facts base, ManifestFacts facts) {
        List<FixtureCell> cells = new ArrayList<>();

        // (1) same-tenant positives — one per base-allowed (action, role) tuple (unchanged baseline).
        base.ceiling().tuples().stream()
                .sorted((a, b) -> (a.role() + ":" + a.action()).compareTo(b.role() + ":" + b.action()))
                .forEach(t -> cells.add(new FixtureCell(
                        Set.of(t.role()), tenantId, Map.of(), tenantId, Map.of(), t.action(),
                        t.role() + "-" + t.action() + "-same-tenant")));

        // (2) attribute negatives for the condition-gated front-door role over a real segment agent.
        cells.addAll(negativeClassCells(tenantId, base, facts));

        return ConsequenceFixtureMatrix.of(cells);
    }

    /**
     * Build the positive + four negative-class cells (attribute-removed, cross-tenant, missing-attribute,
     * wrong-segment) for the front-door role, anchored on a real segment-audience agent and the deployment's
     * domain→segment map. If a front-door role exists, every negative class is mandatory: missing or
     * malformed tenant deployment grounding fails closed rather than silently reviewing a positive-only
     * matrix.
     */
    private List<FixtureCell> negativeClassCells(String tenantId, BaseBundleGrounding.Facts base, ManifestFacts facts) {
        if (base.frontDoorRoles().isEmpty()) {
            return List.of();
        }
        DeploymentSegments segments = loadDeploymentSegments(tenantId);

        // A segment-audience agent whose domain is in the deployment map — its declared attributes are the
        // positive; the negatives perturb exactly one dimension each.
        Optional<AgentProfile> profileOpt = facts.profiles().stream()
                .filter(p -> segments.domainToSegment().containsKey(p.domain()))
                .filter(p -> p.audience() != null && !"enterprise".equals(p.audience()))
                .filter(ManifestBackedStudioGroundingProvider::hasRequiredResourceAttributes)
                .findFirst();
        if (profileOpt.isEmpty()) {
            throw negativeGroundingFailure(tenantId,
                    "no segment-audience agent manifest with domain, audience, access_mode, and data_classification "
                            + "matches the tenant domain-segment map");
        }
        AgentProfile profile = profileOpt.get();

        String domain = profile.domain();
        String segment = segments.domainToSegment().get(domain);
        String topTier = segments.topTier();                            // holds >= any resource classification
        String otherSegment = segments.domainToSegment().values().stream()
                .filter(s -> !s.equals(segment)).sorted().findFirst()
                .orElseThrow(() -> negativeGroundingFailure(tenantId,
                        "domain-segment map must contain at least two distinct segments for wrong-segment coverage"));
        String crossTenant = tenantId + "__other";

        Map<String, Object> resourceAttrs = new LinkedHashMap<>();
        putIfPresent(resourceAttrs, "domain", domain);
        putIfPresent(resourceAttrs, "audience", profile.audience());
        putIfPresent(resourceAttrs, "access_mode", profile.accessMode());
        putIfPresent(resourceAttrs, "data_classification", profile.classification());
        Map<String, Object> memberPrincipal = Map.of("segments", Map.of(segment, topTier));

        List<FixtureCell> out = new ArrayList<>();
        base.frontDoorRoles().stream().sorted().forEach(role -> {
            List<String> actions = ceilingActions(base.ceiling(), role);
            if (actions.isEmpty()) {
                throw negativeGroundingFailure(tenantId,
                        "front-door role '" + role + "' has no ceiling action");
            }
            actions.forEach(action -> {
                String label = role + "-" + action + "-" + domain;
                // positive: a member of the mapped segment at the ceiling tier — allowed.
                out.add(new FixtureCell(Set.of(role), tenantId, memberPrincipal, tenantId, resourceAttrs,
                        action, label + "::segment_positive"));
                // attribute-removed: the principal's segment membership attribute is removed — must deny.
                out.add(new FixtureCell(Set.of(role), tenantId, Map.of(), tenantId, resourceAttrs,
                        action, label + "::attribute_removed"));
                // cross-tenant: the resource is homed in a different tenant — must deny.
                out.add(new FixtureCell(Set.of(role), tenantId, memberPrincipal, crossTenant, resourceAttrs,
                        action, label + "::cross_tenant"));
                // missing-attribute: the classification guard attribute is omitted — must deny.
                out.add(new FixtureCell(Set.of(role), tenantId, memberPrincipal, tenantId,
                        withoutKey(resourceAttrs, "data_classification"), action, label + "::missing_attribute"));
                // wrong-segment: membership does not map to the resource domain — must deny.
                out.add(new FixtureCell(Set.of(role), tenantId, Map.of("segments", Map.of(otherSegment, topTier)),
                        tenantId, resourceAttrs, action, label + "::wrong_segment"));
            });
        });
        return out;
    }

    private static List<String> ceilingActions(BaseCeiling ceiling, String role) {
        return ceiling.tuples().stream()
                .filter(t -> t.role().equals(role))
                .map(BaseCeiling.Tuple::action)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean hasRequiredResourceAttributes(AgentProfile profile) {
        return isPresent(profile.domain()) && isPresent(profile.audience())
                && isPresent(profile.accessMode()) && isPresent(profile.classification());
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static IllegalStateException negativeGroundingFailure(String tenantId, String detail) {
        return new IllegalStateException("cannot ground mandatory negative consequence fixtures for tenant '"
                + tenantId + "': " + detail);
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    private static Map<String, Object> withoutKey(Map<String, Object> attrs, String key) {
        Map<String, Object> copy = new LinkedHashMap<>(attrs);
        copy.remove(key);
        return copy;
    }

    // ── deployment domain→segment map (tenant deployment config) ──────────────────────────────────

    /** The deployment's domain→segment mapping + classification ladder, read from tenant deployment config. */
    private record DeploymentSegments(Map<String, String> domainToSegment, List<String> ladder) {
        String topTier() {
            return ladder.get(ladder.size() - 1);
        }
    }

    private DeploymentSegments loadDeploymentSegments(String tenantId) {
        Path tenantDir = tenantDeploymentRoot.resolve(tenantId).normalize();
        if (!tenantDir.startsWith(tenantDeploymentRoot.normalize())) {
            throw negativeGroundingFailure(tenantId, "tenant id escapes the deployment root");
        }
        Path mapFile = tenantDir.resolve("domain-segment-map.yaml");
        if (!Files.isRegularFile(mapFile)) {
            throw negativeGroundingFailure(tenantId, "domain-segment map is missing at '" + mapFile + "'");
        }
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(mapFile));
            if (!(loaded instanceof Map<?, ?> doc)) {
                throw negativeGroundingFailure(tenantId, "domain-segment map is not a YAML object");
            }
            if (!tenantId.equals(String.valueOf(doc.get("tenant")))) {
                throw negativeGroundingFailure(tenantId,
                        "domain-segment map tenant field does not exactly match the authenticated tenant");
            }
            Map<String, String> mapping = new LinkedHashMap<>();
            Object raw = doc.get("domain_segment_map");
            if (raw instanceof Map<?, ?> m) {
                m.forEach((k, v) -> mapping.put(String.valueOf(k), String.valueOf(v)));
            }
            List<String> ladder = new ArrayList<>();
            Object rawLadder = doc.get("classification_ladder");
            if (rawLadder instanceof List<?> l) {
                l.forEach(t -> ladder.add(String.valueOf(t)));
            }
            if (mapping.isEmpty()) {
                throw negativeGroundingFailure(tenantId, "domain_segment_map is empty");
            }
            if (mapping.entrySet().stream().anyMatch(e -> !isPresent(e.getKey()) || !isPresent(e.getValue()))) {
                throw negativeGroundingFailure(tenantId, "domain_segment_map contains a blank domain or segment");
            }
            if (ladder.isEmpty()) {
                throw negativeGroundingFailure(tenantId, "classification_ladder is empty");
            }
            if (ladder.stream().anyMatch(tier -> !isPresent(tier))) {
                throw negativeGroundingFailure(tenantId, "classification_ladder contains a blank tier");
            }
            return new DeploymentSegments(Map.copyOf(mapping), List.copyOf(ladder));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read domain-segment map '" + mapFile + "'", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("failed to parse domain-segment map '" + mapFile + "'", e);
        }
    }

    // ── registry manifest facts (classifications, attributes, agent profiles) ─────────────────────

    private ManifestFacts loadFacts() {
        LinkedHashSet<String> classifications = new LinkedHashSet<>();
        LinkedHashSet<String> attributes = new LinkedHashSet<>(List.of(
                "domain", "audience", "access_mode", "data_classification",
                "segments", "admin_domains", "domains", "tenant_id"));
        ArrayList<String> refs = new ArrayList<>();
        ArrayList<AgentProfile> profiles = new ArrayList<>();

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
            if (node.hasNonNull("domain")) {
                profiles.add(new AgentProfile(
                        node.path("domain").asText(),
                        node.path("audience").asText(null),
                        constraints.path("access_mode").asText(null),
                        constraints.path("data_classification").asText(null)));
            }
        });

        if (classifications.isEmpty()) {
            throw new IllegalStateException("no data classifications were found under '" + registryRoot.resolve("manifests") + "'");
        }
        // Deterministic profile ordering so the derived matrix is reproducible across machines.
        profiles.sort((a, b) -> {
            int d = safe(a.domain()).compareTo(safe(b.domain()));
            if (d != 0) {
                return d;
            }
            return safe(a.classification()).compareTo(safe(b.classification()));
        });
        return new ManifestFacts(Set.copyOf(classifications), Set.copyOf(attributes), List.copyOf(refs),
                List.copyOf(profiles));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
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

    /** A manifest-declared agent's authorization-relevant attributes (for building attribute-bearing cells). */
    private record AgentProfile(String domain, String audience, String accessMode, String classification) {}

    private record ManifestFacts(Set<String> classifications, Set<String> attributes, List<String> manifestRefs,
                                 List<AgentProfile> profiles) {}

    @FunctionalInterface
    private interface JsonConsumer {
        void accept(JsonNode node);
    }
}
