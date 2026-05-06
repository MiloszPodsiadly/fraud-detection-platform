package com.frauddetection.alert.regulated;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp37")
@Tag("production-image-chaos")
@Tag("docker-chaos")
@Tag("required-transaction")
@Tag("integration")
@EnabledIf("productionImageChaosEnabled")
class RegulatedMutationProductionImageRequiredTransactionChaosIT extends AbstractRegulatedMutationProductionImageChaosIT {

    @Test
    void requiredTransactionModeBusinessCommittingRestartRequiresRecoveryWithoutFalseSuccess() {
        RegulatedMutationChaosScenario scenario = scenario(
                "required-transaction-business-committing",
                RegulatedMutationChaosWindow.LEGACY_BUSINESS_COMMITTING,
                RegulatedMutationState.BUSINESS_COMMITTING,
                RegulatedMutationExecutionStatus.PROCESSING,
                command -> {
                    command.setAttemptedAuditRecorded(true);
                    command.setAttemptedAuditId(insertAudit(command.getResourceId(), AuditOutcome.ATTEMPTED, "attempted-" + command.getId()));
                    command.setLeaseOwner("owner-fdp37-required-transaction");
                    command.setLeaseExpiresAt(Instant.now().minusSeconds(5));
                    command.setUpdatedAt(staleForRecovery());
                }
        );

        RegulatedMutationChaosResult beforeRecovery = chaosHarness.runDurableStateScenario(
                scenario,
                List.of("--app.regulated-mutations.transaction-mode=REQUIRED")
        );
        var recovery = chaosHarness.recoverViaRestartedService();
        RegulatedMutationChaosResult afterRecovery = chaosHarness.collectEvidence(
                scenario,
                chaosHarness.inspectByCommandId(scenario.commandId()),
                recovery,
                EnumSet.of(
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_CONTAINER_KILL,
                        RegulatedMutationProofLevel.PRODUCTION_IMAGE_RESTART_API_PROOF,
                        RegulatedMutationProofLevel.DURABLE_STATE_SEEDED_CONTAINER_PROOF,
                        RegulatedMutationProofLevel.API_PERSISTED_STATE_PROOF
                )
        );

        assertThat(beforeRecovery.killedTargetName()).contains("alert-service");
        assertThat(chaosHarness.lastEffectiveArgs())
                .contains("--app.regulated-mutations.transaction-mode=REQUIRED");
        assertThat(recovery.path("recovery_required").asLong()).isEqualTo(1);
        assertThat(afterRecovery.commandState()).isEqualTo(RegulatedMutationState.BUSINESS_COMMITTING);
        assertThat(afterRecovery.executionStatus()).isEqualTo(RegulatedMutationExecutionStatus.RECOVERY_REQUIRED);
        assertThat(afterRecovery.responseSnapshotPresent()).isFalse();
        assertThat(afterRecovery.outboxRecords()).isZero();
        assertThat(afterRecovery.successAuditEvents()).isZero();
        assertThat(afterRecovery.analystDecision()).isNull();
    }
}
