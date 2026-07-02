package ai.conduit.chat.project;

/** Body for {@code PATCH /api/projects/{id}}. All fields optional (partial update). */
public record UpdateProjectRequest(String name, String color) {
}
