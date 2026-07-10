package ai.conduit.gateway.registry.api;

import ai.conduit.gateway.domain.auth.AgentAuthorization;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.service.AgentRegistrar;
import ai.conduit.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The registry control plane: registering, updating, and deregistering agents.
 *
 * <pre>
 * POST   /admin/agents          register an agent (validate → introspect → persist → index)
 * PUT    /admin/agents/{id}     update and re-introspect
 * DELETE /admin/agents/{id}     deregister
 * </pre>
 *
 * <p>Served by the registry service, not the gateway. Every one of these runs the ingestion
 * pipeline: it embeds the agent's example prompts and rewrites the routing vector index. The
 * gateway holds no corpus embedder and no index writer, so it could not serve these even if asked.
 *
 * <p>Reads stay on the gateway ({@code GET /admin/agents}), because reading the catalogue is a
 * Redis lookup and the admin UI already goes there for it.
 *
 * <p>Authorization is unchanged: {@link AgentAuthorization} enforces that a {@code domain_admin} may
 * only manage agents inside their own domains, while a {@code platform_admin} may manage any. The
 * coarse URL-level role check lives in the security config; this is the fine domain-scope check.
 */
@RestController
@RequestMapping("/admin/agents")
@Profile("registry")
public class AgentRegistrationController {

    private final AgentRegistrar registrar;
    private final AgentRegistry catalog;
    private final AgentAuthorization agentAuth;

    public AgentRegistrationController(AgentRegistrar registrar,
                                       AgentRegistry catalog,
                                       AgentAuthorization agentAuth) {
        this.registrar = registrar;
        this.catalog   = catalog;
        this.agentAuth = agentAuth;
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody JsonNode submission, Authentication auth) {
        try {
            String domain = submission.path("domain").asText(null);
            agentAuth.assertCanManageAgent(auth, domain);
            AgentManifest stored = registrar.register(submission);
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{agentId}")
    public ResponseEntity<?> update(@PathVariable String agentId,
                                    @RequestBody JsonNode submission,
                                    Authentication auth) {
        try {
            String domain = submission.path("domain").asText(null);
            agentAuth.assertCanManageAgent(auth, domain);
            if (!catalog.exists(agentId)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(registrar.register(submission));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deregister(@PathVariable String agentId, Authentication auth) {
        if (!catalog.exists(agentId)) return ResponseEntity.notFound().build();
        // For delete, check domain scope against the existing agent's domain
        AgentManifest existing = catalog.find(agentId).orElse(null);
        String domain = existing != null ? existing.domain() : null;
        try {
            agentAuth.assertCanManageAgent(auth, domain);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).build();
        }
        registrar.deregister(agentId);
        return ResponseEntity.noContent().build();
    }
}
