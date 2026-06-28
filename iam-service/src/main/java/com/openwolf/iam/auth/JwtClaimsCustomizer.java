package com.openwolf.iam.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openwolf.iam.entity.Principal;
import com.openwolf.iam.entity.Role;
import com.openwolf.iam.repository.PrincipalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Enriches JWTs with mandated claims. Every issued access token carries:
 * <ul>
 *   <li>{@code tenant_id} — from principal.tenant_id in the database.
 *       Configured via {@code meridian.iam.default-tenant-id} as the fallback.
 *       Never hardcoded — the value is a mandated claim attribute driven by the
 *       principal's tenant membership at the IAM configuration level.</li>
 *   <li>{@code roles} — list of role names assigned to the principal</li>
 *   <li>{@code permissions} — merged permission set from all assigned roles</li>
 *   <li>{@code segments} / {@code classification} — from principal attributes JSON</li>
 * </ul>
 */
@Configuration
public class JwtClaimsCustomizer {

    private static final Logger log = LoggerFactory.getLogger(JwtClaimsCustomizer.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final PrincipalRepository principalRepository;
    private final ObjectMapper objectMapper;
    private final String defaultTenantId;

    public JwtClaimsCustomizer(
            PrincipalRepository principalRepository,
            ObjectMapper objectMapper,
            @Value("${meridian.iam.default-tenant-id:default}") String defaultTenantId) {
        this.principalRepository = principalRepository;
        this.objectMapper = objectMapper;
        this.defaultTenantId = defaultTenantId;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            // Only enrich ACCESS_TOKEN — not REFRESH_TOKEN or ID_TOKEN
            if (!org.springframework.security.oauth2.server.authorization.OAuth2TokenType.ACCESS_TOKEN
                    .equals(context.getTokenType())) {
                return;
            }

            String subject = context.getPrincipal().getName();
            if (subject == null) {
                log.warn("JWT customizer: no subject in principal — skipping enrichment");
                return;
            }

            // Load principal by ID (subject = principal ID from CustomUserDetailsService)
            Principal principal = principalRepository.findById(subject)
                    .or(() -> principalRepository.findByUsername(subject))
                    .orElse(null);

            if (principal == null) {
                log.warn("JWT customizer: no principal found for sub={} — emitting token with minimal claims", subject);
                context.getClaims().claim("tenant_id", defaultTenantId);
                return;
            }

            // Roles
            List<String> roles = principal.getRoles().stream()
                    .map(Role::getName)
                    .sorted()
                    .toList();

            // Merged permissions from all roles
            Set<String> permissionsSet = new HashSet<>();
            for (Role role : principal.getRoles()) {
                permissionsSet.addAll(parseList(role.getPermissions()));
            }
            List<String> permissions = new ArrayList<>(permissionsSet);
            Collections.sort(permissions);

            // Attributes
            Map<String, Object> attrs = parseAttributes(principal.getAttributes());
            List<String> segments = getStringList(attrs, "segments");
            String classification = (String) attrs.getOrDefault("classification", "internal");

            // Set claims — tenant_id is a mandated claim from the principal's
            // database record, not hardcoded. Configured via meridian.iam.default-tenant-id.
            context.getClaims()
                    .claim("roles", roles)
                    .claim("segments", segments)
                    .claim("classification", classification)
                    .claim("tenant_id", principal.getTenantId())
                    .claim("permissions", permissions);

            log.debug("JWT enriched for sub={} roles={} segments={}", subject, roles, segments);
        };
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse principal attributes for JWT: {}", ex.getMessage());
            return Map.of();
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> attrs, String key) {
        Object val = attrs.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        }
        return Collections.emptyList();
    }
}
