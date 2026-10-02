package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordDocument;
import com.frauddetection.alert.outbox.TransactionalOutboxRecordRepository;
import com.frauddetection.alert.outbox.TransactionalOutboxRuntimeReadiness;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.MissingIdempotencyKeyException;
import com.frauddetection.alert.regulated.MongoRegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationAuditPhaseService;
import com.frauddetection.alert.regulated.RegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationModelVersion;
import com.frauddetection.alert.regulated.RegulatedMutationResult;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.regulated.mutation.submitdecision.SubmitDecisionMutationHandler;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmitDecisionRegulatedMutationServiceTest {

    @Test
    void shouldCommitDecisionThroughRegulatedStateMachine() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.alertRepository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.resultingStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(alert.getAnalystId()).isEqualTo("principal-7");
        assertThat(alert.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(response.decisionEventId()).isEqualTo(alert.getDecisionOutboxEvent().eventId());
        assertThat(fixture.states).containsSubsequence(
                RegulatedMutationState.REQUESTED,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationState.EVIDENCE_PREPARED,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
        InOrder inOrder = inOrder(fixture.auditService, fixture.alertRepository);
        inOrder.verify(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.ATTEMPTED),
                isNull(),
                any(AuditEventMetadataSummary.class),
                org.mockito.ArgumentMatchers.endsWith(":ATTEMPTED")
        );
        inOrder.verify(fixture.alertRepository).save(any(AlertDocument.class));
        inOrder.verify(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                org.mockito.ArgumentMatchers.endsWith(":SUCCESS")
        );
    }

    @Test
    void shouldRejectBeforeMutationWhenAttemptedAuditFails() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.ATTEMPTED),
                isNull(),
                any(AuditEventMetadataSummary.class),
                org.mockito.ArgumentMatchers.endsWith(":ATTEMPTED")
        );

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
        assertThat(fixture.states).contains(RegulatedMutationState.REJECTED_EVIDENCE_UNAVAILABLE);
    }

    @Test
    void shouldAuditFailedWhenBusinessWriteFails() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.alertRepository.save(any(AlertDocument.class))).thenThrow(new DataAccessResourceFailureException("mongo down"));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(fixture.states).contains(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.FAILED),
                eq("EVIDENCE_GATED_FINALIZE_FAILED"),
                any(AuditEventMetadataSummary.class),
                org.mockito.ArgumentMatchers.endsWith(":FAILED")
        );
    }

    @Test
    void shouldFailClosedWhenTransactionalSuccessAuditFails() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.alertRepository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                org.mockito.ArgumentMatchers.endsWith(":SUCCESS")
        );

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(fixture.currentCommand.getPublicStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(fixture.states).contains(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void shouldReplaySameIdempotencyKeyWithIdenticalResponseSnapshot() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        ));
        fixture.commandLookup(Optional.of(existing));
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.decisionEventId()).isEqualTo("event-1");
        assertThat(response.decidedAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.REQUESTED);
        existing.setRequestHash("different");
        fixture.commandLookup(Optional.of(existing));
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
    }

    @Test
    void shouldRequireIdempotencyKeyForRegulatedDecisionMutation() {
        Fixture fixture = new Fixture();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), null))
                .isInstanceOf(MissingIdempotencyKeyException.class);

        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
    }

    @Test
    void shouldFailFastWhenDecisionOutboxRepositoryIsMissing() {
        DecisionOutboxWriter writer = new DecisionOutboxWriter(
                new FraudDecisionEventMapper(),
                null,
                mock(TransactionalOutboxRuntimeReadiness.class)
        );
        AlertDocument alert = new Fixture().alert();

        assertThatThrownBy(() -> writer.attachPendingOutbox(
                alert,
                new AlertDocumentMapper().toDomain(alert),
                request(),
                AlertStatus.RESOLVED,
                "principal-7",
                "mutation-1"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transactional outbox repository is required")
                .hasMessageContaining("regulated mutation decision outbox writes");
    }

    @Test
    void shouldPersistEvidenceGatedSubmitDecisionOperationStatusWhenRequested() {
        Fixture fixture = new Fixture();
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.alertRepository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.outboxRepository.save(any(TransactionalOutboxRecordDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SubmitDecisionMutationHandler handler = new SubmitDecisionMutationHandler(
                fixture.alertRepository,
                new AlertDocumentMapper(),
                new DecisionOutboxWriter(new FraudDecisionEventMapper(), fixture.outboxRepository,
                        mock(TransactionalOutboxRuntimeReadiness.class))
        );

        handler.applyDecision(
                "alert-1",
                request(),
                AlertStatus.RESOLVED,
                "principal-7",
                "idem-1",
                "request-hash-1",
                "mutation-1",
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );

        assertThat(alert.getDecisionOperationStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
    }

    @Test
    void shouldReturnCurrentPhaseWhenDuplicateRequestArrivesDuringActiveLease() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.EVIDENCE_PREPARING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        fixture.commandLookup(Optional.of(existing));
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.EVIDENCE_PREPARING);
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shouldReturnInProgressWhenConcurrentRequestWinsAtomicClaim() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        fixture.claimReturnsNull = true;
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.IN_PROGRESS);
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldAlwaysUseCanonicalModelVersion() {
        AlertRepository alertRepository = mock(AlertRepository.class);
        com.frauddetection.alert.security.principal.AnalystActorResolver actorResolver =
                mock(com.frauddetection.alert.security.principal.AnalystActorResolver.class);
        RegulatedMutationCoordinator coordinator = mock(RegulatedMutationCoordinator.class);
        AlertDocument alert = new Fixture().alert();
        when(alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");
        when(coordinator.commit(any())).thenAnswer(invocation -> {
            RegulatedMutationCommand<AlertDocument, SubmitAnalystDecisionResponse> command = invocation.getArgument(0);
            return new RegulatedMutationResult<>(
                    RegulatedMutationState.REQUESTED,
                    command.statusResponseFactory().response(RegulatedMutationState.REQUESTED)
            );
        });
        SubmitDecisionRegulatedMutationService service = new SubmitDecisionRegulatedMutationService(
                alertRepository,
                new AnalystDecisionStatusMapper(),
                actorResolver,
                mock(SubmitDecisionMutationHandler.class),
                coordinator
        );

        SubmitAnalystDecisionResponse response = service.submit("alert-1", request(), "idem-1");

        ArgumentCaptor<RegulatedMutationCommand<AlertDocument, SubmitAnalystDecisionResponse>> captor =
                ArgumentCaptor.forClass(RegulatedMutationCommand.class);
        verify(coordinator).commit(captor.capture());
        assertThat(captor.getValue().mutationModelVersion())
                .isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.IN_PROGRESS);
        assertThat(response.decision()).isNull();
        assertThat(response.resultingStatus()).isEqualTo(AlertStatus.OPEN);
    }

    private SubmitAnalystDecisionRequest request() {
        return new SubmitAnalystDecisionRequest(
                "analyst-7",
                AnalystDecision.CONFIRMED_FRAUD,
                "Confirmed after manual review",
                List.of("chargeback"),
                Map.of()
        );
    }

    private static final class Fixture {
        private final AlertRepository alertRepository = mock(AlertRepository.class);
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final TransactionalOutboxRecordRepository outboxRepository = mock(TransactionalOutboxRecordRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        private final AuditService auditService = mock(AuditService.class);
        private final AuditDegradationService degradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final com.frauddetection.alert.security.principal.AnalystActorResolver actorResolver =
                mock(com.frauddetection.alert.security.principal.AnalystActorResolver.class);
        private final List<RegulatedMutationState> states = new ArrayList<>();
        private RegulatedMutationCommandDocument currentCommand;
        private boolean claimReturnsNull;

        private SubmitDecisionRegulatedMutationService service() {
            when(outboxRepository.save(any(TransactionalOutboxRecordDocument.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            return new SubmitDecisionRegulatedMutationService(
                    alertRepository,
                    new AnalystDecisionStatusMapper(),
                    actorResolver,
                    new SubmitDecisionMutationHandler(
                            alertRepository,
                            new AlertDocumentMapper(),
                            new DecisionOutboxWriter(new FraudDecisionEventMapper(), outboxRepository,
                                    mock(TransactionalOutboxRuntimeReadiness.class))
                    ),
                    com.frauddetection.alert.regulated.CanonicalRegulatedMutationTestRuntime.coordinator(
                            commandRepository,
                            mongoTemplate,
                            new RegulatedMutationAuditPhaseService(auditEventRepository, auditService),
                            metrics
                    )
            );
        }

        private AlertDocument alert() {
            AlertDocument document = new AlertDocument();
            document.setAlertId("alert-1");
            document.setTransactionId("txn-1");
            document.setCustomerId("cust-1");
            document.setCorrelationId("corr-1");
            document.setCreatedAt(Instant.now());
            document.setAlertTimestamp(Instant.now());
            document.setAlertStatus(AlertStatus.OPEN);
            document.setRiskLevel(RiskLevel.HIGH);
            document.setFraudScore(0.82d);
            document.setFeatureSnapshot(Map.of("recentTransactionCount", 5));
            return document;
        }

        private RegulatedMutationCommandDocument existingCommand(RegulatedMutationState state) {
            RegulatedMutationCommandDocument existing = new RegulatedMutationCommandDocument();
            existing.setId("mutation-1");
            existing.setIdempotencyKey("idem-1");
            existing.setRequestHash("ef884a0e375de07e11b89639ead3cccb2256434e2adc707a0fe377ab5f13b7ad");
            existing.setActorId("principal-7");
            existing.setResourceId("alert-1");
            existing.setResourceType(AuditResourceType.ALERT.name());
            existing.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
            existing.setCorrelationId("corr-1");
            existing.setIntentActorId("principal-7");
            existing.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
            existing.setRevision(0L);
            existing.setState(state);
            existing.setUpdatedAt(Instant.now().minusSeconds(60));
            return existing;
        }

        private void commandLookup(Optional<RegulatedMutationCommandDocument> existing) {
            existing.ifPresent(document -> currentCommand = document);
            when(commandRepository.findByIdempotencyKey("idem-1")).thenAnswer(invocation -> Optional.ofNullable(currentCommand));
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                RegulatedMutationCommandDocument document = invocation.getArgument(0);
                if (document.getId() == null) {
                    document.setId("mutation-1");
                }
                currentCommand = document;
                states.add(document.getState());
                return document;
            });
            when(mongoTemplate.findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(RegulatedMutationCommandDocument.class)
            )).thenAnswer(invocation -> {
                if (claimReturnsNull) {
                    return null;
                }
                if (currentCommand == null) {
                    return null;
                }
                currentCommand.setExecutionStatus(com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus.PROCESSING);
                currentCommand.setAttemptCount(currentCommand.getAttemptCount() + 1);
                return currentCommand;
            });
            when(mongoTemplate.count(any(Query.class), eq(RegulatedMutationCommandDocument.class))).thenReturn(1L);
            when(mongoTemplate.updateFirst(
                    any(Query.class),
                    any(Update.class),
                    eq(RegulatedMutationCommandDocument.class)
            )).thenAnswer(invocation -> {
                applyUpdate(invocation.getArgument(1));
                states.add(currentCommand.getState());
                return UpdateResult.acknowledged(1, 1L, null);
            });
        }

        private void applyUpdate(Update update) {
            Document set = (Document) update.getUpdateObject().get("$set");
            if (set == null || currentCommand == null) {
                return;
            }
            if (set.containsKey("state")) {
                currentCommand.setState((RegulatedMutationState) set.get("state"));
            }
            if (set.containsKey("execution_status")) {
                currentCommand.setExecutionStatus((RegulatedMutationExecutionStatus) set.get("execution_status"));
            }
            if (set.containsKey("last_error")) {
                currentCommand.setLastError((String) set.get("last_error"));
            }
            if (set.containsKey("response_snapshot")) {
                currentCommand.setResponseSnapshot((RegulatedMutationResponseSnapshot) set.get("response_snapshot"));
            }
            if (set.containsKey("outbox_event_id")) {
                currentCommand.setOutboxEventId((String) set.get("outbox_event_id"));
            }
            if (set.containsKey("local_commit_marker")) {
                currentCommand.setLocalCommitMarker((String) set.get("local_commit_marker"));
            }
            if (set.containsKey("local_committed_at")) {
                currentCommand.setLocalCommittedAt((Instant) set.get("local_committed_at"));
            }
            if (set.containsKey("attempted_audit_id")) {
                currentCommand.setAttemptedAuditId((String) set.get("attempted_audit_id"));
            }
            if (set.containsKey("attempted_audit_recorded")) {
                currentCommand.setAttemptedAuditRecorded((Boolean) set.get("attempted_audit_recorded"));
            }
            if (set.containsKey("success_audit_id")) {
                currentCommand.setSuccessAuditId((String) set.get("success_audit_id"));
            }
            if (set.containsKey("success_audit_recorded")) {
                currentCommand.setSuccessAuditRecorded((Boolean) set.get("success_audit_recorded"));
            }
            if (set.containsKey("failed_audit_id")) {
                currentCommand.setFailedAuditId((String) set.get("failed_audit_id"));
            }
            if (set.containsKey("degradation_reason")) {
                currentCommand.setDegradationReason((String) set.get("degradation_reason"));
            }
            if (set.containsKey("public_status")) {
                currentCommand.setPublicStatus((SubmitDecisionOperationStatus) set.get("public_status"));
            }
        }
    }
}
