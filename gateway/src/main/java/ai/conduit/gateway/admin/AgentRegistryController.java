package ai.conduit.gateway.admin;

import ai.conduit.gateway.domain.auth.AgentAuthorization;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent registry management API.
 *
 * POST   /admin/agents          register an agent (full pipeline)
 * GET    /admin/agents          list all registered agents
 * GET    /admin/agents/{id}     fetch one agent manifest
 * PUT    /admin/agents/{id}     update + re-introspect
 * DELETE /admin/agents/{id}     deregister
 *
 * Phase 10: coarse URL-level role check is in SecurityConfig; fine domain-scope check
 * is done here via {@link AgentAuthorization}.
 */
@RestController
@RequestMapping("/admin/agents")
public class AgentRegistryController {

    private final AgentRegistry registry;
    private final AgentAuthorization agentAuth;

    public AgentRegistryController(AgentRegistry registry, AgentAuthorization agentAuth) {
        this.registry  = registry;
        this.agentAuth = agentAuth;
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody JsonNode submission, Authentication auth) {
        try {
            String domain = submission.path("domain").asText(null);
            agentAuth.assertCanManageAgent(auth, domain);
            AgentManifest stored = registry.register(submission);
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public List<AgentManifest> list() {
        return registry.listAll();
    }

    @GetMapping("/{agentId}")
    public ResponseEntity<?> get(@PathVariable String agentId) {
        return registry.find(agentId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{agentId}")
    public ResponseEntity<?> update(
            @PathVariable String agentId,
            @RequestBody JsonNode submission,
            Authentication auth) {
        try {
            String domain = submission.path("domain").asText(null);
            agentAuth.assertCanManageAgent(auth, domain);
            AgentManifest updated = registry.update(agentId, submission);
            return ResponseEntity.ok(updated);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deregister(@PathVariable String agentId, Authentication auth) {
        if (!registry.exists(agentId)) return ResponseEntity.notFound().build();
        // For delete, check domain scope against the existing agent's domain
        AgentManifest existing = registry.find(agentId).orElse(null);
        String domain = existing != null ? existing.domain() : null;
        try {
            agentAuth.assertCanManageAgent(auth, domain);
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(403).build();
        }
        registry.deregister(agentId);
        return ResponseEntity.noContent().build();
    }
}
