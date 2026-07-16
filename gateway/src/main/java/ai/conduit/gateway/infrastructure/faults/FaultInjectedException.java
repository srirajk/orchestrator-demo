package ai.conduit.gateway.infrastructure.faults;

/** Thrown by {@link ThrowingFaultInjector} when an armed point is reached. */
public final class FaultInjectedException extends RuntimeException {
    public FaultInjectedException(String point) {
        super("injected-fault:" + point);
    }
}
