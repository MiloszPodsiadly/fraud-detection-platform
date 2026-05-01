package com.frauddetection.alert.audit;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
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
                ticketEvidence("ticket-123")
        );

        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedBy()).isEqualTo("ops-admin");
        assertThat(resolved.getResolutionReason()).isEqualTo("external evidence verified");
        assertThat(resolved.getResolutionEvidenceReference()).isEqualTo("ticket-123");
        InOrder inOrder = inOrder(auditService);
        inOrder.verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldNotMutateWhenAttemptAuditFails() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService);
        AuditDegradationEventDocument event = unresolvedEvent();
        when(repository.findByAuditId("audit-1")).thenReturn(Optional.of(event));
        doThrow(new RuntimeException("audit down")).when(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> service.resolveDegradation("audit-1", "ops-admin", "reason", ticketEvidence("ticket-123")))
                .isInstanceOf(RuntimeException.class);

        assertThat(event.isResolved()).isFalse();
        verify(repository, never()).save(any());
    }

    @Test
    void shouldAuditFailedWhenResolutionMutationFails() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService);
        AuditDegradationEventDocument event = unresolvedEvent();
        when(repository.findByAuditId("audit-1")).thenReturn(Optional.of(event));
        when(repository.save(event)).thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> service.resolveDegradation("audit-1", "ops-admin", "reason", ticketEvidence("ticket-123")))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin",
                AuditOutcome.FAILED,
                "RESOLUTION_STATE_UPDATE_FAILED"
        );
    }

    @Test
    void shouldRecordPostCommitDegradationWhenSuccessAuditFailsAfterMutation() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService);
        AuditDegradationEventDocument event = unresolvedEvent();
        when(repository.findByAuditId("audit-1")).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);
        doThrow(new RuntimeException("audit down")).when(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "audit-1",
                null,
                "ops-admin",
                AuditOutcome.SUCCESS,
                null
        );

        assertThatThrownBy(() -> service.resolveDegradation("audit-1", "ops-admin", "reason", ticketEvidence("ticket-123")))
                .isInstanceOf(PostCommitEvidenceIncompleteException.class);

        assertThat(event.isResolved()).isTrue();
        verify(repository).insert(any(AuditDegradationEventDocument.class));
    }

    @Test
    void shouldRequireDualControlInBankMode() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService, true);
        AuditDegradationEventDocument event = unresolvedEvent();
        when(repository.findByAuditId("audit-1")).thenReturn(Optional.of(event));
        when(repository.save(event)).thenReturn(event);

        AuditDegradationEventDocument pending = service.resolveDegradation(
                "audit-1",
                "ops-requester",
                "verified",
                ticketEvidence("ticket-123")
        );

        assertThat(pending.isResolutionPending()).isTrue();
        assertThat(pending.isResolved()).isFalse();
        assertThatThrownBy(() -> service.resolveDegradation("audit-1", "ops-requester", "approve", ticketEvidence("ticket-123")))
                .isInstanceOf(ResponseStatusException.class);

        AuditDegradationEventDocument approved = service.resolveDegradation(
                "audit-1",
                "ops-approver",
                "approved",
                ticketEvidence("ticket-123")
        );
        assertThat(approved.isResolved()).isTrue();
        assertThat(approved.isResolutionPending()).isFalse();
        assertThat(approved.getResolutionRequestedBy()).isEqualTo("ops-requester");
        assertThat(approved.getApprovedBy()).isEqualTo("ops-approver");
    }

    private AuditDegradationEventDocument unresolvedEvent() {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("audit-1");
        event.setResolved(false);
        return event;
    }

    private ResolutionEvidenceReference ticketEvidence(String reference) {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.TICKET,
                reference,
                Instant.parse("2026-04-30T12:00:00Z"),
                "ops-admin"
        );
    }
}
