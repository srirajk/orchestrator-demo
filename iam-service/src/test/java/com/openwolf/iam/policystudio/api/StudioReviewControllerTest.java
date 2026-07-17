package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.ConsequenceDiffService;
import com.openwolf.iam.policystudio.ConsequenceReview;
import com.openwolf.iam.policystudio.LocalPdpDecisionSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C4 consequence-diff endpoint. The truth is computed by {@link ConsequenceDiffService} from real PDP
 * decisions; the controller records the VERIFIED author + tenant with the review so promotion can later
 * enforce author≠approver. Tenant is the claim; a cross-tenant re-fetch resolves as 404.
 */
@WebMvcTest(controllers = StudioReviewController.class)
@Import(StudioTestSecurityConfig.class)
class StudioReviewControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean ConsequenceDiffService diff;
    @MockitoBean LocalPdpDecisionSource pdpSource;
    @MockitoBean StudioSessionStore store;

    private static final String BODY = """
            {
              "current":{"bundleId":"b0","policy":null,"ceiling":null,"canonicalContent":"x"},
              "candidate":{"bundleId":"b1","policy":null,"ceiling":null,"canonicalContent":"y"},
              "matrix":{"cells":[],"fixtureSetHash":"fs-1"},
              "vocabulary":{"resourceKind":"agent","actions":[],"classifications":[],
                            "attributes":[],"roles":[],"approvedImports":[]}
            }
            """;

    private static ConsequenceReview review() {
        return new ConsequenceReview("meridian", "agent", "b0", "b1", "fs-1",
                List.of(), true, 3, "cd", "crh-1", null, null, Instant.now(), null);
    }

    @Test
    void computeReturnsReviewAndRecordsVerifiedAuthor() throws Exception {
        when(diff.computeReview(eq("meridian"), any(), any(), any(), any(), any())).thenReturn(review());

        mvc.perform(post("/admin/studio/reviews")
                        .with(StudioMvc.principal("drafter-dan", "meridian", "policy_drafter"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consequenceReviewHash").value("crh-1"))
                .andExpect(jsonPath("$.overPermissionAlarm").value(true))
                .andExpect(jsonPath("$.principalsGainingAccess").value(3));

        // the verified author (sub) + tenant are recorded, never a body field.
        verify(store).putReview(eq("meridian"), eq("drafter-dan"), any());
    }

    @Test
    void getFetchesCachedReview() throws Exception {
        when(store.getReview("meridian", "crh-1")).thenReturn(Optional.of(
                new StudioSessionStore.StoredReview("crh-1", "meridian", "drafter-dan", review(), Instant.now())));
        mvc.perform(get("/admin/studio/reviews/crh-1")
                        .with(StudioMvc.principal("bob", "meridian", "policy_approver")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consequenceReviewHash").value("crh-1"));
    }

    @Test
    void getAnotherTenantsReviewIs404() throws Exception {
        when(store.getReview("meridian", "crh-1")).thenReturn(Optional.empty());
        mvc.perform(get("/admin/studio/reviews/crh-1")
                        .with(StudioMvc.principal("bob", "meridian", "policy_approver")))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonStudioRoleIsDenied403() throws Exception {
        mvc.perform(post("/admin/studio/reviews")
                        .with(StudioMvc.principal("rm", "meridian", "chat_user"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }
}
