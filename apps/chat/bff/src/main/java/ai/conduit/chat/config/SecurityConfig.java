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
