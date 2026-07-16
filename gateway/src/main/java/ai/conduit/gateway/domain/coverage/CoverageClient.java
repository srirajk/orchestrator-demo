package ai.conduit.gateway.domain.coverage;

import ai.conduit.gateway.domain.manifest.DomainManifest;
import ai.conduit.gateway.infrastructure.outbound.OutboundGate;
import ai.conduit.gateway.infrastructure.outbound.OutboundKeys;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * HTTP client for the DISCOVER / CHECK / RESOLVE coverage pipeline.
 *
 * <p><b>Fail-closed everywhere.</b> Any non-2xx response — 5xx, 503, and a 404-on-resolve alike —
 * as well as a connect refusal, a header timeout, or a trickle body that never finishes, all throw
 * {@link CoverageUnavailableException}. The caller must stream a degraded answer rather than silently
 * granting access. This is the exact mapping the previous WebClient version produced (its
 * {@code onStatus(is5xx)} plus the {@code catch(Exception)} fall-through that turned a 404 body-parse
 * into the same exception); {@code CoverageStatusMappingTest} pins the status→exception table.
 *
 * <p><b>VT-pinning discipline (F3).</b> Two independent bounds on every call:
 * <ul>
 *   <li>a timed JDK request factory ({@code conduit.coverage.connect-timeout-ms} /
 *       {@code read-timeout-ms}) — bounds connect and time-to-headers; and</li>
 *   <li>an {@link OutboundGate} deadline wrap keyed by the coverage service base-URL — bounds the
 *       BODY phase (defeats the 4 B/s trickle-body toxic that streams under the socket read timeout)
 *       and bounds per-service concurrency with a leak-proof permit.</li>
 * </ul>
 * The 5s timeout budget is preserved (now config); exception types and fail-closed mappings are
 * unchanged from the WebClient version.
 */
@Service
public class CoverageClient {

    private static final Logger log = LoggerFactory.getLogger(CoverageClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final OutboundGate outboundGate;
    private final long deadlineMs;

    /**
     * Maps ANY non-2xx response to {@link CoverageUnavailableException}. A custom handler (not the
     * default {@code RestClient} 4xx/5xx handler) so a 404 on RESOLVE fail-closes identically to a
     * 500 — the coverage book must never be treated as "granted" on any error status.
     */
    private static final RestClient.ResponseSpec.ErrorHandler NON_2XX_FAIL_CLOSED =
            (request, response) -> {
                throw new CoverageUnavailableException(
                        "Coverage service returned HTTP " + response.getStatusCode().value());
            };

    public CoverageClient(RestClient coverageRestClient, ObjectMapper objectMapper,
                          MeterRegistry meterRegistry, OutboundGate outboundGate,
                          @Value("${conduit.coverage.read-timeout-ms:5000}") long deadlineMs) {
        this.restClient = coverageRestClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.outboundGate = outboundGate;
        this.deadlineMs = deadlineMs;
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
        String body = get("discover", url, tenantId, bearerToken);
        try {
            return objectMapper.readValue(body, new TypeReference<List<CoverageResource>>() {});
        } catch (Exception e) {
            emitUnavailable("discover");
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
        String body = get("check", url, tenantId, bearerToken);
        try {
            return objectMapper.readValue(body, CoverageCheckResult.class);
        } catch (Exception e) {
            emitUnavailable("check");
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
        requireBearer(bearerToken, "coverage resolve");
        Map<String, String> requestBody = Map.of("reference", reference, "type", entityType);
        String key = OutboundKeys.baseUrl(url);
        try {
            String bodyJson = objectMapper.writeValueAsString(requestBody);
            String responseBody = outboundGate.call(key, deadlineMs, () ->
                    restClient.post()
                            .uri(url)
                            .header("X-Tenant-Id", tenantId)
                            .headers(h -> h.setBearerAuth(bearerToken))
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(bodyJson)
                            .retrieve()
                            .onStatus(status -> !status.is2xxSuccessful(), NON_2XX_FAIL_CLOSED)
                            .body(String.class));
            return objectMapper.readValue(responseBody, CoverageResolveResult.class);
        } catch (CoverageUnavailableException e) {
            emitUnavailable("resolve");
            throw e;
        } catch (Exception e) {
            emitUnavailable("resolve");
            throw new CoverageUnavailableException("Coverage resolve failed: " + e.getMessage(), e);
        }
    }

    // ── shared GET (discover/check) ─────────────────────────────────────────────

    private String get(String operation, String url, String tenantId, String bearerToken) {
        requireBearer(bearerToken, "coverage " + operation);
        String key = OutboundKeys.baseUrl(url);
        try {
            return outboundGate.call(key, deadlineMs, () ->
                    restClient.get()
                            .uri(url)
                            .header("X-Tenant-Id", tenantId)
                            .headers(h -> h.setBearerAuth(bearerToken))
                            .retrieve()
                            .onStatus(status -> !status.is2xxSuccessful(), NON_2XX_FAIL_CLOSED)
                            .body(String.class));
        } catch (CoverageUnavailableException e) {
            emitUnavailable(operation);
            throw e;
        } catch (Exception e) {
            emitUnavailable(operation);
            throw new CoverageUnavailableException(
                    "Coverage " + operation + " failed: " + e.getMessage(), e);
        }
    }

    private void emitUnavailable(String operation) {
        Counter.builder("conduit.coverage.unavailable")
                .description("Coverage service unavailable, failed, or timed out")
                .tag("operation", operation)
                .register(meterRegistry)
                .increment();
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

    private void requireBearer(String bearerToken, String operation) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new CoverageUnavailableException(
                    "No caller identity available for " + operation + " — refusing coverage call");
        }
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /**
     * Thrown when the coverage service is unreachable, returns any non-2xx, or times out.
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
