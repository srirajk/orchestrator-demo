package ai.conduit.gateway.infrastructure.faults;

/**
 * The production binding of {@link FaultInjector}: every {@link #at(String)} is a no-op. Bound in
 * every context that has not explicitly armed faults (see {@link FaultConfig}).
 */
public final class NoopFaultInjector implements FaultInjector {

    @Override
    public void at(String point) {
        // Intentionally empty: no fault, no branch, no cost.
    }
}
