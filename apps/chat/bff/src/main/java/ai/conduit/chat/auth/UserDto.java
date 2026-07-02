package ai.conduit.chat.auth;

import java.util.List;

/** Public profile shape returned by {@code GET /api/me}. */
public record UserDto(String id, String username, String email, List<String> roles) {
}
