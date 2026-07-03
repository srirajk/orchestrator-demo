package ai.conduit.gateway.domain.auth;

import ai.conduit.gateway.registry.model.AgentManifest;
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
 * <p><strong>Fail-mode</strong> (configured via {@code conduit.cerbos.fail-mode}):
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

    private static final String POLICY_VERSION    = "default";
    private static final String RESOURCE_AGENT    = "agent";
    private static final String ACTION_READ        = "read";
    private static final String ACTION_INVOKE      = "invoke";
    // Membership-only probe action (glass-box gate trace): tests segment membership WITHOUT the
    // classification-rank comparison, so the gateway can label a segment-miss vs a classification-miss.
    private static final String ACTION_INVOKE_MEMBERSHIP = "invoke_membership";

    private final RestClient   restClient;
    private final ObjectMapper mapper;
    private final String       failMode;  // "closed" | "local"
    // Cerbos resource kind for entity-level checks — domain-declared via config,
    // never a hardcoded domain literal (World B).
    private final String       resourceType;

    public CerbosEntitlementAdapter(
            RestClient.Builder restClientBuilder,
            ObjectMapper mapper,
            @Value("${conduit.cerbos.host:localhost}") String host,
            @Value("${conduit.cerbos.http-port:3592}") int httpPort,
            @Value("${conduit.cerbos.fail-mode:closed}") String failMode,
            @Value("${conduit.authz.resource-type:relationship}") String resourceType) {

        this.mapper   = mapper;
        this.failMode = failMode;
        this.resourceType = resourceType;
        this.restClient = restClientBuilder
                .baseUrl("http://" + host + ":" + httpPort)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("CerbosEntitlementAdapter: PDP=http://{}:{} fail-mode={} resource-type={}",
                host, httpPort, failMode, resourceType);
    }

    /**
     * Batch-check {@code relationshipIds} against the Cerbos PDP for {@code principal}.
     *
     * @return map of {@code relationshipId → allowed}; decision source is "cerbos"
     *         normally, "local-fallback" if Cerbos is unreachable and fail-mode is local.
     */
    /**
     * Batch-check {@code relationshipIds} against the Cerbos PDP for the {@code read} action.
     */
    public BatchResult checkRelationships(Principal principal, List<String> relationshipIds) {
        if (relationshipIds == null || relationshipIds.isEmpty()) {
            return new BatchResult(Map.of(), "cerbos");
        }
        try {
            String requestBody = buildResourceRequest(principal, resourceType, ACTION_READ,
                    relationshipIds, Map.of());
            String responseBody = post(requestBody);
            Map<String, Boolean> results = parseResponse(responseBody, ACTION_READ, relationshipIds);
            log.debug("Cerbos relationship check: principal={} ids={} results={}",
                    principal.id(), relationshipIds, results);
            return new BatchResult(results, "cerbos");

        } catch (Exception e) {
            return handleFailure(e, principal, relationshipIds, resourceType);
        }
    }

    /**
     * Batch-check which agents the principal may {@code invoke}.
     *
     * <p>Each agent is presented as a resource with attributes:
     * <ul>
     *   <li>{@code domain} — the agent's business domain (e.g. "wealth-management")</li>
     *   <li>{@code audience} — "segment" | "enterprise"</li>
     *   <li>{@code access_mode} — "read" | "write"</li>
     *   <li>{@code data_classification} — e.g. "confidential-pii", "confidential"</li>
     * </ul>
     *
     * @return map of {@code agentId → allowed}
     */
    public BatchResult checkAgents(Principal principal, List<AgentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) {
            return new BatchResult(Map.of(), "cerbos");
        }
        try {
            List<String> agentIds = manifests.stream()
                    .map(AgentManifest::agentId).toList();

            String requestBody = buildAgentRequest(principal, manifests, ACTION_INVOKE);
            String responseBody = post(requestBody);
            Map<String, Boolean> results = parseResponse(responseBody, ACTION_INVOKE, agentIds);
            log.debug("Cerbos agent check: principal={} agents={} results={}",
                    principal.id(), agentIds, results);
            return new BatchResult(results, "cerbos");

        } catch (Exception e) {
            // fail-CLOSED for agent checks — Cerbos outage is a security event, not graceful degradation.
            log.error("Cerbos agent check FAILED — denying all agents for principal={}: {}",
                    principal.id(), e.getMessage());
            Map<String, Boolean> denied = new HashMap<>();
            manifests.forEach(m -> denied.put(m.agentId(), false));
            return new BatchResult(denied, "local-fallback-closed");
        }
    }

    /**
     * Batch-check the segment-membership-only probe ({@code invoke_membership}) for the glass-box
     * gate trace. A {@code true} means the agent's domain maps to a segment the principal holds
     * (or the agent is enterprise-audience) — <em>ignoring</em> the classification tier. Combined
     * with the full {@code invoke} verdict, this lets the gateway distinguish a segment-membership
     * deny from a classification deny WITHOUT knowing the domain→segment mapping (it stays in the
     * Cerbos policy — World B). This is EXPLANATION only; enforcement remains {@link #checkAgents}.
     *
     * @return map of {@code agentId → isMember}; on any error every agent is treated as a non-member.
     */
    public Map<String, Boolean> checkAgentMembership(Principal principal, List<AgentManifest> manifests) {
        if (manifests == null || manifests.isEmpty()) return Map.of();
        try {
            List<String> agentIds = manifests.stream().map(AgentManifest::agentId).toList();
            String requestBody = buildAgentRequest(principal, manifests, ACTION_INVOKE_MEMBERSHIP);
            String responseBody = post(requestBody);
            return parseResponse(responseBody, ACTION_INVOKE_MEMBERSHIP, agentIds);
        } catch (Exception e) {
            log.warn("Cerbos membership probe failed for principal={}: {} — treating all as non-members",
                    principal.id(), e.getMessage());
            Map<String, Boolean> denied = new HashMap<>();
            manifests.forEach(m -> denied.put(m.agentId(), false));
            return denied;
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String post(String requestBody) {
        return restClient.post()
                .uri("/api/check/resources")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);
    }

    /** Generic resource-check builder — used for relationships and domains. */
    private String buildResourceRequest(Principal principal, String kind, String action,
                                         List<String> ids, Map<String, String> extraAttr) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        addPrincipal(root, principal);

        ArrayNode resources = root.putArray("resources");
        for (String id : ids) {
            ObjectNode entry   = resources.addObject();
            entry.putArray("actions").add(action);
            ObjectNode resource = entry.putObject("resource");
            resource.put("kind",          kind);
            resource.put("policyVersion", POLICY_VERSION);
            resource.put("id",            id);
            ObjectNode attr = resource.putObject("attr");
            extraAttr.forEach(attr::put);
        }
        return mapper.writeValueAsString(root);
    }

    /** Agent-specific builder — sends domain, audience, access_mode, data_classification per agent. */
    private String buildAgentRequest(Principal principal, List<AgentManifest> manifests, String action) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        addPrincipal(root, principal);

        ArrayNode resources = root.putArray("resources");
        for (AgentManifest m : manifests) {
            ObjectNode entry   = resources.addObject();
            entry.putArray("actions").add(action);
            ObjectNode resource = entry.putObject("resource");
            resource.put("kind",          RESOURCE_AGENT);
            resource.put("policyVersion", POLICY_VERSION);
            resource.put("id",            m.agentId());
            ObjectNode attr = resource.putObject("attr");
            attr.put("domain",              m.domain() != null ? m.domain() : "");
            attr.put("audience",            m.audience() != null ? m.audience() : "segment");
            attr.put("access_mode",         m.constraints() != null && m.constraints().accessMode() != null
                    ? m.constraints().accessMode() : "read");
            attr.put("data_classification", m.constraints() != null
                    ? m.constraints().dataClassification() : "confidential");
        }
        return mapper.writeValueAsString(root);
    }

    /** Populates the principal node with id, roles, and all attributes. */
    private void addPrincipal(ObjectNode root, Principal principal) {
        ObjectNode p = root.putObject("principal");
        p.put("id",            principal.id());
        p.put("policyVersion", POLICY_VERSION);

        ArrayNode roles = p.putArray("roles");
        principal.roles().forEach(roles::add);

        ObjectNode attr = p.putObject("attr");

        // Structural attributes only — no book claim.
        // Book-of-business is enforced by the domain coverage service (DISCOVER/CHECK), not Cerbos.
        // segments is a per-segment classification map: segment -> tier held in that segment.
        ObjectNode segments = attr.putObject("segments");
        principal.segments().forEach(segments::put);

        ArrayNode domains = attr.putArray("domains");
        principal.domains().forEach(domains::add);

        ArrayNode adminDomains = attr.putArray("admin_domains");
        principal.adminDomains().forEach(adminDomains::add);
    }

    private BatchResult handleFailure(Exception e, Principal principal,
                                       List<String> ids, String kind) {
        log.error("Cerbos PDP unreachable ({}): {}", kind, e.getMessage());
        if ("local".equals(failMode)) {
            // local fallback: admins pass, everyone else is denied — no stale book to fall back to.
            log.warn("Cerbos fail-mode=local: principal={} kind={} — admins only", principal.id(), kind);
            Map<String, Boolean> fallback = new HashMap<>();
            boolean isAdmin = principal.roles().contains("platform_admin")
                    || principal.roles().contains("admin");
            ids.forEach(id -> fallback.put(id, isAdmin));
            return new BatchResult(fallback, "local-fallback");
        }
        log.warn("Cerbos fail-mode=closed: denying all {} {}(s) for principal={}",
                ids.size(), kind, principal.id());
        Map<String, Boolean> denied = new HashMap<>();
        ids.forEach(id -> denied.put(id, false));
        return new BatchResult(denied, "local-fallback-closed");
    }

    private Map<String, Boolean> parseResponse(String body, String action, List<String> requestedIds) throws Exception {
        JsonNode root    = mapper.readTree(body);
        JsonNode results = root.path("results");

        Map<String, Boolean> out = new HashMap<>();
        requestedIds.forEach(id -> out.put(id, false));  // default deny

        if (results.isArray()) {
            for (JsonNode result : results) {
                String id     = result.path("resource").path("id").asText(null);
                String effect = result.path("actions").path(action).asText("EFFECT_DENY");
                if (id != null) {
                    out.put(id, "EFFECT_ALLOW".equals(effect));
                }
            }
        }
        return out;
    }

    /** Result of a batch check — holds per-resource verdicts and the decision source. */
    public record BatchResult(Map<String, Boolean> decisions, String source) {
        public boolean isAllowed(String resourceId) {
            return decisions.getOrDefault(resourceId, false);
        }
    }
}
