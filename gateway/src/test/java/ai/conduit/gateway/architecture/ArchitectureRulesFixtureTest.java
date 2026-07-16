package ai.conduit.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the load-bearing architecture rules are FAILABLE, using committed violators (F5 spec §3a-4,
 * harness item 4.2). Evaluates rule 1 (chokepoint) and rule 3 (HTTP hygiene) against the
 * {@code architecture.fixtures} package — which the positive {@link ArchitectureRulesTest} excludes —
 * and asserts each reports a violation.
 *
 * <p>If a rule ever goes vacuous (matches nothing, so can never fail), this test goes red — that is
 * the whole point: failability is guaranteed by code in every CI run, not by trust.
 */
class ArchitectureRulesFixtureTest {

    private static final JavaClasses FIXTURES = new ClassFileImporter()
            .importPackages("ai.conduit.gateway.architecture.fixtures");

    @Test
    void chokepoint_rule_is_failable_against_the_committed_violator() {
        EvaluationResult result = ArchitectureRulesTest.only_harness_invokes_protocol_adapter.evaluate(FIXTURES);
        assertThat(result.hasViolation())
                .as("ChokepointViolator calls ProtocolAdapter.invoke outside the harness — rule must flag it")
                .isTrue();
    }

    @Test
    void http_hygiene_rule_is_failable_against_the_committed_violator() {
        EvaluationResult result = ArchitectureRulesTest.noNoArgRestTemplateRule().evaluate(FIXTURES);
        assertThat(result.hasViolation())
                .as("UnhygienicHttpClientFactory constructs a no-arg RestTemplate — rule must flag it")
                .isTrue();
    }
}
