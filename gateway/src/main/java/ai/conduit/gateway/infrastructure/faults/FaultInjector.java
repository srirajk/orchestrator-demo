package ai.conduit.gateway.infrastructure.faults;

/**
 * A named fault seam the test rig can trigger at pinned points on the request path (F5 spec §3c).
 *
 * <p>Production wiring is a constructor-injected bean. In every normal context the bound
 * implementation is {@link NoopFaultInjector}, whose {@link #at(String)} does nothing and adds no
 * control-flow branch — proven by {@code FaultInjectorTest.disabled_injector_completes_and_invokes_noop}.
 * The active-throwing implementation ({@link ThrowingFaultInjector}) is bound only when
 * {@code conduit.test.faults.enabled=true} AND a test/perf marker is present; otherwise the context
 * refuses to start (see {@link FaultConfig}).
 *
 * <p>Point names are opaque, domain-free strings (e.g. {@code "harness.before-invoke"}); this seam
 * carries no domain knowledge and is World-B inert.
 */
public interface FaultInjector {

    /**
     * Reached at a pinned point. The no-op binding returns immediately; the armed binding throws a
     * {@link FaultInjectedException} iff {@code point} is armed. Because the harness reaches this
     * inside its try/catch immediately before the adapter call, a thrown fault becomes a
     * {@code NodeResult.FAILED}, never a leaked exception (harness "execute never throws" contract).
     */
    void at(String point);
}
