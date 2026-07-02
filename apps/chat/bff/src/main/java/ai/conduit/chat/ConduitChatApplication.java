package ai.conduit.chat;

import ai.conduit.chat.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Conduit Chat BFF — a persistent, LibreChat-like chat backend for the Conduit
 * enterprise AI gateway.
 *
 * <p>This is a faithful Java/Spring Boot re-implementation of the reference Node
 * BFF. It owns conversation persistence, per-user isolation, client-side memory
 * (context assembly + a facts-free rolling summary), and it proxies the OpenAI-
 * compatible streaming call to the Conduit gateway, forwarding the signed-in
 * user's OIDC access token so the gateway can enforce that user's entitlements.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class ConduitChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConduitChatApplication.class, args);
    }
}
