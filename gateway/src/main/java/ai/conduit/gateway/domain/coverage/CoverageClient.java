package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.manifest.DomainManifest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the DISCOVER / CHECK / RESOLVE coverage pipeline.
 *
 * <p>All three methods are fail-closed: a 5xx response or a timeout throws
 * {@link CoverageUnavailableException}, which the caller must handle by
 * streaming a degraded response to the end user rather than silently granting
 * access.
 */
@Service
public class CoverageClient {

    private static final Logger log = LoggerFactory.getLogger(CoverageClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public CoverageClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    // ── DISCOVER ─────────────────────────────────────────────────────────────

    /**
     * Returns all client relationships visible to {@code principalId} in {@code tenantId}.
     * Uses the coverage.discover_url template from the domain manifest.
     */
    public List<CoverageResource> discover(String principalId, String tenantId,
                                            DomainManifest.Coverage coverage,
                                            String bearerToken) {
        String url = bindPathParams(coverage.discoverUrl(), principalId, null);
        try {
            String body = webClient.get()
                .uri(url)
                .header("X-Tenant-Id", tenantId)
                .headers(headers -> applyBearer(headers, bearerToken, "coverage discover"))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                    Mono.error(new CoverageUnavailableException(
                        "Coverage discover returned " + response.statusCode().value())))
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .block();

            return objectMapper.readValue(body, new TypeReference<List<CoverageResource>>() {});
        } catch (CoverageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CoverageUnavailableException("Coverage discover failed: " + e.getMessage(), e);
        }
    }

    // ── CHECK ─────────────────────────────────────────────────────────────────

    /**
     * Checks whether {@code principalId} may access {@code resourceId} within their coverage.
     */
    public CoverageCheckResult check(String principalId, String tenantId,
                                      String resourceId, DomainManifest.Coverage coverage,
                                      String bearerToken) {
        String url = bindPathParams(coverage.checkUrl(), principalId, resourceId);
        try {
            String body = webClient.get()
                .uri(url)
                .header("X-Tenant-Id", tenantId)
                .headers(headers -> applyBearer(headers, bearerToken, "coverage check"))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                    Mono.error(new CoverageUnavailableException(
                        "Coverage check returned " + response.statusCode().value())))
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .block();

            return objectMapper.readValue(body, CoverageCheckResult.class);
        } catch (CoverageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CoverageUnavailableException("Coverage check failed: " + e.getMessage(), e);
        }
    }

    // ── RESOLVE ───────────────────────────────────────────────────────────────

    /**
     * Resolves a free-text {@code reference} (e.g. "the Whitman account") to a canonical
     * relationship ID.  Returns a result indicating whether resolution was unambiguous,
     * ambiguous (multiple candidates), or not found.
     *
     * <p>RESOLVE is principal-agnostic (World-B invariant 5 / hard-rule f): the request is
     * scoped only by tenant, never by the caller's book.  We send {@code X-Tenant-Id}, exactly
     * like DISCOVER and CHECK, and deliberately do NOT send a {@code principal_id}.  The book
     * gate is enforced entirely by the subsequent CHECK call and the gateway-side
     * candidates ∩ discover intersection — never by filtering resolution.
     */
    public CoverageResolveResult resolve(String reference, String entityType,
                                          String tenantId, DomainManifest.Coverage coverage,
                                          String bearerToken) {
        String url = coverage.resolveUrl();
        Map<String, String> requestBody = Map.of(
            "reference", reference,
            "type", entityType
        );
        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String responseBody = webClient.post()
                .uri(url)
                .header("X-Tenant-Id", tenantId)
                .headers(headers -> applyBearer(headers, bearerToken, "coverage resolve"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(bodyJson))
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                    Mono.error(new CoverageUnavailableException(
                        "Coverage resolve returned " + response.statusCode().value())))
                .bodyToMono(String.class)
                .timeout(TIMEOUT)
                .block();

            return objectMapper.readValue(responseBody, CoverageResolveResult.class);
        } catch (CoverageUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new CoverageUnavailableException("Coverage resolve failed: " + e.getMessage(), e);
        }
    }

    // ── URL binding ───────────────────────────────────────────────────────────

    /**
     * Replaces {@code {principal_id}} and {@code {id}} placeholders in the URL template.
     * Null values leave the placeholder in place (which will cause a 404 or similar
     * downstream — intentional; the caller should only bind what's available).
     */
    private String bindPathParams(String template, String principalId, String resourceId) {
        if (template == null) return null;
        String result = template;
        if (principalId != null) result = result.replace("{principal_id}", principalId);
        if (resourceId  != null) result = result.replace("{id}", resourceId);
        return result;
    }

    private void applyBearer(HttpHeaders headers, String bearerToken, String operation) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new CoverageUnavailableException(
                "No caller identity available for " + operation + " — refusing coverage call");
        }
        headers.setBearerAuth(bearerToken);
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /**
     * Thrown when the coverage service is unreachable, returns 5xx, or times out.
     * Callers must FAIL CLOSED — deny access, never grant it on error.
     */
    public static class CoverageUnavailableException extends RuntimeException {
        public CoverageUnavailableException(String message) {
            super(message);
        }

        public CoverageUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
