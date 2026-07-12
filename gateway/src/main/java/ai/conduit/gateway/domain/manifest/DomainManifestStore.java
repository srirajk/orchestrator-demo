package ai.conduit.gateway.domain.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class DomainManifestStore {

    private static final Logger log = LoggerFactory.getLogger(DomainManifestStore.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper mapper;
    private final Environment env;
    private final String registryLocation;
    // Domain + sub-domain manifests are schema-validated at load time exactly as agent manifests are
    // (ManifestValidator). A malformed/unschema'd manifest FAILS STARTUP instead of being silently
    // dropped (which would remove a domain's entity types / CLARIFY gates / copy with no signal).
    // The classpath copies here are the authoritative runtime schemas, kept byte-identical to the
    // registry/ copies by DomainSchemaCopiesInSyncTest.
    private final JsonSchema domainSchema;
    private final JsonSchema subDomainSchema;
    private final Map<String, DomainManifest>    domains          = new HashMap<>();
    private final Map<String, SubDomainManifest> subDomains       = new HashMap<>();
    // Reverse map: agentId → subDomainId — built from sub-domain agents[] lists at load time.
    // Used as fallback when the AgentManifest in Redis lacks sub_domain (Jackson/record issue).
    private final Map<String, String>            agentToSubDomain = new HashMap<>();
    // Compiled id_pattern cache, populated at manifest-load time so the request path never
    // re-compiles a manifest regex per call (extraction/routing hit these on every turn).
    private final Map<String, Pattern>           idPatternCache   = new java.util.concurrent.ConcurrentHashMap<>();

    public DomainManifestStore(ObjectMapper mapper, Environment env,
                               @Value("${conduit.registry.location:classpath:}") String registryLocation) {
        this.mapper = mapper;
        this.env = env;
        this.registryLocation = registryLocation;
        this.domainSchema = loadSchema("/domain-manifest.schema.json");
        this.subDomainSchema = loadSchema("/sub-domain-manifest.schema.json");
    }

    /** Loads a JSON schema from the gateway classpath, failing startup loudly if it is missing. */
    private static JsonSchema loadSchema(String classpathResource) {
        InputStream in = DomainManifestStore.class.getResourceAsStream(classpathResource);
        if (in == null) {
            throw new IllegalStateException(classpathResource + " not found on classpath. Ensure it "
                    + "is present in gateway/src/main/resources/ (kept in sync with registry/).");
        }
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
    }

    /**
     * Validates a raw manifest node against its schema, throwing (→ failing startup) on any error.
     * Mirrors {@code ManifestValidator} so domain/sub-domain manifests get the same fail-loud contract
     * agent manifests already have.
     */
    private void validateSchema(JsonSchema schema, JsonNode node, String kind, String filename) {
        Set<ValidationMessage> errors = schema.validate(node);
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.joining("; "));
            throw new IllegalStateException(
                    kind + " manifest '" + filename + "' failed schema validation: " + detail);
        }
    }

    @PostConstruct
    public void load() throws IOException {
        loadDomains();
        loadSubDomains();
        validate();
        compileIdPatterns();
        log.info("DomainManifestStore loaded: {} domains, {} sub-domains, {} id_pattern(s)",
                domains.size(), subDomains.size(), idPatternCache.size());
    }

    /**
     * Pre-compiles every declared {@code id_pattern} once at load time. The request path then reads
     * a ready {@link Pattern} from the cache via {@link #compiledIdPattern(String)} instead of
     * calling {@link Pattern#compile(String)} (or {@link String#matches(String)}) per turn.
     */
    private void compileIdPatterns() {
        for (SubDomainManifest sd : subDomains.values()) {
            if (sd.entityTypes() == null) continue;
            for (EntityType et : sd.entityTypes()) {
                String p = et.idPattern();
                if (p != null && !p.isBlank()) idPatternCache.computeIfAbsent(p, Pattern::compile);
            }
        }
    }

    /**
     * The compiled form of a manifest {@code id_pattern}, cached at load time. Returns {@code null}
     * for a null/blank pattern. Compiles-and-caches on the (rare) first sight of a pattern not seen
     * at load, so it is always safe to call and never compiles the same regex twice.
     */
    public Pattern compiledIdPattern(String idPattern) {
        if (idPattern == null || idPattern.isBlank()) return null;
        return idPatternCache.computeIfAbsent(idPattern, Pattern::compile);
    }

    private void loadDomains() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(registryLocation + "domains/*.json");
        } catch (IOException e) {
            log.warn("No domain manifests found at {}domains/*.json", registryLocation);
            return;
        }
        for (Resource r : resources) {
            JsonNode node;
            try {
                node = mapper.readTree(r.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
            // FAIL LOUD: an unschema'd/malformed domain manifest aborts startup, never silently dropped.
            validateSchema(domainSchema, node, "Domain", r.getFilename());
            DomainManifest d;
            try {
                d = mapper.treeToValue(node, DomainManifest.class);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to parse domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
            // Resolve environment variable placeholders in Coverage URL fields
            d = resolveEnvVarsInCoverage(d);
            domains.put(d.domainId(), d);
            log.debug("Loaded domain: {}", d.domainId());
        }
    }

    /**
     * Resolves ${VAR_NAME} placeholders in all Coverage URL fields using Spring Environment.
     */
    private DomainManifest resolveEnvVarsInCoverage(DomainManifest d) {
        if (d.coverage() == null) return d;
        DomainManifest.Coverage c = d.coverage();
        String discoverUrl = resolveEnvVars(c.discoverUrl());
        String checkUrl    = resolveEnvVars(c.checkUrl());
        String resolveUrl  = resolveEnvVars(c.resolveUrl());
        if (equalsAll(c.discoverUrl(), discoverUrl, c.checkUrl(), checkUrl, c.resolveUrl(), resolveUrl)) {
            return d; // nothing changed
        }
        DomainManifest.Coverage resolved = new DomainManifest.Coverage(discoverUrl, checkUrl, resolveUrl, c.cacheTtlSeconds());
        return new DomainManifest(d.domainId(), d.displayName(), resolved, d.memoryCompaction(),
                d.clarifyStyle(), d.clarifyTone(), d.domainContext());
    }

    private String resolveEnvVars(String template) {
        if (template == null) return null;
        Matcher m = ENV_VAR_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String varName = m.group(1);
            String value = env.getProperty(varName);
            // Always use quoteReplacement — the fallback m.group(0) contains ${...}
            // which Matcher.appendReplacement would interpret as a named back-reference.
            String replacement = value != null ? value : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private boolean equalsAll(String a1, String a2, String b1, String b2, String c1, String c2) {
        return java.util.Objects.equals(a1, a2) && java.util.Objects.equals(b1, b2) && java.util.Objects.equals(c1, c2);
    }

    private void loadSubDomains() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            // Exactly one directory deep: registry/domains/<domain>/<sub-domain>.json. This
            // deterministically SEPARATES sub-domain files from the domain-level files matched by
            // loadDomains' domains/*.json, so each file is validated against the RIGHT schema. The
            // previous domains/**/*.json matched BOTH and silently skipped the domain-level files —
            // which also meant a malformed sub-domain file was silently dropped (the bug fixed here).
            resources = resolver.getResources(registryLocation + "domains/*/*.json");
        } catch (IOException e) {
            log.warn("No sub-domain manifests found");
            return;
        }
        for (Resource r : resources) {
            JsonNode node;
            try {
                node = mapper.readTree(r.getInputStream());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Failed to read sub-domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
            // FAIL LOUD: a malformed sub-domain manifest silently dropped here would remove a domain's
            // entity types / CLARIFY gates / copy without any signal — abort startup instead.
            validateSchema(subDomainSchema, node, "Sub-domain", r.getFilename());
            SubDomainManifest sd;
            try {
                sd = mapper.treeToValue(node, SubDomainManifest.class);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to parse sub-domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
            subDomains.put(sd.subDomainId(), sd);
            log.debug("Loaded sub-domain: {} (parent: {}) resourceScoped={}",
                sd.subDomainId(), sd.parentDomain(), sd.resourceScoped());
            // Build reverse map so getEffective() works even when AgentManifest.subDomain() is null.
            if (sd.agents() != null) {
                for (String aid : sd.agents()) {
                    agentToSubDomain.put(aid, sd.subDomainId());
                }
            }
        }
    }

    private void validate() {
        for (SubDomainManifest sd : subDomains.values()) {
            if (sd.parentDomain() != null && !domains.containsKey(sd.parentDomain())) {
                throw new IllegalStateException(
                    "Sub-domain '" + sd.subDomainId() + "' references unknown parent domain '" + sd.parentDomain() + "'");
            }
            // FAIL FAST: resource-scoped sub-domain must have a parent domain with coverage.discoverUrl
            if (sd.resourceScoped()) {
                DomainManifest parent = sd.parentDomain() != null ? domains.get(sd.parentDomain()) : null;
                if (parent == null || parent.coverage() == null
                        || parent.coverage().discoverUrl() == null
                        || parent.coverage().discoverUrl().isBlank()) {
                    throw new IllegalStateException(
                        "Sub-domain '" + sd.subDomainId() + "' is resource_scoped=true but its parent domain '"
                        + sd.parentDomain() + "' has no coverage.discover_url configured.");
                }
            }
        }
        log.info("DomainManifestStore validation passed");
    }

    public Optional<DomainManifest> getDomain(String domainId) {
        return Optional.ofNullable(domains.get(domainId));
    }

    public Optional<SubDomainManifest> getSubDomain(String subDomainId) {
        return Optional.ofNullable(subDomains.get(subDomainId));
    }

    public List<SubDomainManifest> getSubDomainsForDomain(String domainId) {
        return subDomains.values().stream()
            .filter(sd -> domainId.equals(sd.parentDomain()))
            .toList();
    }

    public EffectiveManifest getEffective(String agentId, String domainId, String subDomainId) {
        DomainManifest domain = domains.get(domainId);
        // Prefer the explicitly-passed subDomainId; fall back to the reverse map built from agents[] lists.
        // The fallback handles the case where AgentManifest.subDomain() is null due to a Jackson/record
        // deserialization quirk where @JsonProperty("sub_domain") is not applied on the constructor param.
        String resolvedSubDomainId = (subDomainId != null) ? subDomainId : agentToSubDomain.get(agentId);
        SubDomainManifest sub = subDomains.get(resolvedSubDomainId);
        return EffectiveManifest.merge(domain, sub, agentId);
    }

    /**
     * The de-duplicated union (by {@code key}) of entity_types across ALL loaded sub-domain
     * manifests. This is the single source the input pipeline (extract/resolve/bind) loops over
     * instead of hardcoding entity fields.
     *
     * <p>Single-domain today. When domain routing lands this should be scoped to the selected
     * domain(s); that seam is intentionally left for later.
     */
    public List<EntityType> entityTypes() {
        Map<String, EntityType> byKey = new java.util.LinkedHashMap<>();
        for (SubDomainManifest sd : subDomains.values()) {
            if (sd.entityTypes() == null) continue;
            for (EntityType et : sd.entityTypes()) {
                if (et.key() != null) byKey.putIfAbsent(et.key(), et);
            }
        }
        return List.copyOf(byKey.values());
    }

    /**
     * The entity_types declared by ONE specific sub-domain, in declaration order, or an empty
     * list when the sub-domain is unknown. Unlike {@link #entityTypes()} — which unions
     * entity_types across ALL loaded sub-domains — this is scoped to a single routed sub-domain.
     * Once more than one domain is loaded the union is ambiguous (e.g. it would surface both a
     * wealth and an insurance required entity); callers that already know the routed sub-domain
     * use this so coverage/secondary entity selection picks the entity that belongs to it.
     */
    public List<EntityType> entityTypesFor(String subDomainId) {
        SubDomainManifest sd = (subDomainId != null) ? subDomains.get(subDomainId) : null;
        if (sd == null || sd.entityTypes() == null) return List.of();
        return List.copyOf(sd.entityTypes());
    }

    /**
     * The clarification schema declared for an entity key, searched across all sub-domains,
     * or null when none is declared.
     */
    public ClarificationSchema clarificationFor(String entityKey) {
        if (entityKey == null) return null;
        for (SubDomainManifest sd : subDomains.values()) {
            Map<String, ClarificationSchema> cs = sd.clarificationSchema();
            if (cs != null && cs.get(entityKey) != null) {
                return cs.get(entityKey);
            }
        }
        return null;
    }

    /**
     * The de-duplicated union of {@code required_context} entity keys across ALL loaded
     * sub-domains. The deterministic CLARIFY check (WORLD-B §4.2) and the coverage entity
     * selection both read this instead of relying on a per-entity {@code required} flag, so
     * "which entity must be present" is a manifest declaration, not Java logic.
     */
    public List<String> requiredContextKeys() {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        for (SubDomainManifest sd : subDomains.values()) {
            if (sd.requiredContext() != null) keys.addAll(sd.requiredContext());
        }
        return List.copyOf(keys);
    }

    /**
     * A user-facing clarification/error message declared in a sub-domain's {@code messages}
     * block, searched across all sub-domains, or null when none is declared. The gateway holds
     * no domain copy — every user-facing string is sourced here (WORLD-B §5).
     */
    public String message(String key) {
        if (key == null) return null;
        for (SubDomainManifest sd : subDomains.values()) {
            String v = sd.messages().get(key);
            if (v != null) return v;
        }
        return null;
    }

    /**
     * The same as {@link #message(String)} but SCOPED to the routed sub-domain first. Once more
     * than one domain is loaded the unscoped search returns whichever sub-domain the HashMap
     * iterates first — so an insurance denial could surface wealth copy. Callers that already know
     * the routed sub-domain pass its id here so the copy belongs to the right domain; falls back to
     * the cross-domain search when the sub-domain is unknown or lacks the key.
     */
    public String message(String key, String subDomainId) {
        if (key == null) return null;
        SubDomainManifest sd = (subDomainId != null) ? subDomains.get(subDomainId) : null;
        if (sd != null) {
            String v = sd.messages().get(key);
            if (v != null) return v;
        }
        return message(key);
    }

    /**
     * The human-readable denial copy declared for a coverage denial reason code
     * (e.g. {@code not-covered}), searched across all sub-domains. Falls back to the
     * manifest-declared {@code default} entry, then null.
     */
    public String denialMessage(String reasonCode) {
        for (SubDomainManifest sd : subDomains.values()) {
            Map<String, String> dm = sd.denialMessages();
            if (reasonCode != null && dm.get(reasonCode) != null) return dm.get(reasonCode);
        }
        for (SubDomainManifest sd : subDomains.values()) {
            String def = sd.denialMessages().get("default");
            if (def != null) return def;
        }
        return null;
    }

    /**
     * The same as {@link #denialMessage(String)} but SCOPED to the routed sub-domain first
     * (bug 238): resolves the reason code, then that sub-domain's {@code default}, before falling
     * back to the cross-domain search. Without scoping, a HashMap iteration order can make an
     * insurance policy denial emit wealth "client relationship" copy — because both sub-domains
     * declare the same reason keys. The gateway holds no domain copy (WORLD-B §5); this only picks
     * WHICH manifest's declared copy applies.
     */
    public String denialMessage(String reasonCode, String subDomainId) {
        SubDomainManifest sd = (subDomainId != null) ? subDomains.get(subDomainId) : null;
        if (sd != null) {
            Map<String, String> dm = sd.denialMessages();
            if (reasonCode != null && dm.get(reasonCode) != null) return dm.get(reasonCode);
            if (dm.get("default") != null) return dm.get("default");
        }
        return denialMessage(reasonCode);
    }

    /**
     * A reference the gateway identified DETERMINISTICALLY from a typed identifier — the entity
     * type whose manifest {@code id_pattern} matched, its owning (resource-scoped) sub-domain, and
     * that sub-domain's parent-domain coverage. Everything needed to RESOLVE/CHECK the reference in
     * the RIGHT domain without embedding routing.
     */
    public record IdentifiedReference(String id, EntityType entityType,
                                      SubDomainManifest subDomain, DomainManifest.Coverage coverage) {}

    /**
     * Deterministic, non-semantic order for a return-all interpretation list (routing spec V2.1 /
     * Piece 2): sub-domain id, then entity-type key. A stable SORT KEY over manifest identifiers —
     * NOT a Java precedence table and NOT a domain enum (World-B: the ordering encodes no domain
     * preference, only reproducibility so a caller never depends on HashMap iteration order).
     */
    private static final Comparator<IdentifiedReference> STABLE_INTERPRETATION_ORDER =
        Comparator.comparing((IdentifiedReference r) -> r.subDomain() != null ? r.subDomain().subDomainId() : "")
                  .thenComparing(r -> r.entityType() != null ? r.entityType().key() : "");

    /**
     * All typed-identifier interpretations in {@code text}, deterministically ordered. A bare id like
     * {@code REL-00188} carries no domain vocabulary, so embedding routing scores it at noise level;
     * its {@code id_pattern} names its required-context entity type exactly, and that entity type's
     * sub-domain is its domain. Scans only resource-scoped sub-domains' required, resolvable entity
     * types with a coverage-backed parent, returning EVERY match (the same id may satisfy patterns in
     * several sub-domains) so ordering is non-semantic. Manifest-driven — no id prefix or domain
     * literal in the gateway.
     */
    public List<IdentifiedReference> identifyAllByIdPattern(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<IdentifiedReference> out = new ArrayList<>();
        for (SubDomainManifest sd : subDomains.values()) {
            if (!sd.resourceScoped()) continue;
            DomainManifest parent = sd.parentDomain() != null ? domains.get(sd.parentDomain()) : null;
            if (parent == null || parent.coverage() == null) continue;
            List<String> required = sd.requiredContext();
            for (EntityType et : sd.entityTypes()) {
                if (!et.isResolvable()) continue;
                if (required == null || !required.contains(et.key())) continue;
                String pat = et.idPattern();
                if (pat == null || pat.isBlank()) continue;
                Matcher m = compiledIdPattern(pat).matcher(text);
                if (m.find()) {
                    out.add(new IdentifiedReference(m.group(), et, sd, parent.coverage()));
                }
            }
        }
        out.sort(STABLE_INTERPRETATION_ORDER);
        return List.copyOf(out);
    }

    /**
     * The deterministic focal-compatibility view of {@link #identifyAllByIdPattern(String)}: the
     * first interpretation in stable order, or empty. Retained for the single-{@code GroundingResult}
     * focal lattice; multi-interpretation callers use the return-all form.
     */
    public Optional<IdentifiedReference> identifyByIdPattern(String text) {
        return identifyAllByIdPattern(text).stream().findFirst();
    }

    /**
     * All LLM-extracted-name interpretations the {@code bag} carries, deterministically ordered — the
     * reference-grounding counterpart to {@link #identifyAllByIdPattern(String)} for a name like "the
     * Okafor account". A surface reference valid in several sub-domains (each declaring a required
     * resolvable entity type under the same {@code extract_as}) yields several interpretations; a
     * single {@code RESOLVE→CHECK} lattice serves each. Sourced from the {@code extract_as} slot of a
     * resolvable, required entity type on a resource-scoped sub-domain with a coverage-backed parent.
     * Manifest-driven: no entity-type literal or domain name in the gateway. {@code latestPrompt} is
     * part of the signature for a future keyword-fallback source; unused today.
     */
    public List<IdentifiedReference> identifyAllByReference(
            ai.conduit.gateway.synthesis.input.EntityBag bag, String latestPrompt) {
        if (bag == null) return List.of();
        List<IdentifiedReference> out = new ArrayList<>();
        for (SubDomainManifest sd : subDomains.values()) {
            if (!sd.resourceScoped()) continue;
            DomainManifest parent = sd.parentDomain() != null ? domains.get(sd.parentDomain()) : null;
            if (parent == null || parent.coverage() == null) continue;
            List<String> required = sd.requiredContext();
            for (EntityType et : sd.entityTypes()) {
                if (!et.isResolvable()) continue;
                if (required == null || !required.contains(et.key())) continue;
                String extractAs = et.extractAs();
                if (extractAs == null || extractAs.isBlank()) continue;
                String ref = bag.reference(extractAs);
                if (ref != null && !ref.isBlank()) {
                    out.add(new IdentifiedReference(ref, et, sd, parent.coverage()));
                }
            }
        }
        out.sort(STABLE_INTERPRETATION_ORDER);
        return List.copyOf(out);
    }

    /**
     * The deterministic focal-compatibility view of {@link #identifyAllByReference}: the first
     * interpretation in stable order, or empty. Retained so the single-{@code GroundingResult} focal
     * lattice ({@code ReferenceGroundingService.ground}) keeps its exact contract; return-all callers
     * (Piece 2 multi-reference grounding) use {@link #identifyAllByReference}.
     */
    public Optional<IdentifiedReference> identifyByReference(
            ai.conduit.gateway.synthesis.input.EntityBag bag, String latestPrompt) {
        return identifyAllByReference(bag, latestPrompt).stream().findFirst();
    }

    /**
     * All eligible sub-domain interpretations for ONE surface reference under a specific
     * {@code extractAs} slot (Piece 2 per-mention grounding). Same eligibility as
     * {@link #identifyAllByReference} — resource-scoped sub-domains with a coverage-backed parent
     * whose required resolvable entity type declares this {@code extractAs} — but keyed off the
     * mention's own {@code extract_as} + verbatim reference rather than the whole bag, so each of a
     * mention's interpretations can be ground independently. Deterministically ordered; empty when the
     * slot names no coverage-grounding entity. Manifest-driven, World-B clean.
     */
    public List<IdentifiedReference> interpretationsForReference(String extractAs, String reference) {
        if (extractAs == null || extractAs.isBlank() || reference == null || reference.isBlank()) {
            return List.of();
        }
        List<IdentifiedReference> out = new ArrayList<>();
        for (SubDomainManifest sd : subDomains.values()) {
            if (!sd.resourceScoped()) continue;
            DomainManifest parent = sd.parentDomain() != null ? domains.get(sd.parentDomain()) : null;
            if (parent == null || parent.coverage() == null) continue;
            List<String> required = sd.requiredContext();
            for (EntityType et : sd.entityTypes()) {
                if (!et.isResolvable()) continue;
                if (required == null || !required.contains(et.key())) continue;
                if (!extractAs.equals(et.extractAs())) continue;
                out.add(new IdentifiedReference(reference, et, sd, parent.coverage()));
            }
        }
        out.sort(STABLE_INTERPRETATION_ORDER);
        return List.copyOf(out);
    }

    /**
     * True when {@code text} matches the {@code id_pattern} of a resolvable entity type on a
     * {@code resource_scoped=false} sub-domain (routing spec V2.1 #4). Such a type is resolvable but
     * has no coverage book, so it can never {@code RESOLVED_ALLOWED}-ground — yet a deterministic
     * pattern match on it is still a strong, non-semantic signal of intent that a later stage (Piece
     * 3 relaxation) can trust WITHOUT LLM presence-trust. This method only RECORDS the match; it takes
     * no disposition. Manifest-driven: the pattern and the scoping flag both come from the manifest.
     */
    public boolean matchesNonCoverageScopedResolvableId(String text) {
        if (text == null || text.isBlank()) return false;
        for (SubDomainManifest sd : subDomains.values()) {
            if (sd.resourceScoped()) continue;
            for (EntityType et : sd.entityTypes()) {
                if (!et.isResolvable()) continue;
                String pat = et.idPattern();
                if (pat == null || pat.isBlank()) continue;
                if (compiledIdPattern(pat).matcher(text).find()) return true;
            }
        }
        return false;
    }

    /**
     * The classifier/synthesizer domain framing, COMPOSED deterministically from the
     * {@code domain_context} phrases declared by the loaded domain manifests. World-B: the gateway
     * emits NO domain literal — it only JOINS manifest-declared copy. Domains are ordered by
     * {@code domain_id} so the prompt is stable regardless of map/load order. When no domain declares
     * a {@code domain_context}, returns the domain-NEUTRAL default — never a domain-flavored string.
     */
    public String composedDomainContext() {
        List<String> contexts = domains.values().stream()
                .sorted(Comparator.comparing(DomainManifest::domainId))
                .map(DomainManifest::domainContext)
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .toList();
        return composeDomainContext(contexts);
    }

    /**
     * Composes the framing string from already-ordered, non-blank domain-context phrases. Package-
     * private + static so the join / neutral-fallback logic is unit-testable without loading manifests.
     * The neutral default carries no domain knowledge; the "covering …" phrasing only joins the
     * manifest-declared phrases.
     */
    static String composeDomainContext(List<String> contextsInDomainIdOrder) {
        if (contextsInDomainIdOrder == null || contextsInDomainIdOrder.isEmpty()) {
            return "an enterprise data assistant";
        }
        return "an enterprise data assistant covering " + humanJoin(contextsInDomainIdOrder);
    }

    /** Joins items as "a", "a and b", or "a, b and c" — deterministic, preserving list order. */
    private static String humanJoin(List<String> items) {
        int n = items.size();
        if (n == 1) return items.get(0);
        String head = String.join(", ", items.subList(0, n - 1));
        return head + " and " + items.get(n - 1);
    }

    public Map<String, DomainManifest> allDomains() {
        return Map.copyOf(domains);
    }

    public Map<String, SubDomainManifest> allSubDomains() {
        return Map.copyOf(subDomains);
    }
}
