package ai.conduit.chat.files;

import ai.conduit.chat.config.AppProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * MinIO/S3-backed {@link StorageService}. The bucket is created on first use if absent.
 */
@Service
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final AppProperties.Storage config;
    private final MinioClient client;
    private volatile boolean bucketEnsured = false;

    public MinioStorageService(AppProperties appProperties) {
        this.config = appProperties.storage();
        this.client = MinioClient.builder()
                .endpoint(config.endpoint())
                .credentials(config.accessKey(), config.secretKey())
                .build();
    }

    @Override
    public String putObject(String objectName, InputStream data, long size, String contentType) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(config.bucket())
                    .object(objectName)
                    .stream(data, size, -1)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build());
            return objectName;
        } catch (Exception ex) {
            throw new StorageException("Failed to store object " + objectName, ex);
        }
    }

    private void ensureBucket() {
        if (bucketEnsured) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured) {
                return;
            }
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(config.bucket()).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(config.bucket()).build());
                    log.info("[storage] created bucket {}", config.bucket());
                }
                bucketEnsured = true;
            } catch (Exception ex) {
                throw new StorageException("Failed to ensure bucket " + config.bucket(), ex);
            }
        }
    }
}
