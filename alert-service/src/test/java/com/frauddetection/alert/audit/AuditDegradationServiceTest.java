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
import static org.mockito.Mockito.times;
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
    void shouldReadUnresolvedExternalHeadIntegrityCountIndependently() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditDegradationService service = new AuditDegradationService(repository);
        when(repository.countByTypeAndResolved(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY, false))
                .thenReturn(3L);

        assertThat(service.unresolvedExternalHeadIntegrityCount()).isEqualTo(3L);
        verify(repository).countByTypeAndResolved(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY, false);
    }

    @Test
    void shouldFailClosedWhenExternalHeadIntegrityCountIsUnavailable() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditDegradationService service = new AuditDegradationService(repository);
        when(repository.countByTypeAndResolved(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY, false))
                .thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThat(service.unresolvedExternalHeadIntegrityCount()).isOne();
    }

    @Test
    void shouldResolveDegradationWithOperatorEvidenceAndAuditResolution() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService service = new AuditDegradationService(repository, auditService);
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("audit-1");
        event.setType(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED);
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

    @Test
    void shouldRejectManualExternalHeadResolutionWithoutChangingForensicEvidence() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationEventDocument event = unresolvedExternalHeadIncident();
        when(repository.findByAuditId("external-head-1")).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> new AuditDegradationService(repository, auditService)
                .resolveDegradation("external-head-1", "ops-admin", "looks healthy", ticketEvidence("ticket-123")))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(exception.getReason())
                            .isEqualTo("External HEAD integrity incidents require verified technical recovery.");
                });

        assertExternalHeadIncidentUnchanged(event);
        verify(repository, never()).save(any());
        InOrder auditOrder = inOrder(auditService);
        auditOrder.verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "external-head-1",
                null,
                "ops-admin",
                AuditOutcome.ATTEMPTED,
                null
        );
        auditOrder.verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "external-head-1",
                null,
                "ops-admin",
                AuditOutcome.FAILED,
                "VERIFIED_TECHNICAL_RECOVERY_REQUIRED"
        );
    }

    @Test
    void shouldRejectFirstBankApprovalForExternalHeadIncident() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationEventDocument event = unresolvedExternalHeadIncident();
        when(repository.findByAuditId("external-head-1")).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> new AuditDegradationService(repository, auditService, true)
                .resolveDegradation("external-head-1", "ops-requester", "request", ticketEvidence("ticket-123")))
                .isInstanceOf(ResponseStatusException.class);

        assertExternalHeadIncidentUnchanged(event);
        verify(repository, never()).save(any());
        verify(auditService).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "external-head-1",
                null,
                "ops-requester",
                AuditOutcome.FAILED,
                "VERIFIED_TECHNICAL_RECOVERY_REQUIRED"
        );
    }

    @Test
    void shouldNotBypassExternalHeadResolutionOwnershipAfterServiceRestart() {
        AuditDegradationEventRepository repository = mock(AuditDegradationEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationEventDocument event = unresolvedExternalHeadIncident();
        when(repository.findByAuditId("external-head-1")).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> new AuditDegradationService(repository, auditService)
                .resolveDegradation("external-head-1", "ops-admin", "first", null))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> new AuditDegradationService(repository, auditService)
                .resolveDegradation("external-head-1", "ops-admin", "after restart", null))
                .isInstanceOf(ResponseStatusException.class);

        assertExternalHeadIncidentUnchanged(event);
        verify(repository, never()).save(any());
        verify(auditService, times(2)).audit(
                AuditAction.RESOLVE_AUDIT_DEGRADATION,
                AuditResourceType.AUDIT_DEGRADATION,
                "external-head-1",
                null,
                "ops-admin",
                AuditOutcome.FAILED,
                "VERIFIED_TECHNICAL_RECOVERY_REQUIRED"
        );
    }

    private AuditDegradationEventDocument unresolvedEvent() {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("audit-1");
        event.setType(AuditDegradationEventDocument.TYPE_POST_COMMIT_DEGRADED);
        event.setResolved(false);
        return event;
    }

    private AuditDegradationEventDocument unresolvedExternalHeadIncident() {
        AuditDegradationEventDocument event = new AuditDegradationEventDocument();
        event.setAuditId("external-head-1");
        event.setType(AuditDegradationEventDocument.TYPE_EXTERNAL_HEAD_INTEGRITY);
        event.setResolved(false);
        event.setResolutionPending(false);
        event.setReason("HEAD_MANIFEST_HASH_MISMATCH");
        event.setObservedVersion("version-7");
        event.setObservedDigest("digest-7");
        event.setRecoveryAction("RECONSTRUCT_AND_CAS_HEAD");
        event.setRecoveryOutcome("DETECTED_PENDING_REPAIR");
        return event;
    }

    private void assertExternalHeadIncidentUnchanged(AuditDegradationEventDocument event) {
        assertThat(event.isResolved()).isFalse();
        assertThat(event.isResolutionPending()).isFalse();
        assertThat(event.getResolvedAt()).isNull();
        assertThat(event.getResolvedBy()).isNull();
        assertThat(event.getResolutionReason()).isNull();
        assertThat(event.getResolutionRequestedAt()).isNull();
        assertThat(event.getResolutionRequestedBy()).isNull();
        assertThat(event.getReason()).isEqualTo("HEAD_MANIFEST_HASH_MISMATCH");
        assertThat(event.getObservedVersion()).isEqualTo("version-7");
        assertThat(event.getObservedDigest()).isEqualTo("digest-7");
        assertThat(event.getRecoveryOutcome()).isEqualTo("DETECTED_PENDING_REPAIR");
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
