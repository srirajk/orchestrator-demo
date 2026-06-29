package com.openwolf.iam.dto;

import java.time.Instant;
import java.util.List;

/**
 * Public user representation — password_hash is NEVER included.
 */
public record UserResponse(
        String id,
        String username,
        String email,
        boolean isActive,
        List<String> roles,
        List<String> segments,
        String classification,
        List<String> book,
        List<String> adminDomains,
        Instant createdAt
) {}
