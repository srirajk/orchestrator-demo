package ai.conduit.gateway.registry.index;

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
import java.time.Duration;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * EmbeddingClient that delegates to an OpenAI-compatible /v1/embeddings endpoint.
 *
 * Default: the local sentence-transformers sidecar (all-MiniLM-L6-v2, 384-dim).
 * Activated when conduit.embedding.provider=remote.
 */
@Service
@ConditionalOnProperty(name = "conduit.embedding.provider", havingValue = "remote")
public class RemoteEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbeddingClient.class);

    @Value("${conduit.embedding.remote.url:http://localhost:8083/v1/embeddings}")
    private String endpointUrl;

    @Value("${conduit.embedding.dimension:384}")
    private int dimension;

    @Value("${conduit.embedding.model:all-MiniLM-L6-v2}")
    private String modelName;

    /**
     * Connect/read timeouts for the embedding hop, which sits on the routing path of EVERY request.
     *
     * <p>This client used to be a bare {@code new RestTemplate()} — {@code HttpURLConnection} with a
     * read timeout of {@code -1}, i.e. infinite. Proven with Toxiproxy: hang the embeddings service
     * and the request parks forever. The client gives up at its own deadline; the gateway keeps
     * holding the request, never erroring, never completing, still reporting itself healthy.
     *
     * <p>Note this instance is built here rather than injected: it is deliberately *not* the shared
     * {@code AppConfig.restTemplate()} bean, because the embedding hop should fail far faster than an
     * LLM call. A fix that only touched the shared bean would have missed this site entirely.
     */
    @Value("${conduit.embedding.connect-timeout-ms:2000}")
    private long connectTimeoutMs;

    @Value("${conduit.embedding.read-timeout-ms:5000}")
    private long readTimeoutMs;

    private RestTemplate restTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void probe() {
        // HTTP/1.1 like every other outbound client here; JDK HttpClient rather than
        // HttpURLConnection (whose getInputStream0() is synchronized and pins a carrier pre-JEP-491).
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restTemplate = new RestTemplate(factory);
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
