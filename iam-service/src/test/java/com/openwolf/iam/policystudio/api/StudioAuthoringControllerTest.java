package com.openwolf.iam.policystudio.api;

import com.openwolf.iam.policystudio.PolicyStudioGenerationService;
import com.openwolf.iam.policystudio.StudioGenerationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C2 authoring endpoint ({@code POST /admin/studio/drafts}). The author scope is derived from the
 * principal's {@code tenant_id} claim (not the body); requires {@code policy_drafter}. A rejected proposal
 * is a normal 200 with violations, not an error.
 */
@WebMvcTest(controllers = StudioAuthoringController.class)
@Import(StudioTestSecurityConfig.class)
class StudioAuthoringControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PolicyStudioGenerationService generation;

    private static final String BODY = """
            {
              "intent":"allow read for the desk",
              "subscopesEnabled":false,
              "vocabulary":{"resourceKind":"agent","actions":["read"],"classifications":[],
                            "attributes":[],"roles":["desk"],"approvedImports":[]},
              "baseCeiling":{"resourceKind":"agent","tuples":[{"action":"read","role":"desk"}],
                             "carriesTenantEqualityBackstop":true,"reservedIdentities":[]}
            }
            """;

    @Test
    void drafterGeneratesAcceptedCanonicalYaml() throws Exception {
        when(generation.generate(any()))
                .thenReturn(StudioGenerationResult.accepted("canonical: yaml\n",
                        StudioGenerationResult.Stage.ACCEPTED));

        mvc.perform(post("/admin/studio/drafts")
                        .with(StudioMvc.principal("drafter-dan", "meridian", "policy_drafter"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").exists())
                .andExpect(jsonPath("$.tenantId").value("meridian"))
                .andExpect(jsonPath("$.authorId").value("drafter-dan"))
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.canonicalYaml").value("canonical: yaml\n"))
                .andExpect(jsonPath("$.validation.ok").value(true))
                .andExpect(jsonPath("$.validation.stage").value("ACCEPTED"));
    }

    @Test
    void rejectedProposalIsA200WithViolations() throws Exception {
        when(generation.generate(any()))
                .thenReturn(StudioGenerationResult.rejected(
                        StudioGenerationResult.Stage.VALIDATE, List.of("rule escapes author scope")));

        mvc.perform(post("/admin/studio/drafts")
                        .with(StudioMvc.principal("drafter-dan", "meridian", "policy_drafter"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.validation.ok").value(false))
                .andExpect(jsonPath("$.validation.violations[0]").value("rule escapes author scope"))
                .andExpect(jsonPath("$.validation.stage").value("VALIDATE"));
    }

    @Test
    void nonDrafterIsDenied403() throws Exception {
        mvc.perform(post("/admin/studio/drafts")
                        .with(StudioMvc.principal("bob", "meridian", "policy_approver"))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(generation);
    }
}
