package ai.conduit.gateway.config;

import ai.conduit.gateway.domain.chat.RoutePreparationPolicy;
import ai.conduit.gateway.domain.chat.RoutePreparer;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService;
import ai.conduit.gateway.domain.manifest.DomainManifestStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the pre-routing preparation pipeline (routing spec V2 Piece 3) from {@code conduit.routing.*}
 * and {@code conduit.chat.routing-context-turns}. Follows the project's {@code @Value}/{@code
 * application.yml} convention (CLAUDE.md §5) rather than {@code @ConfigurationProperties}. The mask
 * token and the near-empty stopword set are CONFIG — no Java word list lives in source.
 */
@Configuration
public class RoutePreparationConfig {

    @Bean
    public RoutePreparationPolicy routePreparationPolicy(
            @Value("${conduit.routing.entity-mask-token:the subject}") String maskToken,
            @Value("${conduit.routing.residual-stopwords:}") String residualStopwords,
            @Value("${conduit.routing.min-residual-content-tokens:1}") int minContentTokens) {
        return new RoutePreparationPolicy(
                maskToken, RoutePreparationPolicy.parseStopwords(residualStopwords), minContentTokens);
    }

    @Bean
    public RoutePreparer routePreparer(DomainManifestStore manifestStore,
                                       ReferenceGroundingService referenceGrounding,
                                       RoutePreparationPolicy policy,
                                       @Value("${conduit.chat.routing-context-turns:4}") int routingContextTurns) {
        return new RoutePreparer(manifestStore, referenceGrounding, policy, routingContextTurns);
    }
}
