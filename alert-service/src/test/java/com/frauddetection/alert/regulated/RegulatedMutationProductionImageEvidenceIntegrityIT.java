package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Instant;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("evidence-integrity")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageEvidenceIntegrityIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void finalizedReplayAfterProductionImageRestartDoesNotCreateSecondOutboxRecord() {
        RegulatedMutationChaosScenario scenario = finalizedConfirmedScenario("finalized-replay-no-second-outbox");

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.inspectByIdempotencyKey(scenario.idempotencyKey());
        RegulatedMutationChaosResult afterReplay = collectAfterReplay(scenario);

        assertThat(result.outboxRecords()).isOne();
        assertThat(afterReplay.outboxRecords()).isOne();
    }

    @Test
    void finalizedReplayAfterProductionImageRestartDoesNotCreateSecondSuccessAudit() {
        RegulatedMutationChaosScenario scenario = finalizedConfirmedScenario("finalized-replay-no-second-success-audit");

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.inspectByIdempotencyKey(scenario.idempotencyKey());
        RegulatedMutationChaosResult afterReplay = collectAfterReplay(scenario);

        assertThat(result.successAuditEvents()).isOne();
        assertThat(afterReplay.successAuditEvents()).isOne();
    }

    @Test
    void finalizeRecoveryCompletesEvidenceWithoutSecondBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "finalize-recovery-integrity",
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
                    command.setLeaseOwner("owner-production-image-success-integrity");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        chaosHarness.runDurableStateScenario(scenario);
        var recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult result = collectAfterReplay(scenario);

        assertThat(recovery.path("recovered").asLong()).isEqualTo(1);
        assertThat(result.commandState()).isEqualTo(RegulatedMutationState.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.COMPLETED);
        assertThat(result.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(result.businessMutationCount()).isOne();
        assertThat(result.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
    }

    @Test
    void pendingExternalReplayDoesNotCreateDuplicateOutboxOrLocalSuccessAudit() {
        RegulatedMutationChaosScenario scenario = pendingExternalScenario("pending-external-integrity");

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.inspectByIdempotencyKey(scenario.idempotencyKey());
        RegulatedMutationChaosResult afterReplay = collectAfterReplay(scenario);

        assertThat(result.outboxRecords()).isOne();
        assertThat(afterReplay.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
        assertThat(afterReplay.successAuditEvents()).isOne();
        assertThat(afterReplay.publicStatus()).isEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_PENDING_EXTERNAL);
        assertThat(afterReplay.publicStatus()).isNotEqualTo(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
    }

    private RegulatedMutationChaosScenario finalizedConfirmedScenario(String suffix) {
        return scenario(
                suffix,
                RegulatedMutationChaosWindow.FINALIZED_EVIDENCE_PENDING_EXTERNAL,
                RegulatedMutationState.FINALIZED_EVIDENCE_CONFIRMED,
                RegulatedMutationExecutionStatus.COMPLETED,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setPublicStatus(SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED);
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLocalCommitMarker("EVIDENCE_GATED_FINALIZED");
                    command.setLocalCommittedAt(Instant.now());
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );
    }

    private RegulatedMutationChaosScenario pendingExternalScenario(String suffix) {
        return scenario(
                suffix,
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
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command, AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command, AuditOutcome.SUCCESS, "success-" + command.getId()));
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );
    }

    private RegulatedMutationChaosResult collectAfterReplay(RegulatedMutationChaosScenario scenario) {
        return chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                null,
                EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );
    }
}
