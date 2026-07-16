package ai.conduit.gateway.infrastructure.faults;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds exactly one {@link FaultInjector} (F5 spec §3c). Declaration order makes the choice
 * deterministic: the armed bean is declared first, so when it registers the no-op backs off via
 * {@link ConditionalOnMissingBean}; when faults are disabled the armed bean is absent and the no-op
 * registers.
 *
 * <p><b>Startup guard.</b> {@code conduit.test.faults.enabled=true} arms the throwing injector only
 * if a test/perf marker is also present — the {@code test} or {@code perf} Spring profile, or
 * {@code conduit.test.faults.marker=test|perf}. Enabled without a marker throws here, so the context
 * refuses to start: fault injection must never arm in a production context.
 */
@Configuration
public class FaultConfig {

    @Bean
    @ConditionalOnProperty(name = "conduit.test.faults.enabled", havingValue = "true")
    FaultInjector throwingFaultInjector(
            Environment env,
            @Value("${conduit.test.faults.points:}") String pointsCsv,
            @Value("${conduit.test.faults.marker:}") String marker) {

        boolean profileMarker = env.acceptsProfiles(Profiles.of("test", "perf"));
        boolean propMarker = "test".equals(marker) || "perf".equals(marker);
        if (!profileMarker && !propMarker) {
            throw new IllegalStateException(
                    "conduit.test.faults.enabled=true requires a test/perf marker "
                    + "(active profile 'test'|'perf', or conduit.test.faults.marker=test|perf). "
                    + "Refusing to start — fault injection must never arm in a production context.");
        }

        Set<String> points = Arrays.stream(pointsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        return new ThrowingFaultInjector(points);
    }

    @Bean
    @ConditionalOnMissingBean(FaultInjector.class)
    FaultInjector noopFaultInjector() {
        return new NoopFaultInjector();
    }
}
