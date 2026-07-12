package ai.conduit.gateway.config;

import ai.conduit.gateway.domain.coverage.GroundingBudget;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the multi-reference grounding budgets from {@code conduit.grounding.*} (routing spec V2.1 /
 * Piece 2). Follows the project's {@code @Value}/{@code application.yml} config convention (CLAUDE.md
 * §5) rather than a {@code @ConfigurationProperties} class, which this codebase does not use.
 */
@Configuration
public class GroundingConfig {

    @Bean
    public GroundingBudget groundingBudget(
            @Value("${conduit.grounding.max-mentions:8}") int maxMentions,
            @Value("${conduit.grounding.max-interpretations-per-mention:4}") int maxInterpretationsPerMention,
            @Value("${conduit.grounding.concurrency:8}") int concurrency,
            @Value("${conduit.grounding.stage-deadline-ms:8000}") long stageDeadlineMillis,
            @Value("${conduit.grounding.residual-detection.max-resolves:3}") int residualMaxResolves) {
        return new GroundingBudget(maxMentions, maxInterpretationsPerMention, concurrency,
                stageDeadlineMillis, residualMaxResolves);
    }
}
