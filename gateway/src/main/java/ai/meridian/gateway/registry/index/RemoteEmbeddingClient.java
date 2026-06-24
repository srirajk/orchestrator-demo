package ai.meridian.gateway.registry.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * EmbeddingClient that delegates to an OpenAI-compatible /v1/embeddings endpoint.
 *
 * Default: the local sentence-transformers sidecar (all-MiniLM-L6-v2, 384-dim).
 * Activated when meridian.embedding.provider=remote.
 */
@Service
@ConditionalOnProperty(name = "meridian.embedding.provider", havingValue = "remote")
public class RemoteEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbeddingClient.class);

    @Value("${meridian.embedding.remote.url:http://localhost:8083/v1/embeddings}")
    private String endpointUrl;

    @Value("${meridian.embedding.dimension:384}")
    private int dimension;

    @Value("${meridian.embedding.model:all-MiniLM-L6-v2}")
    private String modelName;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void probe() {
        log.info("RemoteEmbeddingClient → {} (dim={}, model={})", endpointUrl, dimension, modelName);
        try {
            float[] probe = embed("probe");
            if (probe.length > 0) {
                log.info("Embedding probe OK — actual dim={}", probe.length);
            }
        } catch (Exception e) {
            log.warn("Embedding probe failed (service may still be starting): {} — {}",
                    e.getMessage(), rootCause(e));
        }
    }

    @Override
    public float[] embed(String text) {
        try {
            ObjectNode body = mapper.createObjectNode()
                    .put("model", modelName)
                    .put("input", text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    endpointUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers),
                    String.class);

            JsonNode root = mapper.readTree(resp.getBody());
            JsonNode embedding = root.path("data").path(0).path("embedding");

            float[] vec = new float[embedding.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embedding.get(i).asDouble();
            }
            return vec;

        } catch (Exception e) {
            throw new RuntimeException("Embedding call to " + endpointUrl + " failed: " + rootCause(e), e);
        }
    }

    @Override
    public int dimension() {
        return dimension;
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
