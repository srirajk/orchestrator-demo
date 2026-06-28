package ai.meridian.gateway.domain.manifest;

import ai.meridian.gateway.domain.auth.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * @deprecated Authorization contract checks have been migrated to the DISCOVER/CHECK/RESOLVE
 *             coverage pipeline ({@link ai.meridian.gateway.domain.coverage.CoverageClient}).
 *             This validator is retained for backwards compatibility but always returns
 *             {@link Verdict#SKIPPED}; remove in a future cleanup pass.
 */
@Deprecated
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
     * Always returns {@link Verdict#SKIPPED}.  Coverage validation is now performed by
     * {@link ai.meridian.gateway.domain.coverage.CoverageClient} before input synthesis.
     */
    public Verdict validate(Principal principal, String domainId, String relationshipId) {
        log.debug("DomainPrerequisiteValidator.validate: skipped (migrated to coverage pipeline) "
            + "domain={} principal={} relId={}", domainId, principal.id(), relationshipId);
        return Verdict.SKIPPED;
    }
}
