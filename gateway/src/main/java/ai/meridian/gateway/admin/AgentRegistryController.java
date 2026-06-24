package ai.meridian.gateway.admin;

import ai.meridian.gateway.registry.model.AgentManifest;
import ai.meridian.gateway.registry.service.AgentRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 */
@RestController
@RequestMapping("/admin/agents")
public class AgentRegistryController {

    private final AgentRegistry registry;

    public AgentRegistryController(AgentRegistry registry) {
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<?> register(@RequestBody JsonNode submission) {
        try {
            AgentManifest stored = registry.register(submission);
            return ResponseEntity.status(HttpStatus.CREATED).body(stored);
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
            @RequestBody JsonNode submission) {
        try {
            AgentManifest updated = registry.update(agentId, submission);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deregister(@PathVariable String agentId) {
        if (!registry.exists(agentId)) return ResponseEntity.notFound().build();
        registry.deregister(agentId);
        return ResponseEntity.noContent().build();
    }
}
