package ai.conduit.gateway.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for object-store integration tests (F4 PayloadStore): a throwaway MinIO in a container, never a
 * developer's live MinIO. Mirrors {@link RedisContainerTest} — a JVM-singleton {@link GenericContainer}
 * (no dedicated Testcontainers MinIO module needed, so it runs fully offline against the locally-present
 * {@code minio/minio} image), started once and shared, Ryuk-reaped at exit.
 */
public abstract class MinioContainerTest {

    protected static final String ACCESS_KEY = "conduit";
    protected static final String SECRET_KEY = "conduit-secret";

    private static final GenericContainer<?> MINIO =
            new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
                    .withExposedPorts(9000)
                    .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
                    .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
                    .withCommand("server", "/data")
                    .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    static {
        MINIO.start();
    }

    /** The S3 endpoint to point a path-style client at. */
    protected static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }
}
