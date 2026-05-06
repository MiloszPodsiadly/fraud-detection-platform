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
    void legacyReplayAfterProductionImageRestartDoesNotCreateSecondOutboxRecord() {
        RegulatedMutationChaosScenario scenario = committedLegacyScenario("legacy-replay-no-second-outbox");

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.inspectByIdempotencyKey(scenario.idempotencyKey());
        RegulatedMutationChaosResult afterReplay = collectAfterReplay(scenario);

        assertThat(result.outboxRecords()).isOne();
        assertThat(afterReplay.outboxRecords()).isOne();
    }

    @Test
    void legacyReplayAfterProductionImageRestartDoesNotCreateSecondSuccessAudit() {
        RegulatedMutationChaosScenario scenario = committedLegacyScenario("legacy-replay-no-second-success-audit");

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.inspectByIdempotencyKey(scenario.idempotencyKey());
        RegulatedMutationChaosResult afterReplay = collectAfterReplay(scenario);

        assertThat(result.successAuditEvents()).isOne();
        assertThat(afterReplay.successAuditEvents()).isOne();
    }

    @Test
    void successAuditPendingRecoveryRetriesAuditOnlyWithoutSecondBusinessMutation() {
        RegulatedMutationChaosScenario scenario = scenario(
                "success-audit-pending-integrity",
                RegulatedMutationChaosWindow.LEGACY_SUCCESS_AUDIT_PENDING,
                RegulatedMutationState.SUCCESS_AUDIT_PENDING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLeaseOwner("owner-fdp37-success-integrity");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );

        chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult result = collectAfterReplay(scenario);

        assertThat(result.businessMutationCount()).isOne();
        assertThat(result.outboxRecords()).isOne();
        assertThat(result.successAuditEvents()).isOne();
    }

    @Test
    void fdp29PendingExternalReplayDoesNotCreateDuplicateOutboxOrLocalSuccessAudit() {
        RegulatedMutationChaosScenario scenario = fdp29PendingExternalScenario("fdp29-pending-integrity");

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

    private RegulatedMutationChaosScenario committedLegacyScenario(String suffix) {
        return scenario(
                suffix,
                RegulatedMutationChaosWindow.LEGACY_SUCCESS_AUDIT_PENDING,
                RegulatedMutationState.EVIDENCE_CONFIRMED,
                RegulatedMutationExecutionStatus.COMPLETED,
                command -> {
                    mutateAlert(command.getResourceId());
                    command.setPublicStatus(SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED);
                    command.setResponseSnapshot(snapshot(command.getResourceId(), SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED));
                    command.setOutboxEventId("event-" + command.getResourceId());
                    command.setLocalCommitMarker("LOCAL_COMMITTED");
                    command.setLocalCommittedAt(Instant.now());
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command.getResourceId(), AuditOutcome.SUCCESS, "success-" + command.getId()));
                    mongoTemplate.save(outboxRecord(command.getResourceId(), command.getId()));
                }
        );
    }

    private RegulatedMutationChaosScenario fdp29PendingExternalScenario(String suffix) {
        return scenario(
                suffix,
                RegulatedMutationChaosWindow.FDP29_FINALIZED_EVIDENCE_PENDING_EXTERNAL,
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
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setSuccessAuditRecorded(true);
                    command.setSuccessAuditId(insertAudit(command.getResourceId(), AuditOutcome.SUCCESS, "success-" + command.getId()));
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
