package ai.conduit.chat.files;

import ai.conduit.chat.auth.CurrentUser;
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
 * via the {@link StorageService} seam. Kept minimal but backed by a real interface.
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final StorageService storageService;
    private final CurrentUser currentUser;

    public FileController(StorageService storageService, CurrentUser currentUser) {
        this.storageService = storageService;
        this.currentUser = currentUser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileUploadResponse upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file uploaded");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String objectName = "uploads/" + currentUser.id() + "/" + System.currentTimeMillis() + "-" + original;
        try {
            String storageKey = storageService.putObject(
                    objectName, file.getInputStream(), file.getSize(), file.getContentType());
            return new FileUploadResponse(storageKey, original, storageKey);
        } catch (IOException ex) {
            throw new BadRequestException("Could not read uploaded file");
        }
    }
}
