package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DomainManifestStore {

    private static final Logger log = LoggerFactory.getLogger(DomainManifestStore.class);
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ObjectMapper mapper;
    private final Environment env;
    private final String registryLocation;
    private final Map<String, DomainManifest>    domains          = new HashMap<>();
    private final Map<String, SubDomainManifest> subDomains       = new HashMap<>();
    // Reverse map: agentId → subDomainId — built from sub-domain agents[] lists at load time.
    // Used as fallback when the AgentManifest in Redis lacks sub_domain (Jackson/record issue).
    private final Map<String, String>            agentToSubDomain = new HashMap<>();

    public DomainManifestStore(ObjectMapper mapper, Environment env,
                               @Value("${meridian.registry.location:classpath:}") String registryLocation) {
        this.mapper = mapper;
        this.env = env;
        this.registryLocation = registryLocation;
    }

    @PostConstruct
    public void load() throws IOException {
        loadDomains();
        loadSubDomains();
        validate();
        log.info("DomainManifestStore loaded: {} domains, {} sub-domains", domains.size(), subDomains.size());
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
            try {
                DomainManifest d = mapper.readValue(r.getInputStream(), DomainManifest.class);
                // Resolve environment variable placeholders in Coverage URL fields
                d = resolveEnvVarsInCoverage(d);
                domains.put(d.domainId(), d);
                log.debug("Loaded domain: {}", d.domainId());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
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
        return new DomainManifest(d.domainId(), d.displayName(), resolved, d.memoryCompaction());
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
            resources = resolver.getResources(registryLocation + "domains/**/*.json");
        } catch (IOException e) {
            log.warn("No sub-domain manifests found");
            return;
        }
        for (Resource r : resources) {
            try {
                SubDomainManifest sd = mapper.readValue(r.getInputStream(), SubDomainManifest.class);
                if (sd.subDomainId() == null) continue; // skip domain-level files that were matched
                subDomains.put(sd.subDomainId(), sd);
                log.debug("Loaded sub-domain: {} (parent: {}) resourceScoped={}",
                    sd.subDomainId(), sd.parentDomain(), sd.resourceScoped());
                // Build reverse map so getEffective() works even when AgentManifest.subDomain() is null.
                if (sd.agents() != null) {
                    for (String aid : sd.agents()) {
                        agentToSubDomain.put(aid, sd.subDomainId());
                    }
                }
            } catch (Exception e) {
                log.debug("Skipping non-subdomain file {}: {}", r.getFilename(), e.getMessage());
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

    public Map<String, DomainManifest> allDomains() {
        return Map.copyOf(domains);
    }

    public Map<String, SubDomainManifest> allSubDomains() {
        return Map.copyOf(subDomains);
    }
}
