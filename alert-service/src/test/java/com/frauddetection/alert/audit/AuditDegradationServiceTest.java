package com.frauddetection.alert.audit;

import org.junit.jupiter.api.Test;

import java.util.Optional;

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

    @Test
    void shouldResolveDegradationWithOperatorEvidenceAndAuditResolution() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService);
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("audit-1");
        event.setResolved(false);
        when(repository.findByAuditId("audit-1")).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);

        AuditDegradationEventDocument resolved = service.resolveDegradation(
                "audit-1",
                "ops-admin",
                "external evidence verified",
                "ticket-123"
        );

        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedBy()).isEqualTo("ops-admin");
        assertThat(resolved.getResolutionReason()).isEqualTo("external evidence verified");
        assertThat(resolved.getResolutionEvidenceReference()).isEqualTo("ticket-123");
        verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin"
        );
    }
}
