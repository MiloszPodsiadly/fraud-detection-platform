package com.frauddetection.alert.governance.modelpolicy;

import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyValidator;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceEnvironment;
import com.frauddetection.alert.governance.modelpromotion.ModelGovernanceScope;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionIntendedUse;
import com.frauddetection.alert.governance.modelpromotion.ModelPromotionPolicyDraft;
import com.frauddetection.alert.security.authorization.ModelPolicyAuthority;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyGovernancePaginationTest {
    @Test
    void boundedPageUsesStableCursorAndNeverExpandsProductionScope() {
        MongoPolicyGovernanceStore store = mock(MongoPolicyGovernanceStore.class);
        Instant timestamp = Instant.parse("2026-11-01T00:00:00Z");
        PolicyProposalDocument first = mock(PolicyProposalDocument.class);
        PolicyProposalDocument second = mock(PolicyProposalDocument.class);
        when(first.id()).thenReturn("id-a");
        when(first.updatedAt()).thenReturn(timestamp);
        when(second.id()).thenReturn("id-b");
        when(second.updatedAt()).thenReturn(timestamp);
        when(store.page(eq(2), eq(false), eq(PolicyGovernanceState.SUBMITTED), eq("risk"),
                isNull(), isNull())).thenReturn(List.of(first, second));
        PolicyGovernanceService service = new PolicyGovernanceService(store,
                new ModelPromotionPolicyValidator(new ObjectMapper()), new ObjectMapper(),
                "publisher", "publisher-key", "issuer", "issuer-key",
                org.mockito.Mockito.mock(com.frauddetection.alert.governance.modelpromotion.OnlinePolicyApproverDirectory.class));
        PolicyGovernanceActor reader = new PolicyGovernanceActor("oidc:reader",
                Set.of(ModelPolicyAuthority.READ));
        var page = service.page(reader, 1, null, PolicyGovernanceState.SUBMITTED, "risk");
        assertThat(page.items()).containsExactly(first);
        assertThat(page.nextCursor()).isNotBlank();
        service.page(reader, 1, page.nextCursor(), PolicyGovernanceState.SUBMITTED, "risk");
        verify(store).page(eq(2), eq(false), eq(PolicyGovernanceState.SUBMITTED), eq("risk"),
                eq(timestamp), eq("id-a"));
        assertThatThrownBy(() -> service.page(reader, 51, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.page(reader, 1, "malformed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.page(new PolicyGovernanceActor("oidc:other", Set.of()),
                1, null, null, null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void boundedHistoryLoadsRationalesOnceForVisiblePageOnly() {
        MongoPolicyGovernanceStore store = mock(MongoPolicyGovernanceStore.class);
        PolicyProposalDocument proposal = mock(PolicyProposalDocument.class);
        ModelPromotionPolicyDraft draft = new ModelPromotionPolicyDraft(ModelPromotionPolicyDraft.VERSION,
                "policy-1", 1, new ModelGovernanceScope(ModelGovernanceScope.CURRENT_INSTITUTION,
                        ModelGovernanceEnvironment.TEST), ModelPromotionIntendedUse.values()[0],
                "author", "approver", "approval-ref", "publisher", "publisher-key", "issuer", "issuer-key",
                "2026-11-01T00:00:00Z", "2027-11-01T00:00:00Z", List.of());
        when(store.find("proposal-1")).thenReturn(proposal);
        when(proposal.id()).thenReturn("proposal-1");
        when(proposal.draft()).thenReturn(draft);
        PolicyGovernanceHistoryDocument first = historyEvent(1, "REVIEW_APPROVE", "operation-1");
        PolicyGovernanceHistoryDocument second = historyEvent(2, "REVIEW_REJECT", "operation-2");
        PolicyGovernanceHistoryDocument lookahead = historyEvent(3, "CREATE_DRAFT", "operation-3");
        when(store.historyPage("proposal-1", 3, null)).thenReturn(List.of(first, second, lookahead));
        when(store.reviewRationales("proposal-1", List.of(first, second)))
                .thenReturn(Map.of("operation-1", "review rationale"));
        PolicyGovernanceService service = service(store);

        var page = service.historyPage(new PolicyGovernanceActor("oidc:auditor",
                Set.of(ModelPolicyAuthority.AUDIT_READ)), "proposal-1", 2, null);

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().getFirst().rationale()).isEqualTo("review rationale");
        assertThat(page.items().get(1).rationale()).isNull();
        assertThat(page.nextCursor()).isEqualTo(2L);
        verify(store).reviewRationales("proposal-1", List.of(first, second));
        verify(store, never()).reviewRationales("proposal-1", List.of(first, second, lookahead));
    }

    private static PolicyGovernanceHistoryDocument historyEvent(long version, String action, String operationId) {
        return new PolicyGovernanceHistoryDocument("event-" + version, "proposal-1", version, action,
                PolicyGovernanceState.DRAFT, PolicyGovernanceState.SUBMITTED, "oidc:reviewer", operationId,
                "command-sha", "draft-sha", null, Instant.parse("2026-11-01T00:00:00Z"), null);
    }

    private static PolicyGovernanceService service(MongoPolicyGovernanceStore store) {
        return new PolicyGovernanceService(store, new ModelPromotionPolicyValidator(new ObjectMapper()),
                new ObjectMapper(), "publisher", "publisher-key", "issuer", "issuer-key",
                mock(com.frauddetection.alert.governance.modelpromotion.OnlinePolicyApproverDirectory.class));
    }
}
