package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.PostCommitEvidenceIncompleteException;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionOutboxReconciliationServiceTest {

    @Test
    void shouldListUnknownConfirmationsWithoutBusinessPayloads() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findTop100ByDecisionOutboxStatusOrderByDecidedAtAsc(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN))
                .thenReturn(List.of(document));

        List<DecisionOutboxReconciliationService.UnknownConfirmation> events = service.listUnknownConfirmations();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().alertId()).isEqualTo("alert-1");
        assertThat(events.getFirst().eventId()).isEqualTo("event-1");
        assertThat(events.getFirst().dedupeKey()).isEqualTo("event-1");
        assertThat(events.getFirst().status()).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
    }

    @Test
    void shouldResolveUnknownConfirmationAsPublishedWithoutChangingDedupeIdentity() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
                brokerEvidence(),
                "ops-admin"
        );

        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(resolved.eventId()).isEqualTo("event-1");
        assertThat(resolved.dedupeKey()).isEqualTo("event-1");
        assertThat(document.getDecisionOutboxPublishedAt()).isNotNull();
        verify(auditService).audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                null,
                "ops-admin",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldResolveUnknownConfirmationAsRetryRequestedWithSameEventIdentity() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "not present in broker",
                null,
                "ops-admin"
        );

        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(resolved.eventId()).isEqualTo("event-1");
        assertThat(resolved.dedupeKey()).isEqualTo("event-1");
        verify(auditService).audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                null,
                "ops-admin",
                AuditOutcome.SUCCESS,
                null
        );
    }

    @Test
    void shouldRejectPublishedResolutionWithoutBrokerEvidence() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        when(repository.findById("alert-1")).thenReturn(Optional.of(unknownDocument()));

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                ticketEvidence(),
                "ops-admin"
        )).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldNotMutateWhenAttemptAuditFails() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        doThrow(new RuntimeException("audit down")).when(auditService).audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                null,
                "ops-admin",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                brokerEvidence(),
                "ops-admin"
        )).isInstanceOf(RuntimeException.class);

        assertThat(document.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        verify(repository, never()).save(any());
    }

    @Test
    void shouldAuditFailedWhenResolutionMutationFails() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenThrow(new DataAccessResourceFailureException("mongo down"));

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                brokerEvidence(),
                "ops-admin"
        )).isInstanceOf(DataAccessResourceFailureException.class);

        verify(auditService).audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                null,
                "ops-admin",
                AuditOutcome.FAILED,
                "RESOLUTION_STATE_UPDATE_FAILED"
        );
    }

    @Test
    void shouldRecordPostCommitDegradationWhenSuccessAuditFailsAfterMutation() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        DecisionOutboxReconciliationService service = new DecisionOutboxReconciliationService(
                repository,
                auditService,
                degradationService,
                false
        );
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);
        doThrow(new RuntimeException("audit down")).when(auditService).audit(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                null,
                "ops-admin",
                AuditOutcome.SUCCESS,
                null
        );

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                brokerEvidence(),
                "ops-admin"
        )).isInstanceOf(PostCommitEvidenceIncompleteException.class);

        assertThat(document.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        verify(degradationService).recordPostCommitDegraded(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "event-1",
                "POST_COMMIT_AUDIT_DEGRADED"
        );
    }

    @Test
    void shouldRequireDualControlInBankMode() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, true);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation pending = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "not present in broker",
                ticketEvidence(),
                "ops-requester"
        );

        assertThat(pending.resolutionPending()).isTrue();
        assertThat(pending.status()).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "approved",
                ticketEvidence(),
                "ops-requester"
        )).isInstanceOf(ResponseStatusException.class);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "approved",
                ticketEvidence(),
                "ops-approver"
        );
        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(resolved.resolutionPending()).isFalse();
        assertThat(resolved.resolutionApprovedBy()).isEqualTo("ops-approver");
    }

    private DecisionOutboxReconciliationService service(AlertRepository repository, AuditService auditService, boolean bankMode) {
        return new DecisionOutboxReconciliationService(repository, auditService, mock(AuditDegradationService.class), bankMode);
    }

    private ResolutionEvidenceReference brokerEvidence() {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.BROKER_OFFSET,
                "topic=fraud-decisions,partition=0,offset=42",
                Instant.parse("2026-04-30T12:01:00Z"),
                "ops-admin"
        );
    }

    private ResolutionEvidenceReference ticketEvidence() {
        return new ResolutionEvidenceReference(
                ResolutionEvidenceType.TICKET,
                "ticket-123",
                Instant.parse("2026-04-30T12:01:00Z"),
                "ops-admin"
        );
    }

    private AlertDocument unknownDocument() {
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setDecidedAt(Instant.parse("2026-04-30T12:00:00Z"));
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        document.setDecisionOutboxAttempts(2);
        document.setDecisionOutboxFailureReason("OUTBOX_PUBLISH_CONFIRMATION_FAILED");
        document.setDecisionOutboxEvent(new FraudDecisionEvent(
                "event-1",
                "decision-1",
                "alert-1",
                "transaction-1",
                "customer-1",
                "correlation-1",
                "analyst-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "confirmed",
                List.of(),
                Map.of(),
                Instant.parse("2026-04-30T12:00:00Z"),
                Instant.parse("2026-04-30T12:00:00Z")
        ));
        return document;
    }
}
