package ai.conduit.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

import java.util.Map;

/**
 * HTTP security for the BFF.
 *
 * <p>Auth model: OIDC {@code authorization_code} + PKCE against Axiom (iam-service),
 * with the authenticated principal held in a MongoDB-backed HTTP session (Spring
 * Session). The BFF is a pure OIDC <em>relying party</em> of Axiom — it adapts to
 * Axiom's registered client contract and never modifies it.
 *
 * <p>Key contract points:
 * <ul>
 *   <li>The OIDC callback is processed at {@code /api/auth/callback} to match the
 *       redirect-uri Axiom already has registered for the {@code conduit-chat} client.</li>
 *   <li>Unauthenticated {@code /api/**} calls receive a REST <b>401</b> (JSON), never a
 *       redirect to a login page — the SPA handles 401 by navigating to the login flow.</li>
 *   <li>The login flow is initiated from {@code /api/auth/login} (see {@code AuthController}),
 *       which forwards to Spring's {@code /oauth2/authorization/conduit-chat}.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    /**
     * Persist the {@link org.springframework.security.oauth2.client.OAuth2AuthorizedClient}
     * (and its {@code OAuth2AccessToken}) in the servlet HTTP session rather than an
     * in-memory store. Because the session is backed by Spring Session on MongoDB, the
     * authorized client survives container restart/recreate — the user's access token is
     * no longer lost when we reconfigure via env and recreate the container.
     *
     * <p>Defining this bean overrides Spring Security's default
     * {@code AuthenticatedPrincipalOAuth2AuthorizedClientRepository} (which delegates
     * authenticated principals to the in-memory {@code OAuth2AuthorizedClientService});
     * {@code oauth2Login} and the {@code OAuth2AuthorizedClientRepository} lookup both
     * resolve this bean. Everything stored ({@code OAuth2AuthorizedClient},
     * {@code ClientRegistration}, {@code OAuth2AccessToken}/{@code OAuth2RefreshToken}) is
     * {@link java.io.Serializable}, so it round-trips through Spring Session's default JDK
     * serialization to Mongo.
     */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http
                // SPA uses a session cookie with credentials:include and does not carry CSRF
                // tokens; the reference Node BFF had no CSRF. Disable to preserve the contract.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/callback",
                                "/api/auth/logout",
                                "/oauth2/**",
                                "/login/oauth2/**",
                                "/health",
                                "/actuator/health/**"
                        ).permitAll()
                        // The SPA shell + its static assets are public; the API is gated.
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                .oauth2Login(oauth -> oauth
                        // Process Axiom's callback at the path Axiom has registered.
                        .redirectionEndpoint(redir -> redir.baseUri("/api/auth/callback"))
                        // After a successful login, land the browser back on the SPA.
                        .defaultSuccessUrl("/", true)
                        .failureUrl("/?login_error")
                )
                // Spring's default logout is replaced by AuthController#logout (JSON response).
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        // Protected-resource access without a session → 401 JSON for /api/**,
                        // a plain 401 elsewhere. Never redirect to a login page.
                        .defaultAuthenticationEntryPointFor(
                                jsonUnauthorizedEntryPoint(objectMapper),
                                request -> {
                                    String uri = request.getRequestURI();
                                    return uri != null && uri.startsWith("/api/");
                                })
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                );

        return http.build();
    }

    /**
     * 401 entry point that emits {@code {"error":"Unauthorized"}} as JSON, matching the
     * reference Node BFF's {@code requireAuth} behaviour.
     */
    private static org.springframework.security.web.AuthenticationEntryPoint jsonUnauthorizedEntryPoint(
            ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of("error", "Unauthorized"));
        };
    }
}
