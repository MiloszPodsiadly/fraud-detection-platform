package com.frauddetection.alert.regulated.failure;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditDegradationService;
import com.frauddetection.alert.audit.AuditEventRepository;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditPersistenceUnavailableException;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.fdp28.FailureInjectionPoint;
import com.frauddetection.alert.fdp28.FailureScenarioRunner;
import com.frauddetection.alert.fdp28.InvariantAssert;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.observability.AlertServiceMetrics;
import com.frauddetection.alert.regulated.MongoRegulatedMutationCoordinator;
import com.frauddetection.alert.regulated.RegulatedMutationAuditPhaseService;
import com.frauddetection.alert.regulated.RegulatedMutationCommand;
import com.frauddetection.alert.regulated.RegulatedMutationCommandDocument;
import com.frauddetection.alert.regulated.RegulatedMutationCommandRepository;
import com.frauddetection.alert.regulated.RegulatedMutationExecutionStatus;
import com.frauddetection.alert.regulated.RegulatedMutationResponseSnapshot;
import com.frauddetection.alert.regulated.RegulatedMutationState;
import com.frauddetection.alert.service.ConflictingIdempotencyKeyException;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("failure-injection")
@Tag("invariant-proof")
class RegulatedMutationCrashWindowInvariantTest {

