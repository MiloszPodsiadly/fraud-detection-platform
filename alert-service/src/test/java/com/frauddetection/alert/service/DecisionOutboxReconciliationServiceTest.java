package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionOutboxReconciliationServiceTest {

    @Test
    void shouldListUnknownConfirmationsWithoutBusinessPayloads() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = new DecisionOutboxReconciliationService(repository, auditService);
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
        DecisionOutboxReconciliationService service = new DecisionOutboxReconciliationService(repository, auditService);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
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
                "ops-admin"
        );
    }

    @Test
    void shouldResolveUnknownConfirmationAsRetryRequestedWithSameEventIdentity() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = new DecisionOutboxReconciliationService(repository, auditService);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "not present in broker",
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
