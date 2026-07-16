package ai.conduit.gateway.infrastructure.faults;

import java.util.Set;

/**
 * The armed binding of {@link FaultInjector}: throws {@link FaultInjectedException} when a reached
 * point is in the armed set. Bound only under {@code conduit.test.faults.enabled=true} with a
 * test/perf marker (see {@link FaultConfig}) — never in a production context.
 */
public final class ThrowingFaultInjector implements FaultInjector {

    private final Set<String> armedPoints;

    public ThrowingFaultInjector(Set<String> armedPoints) {
        this.armedPoints = Set.copyOf(armedPoints);
    }

    @Override
    public void at(String point) {
        if (armedPoints.contains(point)) {
            throw new FaultInjectedException(point);
        }
    }

    public Set<String> armedPoints() {
        return armedPoints;
    }
}
