package com.frauddetection.alert.service;

import com.frauddetection.alert.api.SubmitAnalystDecisionRequest;
import com.frauddetection.alert.api.SubmitAnalystDecisionResponse;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.mapper.AlertDocumentMapper;
import com.frauddetection.alert.mapper.FraudDecisionEventMapper;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.regulated.MissingIdempotencyKeyException;
import com.frauddetection.alert.regulated.MongoRegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import com.frauddetection.common.events.enums.RiskLevel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING);
        assertThat(alert.getAnalystId()).isEqualTo("principal-7");
        assertThat(alert.getDecisionOutboxStatus()).isEqualTo(DecisionOutboxStatus.PENDING);
        assertThat(response.decisionEventId()).isEqualTo(alert.getDecisionOutboxEvent().eventId());
        assertThat(fixture.states).containsSubsequence(
                RegulatedMutationState.REQUESTED,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationState.BUSINESS_COMMITTED,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationState.SUCCESS_AUDIT_RECORDED,
                RegulatedMutationState.EVIDENCE_PENDING
        );
        InOrder inOrder = inOrder(fixture.auditService, fixture.alertRepository);
        inOrder.verify(fixture.auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.ATTEMPTED,
                null
        );
        inOrder.verify(fixture.alertRepository).save(any(AlertDocument.class));
        inOrder.verify(fixture.auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.SUCCESS,
                null
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
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.ATTEMPTED,
                null
        );

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(AuditPersistenceUnavailableException.class);

        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
        assertThat(fixture.states).contains(RegulatedMutationState.REJECTED);
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

        assertThat(fixture.states).contains(RegulatedMutationState.FAILED);
        verify(fixture.auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.FAILED,
                "BUSINESS_WRITE_FAILED"
        );
    }

    @Test
    void shouldReturnCommittedIncompleteWhenSuccessAuditFailsAfterBusinessCommit() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        AlertDocument alert = fixture.alert();
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(alert));
        when(fixture.alertRepository.save(any(AlertDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.SUCCESS,
                null
        );

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.operationStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE);
        assertThat(response.operationStatus().name()).isNotEqualTo("COMMITTED_FULLY_ANCHORED");
        assertThat(alert.getAlertStatus()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(alert.getDecisionOperationStatus()).isEqualTo(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_INCOMPLETE.name());
        assertThat(fixture.states).contains(RegulatedMutationState.COMMITTED_DEGRADED);
        verify(fixture.degradationService).recordPostCommitDegraded(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "POST_COMMIT_AUDIT_DEGRADED"
        );
        verify(fixture.metrics).recordPostCommitAuditDegraded("SUBMIT_ANALYST_DECISION");
    }

    @Test
    void shouldReplaySameIdempotencyKeyWithIdenticalResponseSnapshot() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = new RegulatedMutationCommandDocument();
        existing.setIdempotencyKey("idem-1");
        existing.setRequestHash("ef884a0e375de07e11b89639ead3cccb2256434e2adc707a0fe377ab5f13b7ad");
        existing.setState(RegulatedMutationState.EVIDENCE_PENDING);
        existing.setResponseSnapshot(new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        ));
        fixture.commandLookup(Optional.of(existing));
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        SubmitAnalystDecisionResponse response = fixture.service().submit("alert-1", request(), "idem-1");

        assertThat(response.decisionEventId()).isEqualTo("event-1");
        assertThat(response.decidedAt()).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"));
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentPayload() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = new RegulatedMutationCommandDocument();
        existing.setIdempotencyKey("idem-1");
        existing.setRequestHash("different");
        existing.setState(RegulatedMutationState.EVIDENCE_PENDING);
        fixture.commandLookup(Optional.of(existing));
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(fixture.alert()));
        when(fixture.actorResolver.resolveActorId(eq("analyst-7"), eq("SUBMIT_ANALYST_DECISION"), eq("alert-1")))
                .thenReturn("principal-7");

        assertThatThrownBy(() -> fixture.service().submit("alert-1", request(), "idem-1"))
                .isInstanceOf(ConflictingIdempotencyKeyException.class);

        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any());
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

        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any());
        verify(fixture.alertRepository, never()).save(any(AlertDocument.class));
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
        private final AuditService auditService = mock(AuditService.class);
        private final AuditDegradationService degradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final com.frauddetection.alert.security.principal.AnalystActorResolver actorResolver =
                mock(com.frauddetection.alert.security.principal.AnalystActorResolver.class);
        private final List<RegulatedMutationState> states = new ArrayList<>();

        private SubmitDecisionRegulatedMutationService service() {
            return new SubmitDecisionRegulatedMutationService(
                    alertRepository,
                    new AlertDocumentMapper(),
                    new AnalystDecisionStatusMapper(),
                    actorResolver,
                    new DecisionOutboxWriter(new FraudDecisionEventMapper()),
                    new MongoRegulatedMutationCoordinator(commandRepository, auditService, degradationService, metrics, false)
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

        private void commandLookup(Optional<RegulatedMutationCommandDocument> existing) {
            when(commandRepository.findByIdempotencyKey("idem-1")).thenReturn(existing);
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                RegulatedMutationCommandDocument document = invocation.getArgument(0);
                if (document.getId() == null) {
                    document.setId("mutation-1");
                }
                states.add(document.getState());
                return document;
            });
        }
    }
}
