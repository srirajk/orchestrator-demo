package com.openwolf.iam.dto;

import java.util.List;

/**
 * Adds and/or removes relationship IDs from a RM's book.
 * Both fields are optional — supply only what you want to change.
 */
public record PatchBookRequest(
        List<String> add,
        List<String> remove
) {}
