package ai.conduit.gateway.admin;

import ai.conduit.gateway.registry.model.RoutingCandidate;
import ai.conduit.gateway.resolver.model.ResolverResult;
import ai.conduit.gateway.resolver.service.AgentResolver;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Debug endpoint for inspecting routing decisions without invoking any agents.
 *
 * GET /debug/resolve?prompt=<text>[&domain=wealth-management]
 *
 * Returns the selected agents, their similarity scores, and the fallback flag.
 * Used at the Phase 3 human test gate.
 */
@RestController
@RequestMapping("/debug")
public class DebugResolverController {

    private final AgentResolver resolver;

    public DebugResolverController(AgentResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/resolve")
    public ResolveDebugResponse resolve(
            @RequestParam String prompt,
            @RequestParam(required = false) String domain) {

        ResolverResult result = resolver.resolve(prompt, domain);

        List<CandidateView> selected = result.selected().stream()
                .map(c -> new CandidateView(
                        c.manifest().agentId(),
                        c.manifest().name(),
                        c.manifest().protocol(),
                        c.manifest().domain(),
                        c.score()))
                .toList();

        return new ResolveDebugResponse(
                prompt,
                selected,
                result.fallback(),
                result.topScore()
        );
    }

    public record CandidateView(
            @JsonProperty("agent_id")  String agentId,
            String name,
            String protocol,
            String domain,
            double score
    ) {}

    public record ResolveDebugResponse(
            String prompt,
            List<CandidateView> selected,
            boolean fallback,
            @JsonProperty("top_score") double topScore
    ) {}
}
