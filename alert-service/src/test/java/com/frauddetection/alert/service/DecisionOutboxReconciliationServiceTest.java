package com.frauddetection.alert.service;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.audit.ResolutionEvidenceReference;
import com.frauddetection.alert.audit.ResolutionEvidenceType;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.MongoRegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationAuditPhaseService;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.mutation.decisionoutbox.DecisionOutboxReconciliationMutationHandler;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
                "ops-admin",
                "idem-1"
        );

        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(resolved.eventId()).isEqualTo("event-1");
        assertThat(resolved.dedupeKey()).isEqualTo("event-1");
        assertThat(document.getDecisionOutboxPublishedAt()).isNotNull();
        verify(auditService).audit(
                eq(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION),
                eq(AuditResourceType.DECISION_OUTBOX),
                eq("alert-1"),
                isNull(),
                eq("ops-admin"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:SUCCESS")
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
                "ops-admin",
                "idem-1"
        );

        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(resolved.eventId()).isEqualTo("event-1");
        assertThat(resolved.dedupeKey()).isEqualTo("event-1");
        verify(auditService).audit(
                eq(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION),
                eq(AuditResourceType.DECISION_OUTBOX),
                eq("alert-1"),
                isNull(),
                eq("ops-admin"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:SUCCESS")
        );
    }

    @Test
    void shouldRejectPublishedResolutionWithoutBrokerEvidence() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                ticketEvidence(),
                "ops-admin",
                "idem-1"
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
                eq(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION),
                eq(AuditResourceType.DECISION_OUTBOX),
                eq("alert-1"),
                isNull(),
                eq("ops-admin"),
                eq(AuditOutcome.ATTEMPTED),
                isNull(),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:ATTEMPTED")
        );

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                brokerEvidence(),
                "ops-admin",
                "idem-1"
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
                "ops-admin",
                "idem-1"
        )).isInstanceOf(DataAccessResourceFailureException.class);

        verify(auditService).audit(
                eq(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION),
                eq(AuditResourceType.DECISION_OUTBOX),
                eq("alert-1"),
                isNull(),
                eq("ops-admin"),
                eq(AuditOutcome.FAILED),
                eq("BUSINESS_WRITE_FAILED"),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:FAILED")
        );
    }

    @Test
    void shouldRecordPostCommitDegradationWhenSuccessAuditFailsAfterMutation() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        AuditDegradationService degradationService = mock(AuditDegradationService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, degradationService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);
        doThrow(new RuntimeException("audit down")).when(auditService).audit(
                eq(AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION),
                eq(AuditResourceType.DECISION_OUTBOX),
                eq("alert-1"),
                isNull(),
                eq("ops-admin"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:SUCCESS")
        );

        DecisionOutboxReconciliationService.UnknownConfirmation degraded = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed",
                brokerEvidence(),
                "ops-admin",
                "idem-1"
        );

        assertThat(document.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(degraded.status()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
        assertThat(degraded.status()).isNotEqualTo("COMMITTED_FULLY_ANCHORED");
        verify(degradationService).recordPostCommitDegraded(
                AuditAction.RESOLVE_DECISION_OUTBOX_CONFIRMATION,
                AuditResourceType.DECISION_OUTBOX,
                "alert-1",
                "POST_COMMIT_AUDIT_DEGRADED",
                "mutation-1"
        );
    }

    @Test
    void shouldReplayResolvedOutboxCommandBeforeLiveStateValidation() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        DecisionOutboxReconciliationService.UnknownConfirmation first = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
                brokerEvidence(),
                "ops-admin",
                "idem-1"
        );
        DecisionOutboxReconciliationService.UnknownConfirmation replayed = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
                brokerEvidence(),
                "ops-admin",
                "idem-1"
        );

        assertThat(first).isEqualTo(replayed);
        assertThat(replayed.status()).isEqualTo(DecisionOutboxStatus.PUBLISHED);
    }

    @Test
    void shouldRejectDifferentPayloadForSameResolvedOutboxIdempotencyKey() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));
        when(repository.save(document)).thenReturn(document);

        service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
                brokerEvidence(),
                "ops-admin",
                "idem-1"
        );

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "not present in broker",
                null,
                "ops-admin",
                "idem-1"
        )).isInstanceOf(ConflictingIdempotencyKeyException.class);
    }

    @Test
    void shouldRejectNewOutboxResolutionCommandAfterAlreadyResolved() {
        AlertRepository repository = mock(AlertRepository.class);
        AuditService auditService = mock(AuditService.class);
        DecisionOutboxReconciliationService service = service(repository, auditService, false);
        AlertDocument document = unknownDocument();
        document.setDecisionOutboxStatus(DecisionOutboxStatus.PUBLISHED);
        when(repository.findById("alert-1")).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.PUBLISHED,
                "confirmed in broker logs",
                brokerEvidence(),
                "ops-admin",
                "idem-new"
        )).isInstanceOf(ResponseStatusException.class);
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
                "ops-requester",
                "idem-request"
        );

        assertThat(pending.resolutionPending()).isTrue();
        assertThat(pending.status()).isEqualTo(DecisionOutboxStatus.PUBLISH_CONFIRMATION_UNKNOWN);
        assertThatThrownBy(() -> service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "approved",
                ticketEvidence(),
                "ops-requester",
                "idem-same-actor"
        )).isInstanceOf(ResponseStatusException.class);

        DecisionOutboxReconciliationService.UnknownConfirmation resolved = service.resolve(
                "alert-1",
                DecisionOutboxReconciliationService.Resolution.RETRY_REQUESTED,
                "approved",
                ticketEvidence(),
                "ops-approver",
                "idem-approve"
        );
        assertThat(resolved.status()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(resolved.resolutionPending()).isFalse();
        assertThat(resolved.resolutionApprovedBy()).isEqualTo("ops-approver");
    }

    private DecisionOutboxReconciliationService service(AlertRepository repository, AuditService auditService, boolean bankMode) {
        return service(repository, auditService, mock(AuditDegradationService.class), bankMode);
    }

    private DecisionOutboxReconciliationService service(
            AlertRepository repository,
            AuditService auditService,
            AuditDegradationService degradationService,
            boolean bankMode
    ) {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        Map<String, RegulatedMutationCommandDocument> commands = new HashMap<>();
        RegulatedMutationCommandDocument[] current = new RegulatedMutationCommandDocument[1];
        when(commandRepository.findByIdempotencyKey(any())).thenAnswer(invocation -> Optional.ofNullable(commands.get(invocation.getArgument(0))));
        when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
            RegulatedMutationCommandDocument command = invocation.getArgument(0);
            command.setId("mutation-1");
            current[0] = command;
            commands.put(command.getIdempotencyKey(), command);
            return command;
        });
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        )).thenAnswer(invocation -> {
            current[0].setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
            return current[0];
        });
        return new DecisionOutboxReconciliationService(
                repository,
                new MongoRegulatedMutationCoordinator(
                        commandRepository,
                        mongoTemplate,
                        new RegulatedMutationAuditPhaseService(auditEventRepository, auditService),
                        degradationService,
                        metrics,
                        bankMode,
                        Duration.ofSeconds(30)
                ),
                new DecisionOutboxReconciliationMutationHandler(repository),
                bankMode
        );
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
