package ai.conduit.chat.files;

import ai.conduit.chat.auth.CurrentUser;
import ai.conduit.chat.config.AppProperties;
import ai.conduit.chat.web.BadRequestException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * {@code POST /api/files} — multipart upload of a single {@code file} field, persisted
 * via the {@link StorageService} seam under a per-user key. Returns a compact reference
 * ({@code id, name, mime, size, storageKey}) the frontend can attach to a message.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final StorageService storageService;
    private final CurrentUser currentUser;
    private final AppProperties.Storage storageConfig;

    public FileController(StorageService storageService,
                          CurrentUser currentUser,
                          AppProperties appProperties) {
        this.storageService = storageService;
        this.currentUser = currentUser;
        this.storageConfig = appProperties.storage();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file uploaded");
        }
        if (file.getSize() > storageConfig.maxFileSize()) {
            throw new BadRequestException("File exceeds the maximum allowed size of "
                    + storageConfig.maxFileSize() + " bytes");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String mime = file.getContentType() != null ? file.getContentType() : DEFAULT_CONTENT_TYPE;
        String objectName = "uploads/" + currentUser.id() + "/" + System.currentTimeMillis() + "-" + original;
        try {
            String storageKey = storageService.putObject(
                    objectName, file.getInputStream(), file.getSize(), mime);
            return new FileUploadResponse(storageKey, original, mime, file.getSize(), storageKey);
        } catch (IOException ex) {
            throw new BadRequestException("Could not read uploaded file");
        }
    }
}
