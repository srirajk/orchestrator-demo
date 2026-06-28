package ai.meridian.gateway.domain.manifest;

import ai.meridian.gateway.domain.auth.Principal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class DomainPrerequisiteValidator {

    private static final Logger log = LoggerFactory.getLogger(DomainPrerequisiteValidator.class);

    public enum Verdict { AUTHORIZED, DENIED, SKIPPED }

    private final DomainManifestStore manifestStore;
    private final RestTemplate restTemplate;

    public DomainPrerequisiteValidator(DomainManifestStore manifestStore, RestTemplate restTemplate) {
        this.manifestStore = manifestStore;
        this.restTemplate = restTemplate;
    }

    /**
     * Validates whether principal may access relationshipId according to the domain's authorization_contract.
     * Returns SKIPPED when no authorization_contract is configured.
     * Fails open on HTTP errors (logs loudly, returns AUTHORIZED to not block demo).
     */
    public Verdict validate(Principal principal, String domainId, String relationshipId) {
        if (relationshipId == null || domainId == null) return Verdict.SKIPPED;

        var domain = manifestStore.getDomain(domainId).orElse(null);
        if (domain == null || domain.authorizationContract() == null
                || domain.authorizationContract().urlTemplate() == null
                || domain.authorizationContract().urlTemplate().isBlank()) {
            return Verdict.SKIPPED;
        }

        String url = domain.authorizationContract().urlTemplate()
            .replace("{principal_id}", principal.id())
            .replace("{relationship_id}", relationshipId);

        try {
            var response = restTemplate.getForObject(url, AuthzResponse.class);
            boolean allowed = response != null && Boolean.TRUE.equals(response.allowed());
            log.info("Domain authz: domain={} principal={} relId={} allowed={}",
                domainId, principal.id(), relationshipId, allowed);
            return allowed ? Verdict.AUTHORIZED : Verdict.DENIED;
        } catch (Exception e) {
            log.error("Domain authz contract call failed for domain={} principal={} relId={}: {} — failing open",
                domainId, principal.id(), relationshipId, e.getMessage());
            return Verdict.AUTHORIZED;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthzResponse(Boolean allowed, String reason) {}
}
