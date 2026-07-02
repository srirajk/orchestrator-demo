package ai.conduit.chat.project;

/** Project response shape. Exposes {@code id} (string), never the Mongo {@code _id}. */
public record ProjectDto(String id, String name, String color) {
    public static ProjectDto from(Project p) {
        return new ProjectDto(p.getId(), p.getName(), p.getColor());
    }
}
