package com.frauddetection.alert.governance.audit;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void shouldFallbackToOpenWhenAuditRepositoryFails() {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1"))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(GovernanceAdvisoryLifecycleStatus.OPEN);
    }

    @Test
    void shouldNotPersistLifecycleWhenResolvingStatus() {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1"))
                .thenReturn(Optional.of(document(GovernanceAuditDecision.ACKNOWLEDGED)));

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(GovernanceAdvisoryLifecycleStatus.ACKNOWLEDGED);

        verify(repository, never()).save(any(GovernanceAuditEventDocument.class));
    }

    @Test
    void shouldNotDeclareLifecycleStatusAsMongoAuditField() {
        assertThat(Arrays.stream(GovernanceAuditEventDocument.class.getDeclaredFields())
                .map(this::mongoFieldName))
                .doesNotContain("lifecycle_status", "lifecycleStatus");
    }

    private void assertLifecycle(GovernanceAuditDecision decision, GovernanceAdvisoryLifecycleStatus expected) {
        when(repository.findFirstByAdvisoryEventIdOrderByCreatedAtDesc("advisory-1")).thenReturn(Optional.of(document(decision)));

        assertThat(service.lifecycleStatus("advisory-1")).isEqualTo(expected);
    }

    private String mongoFieldName(Field field) {
        org.springframework.data.mongodb.core.mapping.Field annotation =
                field.getAnnotation(org.springframework.data.mongodb.core.mapping.Field.class);
        return annotation == null ? field.getName() : annotation.value();
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
