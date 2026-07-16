package ai.conduit.gateway.testsupport;

import ai.conduit.gateway.infrastructure.payload.PayloadSpiller;
import ai.conduit.gateway.infrastructure.payload.PayloadStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Test helpers for wiring a {@link PayloadSpiller} without a Spring context. {@link ObjectProvider} is not
 * a functional interface (it has more than one abstract method), so tests need an explicit stub.
 */
public final class PayloadTestSupport {

    private PayloadTestSupport() {
    }

    /** An {@link ObjectProvider} that yields {@code store} (or nothing, when {@code store} is null). */
    public static ObjectProvider<PayloadStore> provider(PayloadStore store) {
        return new ObjectProvider<>() {
            @Override public PayloadStore getObject() {
                if (store == null) throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("no store");
                return store;
            }
            @Override public PayloadStore getObject(Object... args) { return getObject(); }
            @Override public PayloadStore getIfAvailable() { return store; }
            @Override public PayloadStore getIfUnique() { return store; }
        };
    }

    /** A {@link PayloadSpiller} over {@code store} (nullable) at the given spill threshold. */
    public static PayloadSpiller spiller(PayloadStore store, long thresholdBytes) {
        return new PayloadSpiller(provider(store), new SimpleMeterRegistry(), thresholdBytes);
    }
}
