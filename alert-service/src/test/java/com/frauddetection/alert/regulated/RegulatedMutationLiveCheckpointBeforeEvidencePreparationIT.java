package com.frauddetection.alert.regulated;

import com.frauddetection.alert.persistence.AlertDocument;
import com.frauddetection.alert.regulated.chaos.Fdp38LiveRuntimeCheckpoint;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosResult;
import com.frauddetection.alert.regulated.chaos.RegulatedMutationChaosWindow;
import org.bson.Document;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("fdp38")
@Tag("live-runtime-checkpoint-chaos")
@Tag("docker-chaos")
@Tag("evidence-gated-finalize")
@Tag("integration")
@EnabledIf("fdp38LiveCheckpointEnabled")
class RegulatedMutationLiveCheckpointBeforeEvidencePreparationIT
        extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void killBeforeEvidencePreparationDoesNotCommitOrPublish() throws Exception {
        String alertId = "alert-fdp38-before-evidence-preparation";
        String idempotencyKey = "idem-fdp38-before-evidence-preparation";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "before-evidence-preparation",
                Fdp38LiveRuntimeCheckpoint.BEFORE_EVIDENCE_PREPARATION,
                idempotencyKey,
                evidenceGatedArgs()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("before-evidence-preparation"));
        Document barrier = awaitBarrier(idempotencyKey, Fdp38LiveRuntimeCheckpoint.BEFORE_EVIDENCE_PREPARATION);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.getMutationModelVersion()).isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARING);

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-before-evidence-preparation-kill", evidenceGatedArgs());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("before-evidence-preparation", RegulatedMutationChaosWindow.BEFORE_EVIDENCE_PREPARATION, command),
                Fdp38LiveRuntimeCheckpoint.BEFORE_EVIDENCE_PREPARATION,
                chaosHarness.inspectByCommandId(command.getId()),
                null
        );

        RegulatedMutationCommandDocument persistedCommand = commandRepository.findById(command.getId()).orElseThrow();
        AlertDocument persistedAlert = alertRepository.findById(alertId).orElseThrow();
        assertFixtureKillAndRestart(result);
        assertNoCommittedSuccess(persistedCommand, persistedAlert, result);
        assertThat(result.attemptedAuditEvents()).isZero();
    }
}
