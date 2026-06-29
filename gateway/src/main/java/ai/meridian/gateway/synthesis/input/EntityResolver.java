package ai.meridian.gateway.synthesis.input;

import ai.meridian.gateway.domain.manifest.DomainManifestStore;
import ai.meridian.gateway.domain.manifest.EntityType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage 2 — Resolve.
 *
 * <p>Manifest-driven: loops over the resolvable {@code entity_types}, resolving each human
 * reference to a canonical id via its declared {@code resolve_type}. A reference that already
 * looks like a literal id (matches the entity's {@code id_pattern}) short-circuits without a
 * lookup. A required entity that cannot be resolved triggers clarification — never a guess.
 */
@Service
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    @Value("${meridian.crm.wealth.url:http://mock-crm:8085}")
    private String crmUrl;

    private final RestTemplate restTemplate;
    private final DomainManifestStore manifestStore;

    public EntityResolver(RestTemplate restTemplate, ObjectMapper objectMapper,
                          DomainManifestStore manifestStore) {
        this.restTemplate = restTemplate;
        this.manifestStore = manifestStore;
    }

    public EntityBag resolve(EntityBag bag) {
        Map<String, String> resolved = new LinkedHashMap<>();

        for (EntityType et : manifestStore.entityTypes()) {
            if (!et.isResolvable()) continue;
            String reference = bag.reference(et.extractAs());
            if (reference == null || reference.isBlank()) continue;

            String id = resolveOne(reference, et);
            if (id != null) {
                resolved.put(et.key(), id);
            } else if (et.required()) {
                // A required entity could not be resolved — clarify rather than fabricate.
                return bag.withCandidates(List.of());
            }
            // Optional + unresolved → simply omit; binding will drop dependent agents.
        }
        return bag.withResolved(resolved, false);
    }

    /** Resolves one reference for the given entity type, or returns null when unresolved. */
    private String resolveOne(String reference, EntityType et) {
        // Already a literal id → use verbatim (no lookup, no fabrication).
        if (et.idPattern() != null && !et.idPattern().isBlank()
                && reference.toUpperCase().matches(et.idPattern())) {
            return reference.toUpperCase();
        }
        try {
            var reqBody = Map.of("query", reference, "type", et.resolveType());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(reqBody, headers);
            var response = restTemplate.postForObject(
                crmUrl + "/entities/resolve", entity, CrmResolveResponse.class);
            if (response != null && Boolean.TRUE.equals(response.resolved())) {
                log.debug("Resolved '{}' ({}) → '{}'", reference, et.resolveType(), response.entityId());
                return response.entityId();
            }
            if (response != null && response.candidates() != null && !response.candidates().isEmpty()) {
                log.info("Ambiguous reference '{}' ({}) — {} candidates",
                        reference, et.resolveType(), response.candidates().size());
            } else {
                log.warn("Could not resolve '{}' ({}) — clarification may be needed",
                        reference, et.resolveType());
            }
        } catch (Exception e) {
            log.warn("CRM resolution failed for '{}' ({}): {}", reference, et.resolveType(), e.getMessage());
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrmResolveResponse(
        Boolean resolved,
        @JsonProperty("entity_id") String entityId,
        String name,
        String type,
        List<CrmCandidate> candidates
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrmCandidate(
        @JsonProperty("entity_id") String entityId,
        String name
    ) {}
}
