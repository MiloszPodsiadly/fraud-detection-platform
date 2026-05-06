package com.frauddetection.alert.regulated;

import com.frauddetection.alert.api.SubmitDecisionOperationStatus;
import com.frauddetection.alert.audit.AuditOutcome;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosScenario;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("rollback")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageRollbackIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void rollbackRestartKeepsFdp32FencingAndDoesNotCreateNewSuccessClaims() {
        RegulatedMutationChaosScenario scenario = scenario(
                "rollback-business-committing",
                RegulatedMutationChaosWindow.LEGACY_BUSINESS_COMMITTING,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-fdp37-rollback");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult result = chaosHarness.runDurableStateScenario(scenario);
        chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRollbackRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                null,
                java.util.EnumSet.of(
                        com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        com.frauddetection.alert.regulated.chaos.RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        assertThat(result.killedTargetName()).contains("alert-service");
        assertThat(afterRollbackRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRollbackRecovery.publicStatus()).isNotIn(
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_PENDING,
                SubmitDecisionOperationStatus.COMMITTED_EVIDENCE_CONFIRMED,
                SubmitDecisionOperationStatus.FINALIZED_EVIDENCE_CONFIRMED
        );
        assertThat(afterRollbackRecovery.outboxRecords()).isZero();
        assertThat(afterRollbackRecovery.successAuditEvents()).isZero();
        assertThat(afterRollbackRecovery.analystDecision()).isNull();
    }
}
