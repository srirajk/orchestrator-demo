package ai.conduit.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AppConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Legacy synchronous client used by non-agent helper clients.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Synchronous client for agent data-plane calls.
     *
     * <p>Agent calls run under explicit OTel {@code agent.invoke} spans and
     * {@code HttpAdapter} injects W3C trace context from that active span. Keep this
     * client non-observing so Micrometer's RestClient observation cannot replace the
     * carrier with a detached client trace.
     */
    @Bean
    public RestClient agentRestClient() {
        return RestClient.builder().build();
    }
}
