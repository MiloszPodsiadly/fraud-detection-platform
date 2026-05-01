package com.frauddetection.alert.regulated;

import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.alert.audit.AuditService;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegulatedMutationRecoveryServiceTest {

    @Test
    void shouldRetryOnlySuccessAuditForSuccessAuditPendingCommandWithSnapshot() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        command.setResponseSnapshot(snapshot());

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        assertThat(command.isSuccessAuditRecorded()).isTrue();
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PENDING);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        verify(fixture.auditService).audit(
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
    void shouldMarkBusinessCommittingWithoutSnapshotAsRecoveryRequired() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.BUSINESS_COMMITTING);

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERY_REQUIRED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(command.getLastError()).isEqualTo("RECOVERY_REQUIRED");
        verify(fixture.auditService, never()).audit(any(), any(), anyString(), any(), anyString(), any(), any());
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
        verify(fixture.auditService, never()).audit(any(), any(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void shouldDegradeWhenSuccessAuditRetryFailsWithoutRerunningBusinessMutation() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.SUCCESS_AUDIT_PENDING);
        command.setResponseSnapshot(snapshot());
        doThrow(new IllegalStateException("audit unavailable")).when(fixture.auditService).audit(
                AuditAction.SUBMIT_ANALYST_DECISION,
                AuditResourceType.ALERT,
                "alert-1",
                "corr-1",
                "principal-7",
                AuditOutcome.SUCCESS,
                null
        );

        RegulatedMutationRecoveryResult result = fixture.service.recover(command);

        assertThat(result.outcome()).isEqualTo(RegulatedMutationRecoveryOutcome.RECOVERED);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.COMMITTED_DEGRADED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(command.getLastError()).isEqualTo("POST_COMMIT_AUDIT_DEGRADED");
    }

    @Test
    void shouldScanBoundedStuckCommands() {
        Fixture fixture = new Fixture();
        RegulatedMutationCommandDocument command = fixture.command(RegulatedMutationState.REQUESTED);
        RegulatedMutationCommandDocument evidencePending = fixture.command(RegulatedMutationState.EVIDENCE_PENDING);
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
    }

    private static RegulatedMutationResponseSnapshot snapshot() {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING
        );
    }

    private static final class Fixture {
        private final RegulatedMutationCommandRepository commandRepository = mock(RegulatedMutationCommandRepository.class);
        private final AuditService auditService = mock(AuditService.class);
        private final RegulatedMutationRecoveryService service = new RegulatedMutationRecoveryService(
                commandRepository,
                auditService,
                Duration.ofMinutes(2)
        );

        private Fixture() {
            when(commandRepository.save(any(RegulatedMutationCommandDocument.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));
            when(commandRepository.findTop100ByExecutionStatusInAndUpdatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());
            when(commandRepository.findTop100ByStateInAndUpdatedAtBefore(anyCollection(), any()))
                    .thenReturn(List.of());
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
            command.setState(state);
            command.setExecutionStatus(RegulatedMutationExecutionStatus.PROCESSING);
            command.setCreatedAt(Instant.now().minusSeconds(300));
            command.setUpdatedAt(Instant.now().minusSeconds(300));
            return command;
        }
    }
}
