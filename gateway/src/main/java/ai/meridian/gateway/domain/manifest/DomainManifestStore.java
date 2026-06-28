package ai.meridian.gateway.domain.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DomainManifestStore {

    private static final Logger log = LoggerFactory.getLogger(DomainManifestStore.class);

    private final ObjectMapper mapper;
    private final Map<String, DomainManifest> domains = new HashMap<>();
    private final Map<String, SubDomainManifest> subDomains = new HashMap<>();

    public DomainManifestStore(ObjectMapper mapper) {
        this.mapper = mapper;
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
            resources = resolver.getResources("classpath:domains/*.json");
        } catch (IOException e) {
            log.warn("No domain manifests found at classpath:domains/*.json");
            return;
        }
        for (Resource r : resources) {
            try {
                DomainManifest d = mapper.readValue(r.getInputStream(), DomainManifest.class);
                domains.put(d.domainId(), d);
                log.debug("Loaded domain: {}", d.domainId());
            } catch (Exception e) {
                throw new IllegalStateException("Failed to parse domain manifest " + r.getFilename() + ": " + e.getMessage(), e);
            }
        }
    }

    private void loadSubDomains() throws IOException {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath:domains/**/*.json");
        } catch (IOException e) {
            log.warn("No sub-domain manifests found");
            return;
        }
        for (Resource r : resources) {
            try {
                SubDomainManifest sd = mapper.readValue(r.getInputStream(), SubDomainManifest.class);
                if (sd.subDomainId() == null) continue; // skip domain-level files that were matched
                subDomains.put(sd.subDomainId(), sd);
                log.debug("Loaded sub-domain: {} (parent: {})", sd.subDomainId(), sd.parentDomain());
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
        SubDomainManifest sub = subDomains.get(subDomainId);
        return EffectiveManifest.merge(domain, sub, agentId);
    }

    public Map<String, DomainManifest> allDomains() {
        return Map.copyOf(domains);
    }

    public Map<String, SubDomainManifest> allSubDomains() {
        return Map.copyOf(subDomains);
    }
}
