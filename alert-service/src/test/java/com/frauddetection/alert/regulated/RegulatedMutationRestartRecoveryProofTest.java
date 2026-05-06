package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditAction;
import com.frauddetection.alert.audit.AuditResourceType;
import com.frauddetection.common.events.enums.AlertStatus;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("recovery-proof")
@Tag("production-readiness")
@Tag("integration")
class RegulatedMutationRestartRecoveryProofTest {

    private final RegulatedMutationReplayPolicyRegistry replayPolicyRegistry = new RegulatedMutationReplayPolicyRegistry(
            List.of(
                    new LegacyRegulatedMutationReplayPolicy(new RegulatedMutationLeasePolicy()),
                    new EvidenceGatedFinalizeReplayPolicy(new RegulatedMutationLeasePolicy())
            ),
            true
    );

    @Test
    void crashAfterClaimBeforeAttemptedAuditDoesNotReturnSuccess() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        command.setLeaseOwner("owner-a");
        command.setLeaseExpiresAt(Instant.now().plusSeconds(60));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getOutboxEventId()).isNull();
        assertThat(command.isAttemptedAuditRecorded()).isFalse();
    }

    @Test
    void crashAfterAttemptedAuditBeforeBusinessMutationDoesNotExposeUpdatedResource() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.AUDIT_ATTEMPTED,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        command.setLeaseOwner("owner-a");
        command.setLeaseExpiresAt(Instant.now().plusSeconds(60));
        command.setAttemptedAuditRecorded(true);
        command.setAttemptedAuditId("attempted-audit");

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.ACTIVE_IN_PROGRESS);
        assertThat(command.getResponseSnapshot()).isNull();
        assertThat(command.getOutboxEventId()).isNull();
        assertThat(command.getSuccessAuditId()).isNull();
    }

    @Test
    void crashAfterBusinessCommitBeforeSuccessAuditLegacyRequiresOnlyExplicitRecoveryOrAuditRetry() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.LEGACY_REGULATED_MUTATION,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationExecutionStatus.NEW
        );
        command.setAttemptedAuditRecorded(true);
        command.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.NONE);
        assertThat(command.getResponseSnapshot()).isNotNull();
        assertThat(command.isSuccessAuditRecorded()).isFalse();
    }

    @Test
    void crashDuringFdp29FinalizeBeforeCommitRequiresRecoveryWithoutFalseFinalizedResponse() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationExecutionStatus.PROCESSING
        );
        command.setLeaseExpiresAt(Instant.now().minusSeconds(1));
        command.setResponseSnapshot(null);
        command.setOutboxEventId(null);
        command.setLocalCommitMarker(null);

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.FINALIZING_REQUIRES_RECOVERY);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(decision.responseState()).isNotEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getResponseSnapshot()).isNull();
    }

    @Test
    void crashAfterFdp29LocalCommitBeforeExternalConfirmationDoesNotClaimConfirmedFinality() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED
        );
        command.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
        command.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
        command.setSuccessAuditRecorded(true);
        command.setSuccessAuditId("local-success-audit");
        command.setOutboxEventId("outbox-1");

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.REPLAY_SNAPSHOT);
        assertThat(command.getResponseSnapshot().operationStatus())
                .isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(command.getResponseSnapshot().operationStatus())
                .isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
    }

    @Test
    void crashWithRecoveryStateAndStaleSnapshotMakesRecoveryWin() {
        RegulatedMutationCommandDocument command = command(
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED,
                RegulatedMutationExecutionStatus.RECOVERY_REQUIRED
        );
        command.setResponseSnapshot(snapshot(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));

        RegulatedMutationReplayDecision decision = replayPolicyRegistry.resolve(command, Instant.now());

        assertThat(decision.type()).isEqualTo(RegulatedMutationReplayDecisionType.RECOVERY_REQUIRED_RESPONSE);
        assertThat(decision.responseState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(decision.responseState()).isNotEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
    }

    private RegulatedMutationCommandDocument command(
            RegulatedMutationModelVersion modelVersion,
            RegulatedMutationState state,
            RegulatedMutationExecutionStatus executionStatus
    ) {
        RegulatedMutationCommandDocument document = new RegulatedMutationCommandDocument();
        document.setId("command-1");
        document.setIdempotencyKey("idem-1");
        document.setActorId("principal-7");
        document.setResourceId("alert-1");
        document.setResourceType(AuditResourceType.ALERT.name());
        document.setAction(AuditAction.SUBMIT_ANALYST_DECISION.name());
        document.setCorrelationId("corr-1");
        document.setRequestHash("request-hash-1");
        document.setIntentHash("request-hash-1");
        document.setMutationModelVersion(modelVersion);
        document.setState(state);
        document.setExecutionStatus(executionStatus);
        document.setCreatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        document.setUpdatedAt(Instant.parse("2026-05-01T00:00:00Z"));
        return document;
    }

    private RegulatedMutationResponseSnapshot snapshot(SubmitDecisionOperationStatus status) {
        return new RegulatedMutationResponseSnapshot(
                "alert-1",
                AnalystDecision.CONFIRMED_FRAUD,
                AlertStatus.RESOLVED,
                "event-1",
                Instant.parse("2026-05-01T00:00:00Z"),
                status
        );
    }
}
