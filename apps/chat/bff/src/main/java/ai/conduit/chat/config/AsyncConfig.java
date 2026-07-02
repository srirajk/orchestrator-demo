package ai.conduit.chat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Provides a virtual-thread executor used for fire-and-forget background work
 * (e.g. rolling-summary regeneration) that must never block or fail the request
 * that scheduled it.
 */
@Configuration
public class AsyncConfig {

    /**
     * An unbounded per-task virtual-thread executor. Suitable for short, I/O-bound
     * background tasks; each task gets its own lightweight virtual thread.
     */
    @Bean(destroyMethod = "close")
    public ExecutorService backgroundExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
