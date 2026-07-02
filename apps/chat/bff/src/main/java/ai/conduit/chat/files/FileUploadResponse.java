package ai.conduit.chat.files;

/**
 * Response for {@code POST /api/files}.
 *
 * @param id         opaque file id (equal to the durable storage key).
 * @param name       original client filename.
 * @param mime       content type as reported on upload.
 * @param size       size in bytes.
 * @param storageKey durable object key in the store.
 */
public record FileUploadResponse(String id, String name, String mime, long size, String storageKey) {
}
