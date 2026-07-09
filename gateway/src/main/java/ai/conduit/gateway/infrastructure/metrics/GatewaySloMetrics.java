package ai.conduit.gateway.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gateway-level SLO metrics for operator RED + saturation views.
 */
@Service
public class GatewaySloMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger inFlight = new AtomicInteger();

    public GatewaySloMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("conduit.gateway.inflight.requests", inFlight, AtomicInteger::get)
                .description("Requests currently executing inside the gateway chat pipeline")
                .baseUnit("requests")
                .register(registry);
    }

    public RequestScope start() {
        inFlight.incrementAndGet();
        return new RequestScope(System.nanoTime());
    }

    public void finish(RequestScope scope) {
        try {
            if (scope == null) {
                return;
            }
            String path = safe(scope.path);
            String domain = safe(scope.domain);
            String outcome = safe(scope.outcome);
            String reason = safe(scope.reason);
            String statusClass = statusClass(outcome);
            long elapsedNanos = Math.max(0, System.nanoTime() - scope.startedNanos);

            Counter.builder("conduit.gateway.requests")
                    .description("Gateway chat requests by orchestration path, domain, outcome, and status class")
                    .tag("path", path)
                    .tag("domain", domain)
                    .tag("outcome", outcome)
                    .tag("reason", reason)
                    .tag("status_class", statusClass)
                    .register(registry)
                    .increment();

            Timer.builder("conduit.gateway.request.duration")
                    .description("Gateway chat request duration by orchestration path, domain, outcome, and status class")
                    .tag("path", path)
                    .tag("domain", domain)
                    .tag("outcome", outcome)
                    .tag("reason", reason)
                    .tag("status_class", statusClass)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
        } finally {
            inFlight.updateAndGet(v -> Math.max(0, v - 1));
        }
    }

    public void recordStage(String stage, String path, String domain, long elapsedNanos) {
        Timer.builder("conduit.gateway.stage.duration")
                .description("Gateway stage duration by stage, orchestration path, and domain")
                .tag("stage", safe(stage))
                .tag("path", safe(path))
                .tag("domain", safe(domain))
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry)
                .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    private static String statusClass(String outcome) {
        return switch (safe(outcome).toUpperCase(Locale.ROOT)) {
            case "ANSWERED", "CLARIFIED" -> "2xx";
            case "DENIED" -> "4xx";
            default -> "5xx";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    public static final class RequestScope {
        private final long startedNanos;
        private String path = "none";
        private String domain = "unknown";
        private String outcome = "ERROR";
        private String reason = "unset";

        private RequestScope(long startedNanos) {
            this.startedNanos = startedNanos;
        }

        public void path(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }

        public void domain(String domain) {
            this.domain = domain;
        }

        public String domain() {
            return domain;
        }

        public void outcome(String outcome) {
            this.outcome = outcome;
            this.reason = safe(outcome).toLowerCase(Locale.ROOT);
        }

        public void reason(String reason) {
            this.reason = reason;
        }
    }
}
