package ai.conduit.gateway.admin;

import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.registry.service.AgentRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only view of the agent registry.
 *
 * <pre>
 * GET /admin/agents          list all registered agents
 * GET /admin/agents/{id}     fetch one agent manifest
 * </pre>
 *
 * <p>Registration, update, and deregistration used to live here. They run the full ingestion
 * pipeline — schema validation, live-agent introspection, corpus embedding, and a rewrite of the
 * routing vector index — from inside the service whose runtime job is to answer questions. They now
 * live on the registry service, which owns the registry folder and produces what the gateway reads:
 * see {@code ai.conduit.gateway.registry.api.AgentRegistrationController}. Authorization moved with
 * them, unchanged.
 *
 * <p>The gateway holds no registrar, no corpus embedder, and no index writer, so it has no code path
 * that can mutate routing data. Reading the catalogue is a Redis lookup and stays here.
 */
@RestController
@RequestMapping("/admin/agents")
public class AgentRegistryController {

    private final AgentRegistry registry;

    public AgentRegistryController(AgentRegistry registry) {
        this.registry = registry;
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
}
