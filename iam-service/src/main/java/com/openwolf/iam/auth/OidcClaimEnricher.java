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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the enrichment claims for an access token <b>inside a read-only transaction</b>.
 *
 * <p>This MUST be a separate bean from {@link JwtClaimsCustomizer}: the customizer runs as a
 * lambda during token encoding (no active persistence session), and {@code @Transactional}
 * is ignored on self-invocation. By calling into this bean the lazy {@code Principal.roles}
 * collection initializes correctly instead of throwing {@code LazyInitializationException}
 * (which previously broke the OIDC authorization_code flow / SSO login).
 */
@Service
public class OidcClaimEnricher {

    private static final Logger log = LoggerFactory.getLogger(OidcClaimEnricher.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final PrincipalRepository principalRepository;
    private final ObjectMapper objectMapper;
    private final String defaultTenantId;

    public OidcClaimEnricher(
            PrincipalRepository principalRepository,
            ObjectMapper objectMapper,
            @Value("${conduit.iam.default-tenant-id:default}") String defaultTenantId) {
        this.principalRepository = principalRepository;
        this.objectMapper = objectMapper;
        this.defaultTenantId = defaultTenantId;
    }

    /** Returns the claims to add to the access token. Runs in a session so lazy roles load. */
    @Transactional(readOnly = true)
    public Map<String, Object> enrich(String subject) {
        Map<String, Object> claims = new HashMap<>();

        Principal principal = principalRepository.findById(subject)
                .or(() -> principalRepository.findByUsername(subject))
                .orElse(null);

        if (principal == null) {
            log.warn("JWT enrich: no principal for sub={} — minimal claims", subject);
            claims.put("tenant_id", defaultTenantId);
            return claims;
        }

        List<String> roles = principal.getRoles().stream()
                .map(Role::getName)
                .sorted()
                .toList();

        Set<String> permissionsSet = new HashSet<>();
        for (Role role : principal.getRoles()) {
            permissionsSet.addAll(parseList(role.getPermissions()));
        }
        List<String> permissions = new ArrayList<>(permissionsSet);
        Collections.sort(permissions);

        Map<String, Object> attrs = parseAttributes(principal.getAttributes());
        List<String> segments = getStringList(attrs, "segments");
        String classification = (String) attrs.getOrDefault("classification", "internal");

        claims.put("roles", roles);
        claims.put("segments", segments);
        claims.put("classification", classification);
        claims.put("tenant_id", principal.getTenantId());
        claims.put("permissions", permissions);

        log.debug("JWT enriched for sub={} roles={} segments={}", subject, roles, segments);
        return claims;
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
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return Collections.emptyList();
    }
}
