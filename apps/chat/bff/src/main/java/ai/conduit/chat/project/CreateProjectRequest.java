package ai.conduit.chat.project;

import jakarta.validation.constraints.NotBlank;

/** Body for {@code POST /api/projects}. */
public record CreateProjectRequest(@NotBlank String name, String color) {
}
