package ai.meridian.gateway.synthesis.input;

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

import java.util.List;
import java.util.Map;

@Service
public class EntityResolver {

    private static final Logger log = LoggerFactory.getLogger(EntityResolver.class);

    @Value("${meridian.crm.wealth.url:http://mock-crm:8085}")
    private String wealthCrmUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public EntityResolver(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public EntityBag resolve(EntityBag bag) {
        String relationshipId = resolveRelationship(bag.relationshipReference());
        String fundId = resolveFund(bag.fundReference());

        if (relationshipId == null && bag.relationshipReference() != null
                && !bag.relationshipReference().isBlank()) {
            return bag.withCandidates(List.of());
        }
        return bag.withResolved(relationshipId, fundId, false);
    }

    private String resolveRelationship(String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.toUpperCase().matches("REL-\\d+")) return reference.toUpperCase();

        try {
            var reqBody = Map.of("query", reference, "type", "relationship");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var entity = new HttpEntity<>(reqBody, headers);
            var response = restTemplate.postForObject(
                wealthCrmUrl + "/entities/resolve", entity, CrmResolveResponse.class);
            if (response != null && Boolean.TRUE.equals(response.resolved())) {
                log.debug("Resolved '{}' → '{}'", reference, response.entityId());
                return response.entityId();
            }
            if (response != null && response.candidates() != null && !response.candidates().isEmpty()) {
                log.info("Ambiguous reference '{}' — {} candidates", reference, response.candidates().size());
            } else {
                log.warn("Could not resolve relationship reference '{}' — clarification needed", reference);
            }
        } catch (Exception e) {
            log.warn("CRM entity resolution failed for '{}': {} — will trigger clarification", reference, e.getMessage());
        }
        return null;
    }

    private String resolveFund(String reference) {
        if (reference == null || reference.isBlank()) return null;
        if (reference.toUpperCase().matches("FND-[A-Z0-9]+")) return reference.toUpperCase();
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
