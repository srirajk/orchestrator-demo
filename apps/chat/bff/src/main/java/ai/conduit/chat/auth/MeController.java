package ai.conduit.chat.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/me} — the authenticated user's public profile. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final CurrentUser currentUser;

    public MeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    @GetMapping
    public UserDto me() {
        return currentUser.toDto();
    }
}
