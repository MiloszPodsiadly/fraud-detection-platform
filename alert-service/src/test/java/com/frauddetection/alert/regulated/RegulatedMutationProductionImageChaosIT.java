package com.frauddetection.alert.regulated;

import tools.jackson.databind.JsonNode;
import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import com.frauddetection.common.events.enums.AnalystDecision;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageChaosIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void productionImageKillAfterClaimBeforeEvidencePreparationDoesNotCommit() {
        RegulatedMutationChaosScenario scenario = scenario(
                "claim-before-evidence-preparation",
                RegulatedMutationChaosWindow.AFTER_CLAIM_BEFORE_EVIDENCE_PREPARATION,
                RegulatedMutationState.REQUESTED,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setLeaseOwner("owner-production-image-claim-window");
                    command.setLeaseExpiresAt(Instant.now().plusSeconds(30));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);

        assertProductionImageRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.REQUESTED);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(result.responseSnapshotPresent()).isFalse();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.analystDecision()).isNull();
    }

    @Test
    void productionImageKillAfterAttemptedAuditBeforeEvidencePreparationDoesNotPublish() {
        RegulatedMutationChaosScenario scenario = scenario(
                "attempted-before-evidence-preparation",
                RegulatedMutationChaosWindow.AFTER_ATTEMPTED_AUDIT_BEFORE_EVIDENCE_PREPARATION,
                RegulatedMutationState.EVIDENCE_PREPARING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-production-image-attempted-window");
                    command.setLeaseExpiresAt(Instant.now().plusSeconds(30));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);

        assertProductionImageRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);
        assertThat(result.attemptedAuditEvents()).isOne();
        assertThat(result.successAuditEvents()).isZero();
        assertThat(result.outboxRecords()).isZero();
        assertThat(result.analystDecision()).isNull();
    }

    @Test
    void productionImageKillDuringFinalizingRequiresRecoveryWithoutFalseSuccess() {
        RegulatedMutationChaosScenario scenario = scenario(
                "evidence-gated-finalizing",
                RegulatedMutationChaosWindow.EVIDENCE_GATED_FINALIZING,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-production-image-business-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.runDurableStateScenario(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery,
                java.util.EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        assertProductionImageRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.responseSnapshotPresent()).isFalse();
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(afterRecovery.analystDecision()).isNull();
    }

    @Test
    void productionImageKillInFinalizeRecoveryDoesNotRepeatBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalized-visible-local-commit",
                RegulatedMutationChaosWindow.FINALIZED_VISIBLE_LOCAL_COMMIT,
                RegulatedMutationState.FINALIZED_VISIBLE,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLocalCommitMarker(RegulatedMutationDurableLocalFinalizationProof.LOCAL_COMMIT_MARKER);
                    command.setLocalCommittedAt(Instant.now());
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
                    command.setLeaseOwner("owner-production-image-success-audit-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.runDurableStateScenario(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery,
                java.util.EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        assertProductionImageRestarted(beforeRecovery);
        assertThat(recovery.path("recovered").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(afterRecovery.businessMutationCount()).isOne();
        assertThat(afterRecovery.outboxRecords()).isOne();
        assertThat(afterRecovery.successAuditEvents()).isOne();
        assertThat(afterRecovery.analystDecision()).isEqualTo(AnalystDecision.CONFIRMED_FRAUD);
    }

    @Test
    void productionImageKillInFinalizingDoesNotFakeExternalConfirmation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalizing-without-proof",
                RegulatedMutationChaosWindow.EVIDENCE_GATED_FINALIZING,
                RegulatedMutationState.FINALIZING,
                RegulatedMutationExecutionStatus.PROCESSING,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                command -> {
                    command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZING);
                    command.setLeaseOwner("owner-production-image-finalizing-window");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.runDurableStateScenario(scenario);
        JsonNode recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery,
                java.util.EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        assertProductionImageRestarted(beforeRecovery);
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZE_RECOVERY_REQUIRED);
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
    }

    @Test
    void productionImageKillInPendingExternalRemainsPendingWithoutEvidence() {
        RegulatedMutationChaosScenario scenario = scenario(
                "pending-external",
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationExecutionStatus.COMPLETED,
                RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
                    command.setLocalCommittedAt(Instant.now());
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);

        assertProductionImageRestarted(result);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(result.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.publicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
        assertThat(result.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
        assertThat(result.businessMutationCount()).isOne();
    }

    private void assertProductionImageRestarted(RegulatedMutationChaosResult result) {
        assertThat(result.proofLevel()).isEqualTo(RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL);
        assertThat(result.targetKilled()).isTrue();
        assertThat(result.targetRestarted()).isTrue();
        assertThat(result.killedTargetName()).contains("alert-service");
        assertThat(result.restartedTargetName()).contains("alert-service");
        assertThat(result.killedTargetName()).doesNotContain("alpine").doesNotContain("busybox").doesNotContain("dummy");
        assertThat(result.killedTargetId()).isNotBlank();
        assertThat(result.restartedTargetId()).isNotBlank();
        assertThat(result.restartedTargetId()).isNotEqualTo(result.killedTargetId());
        assertThat(result.inspectionResponse()).isNotNull();
    }
}
