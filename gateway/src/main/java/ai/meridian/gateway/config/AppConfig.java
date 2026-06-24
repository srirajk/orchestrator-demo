package ai.meridian.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
     * Shared RestTemplate used by outbound HTTP clients (EntityExtractor,
     * RemoteEmbeddingClient, HttpAdapter).  Virtual threads handle the
     * concurrency so a plain blocking RestTemplate is appropriate here.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
