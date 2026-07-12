package ai.conduit.gateway.domain.chat;

import ai.conduit.gateway.domain.coverage.EntityBinding;
import ai.conduit.gateway.domain.coverage.EntityBindingSet;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundSourceKind;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundStatus;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundedInterpretation;
import ai.conduit.gateway.domain.coverage.ReferenceGroundingService.GroundingResult;
import ai.conduit.gateway.domain.manifest.EntityType;
import ai.conduit.gateway.registry.model.AgentManifest;
import ai.conduit.gateway.synthesis.input.MentionSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for the multi-entity COMPARE decision logic that lives as static helpers on
 * {@link ChatService}: the SECURITY-CRITICAL memo id-equality guard and the coverage-scan binding filter
 * (the two no-leak gates), plus the deterministic per-entity plan expansion + fan-out caps. No Spring,
 * no coverage calls. Domain-shaped literals are TEST fixtures only (the World-B grep scans main).
 */
class ChatServiceEntityCompareTest {

    private static final EntityType REL = new EntityType(
            "relationship_id", "relationship_reference", "resolvable",
            "client relationship", "REL-\\d+", "relationship", true, null);

    private static EntityBinding binding(String canonicalId) {
        return new EntityBinding(canonicalId, "verbatim-" + canonicalId, REL.key(), List.of(), 0);
    }

    private static GroundedInterpretation interp(String canonicalId, GroundStatus status) {
        return new GroundedInterpretation(
                canonicalId, canonicalId, REL.key(), "private-banking",
                "resolve|relationship|" + canonicalId, status, null,
                GroundSourceKind.EXTRACTED_REFERENCE, MentionSource.EXPLICIT, "1", 1, null, REL, null);
    }

    // ── (1) THE SECURITY ANCHOR — memo id-equality guard ────────────────────────────────────────

    @Test
    void memoGuard_focalMemoCannotBindASiblingGroupsId() {
        // Focal memo resolved Whitman (REL-00042). Calderon's entity group (REL-00099) must NOT consume it.
        GroundingResult whitmanMemo = GroundingResult.allowed("REL-00042", REL, "private-banking", null);
        EntityBinding calderon = binding("REL-00099");

        assertThat(ChatService.focalMemoBindsEntity(whitmanMemo, calderon))
                .as("Calderon's group must never bind Whitman's memoized id")
                .isFalse();
    }

    @Test
    void memoGuard_focalMemoBindsItsOwnEntityGroup() {
        GroundingResult whitmanMemo = GroundingResult.allowed("REL-00042", REL, "private-banking", null);
        assertThat(ChatService.focalMemoBindsEntity(whitmanMemo, binding("REL-00042"))).isTrue();
    }

    @Test
    void memoGuard_nullBinding_isSingleEntityPath_alwaysConsumes() {
        GroundingResult memo = GroundingResult.allowed("REL-00042", REL, "private-banking", null);
        assertThat(ChatService.focalMemoBindsEntity(memo, null)).isTrue();   // byte-identical single-entity
    }

    @Test
    void memoGuard_nullMemo_neverBindsAnEntityGroup() {
        assertThat(ChatService.focalMemoBindsEntity(null, binding("REL-00099"))).isFalse();
    }

    // ── (2) The no-leak coverage-scan filter ────────────────────────────────────────────────────

    @Test
    void scanFilter_selectsOnlyTheGroupsOwnCanonicalId() {
        EntityBinding calderon = binding("REL-00099");
        assertThat(ChatService.bindingSelectsInterpretation(calderon, interp("REL-00099", GroundStatus.RESOLVED_ALLOWED)))
                .isTrue();
        // A SIBLING client's ALLOWED interpretation can never serve this group — the core no-leak filter.
        assertThat(ChatService.bindingSelectsInterpretation(calderon, interp("REL-00042", GroundStatus.RESOLVED_ALLOWED)))
                .isFalse();
        assertThat(ChatService.bindingSelectsInterpretation(calderon, interp(null, GroundStatus.NOT_FOUND)))
                .isFalse();
    }

