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
class RegulatedMutationLiveCheckpointAfterEvidencePreparedBeforeFinalizeIT
        extends AbstractRegulatedMutationFdp38LiveCheckpointIT {

    @Test
    void killAfterEvidencePreparedDoesNotCommitOrPublish() throws Exception {
        String alertId = "alert-fdp38-after-evidence-prepared";
        String idempotencyKey = "idem-fdp38-after-evidence-prepared";
        alertRepository.save(alert(alertId));

        chaosHarness.startFixture(
                "after-evidence-prepared-before-finalize",
                Fdp38LiveRuntimeCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE,
                idempotencyKey,
                evidenceGatedArgs()
        );

        var submitFuture = chaosHarness.submitDecisionAsync(alertId, idempotencyKey, decisionJson("after-evidence-prepared"));
        Document barrier = awaitBarrier(idempotencyKey, Fdp38LiveRuntimeCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE);
        RegulatedMutationCommandDocument command = awaitCommand(idempotencyKey);
        assertThat(barrier.getString("mutation_command_id")).isEqualTo(command.getId());
        assertThat(command.getMutationModelVersion()).isEqualTo(RegulatedMutationModelVersion.EVIDENCE_GATED_FINALIZE_V1);
        assertThat(command.getState()).isEqualTo(RegulatedMutationState.EVIDENCE_PREPARED);
        assertThat(command.isAttemptedAuditRecorded()).isTrue();

        chaosHarness.killFixtureAbruptly();
        submitFuture.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
        chaosHarness.restartFixture("after-evidence-prepared-kill", evidenceGatedArgs());

        RegulatedMutationChaosResult result = chaosHarness.collectEvidence(
                scenario("after-evidence-prepared-before-finalize",
                        RegulatedMutationChaosWindow.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE, command),
                Fdp38LiveRuntimeCheckpoint.AFTER_EVIDENCE_PREPARED_BEFORE_FINALIZE,
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
