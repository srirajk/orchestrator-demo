package com.openwolf.iam.config;

import dev.cerbos.sdk.CerbosBlockingClient;
import dev.cerbos.sdk.CerbosClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates a {@link CerbosBlockingClient} when {@code iam.cerbos.authz-enabled=true}.
 * <p>
 * When disabled (dev mode), the bean is absent and {@link com.openwolf.iam.service.CerbosAuthzService}
 * detects the absence, logs a WARN, and skips the authorization check.
 * </p>
 */
@Configuration
@ConditionalOnProperty(name = "iam.cerbos.authz-enabled", havingValue = "true", matchIfMissing = true)
public class CerbosConfig {

    private static final Logger log = LoggerFactory.getLogger(CerbosConfig.class);

    @Value("${iam.cerbos.host:cerbos}")
    private String cerbosHost;

    @Value("${iam.cerbos.port:3593}")
    private int cerbosPort;

    @Bean
    public CerbosBlockingClient cerbosBlockingClient() {
        String target = cerbosHost + ":" + cerbosPort;
        log.info("Connecting to Cerbos PDP at {}", target);
        try {
            return new CerbosClientBuilder(target)
                    .withPlaintext()
                    .buildBlockingClient();
        } catch (Exception ex) {
            log.error("Failed to build Cerbos blocking client for target={} — authorization checks will fail-open", target, ex);
            throw new IllegalStateException("Cannot connect to Cerbos PDP at " + target, ex);
        }
    }
}
