package ai.conduit.chat.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lightweight, dependency-free liveness endpoint at {@code /health} for the container
 * healthcheck (parity with the reference Node BFF). Deeper checks live under
 * {@code /actuator/health}.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true);
    }
}