    @Test
    void scanFilter_nullBinding_isSingleEntityPath_selectsEverything() {
        assertThat(ChatService.bindingSelectsInterpretation(null, interp("REL-00042", GroundStatus.RESOLVED_ALLOWED)))
                .isTrue();
        assertThat(ChatService.bindingSelectsInterpretation(null, interp(null, GroundStatus.AMBIGUOUS)))
                .isTrue();
    }

    // ── (3) expandPerEntity — inert-below-2, capability×entity product, caps ─────────────────────

    private static AgentManifest agent(String id) {
        AgentManifest.Skill skill = new AgentManifest.Skill(
                "skill", "skill", "d", List.of(), List.of("d"), List.of("text"), List.of("json"));
        return new AgentManifest(id, id, "d", "1.0.0", null, "wealth-management", null, "private-banking",
                null, "http", null, new AgentManifest.Capabilities(false, false), List.of(skill),
                new AgentManifest.Constraints("read", "internal", 1_000), null, null, null, null, true, null);
    }

    private static RequestedPlan facetPlan(String... agentIds) {
        List<RequestedPlan.RequestedGroup> groups = new java.util.ArrayList<>();
        for (String id : agentIds) {
            groups.add(new RequestedPlan.RequestedGroup(
                    List.of(id), List.of(agent(id)), RequestedPlan.RequestedGroup.Kind.FLAT, null,
                    List.of(), "rerank-facet"));
        }
        return new RequestedPlan(groups);
    }

    private static EntityBindingSet bindings(String... ids) {
        List<EntityBinding> bs = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            bs.add(new EntityBinding(ids[i], "v-" + ids[i], REL.key(), List.of(), i));
        }
        return new EntityBindingSet(bs, REL.key());
    }

    @Test
    void expand_isNoOp_belowTwoBindings() {
        RequestedPlan plan = facetPlan("meridian.wealth.concentration");
        ChatService.ExpandResult r = ChatService.expandPerEntity(plan, bindings("REL-00042"), 3, 6);
        assertThat(r.plan()).isSameAs(plan);                 // byte-identical single-entity path
        assertThat(r.cappedNote()).isNull();
    }

    @Test
    void expand_oneFacetTimesTwoEntities_yieldsTwoEntityFacetGroups() {
        ChatService.ExpandResult r = ChatService.expandPerEntity(
                facetPlan("meridian.wealth.concentration"), bindings("REL-00042", "REL-00099"), 3, 6);

        assertThat(r.plan().groups()).hasSize(2);
        assertThat(r.plan().groups()).allMatch(g -> "entity-facet".equals(g.routingEvidence()));
        assertThat(r.plan().groups()).extracting(g -> g.binding().canonicalId())
                .containsExactly("REL-00042", "REL-00099");
        assertThat(r.cappedNote()).isNull();
    }

    @Test
    void expand_twoFacetsTimesTwoEntities_yieldsFourGroups() {
        ChatService.ExpandResult r = ChatService.expandPerEntity(
                facetPlan("meridian.wealth.performance", "meridian.wealth.risk_profile"),
                bindings("REL-00042", "REL-00099"), 3, 6);
        assertThat(r.plan().groups()).hasSize(4);            // full capability×entity product
        assertThat(r.cappedNote()).isNull();
    }

    @Test
    void expand_capsTotalGroups_keepsMentionOrder_andSurfacesNote() {
        ChatService.ExpandResult r = ChatService.expandPerEntity(
                facetPlan("meridian.wealth.performance", "meridian.wealth.risk_profile"),
                bindings("REL-00042", "REL-00099"), 3, /* maxTotalGroups */ 3);
        assertThat(r.plan().groups()).hasSize(3);
        assertThat(r.cappedNote()).isNotBlank();             // never silent
    }

    @Test
    void expand_capsEntityBindings_toMaxEntities_andSurfacesNote() {
        ChatService.ExpandResult r = ChatService.expandPerEntity(
                facetPlan("meridian.wealth.concentration"),
                bindings("REL-00042", "REL-00099", "REL-00188"), /* maxEntityBindings */ 2, 6);
        assertThat(r.plan().groups()).hasSize(2);
        assertThat(r.plan().groups()).extracting(g -> g.binding().canonicalId())
                .containsExactly("REL-00042", "REL-00099");  // first two = user order
        assertThat(r.cappedNote()).isNotBlank();
    }
}
