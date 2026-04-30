package com.frauddetection.alert.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditDegradationServiceTest {

    @Test
    void shouldPersistPostCommitDegradationAsUnresolvedDurableState() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditDegradationService service = new AuditDegradationService(repository);

        service.recordPostCommitDegraded(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "POST_COMMIT_AUDIT_DEGRADED"
        );

        org.mockito.ArgumentCaptor<AuditDegradationEventDocument> captor =
                org.mockito.ArgumentCaptor.forClass(AuditDegradationEventDocument.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED);
        assertThat(captor.getValue().isResolved()).isFalse();
        assertThat(captor.getValue().getResourceId()).isEqualTo("alert-1");
    }

    @Test
    void shouldReadUnresolvedPostCommitDegradationCountFromRepository() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditDegradationService service = new AuditDegradationService(repository);
        when(repository.countByTypeAndResolved(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED, false))
                .thenReturn(2L);

        assertThat(service.unresolvedPostCommitDegradedCount()).isEqualTo(2L);
        verify(repository).countByTypeAndResolved(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED, false);
    }
}
