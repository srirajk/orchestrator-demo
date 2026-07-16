package ai.conduit.gateway.infrastructure.payload;

import ai.conduit.gateway.adapter.PayloadHandle;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The spill decision, shared by every adapter (HTTP body over threshold, MCP fetched resource). Given a
 * FINAL stamped tree it returns the right {@link PayloadHandle}:
 *
 * <ul>
 *   <li>store absent / disabled, or {@code conduit.payload.spill-threshold-bytes < 0} (the SHIPPED
 *       default {@code -1} = never spill), or canonical bytes at/under threshold → {@link PayloadHandle.Inline};</li>
 *   <li>over threshold and a store is present → synchronous {@code store.put} of the SAME canonical bytes
 *       the audit path hashes, under key = their sha → {@link PayloadHandle.Ref} that still keeps the tree
 *       in-memory (v1 no-re-fetch);</li>
 *   <li>a store present but failing → {@link PayloadHandle.Inline} fallback + WARN + a
 *       {@code conduit.payload.spill.failed} counter. The request still answers.</li>
 * </ul>
 *
 * <p>Hashing runs ONLY here, at spill time, and only when the threshold is crossed — never in the
 * {@code NodeResult} compat wrap, never at default config.
 */
@Component
public class PayloadSpiller {

    private static final Logger log = LoggerFactory.getLogger(PayloadSpiller.class);

    private final ObjectProvider<PayloadStore> storeProvider;
    private final MeterRegistry meterRegistry;
    private final long spillThresholdBytes;

    public PayloadSpiller(ObjectProvider<PayloadStore> storeProvider,
                          MeterRegistry meterRegistry,
                          @Value("${conduit.payload.spill-threshold-bytes:-1}") long spillThresholdBytes) {
        this.storeProvider = storeProvider;
        this.meterRegistry = meterRegistry;
        this.spillThresholdBytes = spillThresholdBytes;
    }

    /** Wrap {@code stampedTree} as Inline or a spilled Ref, per threshold and store availability. */
    public PayloadHandle handleFor(JsonNode stampedTree, PayloadHandle.Provenance provenance) {
        if (stampedTree == null) {
            return null;
        }
        PayloadStore store = storeProvider.getIfAvailable();
        if (store == null || spillThresholdBytes < 0) {
            return new PayloadHandle.Inline(stampedTree, provenance);
        }
        byte[] canonical = CanonicalSha.canonicalBytes(stampedTree);
        if (canonical.length <= spillThresholdBytes) {
            return new PayloadHandle.Inline(stampedTree, provenance);
        }
        String sha = CanonicalSha.sha256Hex(canonical);
        try {
            return store.put(canonical, sha, "application/json", provenance, stampedTree);
        } catch (Exception e) {
            log.warn("Payload spill failed (sha={}, {} bytes) — keeping Inline: {}",
                    sha, canonical.length, e.getMessage());
            meterRegistry.counter("conduit.payload.spill.failed",
                    "provenance", provenance.name().toLowerCase()).increment();
            return new PayloadHandle.Inline(stampedTree, provenance);
        }
    }
}
