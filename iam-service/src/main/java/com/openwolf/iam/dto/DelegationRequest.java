package com.openwolf.iam.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

/**
 * Request for a cross-tenant admin <b>delegation token</b> (Axiom story A1, contract #7).
 *
 * <p>There is <b>no caller-supplied tenant_id</b>: the delegation's tenant is <em>derived</em>
 * from the target subject's home tenant. The caller names a subject principal and a purpose; the
 * exchange (admin-only, audited) mints a separately-typed, short-lived token scoped to that
 * subject's tenant. The admin (actor) identity + home tenant come from the caller's own verified
 * access token, never from the request body.
 */
public record DelegationRequest(
        @JsonAlias({"subject_user_id", "userId", "user_id", "subject"})
        @NotBlank(message = "subjectUserId is required")
        String subjectUserId,

        @JsonAlias("purpose")
        @NotBlank(message = "purpose is required")
        String purpose
) {}
