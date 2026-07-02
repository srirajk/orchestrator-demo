package ai.conduit.chat.files;

import java.io.InputStream;

/**
 * Object-storage seam for uploaded files. A real interface (not a controller TODO) so
 * the storage backend can be swapped without touching the web layer.
 */
public interface StorageService {

    /**
     * Stores an object and returns its durable storage key.
     *
     * @param objectName  desired object name / key.
     * @param data        object bytes.
     * @param size        object size in bytes.
     * @param contentType MIME type (may be null).
     * @return the storage key under which the object was stored.
     */
    String putObject(String objectName, InputStream data, long size, String contentType);
}
