package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventDocument;
import com.frauddetection.alert.audit.AuditEventMetadataSummary;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.persistence.AlertRepository;
import com.frauddetection.alert.service.DecisionOutboxStatus;
import com.frauddetection.common.events.contract.FraudDecisionEvent;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

class RegulatedMutationRecoveryServiceTest {

    @Test
    void finalizeRecoveryRequiredCountUsesTheCanonicalFinalizeRecoveryState() {
        Fixture fixture = new Fixture();
        when(fixture.commandRepository.countByState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED))
                .thenReturn(4L);

        assertThat(fixture.service.finalizeRecoveryRequiredCount()).isEqualTo(4L);
        verify(fixture.commandRepository).countByState(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
    }

    @Test
    void shouldReturnExistingAuditWhenConcurrentPhaseInsertWinsRace() {
        AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        AuditService auditService = mock(AuditService.class);
        RegulatedMutationAuditPhaseService phaseService = new RegulatedMutationAuditPhaseService(auditEventRepository, auditService);
        RegulatedMutationCommandDocument command = new Fixture().command(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        AuditEventDocument existingAudit = mock(AuditEventDocument.class);
        when(existingAudit.auditId()).thenReturn("audit-success-1");
        when(auditEventRepository.findByRequestId("mutation-1:SUCCESS"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingAudit));
        doThrow(new DuplicateKeyException("duplicate request_id")).when(auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.SUCCESS),
                isNull(),
                any(AuditEventMetadataSummary.class),
                eq("mutation-1:SUCCESS")
        );

        String auditId = phaseService.recordPhase(
                command,
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                AuditOutcome.SUCCESS,
                null
        );

        assertThat(auditId).isEqualTo("audit-success-1");
    }

