package ai.conduit.gateway.domain.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fetches and caches the JWKS (public key set) from the Axiom (iam-service) service.
 * Refreshes the cache every 5 minutes or on key-not-found.
 */
@Component
public class JwksClient {

    private static final Logger log = LoggerFactory.getLogger(JwksClient.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    @Value("${conduit.auth.jwks-url:http://localhost:8084/.well-known/jwks.json}")
    private String jwksUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, RSAPublicKey> keyCache = new ConcurrentHashMap<>();
    private volatile Instant cacheExpiry = Instant.MIN;
    private final ReentrantLock refreshLock = new ReentrantLock();
    private final MeterRegistry meterRegistry;

    public JwksClient(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void warmUp() {
        try {
            refreshLock.lock();
            try { fetchKeys(); } finally { refreshLock.unlock(); }
            log.info("JwksClient: pre-loaded {} key(s) at startup", keyCache.size());
        } catch (Exception e) {
            log.warn("JwksClient: startup pre-load failed (will retry on first request): {}", e.getMessage());
        }
    }

    /**
     * Return the RSA public key for the given kid.
     * Refreshes the cache if stale or if the kid is unknown.
     */
    public RSAPublicKey getPublicKey(String kid) {
        RSAPublicKey cached = keyCache.get(kid);
        if (cached != null && Instant.now().isBefore(cacheExpiry)) {
            return cached;
        }
        // Stale cache or unknown kid (e.g. mid key-rotation). BLOCK on the refresh lock rather
        // than tryLock-and-bail: bailing returned a spurious null → a 401 while another thread
        // was still fetching the rotated key. Re-check under the lock so concurrent callers
        // coalesce onto a single network refresh instead of stampeding.
        refreshLock.lock();
        try {
            cached = keyCache.get(kid);
            if (cached != null && Instant.now().isBefore(cacheExpiry)) {
                return cached;  // a concurrent refresh already satisfied this kid
            }
            fetchKeys();
        } finally {
            refreshLock.unlock();
        }
        return keyCache.get(kid);
    }

    /** Fetches the JWKS and replaces the cache. Must be called while holding {@link #refreshLock}. */
    private void fetchKeys() {
        try {
            log.debug("JwksClient: refreshing keys from {}", jwksUrl);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JWKSet jwkSet = JWKSet.parse(resp.body());
            keyCache.clear();
            for (var jwk : jwkSet.getKeys()) {
                if (jwk instanceof RSAKey rsaKey) {
                    keyCache.put(jwk.getKeyID(), rsaKey.toRSAPublicKey());
                    log.info("JwksClient: loaded key kid={}", jwk.getKeyID());
                }
            }
            cacheExpiry = Instant.now().plus(CACHE_TTL);
        } catch (Exception e) {
            Counter.builder("conduit.jwks.refresh.failures")
                    .description("JWKS refresh failures")
                    .register(meterRegistry)
                    .increment();
            log.error("JwksClient: JWKS refresh failed — {}", e.getMessage());
        }
    }
}