    @Test
    void shouldPersistPostCommitDegradationWhenSubmitDecisionSuccessAuditFails() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.endsWith(":SUCCESS")
        );
        AtomicInteger businessWrites = new AtomicInteger();

        String response = fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        )).response();

        assertThat(response).isEqualTo("COMMITTED_DEGRADED");
        assertThat(businessWrites).hasValue(1);
        InvariantAssert.postCommitDegradationIsExplicit(fixture.currentCommand);
        verify(fixture.degradationService).recordPostCommitDegraded(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "POST_COMMIT_AUDIT_DEGRADED",
                fixture.currentCommand.getId()
        );
        verify(fixture.metrics).recordPostCommitAuditDegraded("SUBMIT_ANALYST_DECISION");
    }

    @Test
    void shouldRejectFraudCaseMutationBeforeBusinessWriteWhenAttemptedAuditFails() {
        Fixture fixture = new Fixture();
        fixture.commandLookup(Optional.empty());
        doThrow(new AuditPersistenceUnavailableException()).when(fixture.auditService).audit(
                eq(AuditAction.UPDATE_FRAUD_CASE),
                eq(AuditResourceType.FRAUD_CASE),
                eq("case-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.ATTEMPTED),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                org.mockito.ArgumentMatchers.endsWith(":ATTEMPTED")
        );
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(
                AuditAction.UPDATE_FRAUD_CASE,
                AuditResourceType.FRAUD_CASE,
                "case-1",
                "case-updated",
                businessWrites
        ))).isInstanceOf(AuditPersistenceUnavailableException.class);

        assertThat(businessWrites).hasValue(0);
        assertThat(fixture.currentCommand.getState()).isEqualTo(RegulatedMutationState.REJECTED);
        assertThat(fixture.currentCommand.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.FAILED);
        assertThat(fixture.currentCommand.getDegradationReason()).isEqualTo("ATTEMPTED_AUDIT_UNAVAILABLE");
        verify(fixture.degradationService, never()).recordPostCommitDegraded(any(), any(), any(), any(), any());
    }

    @Test
    void shouldLeaveRecoveryRequiredWhenCrashWindowHasCommittedStateWithoutSnapshot() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.BUSINESS_COMMITTED);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        String response = fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        )).response();

        assertThat(response).isEqualTo("FAILED");
        assertThat(businessWrites).hasValue(0);
        InvariantAssert.recoveryRequiredIsNotCommitted(existing);
    }

    @Test
    void shouldReplayCompletedCommandWithoutSecondBusinessMutationOrSuccessAudit() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.EVIDENCE_PENDING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(snapshot("event-1"));
        existing.setSuccessAuditRecorded(true);
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        String response = fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        )).response();

        assertThat(response).isEqualTo("RESTORED:COMMITTED_EVIDENCE_PENDING:event-1");
        assertThat(businessWrites).hasValue(0);
        assertThat(existing.getResponseSnapshot()).isEqualTo(snapshot("event-1"));
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldRejectConflictingIdempotencyPayloadWithoutMutationOrAudit() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.EVIDENCE_PENDING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.COMPLETED);
        existing.setResponseSnapshot(snapshot("event-1"));
        existing.setRequestHash("different-request-hash");
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        assertThatThrownBy(() -> fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        ))).isInstanceOf(ConflictingIdempotencyKeyException.class);

        assertThat(businessWrites).hasValue(0);
        assertThat(existing.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    @Test
    void shouldRetryOnlySuccessAuditForPendingSnapshotWithoutSecondBusinessMutation() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        existing.setResponseSnapshot(snapshot("event-1"));
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        String response = fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        )).response();

        assertThat(response).isEqualTo("RESTORED:COMMITTED_EVIDENCE_PENDING:event-1");
        assertThat(businessWrites).hasValue(0);
        assertThat(existing.isSuccessAuditRecorded()).isTrue();
        assertThat(existing.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        verify(fixture.auditService).audit(
                eq(AuditAction.SUBMIT_ANALYST_DECISION),
                eq(AuditResourceType.ALERT),
                eq("alert-1"),
                eq("corr-1"),
                eq("principal-7"),
                eq(AuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.isNull(),
                any(),
                eq("mutation-1:SUCCESS")
        );
    }

    @Test
    void shouldReturnInProgressForActiveProcessingDuplicateWithoutSecondClaim() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument existing = fixture.existingCommand(RegulatedMutationState.AUDIT_ATTEMPTED);
        existing.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
        existing.setLeaseExpiresAt(Instant.now().plusSeconds(30));
        fixture.commandLookup(Optional.of(existing));
        AtomicInteger businessWrites = new AtomicInteger();

        String response = fixture.coordinator.commit(command(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "decision-committed",
                businessWrites
        )).response();

        assertThat(response).isEqualTo("REQUESTED");
        assertThat(businessWrites).hasValue(0);
        verify(fixture.auditService, never()).audit(any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fixture.mongoTemplate, never()).findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(RegulatedMutationCommandDocument.class)
        );
    }

    private RegulatedMutationCommand<String, String> command(
            AuditAction action,
            AuditResourceType resourceType,
            String resourceId,
            String result,
            AtomicInteger businessWrites
    ) {
        FailureScenarioRunner runner = new FailureScenarioRunner();
        return new RegulatedMutationCommand<>(
                "idem-1",
                "principal-7",
                resourceId,
                resourceType,
                action,
                "corr-1",
                "request-hash",
                context -> {
                    runner.run(FailureInjectionPoint.DURING_LOCAL_TRANSACTION);
                    businessWrites.incrementAndGet();
                    runner.run(FailureInjectionPoint.AFTER_BUSINESS_MUTATION);
                    return result;
                },
                (saved, state) -> state.name(),
                response -> snapshot("event-1"),
                snapshot -> snapshot == null ? "restored" : "RESTORED:" + snapshot.operationStatus().name() + ":" + snapshot.decisionEventId(),
                state -> state.name()
        );
    }

    private static RegulatedMutationResponseSnapshot snapshot(String eventId) {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                eventId,
                Instant.parse("2026-05-03T00:00:00Z"),
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        );
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
        private final AuditService auditService = mock(AuditService.class);
        private final AuditDegradationService degradationService = mock(AuditDegradationService.class);
        private final AlertServiceMetrics metrics = mock(AlertServiceMetrics.class);
        private final MongoRegulatedMutationCoordinator coordinator = new MongoRegulatedMutationCoordinator(
                commandRepository,
                mongoTemplate,
                new RegulatedMutationAuditPhaseService(auditEventRepository, auditService),
                degradationService,
                metrics,
                false,
                Duration.ofSeconds(30)
        );
        private final List<RegulatedMutationState> states = new ArrayList<>();
        private RegulatedMutationCommandDocument currentCommand;

        private void commandLookup(Optional<RegulatedMutationCommandDocument> existing) {
            existing.ifPresent(command -> currentCommand = command);
            when(commandRepository.findByIdempotencyKey("idem-1")).thenAnswer(invocation -> Optional.ofNullable(currentCommand));
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class))).thenAnswer(invocation -> {
                RegulatedMutationCommandDocument command = invocation.getArgument(0);
                if (command.getId() == null) {
                    command.setId("mutation-1");
                }
                currentCommand = command;
                states.add(command.getState());
                return command;
            });
            when(mongoTemplate.findAndModify(
                    any(Query.class),
                    any(Update.class),
                    any(FindAndModifyOptions.class),
                    eq(RegulatedMutationCommandDocument.class)
            )).thenAnswer(invocation -> {
                if (currentCommand == null) {
                    return null;
                }
                currentCommand.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
                currentCommand.setAttemptCount(currentCommand.getAttemptCount() + 1);
                return currentCommand;
            });
        }

        private RegulatedMutationCommandDocument existingCommand(RegulatedMutationState state) {
            RegulatedMutationCommandDocument command = new RegulatedMutationCommandDocument();
            command.setId("mutation-1");
            command.setIdempotencyKey("idem-1");
            command.setRequestHash("request-hash");
            command.setActorId("principal-7");
            command.setResourceId("alert-1");
            command.setResourceType(AuditResourceType.ALERT.name());
            command.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
            command.setCorrelationId("corr-1");
            command.setState(state);
            command.setUpdatedAt(Instant.now().minusSeconds(60));
            return command;
        }
    }
}
