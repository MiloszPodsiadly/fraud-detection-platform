package com.frauddetection.alert.governance.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceAdvisoryLifecycleServiceTest {

    private final GovernanceAuditRepository repository = mock(GovernanceAuditRepository.class);
    private final GovernanceAdvisoryLifecycleService service = new GovernanceAdvisoryLifecycleService(repository);

    @Test
    void shouldReturnOpenWhenNoAuditExists() {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1")).thenReturn(Optional.empty());

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(GovernanceAdvisoryLifecycleStatus.OPEN);
    }

    @Test
    void shouldMapLatestDecisionToLifecycleStatus() {
        assertLifecycle(GovernanceAuditDecision.ACKNOWLEDGED, GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);
        assertLifecycle(GovernanceAuditDecision.NEEDS_FOLLOW_UP, GovernanceAdvisoryLifecycleStatus.NEEDS_FOLLOW_UP);
        assertLifecycle(GovernanceAuditDecision.DISMISSED_AS_NOISE, GovernanceAdvisoryLifecycleStatus.DISMISSED_AS_NOISE);
    }

    @Test
    void shouldFallbackToOpenWhenLatestDecisionIsMissing() {
        GovernanceAuditEventDocument document = document(null);
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1")).thenReturn(Optional.of(document));

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(GovernanceAdvisoryLifecycleStatus.OPEN);
    }

    private void assertLifecycle(GovernanceAuditDecision decision, GovernanceAdvisoryLifecycleStatus expected) {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1")).thenReturn(Optional.of(document(decision)));

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(expected);
    }

    private GovernanceAuditEventDocument document(GovernanceAuditDecision decision) {
        GovernanceAuditEventDocument document = new GovernanceAuditEventDocument();
        document.setAuditId("audit-1");
        document.setAdvisoryEventId("advisory-1");
        document.setDecision(decision);
        document.setCreatedAt(Instant.parse("2026-04-26T00:00:00Z"));
        return document;
    }
}
