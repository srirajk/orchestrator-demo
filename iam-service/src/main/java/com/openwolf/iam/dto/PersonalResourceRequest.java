package com.openwolf.iam.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record PersonalResourceRequest(
        @NotBlank(message = "resourceType is required") String resourceType,
        @NotBlank(message = "resourceId is required") String resourceId,
        Map<String, Object> metadata
) {}
