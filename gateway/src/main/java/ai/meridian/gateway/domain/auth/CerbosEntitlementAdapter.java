package ai.meridian.gateway.domain.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch-calls the Cerbos PDP for relationship entitlement checks.
 *
 * <p>One {@link #checkRelationships} call issues a <em>single</em> HTTP POST to
 * {@code /api/check/resources} covering all candidate relationship IDs, so
 * {@code filterCovered()} over N relationships incurs exactly 1 round-trip.
 *
 * <p><strong>Fail-mode</strong> (configured via {@code meridian.cerbos.fail-mode}):
 * <ul>
 *   <li>{@code closed} (default) — Cerbos unreachable → deny all, log ERROR.
 *       This is the production-safe default.</li>
 *   <li>{@code local} — Cerbos unreachable → fall back to the principal's JWT book
 *       claim, log WARN.  Useful for demo resilience when Cerbos is stopped.</li>
 * </ul>
 */
@Component
public class CerbosEntitlementAdapter {

    private static final Logger log = LoggerFactory.getLogger(CerbosEntitlementAdapter.class);

    private static final String POLICY_VERSION = "default";
    private static final String RESOURCE_KIND  = "relationship";
    private static final String ACTION_READ     = "read";

    private final RestClient   restClient;
    private final ObjectMapper mapper;
    private final String       failMode;  // "closed" | "local"

    public CerbosEntitlementAdapter(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
            @Value("${meridian.cerbos.host:localhost}") String host,
            @Value("${meridian.cerbos.http-port:3592}") int httpPort,
            @Value("${meridian.cerbos.fail-mode:closed}") String failMode) {

        this.mapper   = mapper;
        this.failMode = failMode;
        this.restClient = restClientBuilder
                .baseUrl("http://" + host + ":" + httpPort)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("CerbosEntitlementAdapter: PDP=http://{}:{} fail-mode={}", host, httpPort, failMode);
    }

    /**
     * Batch-check {@code relationshipIds} against the Cerbos PDP for {@code principal}.
     *
     * @return map of {@code relationshipId → allowed}; decision source is "cerbos"
     *         normally, "local-fallback" if Cerbos is unreachable and fail-mode is local.
     */
    public BatchResult checkRelationships(Principal principal, List<String> relationshipIds) {
        if (relationshipIds == null || relationshipIds.isEmpty()) {
            return new BatchResult(Map.of(), "cerbos");
        }

        try {
            String requestBody = buildRequest(principal, relationshipIds);
            String responseBody = restClient.post()
                    .uri("/api/check/resources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            Map<String, Boolean> results = parseResponse(responseBody, relationshipIds);
            log.debug("Cerbos batch check: principal={} ids={} results={}",
                    principal.id(), relationshipIds, results);
            return new BatchResult(results, "cerbos");

        } catch (Exception e) {
            log.error("Cerbos PDP unreachable: {}", e.getMessage());
            if ("local".equals(failMode)) {
                log.warn("Cerbos fail-mode=local: falling back to JWT book claim for principal={}",
                        principal.id());
                Map<String, Boolean> fallback = new HashMap<>();
                boolean isAdmin = principal.roles().contains("platform_admin")
                        || principal.roles().contains("admin");
                for (String relId : relationshipIds) {
                    fallback.put(relId, isAdmin || principal.book().contains(relId));
                }
                return new BatchResult(fallback, "local-fallback");
            }
            // fail-mode=closed → deny all
            log.warn("Cerbos fail-mode=closed: denying all {} relationships for principal={}",
                    relationshipIds.size(), principal.id());
            Map<String, Boolean> denied = new HashMap<>();
            relationshipIds.forEach(id -> denied.put(id, false));
            return new BatchResult(denied, "local-fallback");
        }
    }

    private String buildRequest(Principal principal, List<String> relationshipIds) throws Exception {
        ObjectNode root      = mapper.createObjectNode();
        ObjectNode principalNode = root.putObject("principal");
        principalNode.put("id",            principal.id());
        principalNode.put("policyVersion", POLICY_VERSION);

        ArrayNode roles = principalNode.putArray("roles");
        principal.roles().forEach(roles::add);

        ObjectNode attr = principalNode.putObject("attr");
        ArrayNode book  = attr.putArray("book");
        principal.book().forEach(book::add);
        attr.put("clearance", principal.clearance());

        ArrayNode resources = root.putArray("resources");
        for (String relId : relationshipIds) {
            ObjectNode entry    = resources.addObject();
            ArrayNode  actions  = entry.putArray("actions");
            actions.add(ACTION_READ);
            ObjectNode resource = entry.putObject("resource");
            resource.put("kind",          RESOURCE_KIND);
            resource.put("policyVersion", POLICY_VERSION);
            resource.put("id",            relId);
            resource.putObject("attr");
        }
        return mapper.writeValueAsString(root);
    }

    @SuppressWarnings("ConstantConditions")
    private Map<String, Boolean> parseResponse(String body, List<String> requestedIds) throws Exception {
        JsonNode root    = mapper.readTree(body);
        JsonNode results = root.path("results");

        Map<String, Boolean> out = new HashMap<>();
        // default everything to denied in case the PDP omits a result
        requestedIds.forEach(id -> out.put(id, false));

        if (results.isArray()) {
            for (JsonNode result : results) {
                String id     = result.path("resource").path("id").asText(null);
                String effect = result.path("actions").path(ACTION_READ).asText("EFFECT_DENY");
                if (id != null) {
                    out.put(id, "EFFECT_ALLOW".equals(effect));
                }
            }
        }
        return out;
    }

    /** Result of a batch check — holds per-relationship verdicts and the decision source. */
    public record BatchResult(Map<String, Boolean> decisions, String source) {
        public boolean isAllowed(String relationshipId) {
            return decisions.getOrDefault(relationshipId, false);
        }
    }
}
