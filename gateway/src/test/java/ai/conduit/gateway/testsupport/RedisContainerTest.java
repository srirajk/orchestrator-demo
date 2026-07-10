package ai.conduit.gateway.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for full-context ({@code @SpringBootTest}) tests: they get their own Redis, in a
 * container, never the developer's live demo Redis.
 *
 * <p>Why this exists. {@code RedisConfig} resolves {@code spring.data.redis.host} to its default,
 * {@code localhost:6379} — which on a developer machine is the running demo's Redis. Without this,
 * booting a Spring context in a test connected to live routing data, and a change to the vector
 * index once let {@code mvn test} drop the demo's registry. Redirecting the context at a throwaway
 * container makes {@code mvn test} incapable of touching the demo, whatever a test does with Redis.
 *
 * <p>The container is a singleton started once per JVM and shared across every test class that
 * extends this, so the suite pays the startup cost a single time. Ryuk removes it at exit. The image
 * is the same Redis Stack the compose stack runs, so RediSearch/RedisJSON behave identically.
 */
public abstract class RedisContainerTest {

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis/redis-stack-server:7.4.0-v3"))
                    .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
