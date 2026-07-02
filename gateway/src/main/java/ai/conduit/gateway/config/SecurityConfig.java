package ai.conduit.gateway.config;

import ai.conduit.gateway.domain.auth.JwksClient;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.config.Customizer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring Security resource server + role-based URL authorization.
 *
 * <p>Authorization matrix:
 * <ul>
 *   <li>POST/PUT/DELETE/GET /admin/agents/** → platform_admin OR domain_admin (+ fine domain check)</li>
 *   <li>GET /debug/**                        → platform_admin OR domain_admin</li>
 *   <li>POST /v1/chat/completions            → permitAll (identity from the verified JWT sub only; no JWT ⇒ anonymous)</li>
 *   <li>GET /trace/**, /v1/models, /actuator/** → permitAll</li>
 * </ul>
 *
 * <p>JWT validation uses the existing {@link JwksClient} so tests can {@code @MockBean} it.
 * Accepts the Axiom (iam-service) issuer URL variants.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    // Comma-separated list of accepted JWT issuers — injected from conduit.auth.required-issuers.
    // Includes legacy token issuers and the iam-service URL variants used in dev/docker.
    @Value("${conduit.auth.required-issuers:http://host.docker.internal:8084,http://iam-service:8084,http://localhost:8084}")
    private String requiredIssuersRaw;

    @Value("${conduit.auth.required-audience:conduit-gateway}")
    private String expectedAudience;

    // Browser origins allowed to read the glass-box SSE stream cross-origin (the glass-box
    // page is served from its own host/port). Comma-separated; "*" allows any. Configurable
    // so it can be locked down per environment.
    @Value("${conduit.glassbox.allowed-origins:*}")
    private String glassboxAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            JwtDecoder jwtDecoder,
            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())   // applies corsConfigurationSource() bean
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Public endpoints — no auth required
                .requestMatchers("/v1/models", "/actuator/**").permitAll()
                // Glass-box SSE — browser EventSource can't send a Bearer header
                .requestMatchers("/trace/**").permitAll()
                // Chat — permitAll so unauthenticated probes don't 401, but identity is taken
                // ONLY from the verified JWT (see RequestCorrelationFilter); no JWT ⇒ anonymous.
                .requestMatchers(HttpMethod.POST, "/v1/chat/completions").permitAll()
                // Admin plane — requires domain_admin or platform_admin role
                .requestMatchers("/admin/agents/**").hasAnyRole("domain_admin", "platform_admin")
                .requestMatchers("/admin/domains/**").hasAnyRole("domain_admin", "platform_admin")
                .requestMatchers("/debug/**").hasAnyRole("domain_admin", "platform_admin")
                // Everything else needs at least a valid token
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                // Ignore placeholder tokens (LibreChat apiKey="unused") and non-JWT strings.
                // Real JWTs (three dot-delimited parts) are validated; everything else is null
                // so the request proceeds as anonymous (no identity, no data access).
                .bearerTokenResolver(request -> {
                    String header = request.getHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) return null;
                    String token = header.substring(7).trim();
                    if (token.isEmpty() || "unused".equals(token)) return null;
                    // Standard JWS has exactly two dots (header.payload.sig).
                    // JWE (5 parts, 4 dots) must be rejected — we only accept RS256 JWS.
                    long dots = token.chars().filter(c -> c == '.').count();
                    if (dots != 2) return null;
                    return token;
                })
                .jwt(jwt -> jwt
                    .decoder(jwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter)
                )
                .authenticationEntryPoint((req, resp, ex) -> {
                    resp.setStatus(401);
                    resp.setContentType("application/json");
                    resp.getWriter().write(
                            "{\"error\":\"unauthorized\",\"reason\":\"" +
                            (ex.getMessage() != null ? ex.getMessage().replace("\"", "'") : "invalid token") +
                            "\"}");
                })
                .accessDeniedHandler((req, resp, ex) -> {
                    resp.setStatus(403);
                    resp.setContentType("application/json");
                    resp.getWriter().write("{\"error\":\"forbidden\",\"reason\":\"insufficient role\"}");
                })
            );
        return http.build();
    }

    /**
     * CORS for browser clients. The glass-box page loads from its own origin and opens an
     * {@code EventSource} to {@code /trace/**}; without an {@code Access-Control-Allow-Origin}
     * header the browser blocks the stream (the connection fires onerror and the client churns
     * on reconnect). Scoped to {@code /trace/**} (read-only telemetry); origins are configurable.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        for (String origin : glassboxAllowedOrigins.split(",")) {
            cfg.addAllowedOriginPattern(origin.trim());
        }
        cfg.addAllowedMethod("GET");
        cfg.addAllowedHeader("*");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/trace/**", cfg);
        return source;
    }

    /**
     * Custom {@link JwtDecoder} backed by {@link JwksClient}.
     *
     * <p>Using our own JwksClient keeps test mocking simple ({@code @MockBean JwksClient}) and
     * avoids configuring a remote JWKS URI that wouldn't be reachable during unit tests.
     *
     * <p>Validates: algorithm (RS256), signature, {@code exp}, {@code iss}, {@code aud}.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwksClient jwksClient) {
        return token -> {
            try {
                SignedJWT jwt = SignedJWT.parse(token);

                if (jwt.getHeader().getAlgorithm() != JWSAlgorithm.RS256) {
                    throw new JwtException("algorithm must be RS256, got " + jwt.getHeader().getAlgorithm());
                }

                String kid = jwt.getHeader().getKeyID();
                RSAPublicKey pubKey = jwksClient.getPublicKey(kid);
                if (pubKey == null) {
                    throw new JwtException("unknown kid: " + kid);
                }

                if (!jwt.verify(new RSASSAVerifier(pubKey))) {
                    throw new JwtException("signature verification failed");
                }

                JWTClaimsSet claims = jwt.getJWTClaimsSet();

                Date exp = claims.getExpirationTime();
                if (exp == null || exp.before(new Date())) {
                    throw new JwtException("token expired");
                }

                Date nbf = claims.getNotBeforeTime();
                if (nbf != null && nbf.after(new Date())) {
                    throw new JwtException("token not yet valid (nbf=" + nbf + ")");
                }

                List<String> validIssuers = Arrays.asList(requiredIssuersRaw.split(","));
                if (claims.getIssuer() == null || validIssuers.stream().noneMatch(i -> i.trim().equals(claims.getIssuer()))) {
                    throw new JwtException("invalid iss: " + claims.getIssuer());
                }

                List<String> aud = claims.getAudience();
                if (aud == null || !aud.contains(expectedAudience)) {
                    throw new JwtException("invalid aud: " + aud);
                }

                return buildSpringJwt(token, jwt.getHeader().getKeyID(), claims);

            } catch (JwtException e) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", e.getMessage(), null), e.getMessage(), e);
            } catch (Exception e) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", e.getMessage(), null), e.getMessage(), e);
            }
        };
    }

    /** Convert Nimbus {@link JWTClaimsSet} → Spring Security {@link Jwt}. */
    @SuppressWarnings("unchecked")
    private Jwt buildSpringJwt(String token, String kid, JWTClaimsSet claims) throws Exception {
        Map<String, Object> headers = new HashMap<>();
        headers.put("alg", "RS256");
        if (kid != null) headers.put("kid", kid);

        Map<String, Object> claimsMap = new HashMap<>();
        if (claims.getSubject()  != null) claimsMap.put("sub", claims.getSubject());
        if (claims.getIssuer()   != null) claimsMap.put("iss", claims.getIssuer());
        if (claims.getAudience() != null) claimsMap.put("aud", claims.getAudience());
        if (claims.getExpirationTime() != null)
            claimsMap.put("exp", claims.getExpirationTime().toInstant());
        if (claims.getIssueTime() != null)
            claimsMap.put("iat", claims.getIssueTime().toInstant());
        else
            claimsMap.put("iat", Instant.now());

        copyListClaim(claims, claimsMap, "roles");
        copyListClaim(claims, claimsMap, "book");
        copyListClaim(claims, claimsMap, "segments");
        copyListClaim(claims, claimsMap, "domains");
        copyListClaim(claims, claimsMap, "admin_domains");
        Object clearance = claims.getClaim("clearance");
        if (clearance != null) claimsMap.put("clearance", clearance);
        Object name = claims.getClaim("name");
        if (name != null) claimsMap.put("name", name);

        return Jwt.withTokenValue(token)
                .headers(h -> h.putAll(headers))
                .claims(c -> c.putAll(claimsMap))
                .build();
    }

    @SuppressWarnings("unchecked")
    private void copyListClaim(JWTClaimsSet claims, Map<String, Object> out, String key) {
        Object v = claims.getClaim(key);
        if (v instanceof List<?> list) out.put(key, new ArrayList<>((List<String>) list));
    }

    /**
     * Maps the JWT {@code roles} claim to Spring {@code ROLE_} authorities.
     * A token with {@code "roles":["platform_admin"]} produces authority {@code ROLE_platform_admin}.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();
            return roles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                    .toList();
        });
        return converter;
    }
}
