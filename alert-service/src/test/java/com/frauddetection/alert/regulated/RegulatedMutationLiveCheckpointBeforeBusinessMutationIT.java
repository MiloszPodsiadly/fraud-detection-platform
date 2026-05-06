package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.Fdp38LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp38")
@Tag("live-runtime-checkpoint-chaos")
@Tag("docker-chaos")
@Tag("integration")
@EnabledIf("fdp38LiveCheckpointEnabled")
class RegulatedMutationLiveCheckpointBeforeBusinessMutationIT extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void beforeLegacyBusinessMutationLiveKillDoesNotCommitOrPublish() throws Exception {
        String alertId = "alert-fdp38-before-business";
        String idempotencyKey = "idem-fdp38-before-business";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "before-legacy-business-mutation",
                Fdp38LiveRuntimeCheckpoint.BEFORE_LEGACY_BUSINESS_MUTATION,
                idempotencyKey,
                List.of()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("before-business"));
        Document barrier = awaitBarrier(idempotencyKey, Fdp38LiveRuntimeCheckpoint.BEFORE_LEGACY_BUSINESS_MUTATION);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.AUDIT_ATTEMPTED);
        assertThat(command.getExecutionStatus()).isEqualTo(RegulatedMutationExecutionStatus.PROCESSING);

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-before-business-kill", List.of());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("before-legacy-business-mutation", RegulatedMutationChaosWindow.BEFORE_LEGACY_BUSINESS_MUTATION, command),
                Fdp38LiveRuntimeCheckpoint.BEFORE_LEGACY_BUSINESS_MUTATION,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertNoCommittedSuccess(persistedCommand, persistedAlert, result);
        assertThat(result.attemptedAuditEvents()).isEqualTo(1L);
    }
}
