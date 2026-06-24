package ai.meridian.gateway.adapter.http;

import ai.meridian.gateway.adapter.ProtocolAdapter;
import ai.meridian.gateway.registry.model.AgentManifest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProtocolAdapter for HTTP / OpenAPI agents (Wealth domain).
 *
 * <p>For each invocation:
 * <ol>
 *   <li>Derive the agent's base URL by stripping the {@code /openapi.json} suffix
 *       from {@code manifest.connection().openapiUrl()}.</li>
 *   <li>Fetch (and cache) the OpenAPI spec from that base URL.</li>
 *   <li>Scan the spec's {@code paths} to find the path + HTTP method whose
 *       {@code operationId} matches {@code manifest.connection().operationId()}.</li>
 *   <li>Build the request: GET → query params; POST → JSON body.</li>
 *   <li>Return the response parsed as {@link JsonNode}.</li>
 * </ol>
 *
 * <p>The OpenAPI spec cache is keyed by base URL and is safe for concurrent reads
 * after the first population.
 */
@Service
public class HttpAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpAdapter.class);

    /** Simple spec cache: base-URL → parsed OpenAPI document. */
    private final ConcurrentHashMap<String, JsonNode> specCache = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public HttpAdapter(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public String protocol() {
        return "http";
    }

    @Override
    public JsonNode invoke(AgentManifest manifest, JsonNode input) throws Exception {
        String openapiUrl = manifest.connection().openapiUrl();
        if (openapiUrl == null || openapiUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Agent " + manifest.agentId() + " has no openapiUrl in its connection");
        }

        String baseUrl = deriveBaseUrl(openapiUrl);
        JsonNode spec = fetchSpec(baseUrl, openapiUrl);

        OperationInfo op = findOperation(spec, manifest.connection().operationId());
        if (op == null) {
            throw new IllegalStateException(
                    "operationId '" + manifest.connection().operationId()
                    + "' not found in OpenAPI spec at " + openapiUrl);
        }

        String fullPath = baseUrl + op.path();
        log.debug("HttpAdapter → {} {} for agent {}", op.method(), fullPath, manifest.agentId());

        String responseBody;
        if ("get".equalsIgnoreCase(op.method())) {
            responseBody = invokeGet(fullPath, input);
        } else {
            responseBody = invokePost(fullPath, input);
        }

        return objectMapper.readTree(responseBody);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Strip the OpenAPI path suffix to obtain the server base URL.
     * e.g. {@code "http://wealth-http:8081/openapi.json"} → {@code "http://wealth-http:8081"}
     */
    private String deriveBaseUrl(String openapiUrl) {
        // Find the last '/' that precedes the filename and chop from there.
        // We look for known suffixes first, then fall back to stripping the last path segment.
        if (openapiUrl.endsWith("/openapi.json")) {
            return openapiUrl.substring(0, openapiUrl.length() - "/openapi.json".length());
        }
        int lastSlash = openapiUrl.lastIndexOf('/');
        if (lastSlash > 8) { // keep "http://"
            return openapiUrl.substring(0, lastSlash);
        }
        return openapiUrl;
    }

    /** Fetch and cache the OpenAPI spec; uses computeIfAbsent for thread safety. */
    private JsonNode fetchSpec(String baseUrl, String openapiUrl) throws Exception {
        // computeIfAbsent is atomic — only one thread fetches per base URL.
        JsonNode cached = specCache.get(baseUrl);
        if (cached != null) {
            return cached;
        }
        log.info("Fetching OpenAPI spec from {}", openapiUrl);
        String raw = restTemplate.getForObject(openapiUrl, String.class);
        if (raw == null) {
            throw new IllegalStateException("Empty OpenAPI spec returned from " + openapiUrl);
        }
        JsonNode parsed = objectMapper.readTree(raw);
        specCache.putIfAbsent(baseUrl, parsed);
        return specCache.get(baseUrl);
    }

    /**
     * Scan the OpenAPI {@code paths} tree for an entry whose method object contains
     * the target {@code operationId}.
     */
    private OperationInfo findOperation(JsonNode spec, String operationId) {
        JsonNode paths = spec.path("paths");
        if (paths.isMissingNode()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
        while (pathIter.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIter.next();
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            for (String httpMethod : new String[]{"get", "post", "put", "patch", "delete"}) {
                JsonNode opNode = pathItem.path(httpMethod);
                if (!opNode.isMissingNode()) {
                    String opId = opNode.path("operationId").asText(null);
                    if (operationId.equals(opId)) {
                        return new OperationInfo(path, httpMethod);
                    }
                }
            }
        }
        return null;
    }

    /** Build a GET request by appending all input fields as query parameters. */
    private String invokeGet(String url, JsonNode input) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (input != null) {
            Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String value = field.getValue().isTextual()
                        ? field.getValue().asText()
                        : field.getValue().toString();
                builder.queryParam(field.getKey(), value);
            }
        }
        URI uri = builder.build(true).toUri();
        log.debug("GET {}", uri);
        String response = restTemplate.getForObject(uri, String.class);
        return response != null ? response : "{}";
    }

    /** POST the input JsonNode as the request body. */
    private String invokePost(String url, JsonNode input) throws Exception {
        log.debug("POST {} body={}", url, input);
        String body = input != null ? objectMapper.writeValueAsString(input) : "{}";
        // RestTemplate sends application/json by default when given a String body
        // via exchange; use postForObject with a plain String for simplicity.
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.http.HttpEntity<String> entity =
                new org.springframework.http.HttpEntity<>(body, headers);
        String response = restTemplate.postForObject(url, entity, String.class);
        return response != null ? response : "{}";
    }

    /** Lightweight value type for a matched OpenAPI operation. */
    private record OperationInfo(String path, String method) {}
}
