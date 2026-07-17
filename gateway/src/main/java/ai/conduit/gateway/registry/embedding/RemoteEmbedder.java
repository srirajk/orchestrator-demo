package ai.conduit.gateway.registry.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link TextEmbedder} backed by an OpenAI-compatible {@code /v1/embeddings} endpoint —
 * by default the local sentence-transformers sidecar (all-MiniLM-L6-v2, 384-dim).
 *
 * <p>Active when {@code conduit.embedding.provider=remote}.
 *
 * <h2>Stateless — safe to share across tenants (Axiom A4, CLAUDE.md §3)</h2>
 * This embedder holds no per-tenant, per-caller, or per-request state: {@link #embed} and
 * {@link #embedBatch} are pure functions of {@code (text, model, dimension)} — the same text always
 * yields the same vector, whichever tenant asks. There is no tenant-specific preprocessing (no
 * per-tenant tokenizer, vocabulary, normalisation, or prompt prefix); the only mutable field is the
 * {@link RestTemplate} built once in {@link #probe()} and never mutated per call. The one Python
 * service the request path calls (the intended in-JVM move) is therefore a single shared sidecar for
 * all tenants, not one per tenant. Because the vector is a pure function of the text, the corpus
 * cache in {@link ManifestEmbedder} — keyed on {@code (model-id, sha256(text))} — is tenant-agnostic
 * by construction: two tenants ingesting the identical example text share the cached vector without
 * cross-tenant leakage, because the vector carries no tenant identity to leak. Proven by
 * {@code EmbedderStatelessnessTest} (same text under two tenant contexts ⇒ byte-identical vectors).
 */
@Service
@ConditionalOnProperty(name = "conduit.embedding.provider", havingValue = "remote")
public class RemoteEmbedder implements TextEmbedder {

    private static final Logger log = LoggerFactory.getLogger(RemoteEmbedder.class);

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

    @Override
    public String id() {
        return "remote:" + modelName + ":" + dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

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
        log.info("RemoteEmbedder → {} (id={})", endpointUrl, id());
        try {
            assertDimension(embed("probe"));
            log.info("Embedding probe OK — dim={}", dimension);
        } catch (DimensionMismatchException e) {
            // A reachable service answering with the wrong dimension is a misconfiguration, not a
            // transient. Booting anyway would build the index in one space and search it in another.
            throw e;
        } catch (Exception e) {
            // Unreachable is expected during startup ordering; the bootstrap loader retries.
            log.warn("Embedding probe failed (service may still be starting): {} — {}",
                    e.getMessage(), rootCause(e));
        }
    }

    @Override
    public float[] embed(String text) {
        List<float[]> vectors = post(mapper.createArrayNode().add(text));
        if (vectors.isEmpty()) {
            throw new IllegalStateException("Embedding service returned no vector for a single input");
        }
        return assertDimension(vectors.get(0));
    }

    /**
     * One request for the whole batch. The agent corpus is embedded as a unit at startup; issuing a
     * round trip per example turned a single call into one per skill example.
     */
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        ArrayNode input = mapper.createArrayNode();
        texts.forEach(input::add);

        List<float[]> vectors = post(input);
        if (vectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding service returned " + vectors.size()
                    + " vectors for " + texts.size() + " inputs");
        }
        vectors.forEach(this::assertDimension);
        return vectors;
    }

    private List<float[]> post(ArrayNode input) {
        try {
            ObjectNode body = mapper.createObjectNode().put("model", modelName);
            body.set("input", input);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> resp = restTemplate.exchange(
                    endpointUrl,
                    HttpMethod.POST,
                    new HttpEntity<>(mapper.writeValueAsString(body), headers),
                    String.class);

            JsonNode data = mapper.readTree(resp.getBody()).path("data");
            List<float[]> vectors = new ArrayList<>(data.size());
            for (JsonNode entry : data) {
                JsonNode embedding = entry.path("embedding");
                float[] vec = new float[embedding.size()];
                for (int i = 0; i < vec.length; i++) {
                    vec[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vec);
            }
            return vectors;

        } catch (DimensionMismatchException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Embedding call to " + endpointUrl + " failed: " + rootCause(e), e);
        }
    }

    private float[] assertDimension(float[] vec) {
        if (vec.length != dimension) {
            throw new DimensionMismatchException(
                    "Embedding service at " + endpointUrl + " returned " + vec.length
                    + "-dim vectors but conduit.embedding.dimension is " + dimension
                    + ". The routing index would be built in one vector space and searched in another.");
        }
        return vec;
    }

    /** A reachable embedding service whose vectors do not match the configured dimension. */
    public static class DimensionMismatchException extends IllegalStateException {
        public DimensionMismatchException(String message) {
            super(message);
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
