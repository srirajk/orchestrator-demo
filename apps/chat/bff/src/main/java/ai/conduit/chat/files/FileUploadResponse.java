package ai.conduit.chat.files;

/** Response for {@code POST /api/files}. */
public record FileUploadResponse(String id, String name, String storageKey) {
}
