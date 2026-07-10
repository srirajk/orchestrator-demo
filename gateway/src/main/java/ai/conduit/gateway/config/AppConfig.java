package ai.conduit.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;

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
     * Synchronous client used by {@code EntityExtractor} (LLM) and {@code EntityResolver} (CRM).
     *
     * <p><b>Timeouts are not optional.</b> A bare {@code new RestTemplate()} uses
     * {@code SimpleClientHttpRequestFactory} over {@code HttpURLConnection}, whose read timeout
     * defaults to {@code -1} — infinite. Proven with Toxiproxy: hang the upstream and the request
     * parks forever; the client disconnects at its own deadline and the gateway keeps holding the
     * request, never erroring, never completing, still reporting itself healthy.
     *
     * <p>Also on the JDK {@code HttpClient} rather than {@code HttpURLConnection}, pinned to
     * HTTP/1.1 like every other outbound client here. {@code HttpURLConnection.getInputStream0()} is
     * {@code synchronized}, so on a pre-JEP-491 JDK a blocking read inside it pins its carrier.
     */
    @Bean
    public RestTemplate restTemplate(
            @Value("${conduit.http.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${conduit.http.read-timeout-ms:30000}") long readTimeoutMs) {
        return new RestTemplate(timedFactory(connectTimeoutMs, readTimeoutMs));
    }

    /**
     * Synchronous client for agent data-plane calls.
     *
     * <p>Agent calls run under explicit OTel {@code agent.invoke} spans and
     * {@code HttpAdapter} injects W3C trace context from that active span. Keep this
     * client non-observing so Micrometer's RestClient observation cannot replace the
     * carrier with a detached client trace.
     *
     * <p>Read timeout defaults above the harness's per-agent SLA, so the SLA — not the socket —
     * decides when an agent has taken too long, while a hung agent can still never park a request
     * forever.
     */
    @Bean
    public RestClient agentRestClient(
            @Value("${conduit.http.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${conduit.http.agent-read-timeout-ms:35000}") long agentReadTimeoutMs) {
        return RestClient.builder()
                .requestFactory(timedFactory(connectTimeoutMs, agentReadTimeoutMs))
                .build();
    }

    /** JDK HttpClient, HTTP/1.1, with a finite connect AND read timeout. */
    private static ClientHttpRequestFactory timedFactory(long connectTimeoutMs, long readTimeoutMs) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return factory;
    }
}