    @Test
    void shouldMarkFinalizingWithoutSnapshotAsRecoveryRequired() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZING);

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("RECOVERY_REQUIRED");
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        verify(fixture.durableLocalFinalizationProof, never()).verify(any());
        verify(fixture.auditService, never()).audit(any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void shouldRejectExistingSnapshotWhenLocalCommitMarkerIsMissing() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        command.setResponseSnapshot(snapshot());
        when(fixture.durableLocalFinalizationProof.verify(command)).thenReturn(
                DurableLocalFinalizationProofResult.invalid("LOCAL_COMMIT_MARKER_MISSING")
        );

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("LOCAL_COMMIT_MARKER_MISSING");
    }

    @Test
    void shouldRejectExistingSnapshotWhenSuccessAuditIsMissing() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        command.setResponseSnapshot(snapshot());
        when(fixture.durableLocalFinalizationProof.verify(command)).thenReturn(
                DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_MISSING")
        );

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("SUCCESS_AUDIT_MISSING");
    }

    @Test
    void shouldNotUseMatchingBusinessStateAsSubstituteForDurableProof() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        setSubmitDecisionIntent(command, AnalystDecision.CONFIRMED_FRAUD, "Manual review", List.of("chargeback"), "principal-7");
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(committedAlert(DecisionOutboxStatus.PUBLISHED)));
        when(fixture.durableLocalFinalizationProof.verify(command)).thenReturn(
                DurableLocalFinalizationProofResult.invalid("SUCCESS_AUDIT_MISSING")
        );

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getResponseSnapshot()).isNull();
        verify(fixture.alertRepository, never()).findById("alert-1");
    }

    @Test
    void shouldRejectMissingRequiredOutboxProof() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        command.setResponseSnapshot(snapshot());
        when(fixture.durableLocalFinalizationProof.verify(command)).thenReturn(
                DurableLocalFinalizationProofResult.invalid("TRANSACTIONAL_OUTBOX_PROOF_MISSING")
        );

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("TRANSACTIONAL_OUTBOX_PROOF_MISSING");
    }

    @Test
    void shouldReleaseStaleRequestedCommandForSafeRetry() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.REQUESTED);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        command.setLeaseOwner("worker-1");
        command.setLeaseExpiresAt(Instant.now().minusSeconds(30));

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.STILL_PENDING);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.NEW);
        assertThat(command.getLeaseOwner()).isNull();
        assertThat(command.getLeaseExpiresAt()).isNull();
        verify(fixture.auditService, never()).audit(any(), any(), anyString(), any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void shouldReconstructSnapshotFromCommittedBusinessStateAndOutboxWithoutRerunningMutation() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        setSubmitDecisionIntent(command, AnalystDecision.CONFIRMED_FRAUD, "Manual review", List.of("chargeback"), "principal-7");
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(committedAlert(DecisionOutboxStatus.PUBLISHED)));

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getResponseSnapshot()).isNotNull();
        assertThat(command.getResponseSnapshot().decisionEventId()).isEqualTo("event-1");
        assertThat(command.getOutboxEventId()).isEqualTo("event-1");
        assertThat(command.getPublicStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    @Test
    void shouldRequireRecoveryWhenCommittedBusinessStateDoesNotMatchIntent() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        setSubmitDecisionIntent(command, AnalystDecision.CONFIRMED_FRAUD, "Manual review", List.of("chargeback"), "principal-7");
        AlertDocument mismatched = committedAlert(DecisionOutboxStatus.PUBLISHED);
        mismatched.setAnalystDecision(AnalystDecision.MARKED_LEGITIMATE);
        when(fixture.alertRepository.findById("alert-1")).thenReturn(Optional.of(mismatched));

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("BUSINESS_STATE_INTENT_MISMATCH");
        assertThat(command.getResponseSnapshot()).isNull();
    }

    @Test
    void shouldNeverDowngradeConfirmedCommand() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(command.getPublicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
        verify(fixture.fencedCommandWriter, never()).recoveryTransition(any(), any(), any(), any(), any());
    }

    @Test
    void shouldMarkUnsupportedMutationRecoveryRequiredWithoutGuessingSnapshot() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_VISIBLE);
        command.setAction(AuditAction.UPDATE_FRAUD_CASE.name());
        command.setResourceType(AuditResourceType.FRAUD_CASE.name());

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(command.getResponseSnapshot()).isNull();
    }

    @Test
    void shouldMarkStrategyFailureAsRecoveryRequired() {
        RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        RegulatedMutationRecoveryStrategy strategy = mock(RegulatedMutationRecoveryStrategy.class);
        when(strategy.supports(AuditAction.SUBMIT_ANALYST_DECISION, AuditResourceType.ALERT)).thenReturn(true);
        when(strategy.validateBusinessState(any())).thenThrow(new IllegalStateException("repository unavailable"));
        when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        RegulatedMutationRecoveryService service = new RegulatedMutationRecoveryService(
                commandRepository,
                mock(AlertServiceMetrics.class),
                List.of(strategy),
                mock(RegulatedMutationFencedCommandWriter.class),
                acceptedProof(),
                new RegulatedMutationPublicStatusMapper(),
                Duration.ofMinutes(2)
        );
        RegulatedMutationCommandDocument command = new Fixture().command(RegulatedMutationState.FINALIZED_VISIBLE);

        RegulatedMutationRecoveryResult result = service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
    }

    @Test
    void shouldScanBoundedStuckCommands() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.REQUESTED);
        RegulatedMutationCommandDocument evidencePending = fixture.command(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        evidencePending.setIdempotencyKey("idem-2");
        evidencePending.setResponseSnapshot(snapshot());
        when(fixture.commandRepository.findTop100ByExecutionStatusInAndUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(command));
        when(fixture.commandRepository.findTop100ByStateInAndUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(evidencePending));

        List<RegulatedMutationRecoveryResult> results = fixture.service.recoverStuckCommands();

        assertThat(results).hasSize(2);
        assertThat(results.getFirst().outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.STILL_PENDING);
        assertThat(results.get(1).outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        verify(fixture.metrics).recordRegulatedMutationRecoveryOutcome("STILL_PENDING");
        verify(fixture.metrics).recordRegulatedMutationRecoveryOutcome("RECOVERED");
        verify(fixture.metrics, atLeastOnce()).recordRegulatedMutationRecoveryBacklog(anyLong(), any(), anyLong(), anyLong());
        verify(fixture.commandRepository).findTop100ByStateInAndUpdatedAtBefore(
                argThat(states -> states.contains(RegulatedMutationState.FINALIZED_VISIBLE)
                        && states.contains(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL)),
                any()
        );
    }

    @Test
    void shouldSkipStaleRecoveryCandidateWhenAnotherWorkerAdvancesCommand() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.REQUESTED);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.NEW);
        when(fixture.commandRepository.findTop100ByExecutionStatusInAndUpdatedAtBefore(anyCollection(), any()))
                .thenReturn(List.of(command));
        doThrow(new RegulatedMutationRecoveryWriteConflictException(command.getId()))
                .when(fixture.fencedCommandWriter)
                .recoveryTransition(any(), any(), any(), any(), any());

        List<RegulatedMutationRecoveryResult> results = fixture.service.recoverStuckCommands();

        assertThat(results).isEmpty();
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.NEW);
    }

    @Test
    void shouldInspectCommandWithoutPayloadDump() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        command.setResponseSnapshot(snapshot());
        command.setAttemptedAuditId("audit-attempted");
        command.setSuccessAuditId("audit-success");
        command.setLastError("RECOVERY_REQUIRED");
        when(fixture.commandRepository.findByIdempotencyKey("idem-1")).thenReturn(Optional.of(command));

        RegulatedMutationCommandInspectionResponse response = fixture.service.inspect(" idem-1 ");

        assertThat(response.idempotencyKeyHash()).isEqualTo(RegulatedMutationIntentHasher.hash("idem-1"));
        assertThat(response.idempotencyKeyMasked()).isEqualTo("...em-1");
        assertThat(response.action()).isEqualTo(AuditAction.SUBMIT_ANALYST_DECISION.name());
        assertThat(response.resourceType()).isEqualTo(AuditResourceType.ALERT.name());
        assertThat(response.resourceIdPresent()).isTrue();
        assertThat(response.resourceIdHash()).isEqualTo(RegulatedMutationIntentHasher.hash("resourceId=alert-1"));
        assertThat(response.state()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL.name());
        assertThat(response.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED.name());
        assertThat(response.responseSnapshotPresent()).isTrue();
        assertThat(response.attemptedAuditId()).isEqualTo("audit-attempted");
        assertThat(response.successAuditId()).isEqualTo("audit-success");
        assertThat(response.lastErrorCode()).isEqualTo("RECOVERY_REQUIRED");
    }

    @Test
    void shouldInspectCommandByCommandIdAndIdempotencyHash() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        command.setIdempotencyKeyHash(RegulatedMutationIntentHasher.hash("idem-1"));
        when(fixture.commandRepository.findById("mutation-1")).thenReturn(Optional.of(command));
        when(fixture.commandRepository.findByIdempotencyKeyHash(command.getIdempotencyKeyHash())).thenReturn(Optional.of(command));

        assertThat(fixture.service.inspectByCommandId(" mutation-1 ").idempotencyKeyHash()).isEqualTo(command.getIdempotencyKeyHash());
        assertThat(fixture.service.inspectByIdempotencyHash(command.getIdempotencyKeyHash()).idempotencyKeyHash()).isEqualTo(command.getIdempotencyKeyHash());
    }

    @Test
    void shouldReturnNotFoundForMissingInspectionCommand() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.service.inspect("missing"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static RegulatedMutationResponseSnapshot snapshot() {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL
        );
    }

    private static AlertDocument committedAlert(String outboxStatus) {
        AlertDocument document = new AlertDocument();
        document.setAlertId("alert-1");
        document.setTransactionId("txn-1");
        document.setCustomerId("cust-1");
        document.setCorrelationId("corr-1");
        document.setAnalystId("principal-7");
        document.setAnalystDecision(AnalystDecision.CONFIRMED_FRAUD);
        document.setDecisionReason("Manual review");
        document.setDecisionTags(List.of("chargeback"));
        document.setAlertStatus(AlertStatus.RESOLVED);
        document.setDecidedAt(Instant.parse("2026-05-01T00:00:00Z"));
        document.setDecisionOutboxStatus(outboxStatus);
        document.setDecisionOutboxEvent(new FraudDecisionEvent(
                "event-1",
                "decision-1",
                "alert-1",
                "txn-1",
                "cust-1",
                "corr-1",
                "principal-7",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "Manual review",
                List.of("chargeback"),
                java.util.Map.of(),
                Instant.parse("2026-05-01T00:00:00Z"),
                Instant.parse("2026-05-01T00:00:00Z")
        ));
        return document;
    }

    private static void setSubmitDecisionIntent(
            RegulatedMutationCommandDocument command,
            AnalystDecision decision,
            String reason,
            List<String> tags,
            String actorId
    ) {
        RegulatedMutationIntent intent = RegulatedMutationIntentHasher.submitDecision(
                command.getResourceId(),
                actorId,
                decision,
                reason,
                tags
        );
        command.setIntentHash(intent.intentHash());
        command.setIntentResourceId(intent.resourceId());
        command.setIntentAction(intent.action());
        command.setIntentActorId(intent.actorId());
        command.setIntentDecision(intent.decision());
        command.setIntentReasonHash(intent.reasonHash());
        command.setIntentTagsHash(intent.tagsHash());
    }

    private static RegulatedMutationDurableLocalFinalizationProof acceptedProof() {
        RegulatedMutationDurableLocalFinalizationProof proof = mock(RegulatedMutationDurableLocalFinalizationProof.class);
        when(proof.verify(any())).thenReturn(DurableLocalFinalizationProofResult.accepted());
        return proof;
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        private final AuditService auditService = mock(AuditService.class);
        private final AuditDegradationService auditDegradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final AlertRepository alertRepository = mock(AlertRepository.class);
        private final RegulatedMutationFencedCommandWriter fencedCommandWriter =
                mock(RegulatedMutationFencedCommandWriter.class);
        private final RegulatedMutationDurableLocalFinalizationProof durableLocalFinalizationProof =
                mock(RegulatedMutationDurableLocalFinalizationProof.class);
        private final RegulatedMutationRecoveryService service = new RegulatedMutationRecoveryService(
                commandRepository,
                metrics,
                List.of(new SubmitDecisionRecoveryStrategy(alertRepository)),
                fencedCommandWriter,
                durableLocalFinalizationProof,
                new RegulatedMutationPublicStatusMapper(),
                Duration.ofMinutes(2)
        );

        private Fixture() {
            when(commandRepository.findTop100ByExecutionStatusInAndUpdatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());
            when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());
            when(commandRepository.findTop100ByExecutionStatusOrderByUpdatedAtAsc(any()))
                    .thenReturn(List.of());
            when(commandRepository.findTop100ByExecutionStatusAndLeaseExpiresAtBeforeOrderByUpdatedAtAsc(any(), any()))
                    .thenReturn(List.of());
            when(durableLocalFinalizationProof.verify(any()))
                    .thenReturn(DurableLocalFinalizationProofResult.accepted());
            when(fencedCommandWriter.recoveryTransition(any(), any(), any(), any(), any()))
                    .thenAnswer(invocation -> ((RegulatedMutationCommandDocument) invocation.getArgument(0)).requireRevision() + 1L);
        }

        private RegulatedMutationCommandDocument command(RegulatedMutationState state) {
            RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
            command.setId("mutation-1");
            command.setIdempotencyKey("idem-1");
            command.setActorId("principal-7");
            command.setResourceId("alert-1");
            command.setResourceType(AuditResourceType.ALERT.name());
            command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
            command.setCorrelationId("corr-1");
            command.setRequestHash("request-hash");
            command.setMutationModelVersion(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
            command.setRevision(0L);
            command.setState(state);
            command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
            command.setCreatedAt(Instant.now().minusSeconds(300));
            command.setUpdatedAt(Instant.now().minusSeconds(300));
            return command;
        }
    }
}
